using System.Diagnostics;
using System.Security.Cryptography;
using System.Text;
using ClipSync.Core.Security.Bt1;

namespace ClipSync.Spike.Bt1Windows;

/// <summary>
/// SPIKE-ONLY session logic: bt1 listener handshake (using the real phase 1 types from
/// ClipSync.Core) followed by a tiny measurement protocol the Android spike drives.
/// The measurement protocol is NOT part of bt1 or protocol v1 — it exists only to
/// produce the phase 0 numbers and dies with the spike:
///   "ping &lt;bytes&gt;"  -> echoed back verbatim (RTT),
///   "up &lt;n&gt;"       -> client then sends "data &lt;bytes&gt;" frames totaling n data bytes,
///                      listener answers "up-ok &lt;received&gt;" (uplink throughput),
///   "down &lt;n&gt;"     -> listener sends "data &lt;bytes&gt;" frames totaling n data bytes
///                      (downlink throughput),
///   "bye"           -> listener answers "bye" and the session ends.
/// In bt1 mode every one of these payloads is one encrypted bt1 frame; in raw mode it is
/// one plain length-prefixed frame (same 4-byte big-endian prefix, no crypto).
/// </summary>
internal sealed class SpikeSession : IDisposable
{
    /// <summary>Data bytes carried per "data " frame during throughput tests.</summary>
    private const int DataChunkLength = 32 * 1024;

    /// <summary>Upper bound on one transfer request, to keep the spike bounded.</summary>
    private const long MaxTransferBytes = 16L * 1024 * 1024;

    private const int RawModeMaxPayload = 8 * 1024 * 1024;

    private static readonly byte[] PingPrefix = "ping "u8.ToArray();
    private static readonly byte[] DataPrefix = "data "u8.ToArray();
    private static readonly byte[] ByeCommand = "bye"u8.ToArray();

    private readonly SpikeOptions _options;
    private readonly Stream _input;
    private readonly Stream _output;

    private Bt1FrameEncryptor? _encryptor;
    private Bt1FrameDecryptor? _decryptor;

    public SpikeSession(SpikeOptions options, Stream input, Stream output)
    {
        _options = options;
        _input = input;
        _output = output;
    }

    public void Dispose()
    {
        _encryptor?.Dispose();
        _decryptor?.Dispose();
    }

    public async Task RunAsync(CancellationToken cancellationToken)
    {
        if (_options.UseBt1)
        {
            var handshakeWatch = Stopwatch.StartNew();
            using var handshakeTimeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
            handshakeTimeout.CancelAfter(TimeSpan.FromSeconds(30));
            try
            {
                await RunListenerHandshakeAsync(handshakeTimeout.Token);
            }
            catch (Exception exception)
            {
                SpikeLog.Result("bt1_handshake", "failed");
                SpikeLog.Result("bt1_handshake_error", exception.Message);
                throw;
            }

            handshakeWatch.Stop();
            SpikeLog.Result("bt1_handshake", "ok");
            SpikeLog.Result("bt1_handshake_ms", handshakeWatch.ElapsedMilliseconds);
        }
        else
        {
            SpikeLog.Result("bt1_handshake", "skipped_raw_mode");
        }

        await RunDispatchLoopAsync(cancellationToken);
    }

    /// <summary>
    /// Listener half of the docs/protocol-bt1.md section 3 handshake, driven by the real
    /// ClipSync.Core phase 1 primitives so the spike proves the exact bits phase 2 ships.
    /// </summary>
    private async Task RunListenerHandshakeAsync(CancellationToken cancellationToken)
    {
        var clientHello = await ReceiveHandshakeAsync(cancellationToken);
        if (clientHello is not Bt1HandshakeMessage.Hello { SenderRole: Bt1Role.Client } hello)
        {
            await SendHandshakeErrorAsync(Bt1ErrorCodes.SchemaViolation, cancellationToken);
            throw new InvalidDataException("First handshake message was not bt1_client_hello.");
        }

        if (hello.DeviceId != _options.ClientDeviceId || hello.TrustEpoch != _options.TrustEpoch)
        {
            await SendHandshakeErrorAsync(Bt1ErrorCodes.AuthFailed, cancellationToken);
            throw new InvalidDataException(
                "bt1_client_hello device_id/trust_epoch does not match the spike configuration "
                + "(check both sides use the same defaults or the same overrides).");
        }

        var nonceClient = hello.Nonce.ToArray();
        var nonceListener = RandomNumberGenerator.GetBytes(Bt1AuthProof.NonceLength);
        await SendHandshakePayloadAsync(
            Bt1HandshakeCodec.SerializeHello(Bt1Role.Listener, _options.ListenerDeviceId, _options.TrustEpoch, nonceListener),
            cancellationToken);

        var clientAuth = await ReceiveHandshakeAsync(cancellationToken);
        if (clientAuth is not Bt1HandshakeMessage.Auth { SenderRole: Bt1Role.Client } auth)
        {
            await SendHandshakeErrorAsync(Bt1ErrorCodes.SchemaViolation, cancellationToken);
            throw new InvalidDataException("Third handshake message was not bt1_client_auth.");
        }

        var clientProofValid = Bt1AuthProof.Verify(
            _options.PairSecret,
            Bt1Role.Client,
            nonceClient,
            nonceListener,
            _options.ClientDeviceId,
            _options.ListenerDeviceId,
            _options.TrustEpoch,
            auth.Proof.Span);
        if (!clientProofValid)
        {
            await SendHandshakeErrorAsync(Bt1ErrorCodes.AuthFailed, cancellationToken);
            throw new InvalidDataException(
                "Client proof verification failed (secret/device-id/epoch mismatch between the two spikes).");
        }

        var listenerProof = Bt1AuthProof.Compute(
            _options.PairSecret,
            Bt1Role.Listener,
            nonceClient,
            nonceListener,
            _options.ClientDeviceId,
            _options.ListenerDeviceId,
            _options.TrustEpoch);
        await SendHandshakePayloadAsync(
            Bt1HandshakeCodec.SerializeAuth(Bt1Role.Listener, listenerProof),
            cancellationToken);

        var keys = Bt1KeySchedule.Derive(_options.PairSecret, nonceClient, nonceListener);
        _encryptor = new Bt1FrameEncryptor(keys.ListenerToClient.Span);
        _decryptor = new Bt1FrameDecryptor(keys.ClientToListener.Span);
    }

    private async Task RunDispatchLoopAsync(CancellationToken cancellationToken)
    {
        long pingCount = 0;
        while (true)
        {
            var payload = await ReceivePayloadAsync(cancellationToken);
            if (payload is null)
            {
                SpikeLog.Info("Peer closed the stream.");
                SpikeLog.Result("session", "peer_closed_before_bye");
                return;
            }

            if (StartsWith(payload, PingPrefix))
            {
                pingCount++;
                await SendPayloadAsync(payload, cancellationToken);
                continue;
            }

            if (TryParseCommand(payload, "up ", out var uplinkTotal))
            {
                await ReceiveUplinkAsync(uplinkTotal, cancellationToken);
                continue;
            }

            if (TryParseCommand(payload, "down ", out var downlinkTotal))
            {
                await SendDownlinkAsync(downlinkTotal, cancellationToken);
                continue;
            }

            if (payload.AsSpan().SequenceEqual(ByeCommand))
            {
                await SendPayloadAsync(ByeCommand, cancellationToken);
                SpikeLog.Result("ping_frames_echoed", pingCount);
                SpikeLog.Result("session", "completed");
                return;
            }

            SpikeLog.Info(FormattableString.Invariant(
                $"Unknown spike command ({payload.Length} bytes); replying err."));
            await SendPayloadAsync("err unknown-command"u8.ToArray(), cancellationToken);
        }
    }

    private async Task ReceiveUplinkAsync(long total, CancellationToken cancellationToken)
    {
        SpikeLog.Info(FormattableString.Invariant($"Uplink test: expecting {total} data bytes from the client."));
        long received = 0;
        var stopwatch = Stopwatch.StartNew();
        while (received < total)
        {
            var payload = await ReceivePayloadAsync(cancellationToken)
                ?? throw new EndOfStreamException("Stream ended mid-uplink.");
            if (!StartsWith(payload, DataPrefix))
            {
                throw new InvalidDataException("Expected a data frame during the uplink test.");
            }

            received += payload.Length - DataPrefix.Length;
        }

        stopwatch.Stop();
        await SendPayloadAsync(
            Encoding.ASCII.GetBytes(FormattableString.Invariant($"up-ok {received}")),
            cancellationToken);

        // The authoritative uplink number is measured on the Android side (it spans the
        // full command round trip); this listener-side figure corroborates it.
        SpikeLog.Result("up_bytes", received);
        SpikeLog.Result("up_ms_listener_side", stopwatch.ElapsedMilliseconds);
        if (stopwatch.ElapsedMilliseconds > 0)
        {
            SpikeLog.Result("up_kib_per_s_listener_side", received / 1024.0 / (stopwatch.ElapsedMilliseconds / 1000.0));
        }
    }

    private async Task SendDownlinkAsync(long total, CancellationToken cancellationToken)
    {
        SpikeLog.Info(FormattableString.Invariant($"Downlink test: sending {total} data bytes to the client."));
        var chunk = new byte[DataPrefix.Length + DataChunkLength];
        DataPrefix.CopyTo(chunk, 0);
        RandomNumberGenerator.Fill(chunk.AsSpan(DataPrefix.Length));

        long sent = 0;
        var stopwatch = Stopwatch.StartNew();
        while (sent < total)
        {
            var dataLength = (int)Math.Min(DataChunkLength, total - sent);
            await SendPayloadAsync(chunk.AsMemory(0, DataPrefix.Length + dataLength), cancellationToken);
            sent += dataLength;
        }

        stopwatch.Stop();
        SpikeLog.Result("down_bytes", sent);
        SpikeLog.Result("down_ms_listener_side", stopwatch.ElapsedMilliseconds);
        if (stopwatch.ElapsedMilliseconds > 0)
        {
            SpikeLog.Result("down_kib_per_s_listener_side", sent / 1024.0 / (stopwatch.ElapsedMilliseconds / 1000.0));
        }
    }

    private static bool TryParseCommand(byte[] payload, string prefix, out long value)
    {
        value = 0;
        var text = DecodeAsciiOrNull(payload);
        if (text is null || !text.StartsWith(prefix, StringComparison.Ordinal))
        {
            return false;
        }

        if (!long.TryParse(text.AsSpan(prefix.Length), System.Globalization.CultureInfo.InvariantCulture, out value)
            || value < 1
            || value > MaxTransferBytes)
        {
            throw new InvalidDataException(FormattableString.Invariant(
                $"Spike transfer command '{prefix.TrimEnd()}' must carry 1..{MaxTransferBytes} bytes."));
        }

        return true;
    }

    private static string? DecodeAsciiOrNull(byte[] payload)
    {
        if (payload.Length > 64)
        {
            return null;
        }

        foreach (var b in payload)
        {
            if (b is < 0x20 or > 0x7e)
            {
                return null;
            }
        }

        return Encoding.ASCII.GetString(payload);
    }

    private static bool StartsWith(byte[] payload, byte[] prefix) =>
        payload.Length >= prefix.Length && payload.AsSpan(0, prefix.Length).SequenceEqual(prefix);

    // ---- frame IO -------------------------------------------------------------------

    private async Task<Bt1HandshakeMessage> ReceiveHandshakeAsync(CancellationToken cancellationToken)
    {
        var payload = await ReadFrameAsync(Bt1Frames.MaxHandshakePayloadLength, cancellationToken)
            ?? throw new EndOfStreamException("Stream ended during the bt1 handshake.");
        var outcome = Bt1HandshakeCodec.Parse(payload);
        switch (outcome)
        {
            case Bt1HandshakeParseOutcome.Success { Message: Bt1HandshakeMessage.ChannelError error }:
                throw new InvalidDataException($"Peer sent bt1_error {error.Code} during the handshake.");
            case Bt1HandshakeParseOutcome.Success success:
                return success.Message;
            case Bt1HandshakeParseOutcome.Failure failure:
                await SendHandshakeErrorAsync(
                    Bt1ErrorCodes.WireCodes.Contains(failure.ErrorCode) ? failure.ErrorCode : Bt1ErrorCodes.SchemaViolation,
                    cancellationToken);
                throw new InvalidDataException($"Handshake message rejected: {failure.ErrorCode} ({failure.Reason}).");
            default:
                throw new InvalidOperationException("Unreachable handshake parse outcome.");
        }
    }

    private Task SendHandshakePayloadAsync(string json, CancellationToken cancellationToken) =>
        WriteRawFrameAsync(Encoding.UTF8.GetBytes(json), cancellationToken);

    private Task SendHandshakeErrorAsync(string code, CancellationToken cancellationToken) =>
        SendHandshakePayloadAsync(Bt1HandshakeCodec.SerializeError(code), cancellationToken);

    private async Task<byte[]?> ReceivePayloadAsync(CancellationToken cancellationToken)
    {
        if (_decryptor is null)
        {
            return await ReadFrameAsync(RawModeMaxPayload, cancellationToken);
        }

        var payload = await ReadFrameAsync(Bt1Frames.MaxEncryptedPayloadLength, cancellationToken);
        if (payload is null)
        {
            return null;
        }

        if (!_decryptor.TryDecryptPayload(payload, out var plaintext))
        {
            throw new InvalidDataException("bt1 frame decryption failed (BT1_DECRYPT_FAILED); closing.");
        }

        return plaintext;
    }

    private Task SendPayloadAsync(byte[] payload, CancellationToken cancellationToken) =>
        SendPayloadAsync(payload.AsMemory(), cancellationToken);

    private async Task SendPayloadAsync(ReadOnlyMemory<byte> payload, CancellationToken cancellationToken)
    {
        if (_encryptor is null)
        {
            await WriteRawFrameAsync(payload, cancellationToken);
            return;
        }

        var frame = _encryptor.EncryptFrame(payload.Span);
        await _output.WriteAsync(frame, cancellationToken);
        await _output.FlushAsync(cancellationToken);
    }

    private async Task WriteRawFrameAsync(ReadOnlyMemory<byte> payload, CancellationToken cancellationToken)
    {
        var prefix = new byte[Bt1Frames.LengthPrefixLength];
        System.Buffers.Binary.BinaryPrimitives.WriteUInt32BigEndian(prefix, (uint)payload.Length);
        await _output.WriteAsync(prefix, cancellationToken);
        await _output.WriteAsync(payload, cancellationToken);
        await _output.FlushAsync(cancellationToken);
    }

    private async Task<byte[]?> ReadFrameAsync(int maxPayloadLength, CancellationToken cancellationToken)
    {
        var prefix = new byte[Bt1Frames.LengthPrefixLength];
        try
        {
            await _input.ReadExactlyAsync(prefix, cancellationToken);
        }
        catch (EndOfStreamException)
        {
            return null;
        }

        var declared = Bt1Frames.ReadDeclaredPayloadLength(prefix);
        if (declared < 1 || declared > maxPayloadLength)
        {
            throw new InvalidDataException(FormattableString.Invariant(
                $"Declared frame length {declared} is outside 1..{maxPayloadLength}."));
        }

        var payload = new byte[declared];
        await _input.ReadExactlyAsync(payload, cancellationToken);
        return payload;
    }
}

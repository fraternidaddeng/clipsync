using System.IO.Pipelines;
using System.Security.Cryptography;
using System.Threading.Channels;
using ClipSync.Core.Security.Bt1;
using ClipSync.Peer.Bluetooth;

namespace ClipSync.Tests.Peer;

/// <summary>
/// In-memory stand-ins for the RFCOMM layer: a connected duplex stream pair (the same
/// shape a socket presents), a fake <see cref="IRfcommServer"/> the tests enqueue
/// connections into, and an honest bt1 client half built from the Core primitives —
/// the role Android plays in production.
/// </summary>
public static class BluetoothTestInfrastructure
{
    /// <summary>Two connected duplex streams: bytes written to one side are read by the other.</summary>
    public static (Stream ClientSide, Stream ListenerSide) CreateDuplexPair()
    {
        var clientToListener = new Pipe();
        var listenerToClient = new Pipe();
        var client = new DuplexStream(listenerToClient.Reader.AsStream(), clientToListener.Writer.AsStream());
        var listener = new DuplexStream(clientToListener.Reader.AsStream(), listenerToClient.Writer.AsStream());
        return (client, listener);
    }

    private sealed class DuplexStream(Stream input, Stream output) : Stream
    {
        public override bool CanRead => true;

        public override bool CanSeek => false;

        public override bool CanWrite => true;

        public override long Length => throw new NotSupportedException();

        public override long Position
        {
            get => throw new NotSupportedException();
            set => throw new NotSupportedException();
        }

        public override int Read(byte[] buffer, int offset, int count) => input.Read(buffer, offset, count);

        public override async ValueTask<int> ReadAsync(Memory<byte> buffer, CancellationToken cancellationToken = default)
        {
            try
            {
                return await input.ReadAsync(buffer, cancellationToken);
            }
            catch (InvalidOperationException)
            {
                // PipeReaderStream throws this after disposal; a real socket stream throws
                // ObjectDisposedException, which is what production code handles.
                throw new ObjectDisposedException(nameof(DuplexStream));
            }
        }

        public override void Write(byte[] buffer, int offset, int count) => output.Write(buffer, offset, count);

        public override async ValueTask WriteAsync(ReadOnlyMemory<byte> buffer, CancellationToken cancellationToken = default)
        {
            try
            {
                await output.WriteAsync(buffer, cancellationToken);
            }
            catch (InvalidOperationException)
            {
                throw new ObjectDisposedException(nameof(DuplexStream));
            }
        }

        public override void Flush() => output.Flush();

        public override Task FlushAsync(CancellationToken cancellationToken) => Task.CompletedTask;

        public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();

        public override void SetLength(long value) => throw new NotSupportedException();

        protected override void Dispose(bool disposing)
        {
            if (disposing)
            {
                input.Dispose();
                output.Dispose();
            }

            base.Dispose(disposing);
        }
    }
}

/// <summary>The listener refused the test client's handshake with a wire bt1_error.</summary>
public sealed class Bt1TestRefusalException(string code) : Exception($"bt1 listener refused: {code}")
{
    public string Code { get; } = code;
}

/// <summary>
/// The client half of the bt1 handshake, honestly implemented from the Core codec and
/// crypto — what the Android dialer does in production, so the listener under test is
/// exercised by a real counterpart rather than canned bytes.
/// </summary>
public static class Bt1TestClient
{
    public static async Task<(Bt1FrameEncryptor Send, Bt1FrameDecryptor Receive)> HandshakeAsync(
        Stream stream,
        byte[] pairSecret,
        Guid clientDeviceId,
        Guid listenerDeviceId,
        long trustEpoch,
        CancellationToken cancellationToken = default)
    {
        var nonceClient = RandomNumberGenerator.GetBytes(Bt1AuthProof.NonceLength);
        await Bt1StreamFrames.WriteHandshakeFrameAsync(
            stream,
            Bt1HandshakeCodec.SerializeHello(Bt1Role.Client, clientDeviceId, trustEpoch, nonceClient),
            cancellationToken);

        var listenerHello = await ReadMessageAsync(stream, cancellationToken);
        if (listenerHello is not Bt1HandshakeMessage.Hello { SenderRole: Bt1Role.Listener } hello
            || hello.DeviceId != listenerDeviceId
            || hello.TrustEpoch != trustEpoch)
        {
            throw new InvalidOperationException("The listener hello did not match the dialed pairing.");
        }

        var nonceListener = hello.Nonce.ToArray();
        var clientProof = Bt1AuthProof.Compute(
            pairSecret, Bt1Role.Client, nonceClient, nonceListener, clientDeviceId, listenerDeviceId, trustEpoch);
        await Bt1StreamFrames.WriteHandshakeFrameAsync(
            stream,
            Bt1HandshakeCodec.SerializeAuth(Bt1Role.Client, clientProof),
            cancellationToken);

        var listenerAuth = await ReadMessageAsync(stream, cancellationToken);
        if (listenerAuth is not Bt1HandshakeMessage.Auth { SenderRole: Bt1Role.Listener } auth
            || !Bt1AuthProof.Verify(
                pairSecret, Bt1Role.Listener, nonceClient, nonceListener,
                clientDeviceId, listenerDeviceId, trustEpoch, auth.Proof.Span))
        {
            throw new InvalidOperationException("The listener proof failed verification.");
        }

        var keys = Bt1KeySchedule.Derive(pairSecret, nonceClient, nonceListener);
        return (new Bt1FrameEncryptor(keys.ClientToListener.Span), new Bt1FrameDecryptor(keys.ListenerToClient.Span));
    }

    private static async Task<Bt1HandshakeMessage> ReadMessageAsync(Stream stream, CancellationToken cancellationToken)
    {
        var payload = await Bt1StreamFrames.ReadHandshakePayloadAsync(stream, cancellationToken)
            ?? throw new EndOfStreamException("The listener closed during the handshake.");
        var message = Bt1HandshakeCodec.Parse(payload) switch
        {
            Bt1HandshakeParseOutcome.Success success => success.Message,
            Bt1HandshakeParseOutcome.Failure failure => throw new InvalidOperationException($"unparseable: {failure.Reason}"),
            var other => throw new InvalidOperationException(other.GetType().Name)
        };
        if (message is Bt1HandshakeMessage.ChannelError error)
        {
            throw new Bt1TestRefusalException(error.Code);
        }

        return message;
    }
}

/// <summary>Fake RFCOMM server: the test enqueues pre-connected duplex streams as inbound connections.</summary>
public sealed class FakeRfcommServer : IRfcommServer
{
    private readonly Channel<IRfcommConnection> pending = Channel.CreateUnbounded<IRfcommConnection>();

    public int StartCount { get; private set; }

    public int StopCount { get; private set; }

    public void EnqueueConnection(Stream listenerSideStream, string remoteAddress = "AA:BB:CC:DD:EE:FF") =>
        pending.Writer.TryWrite(new FakeConnection(listenerSideStream, remoteAddress));

    public ValueTask StartAsync(CancellationToken cancellationToken)
    {
        StartCount++;
        return ValueTask.CompletedTask;
    }

    public async ValueTask<IRfcommConnection> AcceptAsync(CancellationToken cancellationToken) =>
        await pending.Reader.ReadAsync(cancellationToken);

    public ValueTask StopAsync(CancellationToken cancellationToken)
    {
        StopCount++;
        return ValueTask.CompletedTask;
    }

    public ValueTask DisposeAsync() => ValueTask.CompletedTask;

    private sealed class FakeConnection(Stream stream, string remoteAddress) : IRfcommConnection
    {
        public Stream Stream { get; } = stream;

        public string RemoteAddress { get; } = remoteAddress;

        public ValueTask DisposeAsync()
        {
            Stream.Dispose();
            return ValueTask.CompletedTask;
        }
    }
}

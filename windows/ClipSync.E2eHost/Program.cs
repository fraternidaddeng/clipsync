using System.Globalization;
using System.Net;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;
using ClipSync.Core.Clipboard;
using ClipSync.Core.Media;
using ClipSync.Core.Protocol;
using ClipSync.Core.Security;
using ClipSync.Core.Storage;
using ClipSync.Peer.Security;
using ClipSync.Peer.Server;
using ClipSync.Peer.Sessions;
using Microsoft.Data.Sqlite;
using Microsoft.Extensions.Logging;

namespace ClipSync.E2eHost;

/// <summary>
/// Test-only Windows listener for the cross-client E2E run (scripts/run-e2e-stage4.ps1).
/// Stdout is the command protocol only; logs go to stderr and never include clip text,
/// secrets, nonces, or proofs. Commands: <c>capture &lt;base64url-utf8&gt;</c> stores a local
/// text clip, <c>capture-image &lt;base64url-bytes&gt;</c> stores a local image clip (validated
/// like the real capture ingress), <c>gen-large-png &lt;seed&gt; [capture]</c> writes a
/// deterministic multi-chunk noise PNG to a temp file (optionally storing it as a local image
/// clip) and prints its path/hash, <c>list</c> prints the visible history texts as JSON,
/// <c>list-images</c> prints the visible image content hashes as JSON, <c>quit</c> exits.
/// </summary>
internal static class Program
{
    internal const string WindowsDeviceId = "11111111-1111-4111-8111-111111111111";
    internal const string AndroidDeviceId = "22222222-2222-4222-8222-222222222222";

    /// <summary>
    /// The v2 image leg dials as its own paired device: each dialer test starts from a fresh
    /// repository whose local sequences begin at 1, so sharing one identity would collide the
    /// (origin, seq) keyspace between legs. Two devices = two independent origin keyspaces.
    /// </summary>
    internal const string AndroidImageDeviceId = "33333333-3333-4333-8333-333333333333";

    /// <summary>
    /// The multi-chunk image leg (LargeImageCrossClientE2eTest) — same reasoning: its fresh
    /// repository also pushes from sequence 1, so it needs its own origin keyspace too.
    /// </summary>
    internal const string AndroidLargeImageDeviceId = "44444444-4444-4444-8444-444444444444";

    public static async Task<int> Main()
    {
        var dataDir = Path.Combine(Path.GetTempPath(), "clipsync-e2e", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(dataDir);

        SqliteClipboardEventStore? store = null;
        PeerServer? server = null;
        X509Certificate2? certificate = null;
        using var logs = new StderrLoggerFactory();
        try
        {
            store = new SqliteClipboardEventStore(Path.Combine(dataDir, "windows.db"), WindowsDeviceId);
            await store.InitializeAsync().ConfigureAwait(false);

            var protector = new PassthroughSecretProtector();
            var pairSecret = RandomNumberGenerator.GetBytes(32);
            var protectedSecret = Convert.ToBase64String(protector.Protect(pairSecret));
            var device = await store.UpsertDeviceAsync(
                new NewPairedDevice(AndroidDeviceId, "Android", "android", "ab".PadLeft(64, 'a'), protectedSecret),
                DateTimeOffset.UtcNow).ConfigureAwait(false);
            var imageDevice = await store.UpsertDeviceAsync(
                new NewPairedDevice(AndroidImageDeviceId, "Android-Image", "android", "cd".PadLeft(64, 'c'), protectedSecret),
                DateTimeOffset.UtcNow).ConfigureAwait(false);
            var largeImageDevice = await store.UpsertDeviceAsync(
                new NewPairedDevice(AndroidLargeImageDeviceId, "Android-Large", "android", "ef".PadLeft(64, 'e'), protectedSecret),
                DateTimeOffset.UtcNow).ConfigureAwait(false);

            certificate = PeerCertificate.CreateSelfSigned(WindowsDeviceId, DateTimeOffset.UtcNow, TimeSpan.FromDays(365));
            var fingerprint = PeerCertificate.Fingerprint(certificate);

            server = new PeerServer(
                store,
                protector,
                new PeerServerOptions
                {
                    Certificate = certificate,
                    SessionOptions = new SyncSessionOptions
                    {
                        ClientVersion = "0.2.0",
                        OutboxDrainInterval = TimeSpan.FromMilliseconds(100),
                        PingInterval = TimeSpan.FromSeconds(60),
                        // The image leg dials /v2, which PeerServer only accepts with the
                        // 图片同步 gate open. The production default is off (ADR 0004,
                        // fail-closed); this E2E pair opts in explicitly to exercise it.
                        ImageSyncEnabled = static () => true
                    },
                    BindAddresses = [IPAddress.Loopback],
                    Port = 0
                },
                logs);
            await server.StartAsync().ConfigureAwait(false);

            var ready = new ReadyPayload(
                server.Port,
                fingerprint,
                WindowsDeviceId,
                AndroidDeviceId,
                ProtocolValidation.EncodeBase64Url(pairSecret),
                device.TrustEpoch,
                AndroidImageDeviceId,
                imageDevice.TrustEpoch,
                AndroidLargeImageDeviceId,
                largeImageDevice.TrustEpoch);
            await WriteStdoutAsync(JsonSerializer.Serialize(ready)).ConfigureAwait(false);
            Console.Error.WriteLine("e2e-host listening");

            await RunCommandLoopAsync(store, dataDir).ConfigureAwait(false);
            return 0;
        }
        catch (Exception exception)
        {
            Console.Error.WriteLine("e2e-host failed: " + exception.GetType().Name);
            return 1;
        }
        finally
        {
            if (server is not null)
            {
                await server.DisposeAsync().ConfigureAwait(false);
            }

            certificate?.Dispose();
            if (store is not null)
            {
                await store.DisposeAsync().ConfigureAwait(false);
            }

            SqliteConnection.ClearAllPools();
            TryDeleteDirectory(dataDir);
        }
    }

    private static async Task RunCommandLoopAsync(SqliteClipboardEventStore store, string dataDir)
    {
        while (true)
        {
            var line = await Console.In.ReadLineAsync().ConfigureAwait(false);
            if (line is null || line.Equals("quit", StringComparison.Ordinal))
            {
                Console.Error.WriteLine("e2e-host quit");
                return;
            }

            if (line.Equals("list", StringComparison.Ordinal))
            {
                var entries = await store.SearchAsync(new ClipboardHistoryQuery(Limit: 500)).ConfigureAwait(false);
                var payload = new ListPayload(entries.Select(entry => entry.Text).ToArray());
                await WriteStdoutAsync(JsonSerializer.Serialize(payload)).ConfigureAwait(false);
                Console.Error.WriteLine("e2e-host listed count=" + payload.Texts.Count.ToString(CultureInfo.InvariantCulture));
                continue;
            }

            if (line.Equals("list-images", StringComparison.Ordinal))
            {
                var entries = await store.SearchAsync(new ClipboardHistoryQuery(Limit: 500)).ConfigureAwait(false);
                var payload = new ImageListPayload(
                    entries.Where(entry => entry.IsImage).Select(entry => entry.ContentHash).ToArray());
                await WriteStdoutAsync(JsonSerializer.Serialize(payload)).ConfigureAwait(false);
                Console.Error.WriteLine(
                    "e2e-host listed images count=" + payload.ImageHashes.Count.ToString(CultureInfo.InvariantCulture));
                continue;
            }

            const string captureImagePrefix = "capture-image ";
            if (line.StartsWith(captureImagePrefix, StringComparison.Ordinal))
            {
                await HandleCaptureImageAsync(store, line[captureImagePrefix.Length..]).ConfigureAwait(false);
                continue;
            }

            const string genLargePngPrefix = "gen-large-png ";
            if (line.StartsWith(genLargePngPrefix, StringComparison.Ordinal))
            {
                await HandleGenLargePngAsync(store, dataDir, line[genLargePngPrefix.Length..]).ConfigureAwait(false);
                continue;
            }

            const string capturePrefix = "capture ";
            if (line.StartsWith(capturePrefix, StringComparison.Ordinal))
            {
                await HandleCaptureAsync(store, line[capturePrefix.Length..]).ConfigureAwait(false);
                continue;
            }

            Console.Error.WriteLine("e2e-host unknown command");
        }
    }

    private static async Task HandleCaptureAsync(SqliteClipboardEventStore store, string encoded)
    {
        if (!TryDecodeBase64Url(encoded.Trim(), out var bytes) || bytes.Length == 0)
        {
            Console.Error.WriteLine("e2e-host capture rejected");
            return;
        }

        var text = Encoding.UTF8.GetString(bytes);
        if (string.IsNullOrEmpty(text))
        {
            Console.Error.WriteLine("e2e-host capture rejected");
            return;
        }

        var hash = Convert.ToHexString(SHA256.HashData(bytes)).ToLowerInvariant();
        var stored = await store.StoreAsync(
            new AcceptedClipboardContent(text, hash, bytes.Length, "e2e", DateTimeOffset.UtcNow)).ConfigureAwait(false);
        await WriteStdoutAsync("ok").ConfigureAwait(false);
        Console.Error.WriteLine("e2e-host captured seq=" + stored.OriginSequence.ToString(CultureInfo.InvariantCulture));
    }

    private static async Task HandleCaptureImageAsync(SqliteClipboardEventStore store, string encoded)
    {
        // Inspect exactly like the real capture ingress: magic bytes decide the mime type,
        // dimensions and hash are computed here — the seeded row is as honest as a capture.
        if (!TryDecodeBase64Url(encoded.Trim(), out var bytes) ||
            ImageCodec.TryInspect(bytes, out var inspected) != ImageCodecError.Ok ||
            inspected is null)
        {
            Console.Error.WriteLine("e2e-host capture-image rejected");
            return;
        }

        var stored = await store.StoreImageAsync(
            new AcceptedImageContent(
                bytes,
                inspected.ContentHash,
                inspected.MimeType,
                inspected.PixelWidth,
                inspected.PixelHeight,
                "e2e",
                DateTimeOffset.UtcNow)).ConfigureAwait(false);
        await WriteStdoutAsync("ok").ConfigureAwait(false);
        Console.Error.WriteLine(
            "e2e-host captured image seq=" + stored.OriginSequence.ToString(CultureInfo.InvariantCulture));
    }

    /// <summary>
    /// Generates a deterministic incompressible noise PNG for the multi-chunk leg — big
    /// enough that the transfer must stream several begin/chunk/end frames, unlike the tiny
    /// shared media fixtures. Args: <c>&lt;seed&gt; [capture]</c>; the bytes land in a temp
    /// file the driver hands to the Kotlin test, and <c>capture</c> additionally stores them
    /// as a local Windows image clip (the seed the Android side must pull byte-exact).
    /// Replies with one JSON line: <c>{"path": ..., "sha256": ..., "bytes": N}</c>.
    /// </summary>
    private static async Task HandleGenLargePngAsync(SqliteClipboardEventStore store, string dataDir, string args)
    {
        var parts = args.Trim().Split(' ', StringSplitOptions.RemoveEmptyEntries);
        var capture = parts.Length == 2 && parts[1].Equals("capture", StringComparison.Ordinal);
        if (parts.Length is < 1 or > 2 || !int.TryParse(parts[0], NumberStyles.None, CultureInfo.InvariantCulture, out var seed)
            || (parts.Length == 2 && !capture))
        {
            Console.Error.WriteLine("e2e-host gen-large-png rejected");
            return;
        }

        // 700x700 noise is incompressible: the PNG lands near 2 MB, i.e. ~8 chunk frames.
        const int side = 700;
        var bgra = new byte[side * side * 4];
        var state = (uint)seed;
        for (var index = 0; index < bgra.Length; index++)
        {
            state = (state * 1664525u) + 1013904223u;
            bgra[index] = (byte)(state >> 24);
        }

        var png = ImageCodec.EncodePngBgra(side, side, bgra);
        var hash = ImageCodec.HashBytes(png);
        var path = Path.Combine(dataDir, "large-" + seed.ToString(CultureInfo.InvariantCulture) + ".png");
        await File.WriteAllBytesAsync(path, png).ConfigureAwait(false);

        if (capture)
        {
            await store.StoreImageAsync(
                new AcceptedImageContent(png, hash, MediaLimits.MimePng, side, side, "e2e", DateTimeOffset.UtcNow))
                .ConfigureAwait(false);
        }

        await WriteStdoutAsync(JsonSerializer.Serialize(new LargePngPayload(path, hash, png.Length))).ConfigureAwait(false);
        Console.Error.WriteLine(
            "e2e-host gen-large-png seed=" + seed.ToString(CultureInfo.InvariantCulture)
            + " bytes=" + png.Length.ToString(CultureInfo.InvariantCulture)
            + " captured=" + capture);
    }

    private static async Task WriteStdoutAsync(string line)
    {
        await Console.Out.WriteLineAsync(line).ConfigureAwait(false);
        await Console.Out.FlushAsync().ConfigureAwait(false);
    }

    private static bool TryDecodeBase64Url(string value, out byte[] bytes)
    {
        bytes = [];
        if (string.IsNullOrWhiteSpace(value))
        {
            return false;
        }

        try
        {
            var padded = value.Replace('-', '+').Replace('_', '/');
            switch (padded.Length % 4)
            {
                case 2:
                    padded += "==";
                    break;
                case 3:
                    padded += "=";
                    break;
                case 1:
                    return false;
            }

            bytes = Convert.FromBase64String(padded);
            return true;
        }
        catch (FormatException)
        {
            return false;
        }
    }

    private static void TryDeleteDirectory(string dataDir)
    {
        try
        {
            if (Directory.Exists(dataDir))
            {
                Directory.Delete(dataDir, recursive: true);
            }
        }
        catch (IOException)
        {
        }
        catch (UnauthorizedAccessException)
        {
        }
    }
}

/// <summary>Identity protector for the test host only. Production uses DPAPI.</summary>
internal sealed class PassthroughSecretProtector : ISecretProtector
{
    public byte[] Protect(ReadOnlySpan<byte> plaintext) => plaintext.ToArray();

    public byte[] Unprotect(ReadOnlySpan<byte> ciphertext) => ciphertext.ToArray();
}

internal sealed class StderrLoggerFactory : ILoggerFactory
{
    public ILogger CreateLogger(string categoryName) => new StderrLogger(categoryName);

    public void AddProvider(ILoggerProvider provider)
    {
    }

    public void Dispose()
    {
    }

    private sealed class StderrLogger(string category) : ILogger
    {
        public IDisposable? BeginScope<TState>(TState state) where TState : notnull => null;

        public bool IsEnabled(LogLevel logLevel) => logLevel >= LogLevel.Information;

        public void Log<TState>(
            LogLevel logLevel,
            EventId eventId,
            TState state,
            Exception? exception,
            Func<TState, Exception?, string> formatter)
        {
            if (!IsEnabled(logLevel))
            {
                return;
            }

            Console.Error.WriteLine(category + " " + logLevel + ": " + formatter(state, exception));
        }
    }
}

internal sealed record ReadyPayload(
    [property: JsonPropertyName("port")] int Port,
    [property: JsonPropertyName("cert_sha256")] string CertSha256,
    [property: JsonPropertyName("windows_device_id")] string WindowsDeviceId,
    [property: JsonPropertyName("android_device_id")] string AndroidDeviceId,
    [property: JsonPropertyName("pair_secret_b64url")] string PairSecretB64url,
    [property: JsonPropertyName("trust_epoch")] long TrustEpoch,
    [property: JsonPropertyName("android_image_device_id")] string AndroidImageDeviceId,
    [property: JsonPropertyName("image_trust_epoch")] long ImageTrustEpoch,
    [property: JsonPropertyName("android_large_image_device_id")] string AndroidLargeImageDeviceId,
    [property: JsonPropertyName("large_image_trust_epoch")] long LargeImageTrustEpoch);

internal sealed record ListPayload(
    [property: JsonPropertyName("texts")] IReadOnlyList<string> Texts);

internal sealed record ImageListPayload(
    [property: JsonPropertyName("image_hashes")] IReadOnlyList<string> ImageHashes);

internal sealed record LargePngPayload(
    [property: JsonPropertyName("path")] string Path,
    [property: JsonPropertyName("sha256")] string Sha256,
    [property: JsonPropertyName("bytes")] int Bytes);

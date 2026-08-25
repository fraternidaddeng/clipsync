using System.Globalization;
using System.Net;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;
using ClipSync.Core.Clipboard;
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
/// clip, <c>list</c> prints the visible history as JSON, <c>quit</c> exits.
/// </summary>
internal static class Program
{
    internal const string WindowsDeviceId = "11111111-1111-4111-8111-111111111111";
    internal const string AndroidDeviceId = "22222222-2222-4222-8222-222222222222";

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
                        PingInterval = TimeSpan.FromSeconds(60)
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
                device.TrustEpoch);
            await WriteStdoutAsync(JsonSerializer.Serialize(ready)).ConfigureAwait(false);
            Console.Error.WriteLine("e2e-host listening");

            await RunCommandLoopAsync(store).ConfigureAwait(false);
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

    private static async Task RunCommandLoopAsync(SqliteClipboardEventStore store)
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
    [property: JsonPropertyName("trust_epoch")] long TrustEpoch);

internal sealed record ListPayload(
    [property: JsonPropertyName("texts")] IReadOnlyList<string> Texts);

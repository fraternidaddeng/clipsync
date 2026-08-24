using System.Collections.Concurrent;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using ClipSync.Core.Clipboard;
using ClipSync.Core.Protocol;
using ClipSync.Core.Security;
using ClipSync.Core.Storage;
using ClipSync.Peer.Pairing;
using ClipSync.Peer.Security;
using ClipSync.Peer.Server;
using ClipSync.Peer.Sessions;
using Microsoft.Data.Sqlite;
using Microsoft.Extensions.Logging;

namespace ClipSync.Tests.Peer;

/// <summary>Reversible fake for tests only; production uses DPAPI in the App layer.</summary>
public sealed class FakeSecretProtector : ISecretProtector
{
    private static readonly byte[] Mask = [0x5a, 0xa5, 0x3c];

    public byte[] Protect(ReadOnlySpan<byte> plaintext) => Transform(plaintext);

    public byte[] Unprotect(ReadOnlySpan<byte> ciphertext) => Transform(ciphertext);

    private static byte[] Transform(ReadOnlySpan<byte> input)
    {
        var output = new byte[input.Length];
        for (var index = 0; index < input.Length; index++)
        {
            output[index] = (byte)(input[index] ^ Mask[index % Mask.Length]);
        }

        return output;
    }
}

/// <summary>Captures every formatted log line so tests can assert log hygiene.</summary>
public sealed class CollectingLoggerFactory : ILoggerFactory
{
    public ConcurrentQueue<string> Lines { get; } = new();

    public ILogger CreateLogger(string categoryName) => new CollectingLogger(categoryName, Lines);

    public void AddProvider(ILoggerProvider provider)
    {
    }

    public void Dispose()
    {
    }

    private sealed class CollectingLogger(string category, ConcurrentQueue<string> lines) : ILogger
    {
        public IDisposable? BeginScope<TState>(TState state) where TState : notnull => null;

        public bool IsEnabled(LogLevel logLevel) => true;

        public void Log<TState>(
            LogLevel logLevel,
            EventId eventId,
            TState state,
            Exception? exception,
            Func<TState, Exception?, string> formatter)
        {
            lines.Enqueue($"{category} {logLevel}: {formatter(state, exception)} {exception}");
        }
    }
}

/// <summary>
/// Two paired stores ("windows" listener with a real Kestrel endpoint, "android" dialer)
/// sharing one pair secret, plus helpers to dial sessions and await convergence.
/// </summary>
public sealed class PeerPair : IAsyncDisposable
{
    public const string WindowsDeviceId = "11111111-1111-4111-8111-111111111111";
    public const string AndroidDeviceId = "22222222-2222-4222-8222-222222222222";

    private readonly string directory;
    private readonly List<DialedSession> sessions = [];
    private X509Certificate2 certificate = null!;

    private PeerPair(string directory)
    {
        this.directory = directory;
    }

    public FakeSecretProtector Protector { get; } = new();

    public CollectingLoggerFactory Logs { get; } = new();

    public SqliteClipboardEventStore WindowsStore { get; private set; } = null!;

    public SqliteClipboardEventStore AndroidStore { get; private set; } = null!;

    public PeerServer Server { get; private set; } = null!;

    public string ServerFingerprint { get; private set; } = string.Empty;

    public byte[] PairSecret { get; private set; } = [];

    public PairingService? Pairing { get; private set; }

    public static async Task<PeerPair> CreateAsync(
        bool pairAndroidSide = true,
        bool pairWindowsSide = true,
        int extraWindowsUpserts = 0,
        SyncSessionOptions? serverSessionOptions = null,
        bool useDifferentAndroidSecret = false,
        IPairingApprover? pairingApprover = null)
    {
        var directory = Path.Combine(Path.GetTempPath(), "clipsync-peer-tests", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(directory);
        var pair = new PeerPair(directory);

        pair.WindowsStore = new SqliteClipboardEventStore(Path.Combine(directory, "windows.db"), WindowsDeviceId);
        pair.AndroidStore = new SqliteClipboardEventStore(Path.Combine(directory, "android.db"), AndroidDeviceId);
        await pair.WindowsStore.InitializeAsync();
        await pair.AndroidStore.InitializeAsync();

        pair.PairSecret = RandomNumberGenerator.GetBytes(32);
        var protectedSecret = Convert.ToBase64String(pair.Protector.Protect(pair.PairSecret));
        var androidSecret = useDifferentAndroidSecret
            ? Convert.ToBase64String(pair.Protector.Protect(RandomNumberGenerator.GetBytes(32)))
            : protectedSecret;

        pair.certificate = PeerCertificate.CreateSelfSigned(WindowsDeviceId, DateTimeOffset.UtcNow, TimeSpan.FromDays(365));
        pair.ServerFingerprint = PeerCertificate.Fingerprint(pair.certificate);

        var now = DateTimeOffset.UtcNow;
        if (pairWindowsSide)
        {
            for (var upsert = 0; upsert <= extraWindowsUpserts; upsert++)
            {
                await pair.WindowsStore.UpsertDeviceAsync(
                    new NewPairedDevice(AndroidDeviceId, "Android", "android", "ab".PadLeft(64, 'a'), protectedSecret),
                    now);
            }
        }

        if (pairAndroidSide)
        {
            await pair.AndroidStore.UpsertDeviceAsync(
                new NewPairedDevice(WindowsDeviceId, "Windows", "windows", pair.ServerFingerprint, androidSecret),
                now);
        }

        if (pairingApprover is not null)
        {
            pair.Pairing = new PairingService(
                pair.WindowsStore,
                pair.Protector,
                pairingApprover,
                new PairingServiceOptions { LocalDisplayName = "DESKTOP-WIN" },
                pair.Logs.CreateLogger("ClipSync.Peer.Pairing"));
        }

        pair.Server = new PeerServer(
            pair.WindowsStore,
            pair.Protector,
            new PeerServerOptions
            {
                Certificate = pair.certificate,
                SessionOptions = serverSessionOptions ?? DefaultSessionOptions(),
                Port = 0
            },
            pair.Logs,
            pair.Pairing);
        await pair.Server.StartAsync();
        return pair;
    }

    /// <summary>An HTTP client that pins the listener certificate and sends the version header.</summary>
    public HttpClient CreatePinnedHttpClient()
    {
        var handler = new HttpClientHandler
        {
            ServerCertificateCustomValidationCallback = (_, certificate, _, _) =>
                certificate is not null
                && string.Equals(
                    Convert.ToHexString(SHA256.HashData(certificate.RawData)).ToLowerInvariant(),
                    ServerFingerprint,
                    StringComparison.Ordinal)
        };
        var client = new HttpClient(handler) { BaseAddress = new Uri($"https://127.0.0.1:{Server.Port}") };
        client.DefaultRequestHeaders.Add("X-Protocol-Version", "1");
        return client;
    }

    public static SyncSessionOptions DefaultSessionOptions() => new()
    {
        ClientVersion = "0.2.0",
        OutboxDrainInterval = TimeSpan.FromMilliseconds(100),
        PingInterval = TimeSpan.FromSeconds(60),
        ProtocolVersion = ProtocolLimits.ProtocolVersionV2
    };

    public static SyncSessionOptions DialerOptions() => DefaultSessionOptions() with
    {
        Platform = "android",
        ExpectedPeerDeviceId = WindowsDeviceId
    };

    /// <summary>Dials the listener and runs a session until the test closes it.</summary>
    public async Task<DialedSession> DialAsync(SyncSessionOptions? options = null)
    {
        var sessionOptions = options ?? DialerOptions();
        var transport = await ClipSync.Peer.Client.PeerSyncClient.ConnectAsync(
            "127.0.0.1",
            Server.Port,
            ServerFingerprint,
            sessionOptions.ProtocolVersion,
            CancellationToken.None);
        var engine = new SyncSessionEngine(
            SyncSessionRole.Dialer,
            AndroidStore,
            Protector,
            sessionOptions,
            authFailureSink: null,
            Logs.CreateLogger("ClipSync.Peer.DialerSession"));
        var committed = new List<RemoteClipApplied>();
        engine.RemoteClipsCommitted += batch =>
        {
            lock (committed)
            {
                committed.AddRange(batch);
            }
        };
        var readyPeers = new List<string>();
        engine.SessionReady += peerId =>
        {
            lock (readyPeers)
            {
                readyPeers.Add(peerId);
            }
        };
        var run = engine.RunAsync(transport, CancellationToken.None);
        var session = new DialedSession(engine, run, committed, readyPeers);
        sessions.Add(session);
        return session;
    }

    public static async Task<StoredClipboardEvent> CaptureAsync(SqliteClipboardEventStore store, string text)
    {
        var bytes = Encoding.UTF8.GetBytes(text);
        var hash = Convert.ToHexString(SHA256.HashData(bytes)).ToLowerInvariant();
        return await store.StoreAsync(new AcceptedClipboardContent(text, hash, bytes.Length, "test", DateTimeOffset.UtcNow));
    }

    public static async Task<StoredImageEvent> CaptureImageAsync(
        SqliteClipboardEventStore store,
        byte[] encoded,
        string contentHash,
        string mimeType = "image/png",
        int width = 1,
        int height = 1)
    {
        return await store.StoreImageAsync(new AcceptedImageContent(
            encoded,
            contentHash,
            mimeType,
            width,
            height,
            "test",
            DateTimeOffset.UtcNow));
    }

    public async Task WaitUntilAsync(Func<Task<bool>> condition, TimeSpan? timeout = null)
    {
        var deadline = DateTimeOffset.UtcNow + (timeout ?? TimeSpan.FromSeconds(20));
        while (DateTimeOffset.UtcNow < deadline)
        {
            if (await condition())
            {
                return;
            }

            await Task.Delay(50);
        }

        Assert.Fail("condition not met before timeout; logs:\n" + string.Join('\n', Logs.Lines.TakeLast(80)));
    }

    public static async Task<IReadOnlyList<string>> VisibleTextsAsync(SqliteClipboardEventStore store)
    {
        var items = await store.SearchAsync(new ClipboardHistoryQuery(Limit: 500));
        return items.Select(item => item.Text).ToArray();
    }

    public async ValueTask DisposeAsync()
    {
        foreach (var session in sessions)
        {
            await session.CloseQuietlyAsync();
        }

        await Server.DisposeAsync();
        certificate.Dispose();
        await WindowsStore.DisposeAsync();
        await AndroidStore.DisposeAsync();
        SqliteConnection.ClearAllPools();
        Directory.Delete(directory, recursive: true);
    }
}

public sealed record DialedSession(
    SyncSessionEngine Engine,
    Task<SyncSessionResult> Run,
    List<RemoteClipApplied> Committed,
    List<string> ReadyPeers)
{
    public async Task<SyncSessionResult> CloseAsync()
    {
        Engine.RequestClose();
        return await Run.WaitAsync(TimeSpan.FromSeconds(10));
    }

    public async Task CloseQuietlyAsync()
    {
        try
        {
            Engine.RequestClose();
            await Run.WaitAsync(TimeSpan.FromSeconds(10));
        }
        catch (TimeoutException)
        {
        }
        finally
        {
            Engine.Dispose();
        }
    }
}

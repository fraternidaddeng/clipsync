using System.IO;
using ClipSync.App.Security;
using ClipSync.App.Sync;
using ClipSync.Core.Storage;
using ClipSync.Peer.Resilience;
using ClipSync.Peer.Security;
using Microsoft.Data.Sqlite;

namespace ClipSync.App.Tests.Sync;

/// <summary>
/// Drives <see cref="PeerSyncHost"/> recovery through a fake <see cref="ISystemStateEvents"/>
/// source: a real listener starts, then simulated resume/network signals must leave the
/// endpoint online and re-raise the status event for the view model.
/// </summary>
public sealed class PeerSyncHostRecoveryTests : IAsyncDisposable
{
    private const string LocalDeviceId = "11111111-1111-4111-8111-111111111111";

    private static readonly SyncResilienceOptions FastOptions = new()
    {
        ResumeSettleDelay = TimeSpan.FromMilliseconds(20),
        NetworkChangeThrottle = TimeSpan.FromMilliseconds(20)
    };

    private readonly string directory;
    private readonly SqliteClipboardEventStore store;
    private readonly System.Security.Cryptography.X509Certificates.X509Certificate2 certificate;

    public PeerSyncHostRecoveryTests()
    {
        directory = Path.Combine(Path.GetTempPath(), "clipsync-host-recovery-tests", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(directory);
        store = new SqliteClipboardEventStore(Path.Combine(directory, "host.db"), LocalDeviceId);
        certificate = PeerCertificate.CreateSelfSigned(LocalDeviceId, DateTimeOffset.UtcNow, TimeSpan.FromDays(1));
    }

    private sealed class FakeSystemStateEvents : ISystemStateEvents
    {
        public event Action? ResumedFromSuspend;

        public event Action? NetworkAddressChanged;

        public void RaiseResume() => ResumedFromSuspend?.Invoke();

        public void RaiseNetworkChanged() => NetworkAddressChanged?.Invoke();
    }

    [Fact]
    public async Task ResumeSignalKeepsEndpointOnlineAndRaisesStatusChanged()
    {
        await store.InitializeAsync();
        var events = new FakeSystemStateEvents();
        await using var host = new PeerSyncHost(
            store,
            new DpapiSecretProtector(),
            certificate,
            systemEvents: events,
            resilienceOptions: FastOptions);
        await host.StartAsync(extraBindAddresses: null);
        var portBefore = host.Port;
        var hostsBefore = host.ReachableHosts;
        var statusChanges = 0;
        host.PeerStatusChanged += () => Interlocked.Increment(ref statusChanges);

        events.RaiseResume();

        await WaitUntilAsync(() => Volatile.Read(ref statusChanges) >= 1);
        Assert.True(host.IsRunning);
        // The interface set did not change across the simulated suspend, so the listener
        // (and with it the advertised port and QR hosts) must survive untouched.
        Assert.Equal(portBefore, host.Port);
        Assert.Equal(hostsBefore, host.ReachableHosts);
    }

    [Fact]
    public async Task QuietNetworkChangeSignalLeavesEndpointUntouched()
    {
        await store.InitializeAsync();
        var events = new FakeSystemStateEvents();
        await using var host = new PeerSyncHost(
            store,
            new DpapiSecretProtector(),
            certificate,
            systemEvents: events,
            resilienceOptions: FastOptions);
        await host.StartAsync(extraBindAddresses: null);
        var portBefore = host.Port;
        var statusChanges = 0;
        host.PeerStatusChanged += () => Interlocked.Increment(ref statusChanges);

        // No interface actually changed, so this must only re-beacon: no status event,
        // no rebind, same port.
        events.RaiseNetworkChanged();
        await Task.Delay(200);

        Assert.True(host.IsRunning);
        Assert.Equal(portBefore, host.Port);
        Assert.Equal(0, Volatile.Read(ref statusChanges));
    }

    [Fact]
    public async Task RecoverBeforeStartIsANoOp()
    {
        await store.InitializeAsync();
        await using var host = new PeerSyncHost(
            store,
            new DpapiSecretProtector(),
            certificate,
            systemEvents: new FakeSystemStateEvents(),
            resilienceOptions: FastOptions);

        await host.RecoverAsync(afterResume: true);

        Assert.False(host.IsRunning);
        Assert.Empty(host.ReachableHosts);
    }

    private static async Task WaitUntilAsync(Func<bool> condition, TimeSpan? timeout = null)
    {
        var deadline = DateTimeOffset.UtcNow + (timeout ?? TimeSpan.FromSeconds(15));
        while (DateTimeOffset.UtcNow < deadline)
        {
            if (condition())
            {
                return;
            }

            await Task.Delay(10);
        }

        Assert.Fail("condition not met before timeout");
    }

    public async ValueTask DisposeAsync()
    {
        certificate.Dispose();
        await store.DisposeAsync();
        SqliteConnection.ClearAllPools();
        Directory.Delete(directory, recursive: true);
    }
}

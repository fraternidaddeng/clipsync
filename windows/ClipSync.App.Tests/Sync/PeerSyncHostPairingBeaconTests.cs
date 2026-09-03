using System.IO;
using ClipSync.App.Diagnostics;
using ClipSync.App.Security;
using ClipSync.App.Sync;
using ClipSync.Core.Storage;
using ClipSync.Peer.Security;
using Microsoft.Data.Sqlite;

namespace ClipSync.App.Tests.Sync;

/// <summary>
/// The pairing beacon handle on <see cref="PeerSyncHost"/>: reference-counted holds, idempotent
/// disposal, and the two diagnostics codes that bracket the dense cadence. The cadence itself
/// is pinned by the pure <c>DiscoveryBeaconSchedule</c> tests in ClipSync.Tests.
/// </summary>
public sealed class PeerSyncHostPairingBeaconTests : IAsyncDisposable
{
    private const string LocalDeviceId = "22222222-2222-4222-8222-222222222222";

    private readonly string directory;
    private readonly SqliteClipboardEventStore store;
    private readonly System.Security.Cryptography.X509Certificates.X509Certificate2 certificate;

    public PeerSyncHostPairingBeaconTests()
    {
        directory = Path.Combine(Path.GetTempPath(), "clipsync-host-beacon-tests", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(directory);
        store = new SqliteClipboardEventStore(Path.Combine(directory, "host.db"), LocalDeviceId);
        certificate = PeerCertificate.CreateSelfSigned(LocalDeviceId, DateTimeOffset.UtcNow, TimeSpan.FromDays(1));
    }

    [Fact]
    public async Task HoldsAreReferenceCountedAndDisposalIsIdempotent()
    {
        await using var host = new PeerSyncHost(store, new DpapiSecretProtector(), certificate);
        Assert.False(host.PairingBeaconActive);

        var first = host.BeginPairingBeacon();
        var second = host.BeginPairingBeacon();
        Assert.True(host.PairingBeaconActive);

        first.Dispose();
        // The other QR window is still open: the dense cadence must survive.
        Assert.True(host.PairingBeaconActive);

        // Disposing the same handle again must not release the hold that is still held.
        first.Dispose();
        Assert.True(host.PairingBeaconActive);

        second.Dispose();
        Assert.False(host.PairingBeaconActive);
    }

    [Fact]
    public async Task StartAndStopAreRecordedAsDiagnosticsCodesOnceForTheOuterHold()
    {
        await using var host = new PeerSyncHost(store, new DpapiSecretProtector(), certificate);
        var before = LocalDiagnostics.Snapshot().Select(entry => entry.Code).ToList();

        using (host.BeginPairingBeacon())
        {
            using (host.BeginPairingBeacon())
            {
            }
        }

        // Other test classes share the ring buffer, so count our own codes rather than
        // asserting on the exact tail.
        var after = LocalDiagnostics.Snapshot().Select(entry => entry.Code).ToList();
        Assert.Equal(
            before.Count(code => code == "pairing_beacon_started") + 1,
            after.Count(code => code == "pairing_beacon_started"));
        Assert.Equal(
            before.Count(code => code == "pairing_beacon_stopped") + 1,
            after.Count(code => code == "pairing_beacon_stopped"));
    }

    [Fact]
    public async Task BeaconHoldSurvivesUntilTheListenerIsRunning()
    {
        // The QR window may open before the endpoint finished starting (or after a failed
        // start): the hold is a schedule fact, not a socket fact, so it must just work.
        await store.InitializeAsync();
        await using var host = new PeerSyncHost(store, new DpapiSecretProtector(), certificate);
        using var hold = host.BeginPairingBeacon();

        await host.StartAsync(extraBindAddresses: null);

        Assert.True(host.IsRunning);
        Assert.True(host.PairingBeaconActive);
    }

    public async ValueTask DisposeAsync()
    {
        certificate.Dispose();
        await store.DisposeAsync();
        SqliteConnection.ClearAllPools();
        Directory.Delete(directory, recursive: true);
    }
}

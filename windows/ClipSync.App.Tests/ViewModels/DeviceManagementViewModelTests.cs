using System.IO;
using System.Security.Cryptography;
using System.Text;
using ClipSync.App.Clipboard;
using ClipSync.App.ViewModels;
using ClipSync.Core.Clipboard;
using ClipSync.Core.Storage;
using Microsoft.Data.Sqlite;

namespace ClipSync.App.Tests.ViewModels;

public sealed class DeviceManagementViewModelTests : IAsyncDisposable
{
    private const string LocalDeviceId = "11111111-1111-4111-8111-111111111111";
    private const string PeerDeviceId = "22222222-2222-4222-8222-222222222222";
    private const string GhostDeviceId = "33333333-3333-4333-8333-333333333333";

    private readonly string directory;
    private readonly SqliteClipboardEventStore store;
    private readonly Win32ClipboardAdapter adapter = new();
    private readonly MainViewModel viewModel;

    public DeviceManagementViewModelTests()
    {
        directory = Path.Combine(Path.GetTempPath(), "clipsync-vm-tests", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(directory);
        store = new SqliteClipboardEventStore(Path.Combine(directory, "vm.db"), LocalDeviceId);
        viewModel = new MainViewModel(store, new ClipboardCapturePolicy(), adapter);
    }

    [Fact]
    public async Task InitializeLoadsPairedDevices()
    {
        await SeedDeviceAsync();
        await viewModel.InitializeAsync();

        var device = Assert.Single(viewModel.Devices);
        Assert.Equal(PeerDeviceId, device.DeviceId);
        Assert.Equal("Pixel 8", device.DisplayName);
        Assert.Equal("Android", device.Platform);
        Assert.False(device.IsRevoked);
        Assert.Equal("Never connected", device.LastSeenText);

        // A freshly paired device is healthy: no stale badge, no cleanup banner.
        Assert.False(device.IsStale);
        Assert.False(viewModel.HasStaleDevices);
        Assert.Equal(string.Empty, viewModel.StaleBannerText);
        Assert.False(viewModel.CleanupStaleDevicesCommand.CanExecute(null));
    }

    [Fact]
    public async Task DuplicateSameNameEntryIsFlaggedStaleWithItsBacklog()
    {
        await SeedGhostAndCurrentPairAsync();
        await CaptureLocalAsync("queued while the ghost lingers");

        await viewModel.InitializeAsync();

        // The older same-name identity carries the stale badge and its own backlog count.
        var ghost = Assert.Single(viewModel.Devices, device => device.DeviceId == GhostDeviceId);
        Assert.True(ghost.IsStale);
        Assert.Contains("疑似重复配对残留", ghost.StaleReason, StringComparison.Ordinal);
        Assert.Contains("积压 1 条", ghost.StaleReason, StringComparison.Ordinal);
        Assert.Equal(1, ghost.PendingCount);

        // The current identity of the same phone stays unflagged.
        var current = Assert.Single(viewModel.Devices, device => device.DeviceId == PeerDeviceId);
        Assert.False(current.IsStale);

        Assert.True(viewModel.HasStaleDevices);
        Assert.Equal(1, viewModel.StaleDeviceCount);
        Assert.Contains("1 台疑似残留设备", viewModel.StaleBannerText, StringComparison.Ordinal);
        Assert.Contains("积压 1 条", viewModel.StaleBannerText, StringComparison.Ordinal);
    }

    [Fact]
    public async Task CleanupStaleDevicesRevokesGhostsAndDropsTheirBacklog()
    {
        await SeedGhostAndCurrentPairAsync();
        await CaptureLocalAsync("queued while the ghost lingers");
        await viewModel.InitializeAsync();
        var revokedIds = new List<string>();
        viewModel.DeviceRevoked += id => revokedIds.Add(id);

        Assert.True(viewModel.CleanupStaleDevicesCommand.CanExecute(null));
        await viewModel.CleanupStaleDevicesCommand.ExecuteAsync(null);

        // The ghost is revoked with its outbox rows gone, deflating the 待发 count to the
        // one row still owed to the live device; the live device is untouched.
        Assert.Equal(new[] { GhostDeviceId }, revokedIds);
        var ghost = await store.GetDeviceAsync(GhostDeviceId);
        Assert.NotNull(ghost);
        Assert.True(ghost.IsRevoked);
        Assert.Empty(await store.GetOutboxBatchAsync(GhostDeviceId, 10));
        Assert.False((await store.GetDeviceAsync(PeerDeviceId))!.IsRevoked);
        Assert.Single(await store.GetOutboxBatchAsync(PeerDeviceId, 10));
        Assert.Equal(1, viewModel.OutboxPendingCount);

        Assert.False(viewModel.HasStaleDevices);
        Assert.Equal(string.Empty, viewModel.StaleBannerText);
        Assert.False(viewModel.CleanupStaleDevicesCommand.CanExecute(null));
    }

    [Fact]
    public async Task LongUnseenDeviceIsFlaggedStale()
    {
        await store.InitializeAsync();
        await store.UpsertDeviceAsync(
            new NewPairedDevice(PeerDeviceId, "Pixel 8", "android", CertificateFingerprint: string.Empty, "c2VjcmV0"),
            DateTimeOffset.UtcNow.AddDays(-40));
        await store.UpdateDeviceLastSeenAsync(PeerDeviceId, DateTimeOffset.UtcNow.AddDays(-15));

        await viewModel.InitializeAsync();

        var device = Assert.Single(viewModel.Devices);
        Assert.True(device.IsStale);
        Assert.Contains("14 天未连接", device.StaleReason, StringComparison.Ordinal);
        Assert.True(viewModel.HasStaleDevices);
    }

    [Fact]
    public async Task RenameTrimsAndPersists()
    {
        await SeedDeviceAsync();
        await viewModel.InitializeAsync();
        viewModel.SelectedDevice = viewModel.Devices[0];
        viewModel.RenameText = "  Bedroom phone  ";

        await viewModel.RenameDeviceCommand.ExecuteAsync(null);

        var stored = await store.GetDeviceAsync(PeerDeviceId);
        Assert.Equal("Bedroom phone", stored!.DisplayName);
        Assert.Equal("Bedroom phone", Assert.Single(viewModel.Devices).DisplayName);
    }

    [Fact]
    public async Task RevokeRaisesEventMarksDeviceAndBlocksSecondRevoke()
    {
        await SeedDeviceAsync();
        await viewModel.InitializeAsync();
        string? revokedId = null;
        viewModel.DeviceRevoked += id => revokedId = id;
        viewModel.SelectedDevice = viewModel.Devices[0];

        Assert.True(viewModel.RevokeDeviceCommand.CanExecute(null));
        await viewModel.RevokeDeviceCommand.ExecuteAsync(null);

        Assert.Equal(PeerDeviceId, revokedId);
        var device = Assert.Single(viewModel.Devices);
        Assert.True(device.IsRevoked);
        var stored = await store.GetDeviceAsync(PeerDeviceId);
        Assert.True(stored!.IsRevoked);
        Assert.Empty(stored.PairSecretProtected);

        // A revoked selection cannot be revoked again from the UI.
        viewModel.SelectedDevice = viewModel.Devices[0];
        Assert.False(viewModel.RevokeDeviceCommand.CanExecute(null));
    }

    [Fact]
    public async Task SelectingADeviceSeedsTheRenameBox()
    {
        await SeedDeviceAsync();
        await viewModel.InitializeAsync();

        viewModel.SelectedDevice = viewModel.Devices[0];

        Assert.Equal("Pixel 8", viewModel.RenameText);
    }

    [Fact]
    public async Task DeviceAccentSwatchTapPinsColourAndDefaultTapClearsIt()
    {
        await SeedDeviceAsync();
        await viewModel.InitializeAsync();

        // First paired device follows pairing order: dev-1, worded as a fact.
        var device = Assert.Single(viewModel.Devices);
        Assert.Equal(1, device.AccentIndex);
        Assert.Equal(1, device.DefaultAccentIndex);
        Assert.False(device.HasCustomAccent);
        Assert.Equal("跟随配对顺位", device.AccentSourceText);
        Assert.Equal(5, device.AccentSwatches.Count);
        Assert.True(device.AccentSwatches.Single(swatch => swatch.Slot == 1).IsSelected);

        // Tapping the third dot pins dev-3 (设备色手动改, P1#14).
        await viewModel.SetDeviceAccentCommand.ExecuteAsync(
            device.AccentSwatches.Single(swatch => swatch.Slot == 3));
        device = Assert.Single(viewModel.Devices);
        Assert.Equal(3, device.AccentIndex);
        Assert.True(device.HasCustomAccent);
        Assert.Equal("手动指定", device.AccentSourceText);
        Assert.True(device.AccentSwatches.Single(swatch => swatch.Slot == 3).IsSelected);
        Assert.Equal(3, (await store.GetDeviceAsync(PeerDeviceId))!.AccentOverride);

        // Tapping the pairing-order default clears the stored override instead of pinning it.
        var defaultSwatch = device.AccentSwatches.Single(swatch => swatch.IsDefault);
        Assert.Null(defaultSwatch.OverrideToStore);
        await viewModel.SetDeviceAccentCommand.ExecuteAsync(defaultSwatch);
        device = Assert.Single(viewModel.Devices);
        Assert.Equal(1, device.AccentIndex);
        Assert.False(device.HasCustomAccent);
        Assert.Null((await store.GetDeviceAsync(PeerDeviceId))!.AccentOverride);
    }

    private async Task SeedDeviceAsync()
    {
        await store.InitializeAsync();
        await store.UpsertDeviceAsync(
            new NewPairedDevice(PeerDeviceId, "Pixel 8", "android", CertificateFingerprint: string.Empty, "c2VjcmV0"),
            DateTimeOffset.UtcNow);
    }

    /// <summary>
    /// The manual-QA scenario: the same phone paired days ago under one id, then re-paired
    /// under a fresh id, so two active rows share one display name and the old one lags.
    /// </summary>
    private async Task SeedGhostAndCurrentPairAsync()
    {
        await store.InitializeAsync();
        var now = DateTimeOffset.UtcNow;
        await store.UpsertDeviceAsync(
            new NewPairedDevice(GhostDeviceId, "Xiaomi 22041216C", "android", CertificateFingerprint: string.Empty, "b2xk"),
            now.AddDays(-4));
        await store.UpdateDeviceLastSeenAsync(GhostDeviceId, now.AddDays(-4));
        await store.UpsertDeviceAsync(
            new NewPairedDevice(PeerDeviceId, "Xiaomi 22041216C", "android", CertificateFingerprint: string.Empty, "c2VjcmV0"),
            now.AddHours(-1));
        await store.UpdateDeviceLastSeenAsync(PeerDeviceId, now);
    }

    /// <summary>A local capture fans out one outbox row to every non-revoked device.</summary>
    private async Task CaptureLocalAsync(string text)
    {
        var bytes = Encoding.UTF8.GetBytes(text);
        var hash = Convert.ToHexString(SHA256.HashData(bytes)).ToLowerInvariant();
        await store.StoreAsync(new AcceptedClipboardContent(text, hash, bytes.Length, "notepad", DateTimeOffset.UtcNow));
    }

    public async ValueTask DisposeAsync()
    {
        adapter.Dispose();
        await store.DisposeAsync();
        SqliteConnection.ClearAllPools();
        Directory.Delete(directory, recursive: true);
    }
}

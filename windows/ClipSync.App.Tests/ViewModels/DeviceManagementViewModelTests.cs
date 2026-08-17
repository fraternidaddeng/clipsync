using System.IO;
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

    private async Task SeedDeviceAsync()
    {
        await store.InitializeAsync();
        await store.UpsertDeviceAsync(
            new NewPairedDevice(PeerDeviceId, "Pixel 8", "android", CertificateFingerprint: string.Empty, "c2VjcmV0"),
            DateTimeOffset.UtcNow);
    }

    public async ValueTask DisposeAsync()
    {
        adapter.Dispose();
        await store.DisposeAsync();
        SqliteConnection.ClearAllPools();
        Directory.Delete(directory, recursive: true);
    }
}

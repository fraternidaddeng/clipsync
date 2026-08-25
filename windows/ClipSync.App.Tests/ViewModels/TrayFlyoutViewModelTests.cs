using System.IO;
using System.Security.Cryptography;
using System.Text;
using ClipSync.App.Clipboard;
using ClipSync.App.ViewModels;
using ClipSync.Core.Clipboard;
using ClipSync.Core.Storage;
using Microsoft.Data.Sqlite;

namespace ClipSync.App.Tests.ViewModels;

/// <summary>
/// ViewModel state behind the tray flyout: the recent-clip strip, the one-line status
/// text, and the neighbour-hue accents assigned by pairing order.
/// </summary>
public sealed class TrayFlyoutViewModelTests : IAsyncDisposable
{
    private const string LocalDeviceId = "11111111-1111-4111-8111-111111111111";

    private readonly string directory;
    private readonly SqliteClipboardEventStore store;
    private readonly Win32ClipboardAdapter adapter = new();
    private readonly MainViewModel viewModel;

    public TrayFlyoutViewModelTests()
    {
        directory = Path.Combine(Path.GetTempPath(), "clipsync-flyout-tests", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(directory);
        store = new SqliteClipboardEventStore(Path.Combine(directory, "flyout.db"), LocalDeviceId);
        viewModel = new MainViewModel(store, new ClipboardCapturePolicy(), adapter);
    }

    [Fact]
    public void TrayStatusTextFollowsChartersPriorityOrder()
    {
        // flow, endpoint not started yet
        Assert.Equal("监听中 · 同步未启动", viewModel.TrayStatusText);

        viewModel.UpdatePeerStatus(online: true, port: 40000, connectedCount: 0);
        Assert.Equal("监听中 · 等待设备连入", viewModel.TrayStatusText);

        viewModel.UpdatePeerStatus(online: true, port: 40000, connectedCount: 2);
        Assert.Equal("监听中 · 已连 2 台", viewModel.TrayStatusText);

        viewModel.CaptureFaulted = true;
        Assert.Equal("捕获降级 · 剪贴板访问失败", viewModel.TrayStatusText);

        // paused beats attention; private beats paused (same order as the tray icon)
        viewModel.IsPaused = true;
        Assert.Equal("已暂停 · 不再记录", viewModel.TrayStatusText);

        viewModel.IsPrivateMode = true;
        Assert.Equal("私密模式 · 不留痕迹", viewModel.TrayStatusText);
    }

    [Fact]
    public void TrayStatusTextRaisesChangeNotifications()
    {
        var raised = 0;
        viewModel.PropertyChanged += (_, e) =>
        {
            if (e.PropertyName == nameof(MainViewModel.TrayStatusText))
            {
                raised++;
            }
        };

        viewModel.IsPaused = true;
        viewModel.IsPrivateMode = true;
        viewModel.CaptureFaulted = true;
        viewModel.UpdatePeerStatus(online: true, port: 40000, connectedCount: 1);

        // PeerOnline and ConnectedDeviceCount both notify on the last call.
        Assert.True(raised >= 4);
    }

    [Fact]
    public async Task RecentHistoryKeepsTheNewestFourClips()
    {
        await store.InitializeAsync();
        var start = DateTimeOffset.UtcNow.AddMinutes(-10);
        for (var i = 0; i < 6; i++)
        {
            await CaptureLocalAsync($"clip {i}", start.AddMinutes(i));
        }

        await viewModel.InitializeAsync();

        Assert.Equal(6, viewModel.History.Count);
        Assert.Equal(4, viewModel.RecentHistory.Count);
        Assert.Equal("clip 5", viewModel.RecentHistory[0].Text);
        Assert.Equal("clip 2", viewModel.RecentHistory[3].Text);
    }

    [Fact]
    public async Task DeviceAccentsFollowPairingOrderAndCycle()
    {
        await store.InitializeAsync();
        var start = DateTimeOffset.UtcNow.AddDays(-1);
        for (var i = 0; i < 6; i++)
        {
            await store.UpsertDeviceAsync(
                new NewPairedDevice(DeviceId(i + 2), $"Device {i}", "android", string.Empty, "c2VjcmV0"),
                start.AddMinutes(i));
        }

        await viewModel.InitializeAsync();

        int[] expected = [1, 2, 3, 4, 5, 1];
        Assert.Equal(expected, viewModel.Devices.Select(device => device.AccentIndex));
    }

    [Fact]
    public async Task HistoryOriginBoxesUseTheOriginDevicesAccent()
    {
        await store.InitializeAsync();
        var start = DateTimeOffset.UtcNow.AddDays(-1);
        await store.UpsertDeviceAsync(
            new NewPairedDevice(DeviceId(2), "Pixel 8", "android", string.Empty, "c2VjcmV0"), start);
        await store.UpsertDeviceAsync(
            new NewPairedDevice(DeviceId(3), "Redmi K60", "android", string.Empty, "c2VjcmV0"), start.AddMinutes(1));

        await CaptureLocalAsync("typed here", DateTimeOffset.UtcNow);
        await StoreRemoteAsync(DeviceId(3), "from the second device", 1);
        await StoreRemoteAsync(DeviceId(9), "from a stranger", 1);

        await viewModel.InitializeAsync();

        var local = Assert.Single(viewModel.History, item => item.Text == "typed here");
        Assert.Equal(0, local.OriginAccentIndex);

        var second = Assert.Single(viewModel.History, item => item.Text == "from the second device");
        Assert.Equal(2, second.OriginAccentIndex); // second paired device = water-blue 215

        var unknown = Assert.Single(viewModel.History, item => item.Text == "from a stranger");
        Assert.Equal(0, unknown.OriginAccentIndex);
    }

    private static string DeviceId(int n) => $"{n}{n}{n}{n}{n}{n}{n}{n}-{n}{n}{n}{n}-4{n}{n}{n}-8{n}{n}{n}-{n}{n}{n}{n}{n}{n}{n}{n}{n}{n}{n}{n}";

    private async Task CaptureLocalAsync(string text, DateTimeOffset at)
    {
        var bytes = Encoding.UTF8.GetBytes(text);
        await store.StoreAsync(new AcceptedClipboardContent(text, Hash(text), bytes.Length, "notepad", at));
    }

    private async Task StoreRemoteAsync(string originDeviceId, string text, long seq)
    {
        await store.StoreRemoteEventAsync(
            new RemoteClipEvent(Guid.NewGuid(), originDeviceId, seq, text, Hash(text), "phone-app", DateTimeOffset.UtcNow, null),
            sourcePeerId: originDeviceId);
    }

    private static string Hash(string text) =>
        Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(text))).ToLowerInvariant();

    public async ValueTask DisposeAsync()
    {
        adapter.Dispose();
        await store.DisposeAsync();
        SqliteConnection.ClearAllPools();
        Directory.Delete(directory, recursive: true);
    }
}

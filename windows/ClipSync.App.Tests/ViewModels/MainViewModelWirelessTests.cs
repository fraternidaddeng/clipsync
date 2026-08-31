using System.IO;
using ClipSync.App.Clipboard;
using ClipSync.App.ViewModels;
using ClipSync.Core.Clipboard;
using ClipSync.Core.Clipboard.PrivilegedHost;
using ClipSync.Core.Storage;
using Microsoft.Data.Sqlite;

namespace ClipSync.App.Tests.ViewModels;

/// <summary>
/// The 特权直读 card's wireless-debugging honesty, driven end to end through the view model
/// against a scripted adb: a connect that succeeds is remembered, a later probe that
/// contradicts it states the session loss (端口漂移) with the recovery step instead of the
/// stale "已连接" line, a stale "already connected" is re-dialed and the recovery stated, an
/// offline wireless listing names the wireless session instead of advising 重新插拔, and
/// revoking consent mid-chain stops the chain without another adb call.
/// </summary>
public sealed class MainViewModelWirelessTests : IAsyncDisposable
{
    private const string Endpoint = "192.168.1.10:40331";
    private const string ReadyListing =
        "List of devices attached\n192.168.1.10:40331     device product:panther model:Pixel_8 device:panther\n";
    private const string OfflineListing =
        "List of devices attached\n192.168.1.10:40331     offline\n";

    private readonly string directory;
    private readonly SqliteClipboardEventStore store;
    private readonly Win32ClipboardAdapter adapter = new();
    private readonly ScriptedAdbRunner adb = new();
    private readonly MainViewModel viewModel;

    public MainViewModelWirelessTests()
    {
        directory = Path.Combine(Path.GetTempPath(), "clipsync-wireless-tests", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(directory);
        store = new SqliteClipboardEventStore(Path.Combine(directory, "wireless.db"), Guid.NewGuid().ToString());
        viewModel = new MainViewModel(
            store,
            new ClipboardCapturePolicy(),
            adapter,
            privilegedHost: new PrivilegedHostAssistant(adb))
        {
            PrivilegedAdbConsent = true,
        };
    }

    [Fact]
    public async Task SuccessfulConnectIsRememberedAndALaterOfflineProbeStatesTheLoss()
    {
        adb.OnArgs(
            ["connect", Endpoint],
            new AdbCommandResult(0, $"connected to {Endpoint}\n", string.Empty));
        // First listing (the post-connect 检测手机) backs the connection; the second (a later
        // manual 检测手机 after 息屏/切网 drift) contradicts it.
        adb.OnArgs(
            ["devices", "-l"],
            new AdbCommandResult(0, ReadyListing, string.Empty),
            new AdbCommandResult(0, OfflineListing, string.Empty));
        adb.OnArgs(
            ["-s", Endpoint, "shell", "pgrep", "-f", "clipsync_priv_server"],
            new AdbCommandResult(0, "1234\n", string.Empty));

        viewModel.WirelessConnectEndpointText = Endpoint;
        await viewModel.ConnectWirelessCommand.ExecuteAsync(null);

        // The connect chain's own narration survives the ready probe untouched.
        Assert.Contains("已连接 " + Endpoint, viewModel.WirelessStatus, StringComparison.Ordinal);
        Assert.Equal(string.Empty, viewModel.WirelessHint);
        Assert.Contains("特权直读通道运行中", viewModel.PrivilegedStatus, StringComparison.Ordinal);

        await viewModel.DetectPhoneCommand.ExecuteAsync(null);

        // The stale "已连接" is replaced by the loss plus the recovery step (核对 IP:端口,
        // 配对不用重来) — on both the wireless section and the card's main status line.
        Assert.Contains("无线连接已断开", viewModel.WirelessStatus, StringComparison.Ordinal);
        Assert.Contains(Endpoint, viewModel.WirelessStatus, StringComparison.Ordinal);
        Assert.Contains("无线调试", viewModel.WirelessHint, StringComparison.Ordinal);
        Assert.Contains("无线调试会话已失效", viewModel.PrivilegedStatus, StringComparison.Ordinal);
        Assert.DoesNotContain("重新插拔", viewModel.PrivilegedStatus, StringComparison.Ordinal);
    }

    [Fact]
    public async Task StaleAlreadyConnectedSessionIsRedialedAndTheRecoveryStated()
    {
        // adb's session table answers "already connected" for a dead transport; the device
        // list shows the entry offline. The verified connect disconnects, re-dials, and the
        // recovery is stated on the card — never silent, never a false "已连接" over a corpse.
        adb.OnArgs(
            ["connect", Endpoint],
            new AdbCommandResult(0, $"already connected to {Endpoint}\n", string.Empty),
            new AdbCommandResult(0, $"connected to {Endpoint}\n", string.Empty));
        adb.OnArgs(
            ["devices", "-l"],
            new AdbCommandResult(0, OfflineListing, string.Empty),
            new AdbCommandResult(0, ReadyListing, string.Empty));
        adb.OnArgs(
            ["disconnect", Endpoint],
            new AdbCommandResult(0, $"disconnected {Endpoint}\n", string.Empty));
        adb.OnArgs(
            ["-s", Endpoint, "shell", "pgrep", "-f", "clipsync_priv_server"],
            new AdbCommandResult(1, string.Empty, string.Empty));

        viewModel.WirelessConnectEndpointText = Endpoint;
        await viewModel.ConnectWirelessCommand.ExecuteAsync(null);

        Assert.Contains("已连接 " + Endpoint, viewModel.WirelessStatus, StringComparison.Ordinal);
        Assert.Contains("旧无线会话", viewModel.WirelessHint, StringComparison.Ordinal);
        Assert.Contains(
            adb.Invocations.Select(args => string.Join(' ', args)),
            line => line == "disconnect " + Endpoint);
    }

    [Fact]
    public async Task ConnectFailureStatesTheFactAndPointsAtThePhonesCurrentEndpoint()
    {
        adb.OnArgs(
            ["connect", Endpoint],
            new AdbCommandResult(0, $"failed to connect to '{Endpoint}': Connection refused\n", string.Empty));

        viewModel.WirelessConnectEndpointText = Endpoint;
        await viewModel.ConnectWirelessCommand.ExecuteAsync(null);

        Assert.Contains("连接失败", viewModel.WirelessStatus, StringComparison.Ordinal);
        // The hint names the usual culprit (port drift) and where the current value lives.
        Assert.Contains("无线调试", viewModel.WirelessHint, StringComparison.Ordinal);
        Assert.Contains("IP 地址和端口", viewModel.WirelessHint, StringComparison.Ordinal);
    }

    [Fact]
    public async Task OfflineWirelessListingNamesTheStaleSessionInsteadOfCableAdvice()
    {
        adb.OnArgs(["devices", "-l"], new AdbCommandResult(0, OfflineListing, string.Empty));

        await viewModel.DetectPhoneCommand.ExecuteAsync(null);

        Assert.Contains("无线调试会话已失效", viewModel.PrivilegedStatus, StringComparison.Ordinal);
        Assert.Contains(Endpoint, viewModel.PrivilegedStatus, StringComparison.Ordinal);
        Assert.DoesNotContain("重新插拔", viewModel.PrivilegedStatus, StringComparison.Ordinal);
    }

    [Fact]
    public async Task OfflineUsbListingKeepsTheCableAdvice()
    {
        adb.OnArgs(
            ["devices", "-l"],
            new AdbCommandResult(0, "List of devices attached\nRF8N123456     offline\n", string.Empty));

        await viewModel.DetectPhoneCommand.ExecuteAsync(null);

        Assert.Contains("重新插拔", viewModel.PrivilegedStatus, StringComparison.Ordinal);
    }

    [Fact]
    public async Task UnprobeableHostStateIsStatedAsUnknownNotStopped()
    {
        adb.OnArgs(["devices", "-l"], new AdbCommandResult(0, ReadyListing, string.Empty));
        // pgrep killed / shell error: exit 137 with no output is a failed probe, not "no match".
        adb.OnArgs(
            ["-s", Endpoint, "shell", "pgrep", "-f", "clipsync_priv_server"],
            new AdbCommandResult(137, string.Empty, string.Empty));

        await viewModel.DetectPhoneCommand.ExecuteAsync(null);

        Assert.Contains("暂时无法确认", viewModel.PrivilegedStatus, StringComparison.Ordinal);
        Assert.False(viewModel.PrivilegedHostRunning);
        // The device itself is ready, so the start button must stay live: restarting is the
        // suggested harmless next step.
        Assert.True(viewModel.PrivilegedDeviceReady);
    }

    [Fact]
    public async Task RevokingConsentMidConnectStopsTheChainWithoutAnotherAdbCall()
    {
        await store.InitializeAsync();
        var gate = new TaskCompletionSource<AdbCommandResult>(TaskCreationOptions.RunContinuationsAsynchronously);
        adb.OnArgsPending(["connect", Endpoint], gate.Task);

        viewModel.WirelessConnectEndpointText = Endpoint;
        var connecting = viewModel.ConnectWirelessCommand.ExecuteAsync(null);

        // 撤销同意 while adb connect is still in flight.
        await viewModel.RevokePrivilegedConsentCommand.ExecuteAsync(null);
        gate.SetResult(new AdbCommandResult(0, $"connected to {Endpoint}\n", string.Empty));
        await connecting;

        // The chain died at its next step: no probe ran, and the card no longer narrates a
        // wireless session the user just walked away from.
        Assert.DoesNotContain(adb.Invocations, args => args[0] == "devices");
        Assert.Equal(string.Empty, viewModel.WirelessStatus);
        Assert.Equal(string.Empty, viewModel.WirelessHint);

        // Even a later (hypothetical) probe cannot resurrect the forgotten session memory.
        viewModel.PrivilegedAdbConsent = true;
        adb.OnArgs(["devices", "-l"], new AdbCommandResult(0, OfflineListing, string.Empty));
        await viewModel.DetectPhoneCommand.ExecuteAsync(null);
        Assert.DoesNotContain("无线连接已断开", viewModel.WirelessStatus, StringComparison.Ordinal);
    }

    /// <summary>
    /// A scripted adb: exact argv match, one or more queued results (the last repeats), plus
    /// pending tasks for holding a call open across a consent revocation.
    /// </summary>
    private sealed class ScriptedAdbRunner : IAdbRunner
    {
        private readonly List<(string[] Args, Queue<Task<AdbCommandResult>> Results, Task<AdbCommandResult> Last)> responses = new();

        public List<string[]> Invocations { get; } = new();

        public bool IsAvailable => true;

        public string LocationDescription => "adb: /scripted/adb";

        public void OnArgs(string[] args, params AdbCommandResult[] results)
        {
            var tasks = results.Select(Task.FromResult).ToArray();
            responses.Add((args, new Queue<Task<AdbCommandResult>>(tasks), tasks[^1]));
        }

        public void OnArgsPending(string[] args, Task<AdbCommandResult> pending) =>
            responses.Add((args, new Queue<Task<AdbCommandResult>>(new[] { pending }), pending));

        public Task<AdbCommandResult> RunAsync(
            IReadOnlyList<string> arguments,
            TimeSpan? timeout = null,
            CancellationToken cancellationToken = default)
        {
            var args = arguments.ToArray();
            Invocations.Add(args);
            foreach (var (candidate, queued, last) in responses)
            {
                if (candidate.SequenceEqual(args))
                {
                    return queued.Count > 0 ? queued.Dequeue() : last;
                }
            }

            return Task.FromResult(new AdbCommandResult(0, string.Empty, string.Empty));
        }
    }

    public async ValueTask DisposeAsync()
    {
        adapter.Dispose();
        await store.DisposeAsync();
        SqliteConnection.ClearAllPools();
        Directory.Delete(directory, recursive: true);
    }
}

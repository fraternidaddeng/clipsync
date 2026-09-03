using ClipSync.App.Startup;
using ClipSync.App.ViewModels;
using ClipSync.Peer.Server;

namespace ClipSync.App.Tests.ViewModels;

/// <summary>
/// The fact line beside each switch is a pure mapping (the preferences counterpart of
/// TrayStateMapper): these pin the tone rules — ochre only when a switch is on but the
/// system is not honouring it, quiet grey for plain facts, empty for the default.
/// </summary>
public sealed class SettingStatusMapperTests
{
    [Fact]
    public void StartupOffAndAbsentSaysNothing() =>
        Assert.True(SettingStatusMapper.Startup(intent: false, StartupRegistrationState.NotRegistered).IsEmpty);

    [Fact]
    public void StartupOnAndRegisteredIsAQuietFact()
    {
        var status = SettingStatusMapper.Startup(intent: true, StartupRegistrationState.Registered);

        Assert.Equal(SettingStatusTone.Quiet, status.Tone);
        Assert.Contains("已登记", status.Text, StringComparison.Ordinal);
    }

    [Fact]
    public void StartupOnButMissingOrDisabledNeedsTheUser()
    {
        var missing = SettingStatusMapper.Startup(intent: true, StartupRegistrationState.NotRegistered);
        var disabled = SettingStatusMapper.Startup(intent: true, StartupRegistrationState.DisabledInTaskManager);

        Assert.Equal(SettingStatusTone.Attention, missing.Tone);
        Assert.Contains("未能登记", missing.Text, StringComparison.Ordinal);
        Assert.Equal(SettingStatusTone.Attention, disabled.Tone);
        Assert.Contains("任务管理器", disabled.Text, StringComparison.Ordinal);
    }

    [Fact]
    public void StartupOffButStillRegisteredNeedsTheUser()
    {
        var status = SettingStatusMapper.Startup(intent: false, StartupRegistrationState.Registered);

        Assert.Equal(SettingStatusTone.Attention, status.Tone);
        Assert.Contains("仍在", status.Text, StringComparison.Ordinal);
    }

    [Fact]
    public void StartupUnreadableIsAQuietFactWhateverTheIntent()
    {
        Assert.Equal(SettingStatusTone.Quiet, SettingStatusMapper.Startup(true, StartupRegistrationState.Unknown).Tone);
        Assert.Equal(SettingStatusTone.Quiet, SettingStatusMapper.Startup(false, StartupRegistrationState.Unknown).Tone);
    }

    [Fact]
    public void RestartBoundTurnsOchreOnlyWhenTheSavedValueDiffersFromTheActiveOne()
    {
        var same = SettingStatusMapper.RestartBound("192.168.1.5", " 192.168.1.5 ", "hint");
        var changed = SettingStatusMapper.RestartBound("", "192.168.1.5", "hint");

        Assert.Equal(SettingStatus.Quiet("hint"), same);
        Assert.Equal(SettingStatusTone.Attention, changed.Tone);
        Assert.Contains("重启后生效", changed.Text, StringComparison.Ordinal);
    }

    [Fact]
    public void PauseAndPrivateRowsStateTheSkipCountOnceThereIsOne()
    {
        Assert.True(SettingStatusMapper.Paused(paused: false, 5).IsEmpty);
        Assert.Contains("不再记录", SettingStatusMapper.Paused(paused: true, 0).Text, StringComparison.Ordinal);
        Assert.Contains("跳过 3 条", SettingStatusMapper.Paused(paused: true, 3).Text, StringComparison.Ordinal);
        Assert.True(SettingStatusMapper.Private(privateMode: false, 5).IsEmpty);
        Assert.Contains("不留痕迹", SettingStatusMapper.Private(privateMode: true, 0).Text, StringComparison.Ordinal);
        Assert.Contains("跳过 2 条", SettingStatusMapper.Private(privateMode: true, 2).Text, StringComparison.Ordinal);
    }

    [Fact]
    public void AutoApplyTextGatesBeforeEvidence()
    {
        Assert.True(SettingStatusMapper.AutoApplyText(false, false, false, ClipboardApplyStates.Applied).IsEmpty);

        var paused = SettingStatusMapper.AutoApplyText(true, paused: true, privateMode: false, ClipboardApplyStates.Applied);
        Assert.Equal(SettingStatusTone.Attention, paused.Tone);
        Assert.Contains("暂停期间", paused.Text, StringComparison.Ordinal);

        // Private outranks paused, mirroring the tray priority.
        var privateMode = SettingStatusMapper.AutoApplyText(true, paused: true, privateMode: true, ClipboardApplyStates.Applied);
        Assert.Contains("私密模式", privateMode.Text, StringComparison.Ordinal);
    }

    [Fact]
    public void AutoApplyTextReportsTheLatestRealApply()
    {
        Assert.Equal(SettingStatusTone.Quiet, SettingStatusMapper.AutoApplyText(true, false, false, ClipboardApplyStates.Unverified).Tone);
        Assert.Equal(SettingStatusTone.Flow, SettingStatusMapper.AutoApplyText(true, false, false, ClipboardApplyStates.Applied).Tone);

        var failed = SettingStatusMapper.AutoApplyText(true, false, false, ClipboardApplyStates.Failed);
        Assert.Equal(SettingStatusTone.Attention, failed.Tone);
        Assert.Contains("未成功", failed.Text, StringComparison.Ordinal);
    }

    [Fact]
    public void AutoApplyImagesGatesBeforeEvidenceAndSaysNothingWhileUnverified()
    {
        Assert.True(SettingStatusMapper.AutoApplyImages(false, true, true, ClipboardApplyStates.Applied).IsEmpty);
        Assert.True(SettingStatusMapper.AutoApplyImages(true, false, false, ClipboardApplyStates.Unverified).IsEmpty);
        Assert.Equal(SettingStatusTone.Attention, SettingStatusMapper.AutoApplyImages(true, true, false, ClipboardApplyStates.Applied).Tone);
    }

    [Fact]
    public void AutoApplyImagesReportsTheLatestRealApply()
    {
        var applied = SettingStatusMapper.AutoApplyImages(true, false, false, ClipboardApplyStates.Applied);
        Assert.Equal(SettingStatusTone.Flow, applied.Tone);
        Assert.Contains("图片已写入", applied.Text, StringComparison.Ordinal);

        var failed = SettingStatusMapper.AutoApplyImages(true, false, false, ClipboardApplyStates.Failed);
        Assert.Equal(SettingStatusTone.Attention, failed.Tone);
        Assert.Contains("未成功", failed.Text, StringComparison.Ordinal);
    }

    [Fact]
    public void ImageSyncStatesTextOnlyOrTheConnectedCount()
    {
        Assert.Contains("只收发文本", SettingStatusMapper.ImageSync(false, 2).Text, StringComparison.Ordinal);
        Assert.Contains("等待手机连接", SettingStatusMapper.ImageSync(true, 0).Text, StringComparison.Ordinal);
        Assert.Contains("2 台设备已连接", SettingStatusMapper.ImageSync(true, 2).Text, StringComparison.Ordinal);
        // Without a negotiation report nothing is claimed about the phones, and never in colour.
        Assert.Equal(SettingStatusTone.Quiet, SettingStatusMapper.ImageSync(true, 2).Tone);
        Assert.Contains("2 台设备已连接", SettingStatusMapper.ImageSync(true, 2, imageCapableDevices: 2).Text, StringComparison.Ordinal);
        Assert.Contains("2 台设备已连接", SettingStatusMapper.ImageSync(true, 2, imageCapableDevices: 1).Text, StringComparison.Ordinal);
    }

    [Fact]
    public void ImageSyncSaysTextOnlyWhenNoConnectedPhoneNegotiatedImages()
    {
        var textOnly = SettingStatusMapper.ImageSync(true, 1, imageCapableDevices: 0);

        Assert.Equal(SettingStatusTone.Quiet, textOnly.Tone);
        Assert.Contains("未开启图片同步", textOnly.Text, StringComparison.Ordinal);
        Assert.Contains("只同步文本", textOnly.Text, StringComparison.Ordinal);
        // Zero capable phones with zero connected phones is still "waiting", not a claim about a phone.
        Assert.Contains("等待手机连接", SettingStatusMapper.ImageSync(true, 0, imageCapableDevices: 0).Text, StringComparison.Ordinal);
        Assert.Contains("只收发文本", SettingStatusMapper.ImageSync(false, 1, imageCapableDevices: 0).Text, StringComparison.Ordinal);
    }

    [Fact]
    public void BluetoothRowColoursTheListenerLineByOutcome()
    {
        Assert.True(SettingStatusMapper.Bluetooth(false, true, false, "off").IsEmpty);
        Assert.Equal(SettingStatus.Attention("no adapter"), SettingStatusMapper.Bluetooth(true, true, false, "no adapter"));
        Assert.Equal(SettingStatus.Flow("syncing"), SettingStatusMapper.Bluetooth(true, false, true, "syncing"));
        Assert.Equal(SettingStatus.Quiet("armed"), SettingStatusMapper.Bluetooth(true, false, false, "armed"));
    }

    [Fact]
    public void BlockedProcessesDistinguishesEditingFromApplied()
    {
        string[] applied = ["1password", "keepass"];

        Assert.Contains("离开输入框", SettingStatusMapper.BlockedProcesses("1password", applied).Text, StringComparison.Ordinal);
        Assert.Contains("2 个进程", SettingStatusMapper.BlockedProcesses(" 1password ;keepass\n", applied).Text, StringComparison.Ordinal);
        Assert.Contains("未屏蔽", SettingStatusMapper.BlockedProcesses("  ", []).Text, StringComparison.Ordinal);
    }

    [Fact]
    public void FlyoutDetailPrefersTheFirewallThenTheBluetoothAdapter()
    {
        Assert.True(SettingStatusMapper.FlyoutDetail(false, true, false, "armed").IsEmpty);
        Assert.True(SettingStatusMapper.FlyoutDetail(false, false, true, "stale").IsEmpty);

        var firewall = SettingStatusMapper.FlyoutDetail(true, true, true, "no adapter");
        Assert.Equal(SettingStatusTone.Attention, firewall.Tone);
        Assert.Contains("TCP 47654", firewall.Text, StringComparison.Ordinal);

        Assert.Equal(SettingStatus.Attention("no adapter"), SettingStatusMapper.FlyoutDetail(false, true, true, "no adapter"));
    }
}

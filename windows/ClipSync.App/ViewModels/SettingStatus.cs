using ClipSync.App.Localization;
using ClipSync.App.Startup;
using ClipSync.Peer.Server;

namespace ClipSync.App.ViewModels;

/// <summary>
/// How a fact line under a switch is coloured (charter §5.5 / tokens §3): quiet detail grey
/// for plain facts, flow blue for "content is moving through here", ochre for the one case
/// that may interrupt — the setting is on but the system is not honouring it.
/// </summary>
public enum SettingStatusTone
{
    Quiet,
    Flow,
    Attention,
}

/// <summary>
/// One fact line stating what a switch is *actually* doing right now (not the switch's own
/// on/off look). Empty text hides the line — the default state has nothing to say.
/// </summary>
public sealed record SettingStatus(string Text, SettingStatusTone Tone)
{
    public static SettingStatus None { get; } = new(string.Empty, SettingStatusTone.Quiet);

    public bool IsEmpty => Text.Length == 0;

    public static SettingStatus Quiet(string text) => new(text, SettingStatusTone.Quiet);

    public static SettingStatus Flow(string text) => new(text, SettingStatusTone.Flow);

    public static SettingStatus Attention(string text) => new(text, SettingStatusTone.Attention);
}

/// <summary>
/// Pure mappings from live app state to the fact line shown beside each switch (the
/// preferences/conduit counterpart of <c>TrayStateMapper</c>). Every rule here is a
/// sentence the UI can stand behind; nothing reads the system directly.
/// </summary>
public static class SettingStatusMapper
{
    /// <summary>开机自启: the stored intent against what the per-user Run entry really holds.</summary>
    public static SettingStatus Startup(bool intent, StartupRegistrationState registry) => registry switch
    {
        StartupRegistrationState.Unknown => SettingStatus.Quiet(Strings.Status_Startup_Unreadable),
        StartupRegistrationState.NotRegistered => intent
            ? SettingStatus.Attention(Strings.Status_Startup_WriteFailed)
            : SettingStatus.None,
        StartupRegistrationState.DisabledInTaskManager => intent
            ? SettingStatus.Attention(Strings.Status_Startup_DisabledByTaskManager)
            : SettingStatus.Attention(Strings.Status_Startup_RemoveFailed),
        _ => intent
            ? SettingStatus.Quiet(Strings.Status_Startup_Registered)
            : SettingStatus.Attention(Strings.Status_Startup_RemoveFailed),
    };

    /// <summary>
    /// A setting that only takes effect after restart (额外监听地址, 语言): ochre while the saved
    /// value differs from the one this process started with, otherwise the quiet hint given.
    /// </summary>
    public static SettingStatus RestartBound(string activeValue, string savedValue, string quietHint)
    {
        ArgumentNullException.ThrowIfNull(activeValue);
        ArgumentNullException.ThrowIfNull(savedValue);
        return string.Equals(activeValue.Trim(), savedValue.Trim(), StringComparison.Ordinal)
            ? SettingStatus.Quiet(quietHint)
            : SettingStatus.Attention(Strings.Status_RestartPending);
    }

    /// <summary>暂停捕获 row: off says nothing; on states the pause and how many clips it has skipped this session.</summary>
    public static SettingStatus Paused(bool paused, int skippedThisSession) =>
        !paused ? SettingStatus.None
        : skippedThisSession > 0
            ? SettingStatus.Quiet(Strings.Format(nameof(Strings.Status_Paused_SkippedFormat), skippedThisSession))
            : SettingStatus.Quiet(Strings.Conduit_Status_Paused);

    /// <summary>私密模式 row: same shape as the pause row.</summary>
    public static SettingStatus Private(bool privateMode, int skippedThisSession) =>
        !privateMode ? SettingStatus.None
        : skippedThisSession > 0
            ? SettingStatus.Quiet(Strings.Format(nameof(Strings.Status_Private_SkippedFormat), skippedThisSession))
            : SettingStatus.Quiet(Strings.Conduit_Status_Private);

    /// <summary>
    /// 自动写入（文本）: the user's gate first (paused/private stop the write even when on),
    /// then the evidence of the most recent real apply this session — the same three values
    /// the health endpoint reports to the phone.
    /// </summary>
    public static SettingStatus AutoApplyText(bool enabled, bool paused, bool privateMode, string evidence)
    {
        if (!enabled)
        {
            return SettingStatus.None;
        }

        var gate = ApplyGate(paused, privateMode);
        if (gate is not null)
        {
            return gate;
        }

        return evidence switch
        {
            ClipboardApplyStates.Applied => SettingStatus.Flow(Strings.Status_AutoApply_Applied),
            ClipboardApplyStates.Failed => SettingStatus.Attention(Strings.Status_AutoApply_Failed),
            _ => SettingStatus.Quiet(Strings.Status_AutoApply_Unverified),
        };
    }

    /// <summary>
    /// 自动写入（图片）: the user's gate first, then the most recent real image apply this session.
    /// Unverified says nothing — an image that has not arrived yet is not a fact about the switch.
    /// </summary>
    public static SettingStatus AutoApplyImages(bool enabled, bool paused, bool privateMode, string evidence)
    {
        ArgumentNullException.ThrowIfNull(evidence);
        if (!enabled)
        {
            return SettingStatus.None;
        }

        var gate = ApplyGate(paused, privateMode);
        if (gate is not null)
        {
            return gate;
        }

        return evidence switch
        {
            ClipboardApplyStates.Applied => SettingStatus.Flow(Strings.Status_AutoApplyImages_Applied),
            ClipboardApplyStates.Failed => SettingStatus.Attention(Strings.Status_AutoApplyImages_Failed),
            _ => SettingStatus.None,
        };
    }

    private static SettingStatus? ApplyGate(bool paused, bool privateMode) =>
        privateMode ? SettingStatus.Attention(Strings.Status_AutoApply_PrivateGate)
        : paused ? SettingStatus.Attention(Strings.Status_AutoApply_PausedGate)
        : null;

    /// <summary>
    /// 图片同步: off means this PC is a text-only peer; on states how many phones are connected.
    /// When the endpoint reports that none of the connected phones negotiated image frames
    /// (every session is on text-only v1), the line says so — the phone side is off, and this
    /// session carries text only; when at least one did, images are known to travel both ways
    /// and the line stops hedging about the phone side. Null <paramref name="imageCapableDevices"/>
    /// means the sync layer has not reported it, so nothing is claimed about the phones.
    /// </summary>
    public static SettingStatus ImageSync(bool enabled, int connectedDevices, int? imageCapableDevices = null)
    {
        if (!enabled)
        {
            return SettingStatus.Quiet(Strings.Status_ImageSync_Off);
        }

        if (connectedDevices <= 0)
        {
            return SettingStatus.Quiet(Strings.Status_ImageSync_OnWaiting);
        }

        return imageCapableDevices switch
        {
            0 => SettingStatus.Quiet(Strings.Status_ImageSync_PeerTextOnly),
            >= 1 => SettingStatus.Quiet(Strings.Format(nameof(Strings.Status_ImageSync_OnImageCapableFormat), connectedDevices)),
            _ => SettingStatus.Quiet(Strings.Format(nameof(Strings.Status_ImageSync_OnConnectedFormat), connectedDevices)),
        };
    }

    /// <summary>
    /// 蓝牙备援 toggle row: the listener's own words, coloured by outcome — carrying a session
    /// is flow, an adapter that refuses to start while the switch is on is ochre.
    /// </summary>
    public static SettingStatus Bluetooth(bool enabled, bool unavailable, bool sessionActive, string listenerText)
    {
        ArgumentNullException.ThrowIfNull(listenerText);
        if (!enabled)
        {
            return SettingStatus.None;
        }

        return unavailable ? SettingStatus.Attention(listenerText)
            : sessionActive ? SettingStatus.Flow(listenerText)
            : SettingStatus.Quiet(listenerText);
    }

    /// <summary>
    /// 屏蔽进程 text box saves on focus loss: while the text differs from what the capture
    /// policy holds, say so; otherwise state how many names are in force.
    /// </summary>
    public static SettingStatus BlockedProcesses(string editedText, IReadOnlyList<string> appliedNames)
    {
        ArgumentNullException.ThrowIfNull(editedText);
        ArgumentNullException.ThrowIfNull(appliedNames);
        var edited = SplitProcessNames(editedText);
        if (!edited.SequenceEqual(appliedNames, StringComparer.Ordinal))
        {
            return SettingStatus.Quiet(Strings.Status_BlockedProcesses_Editing);
        }

        return appliedNames.Count == 0
            ? SettingStatus.Quiet(Strings.Status_BlockedProcesses_None)
            : SettingStatus.Quiet(Strings.Format(nameof(Strings.Status_BlockedProcesses_AppliedFormat), appliedNames.Count));
    }

    /// <summary>The one split rule for the 屏蔽进程 box, shared with the capture policy update.</summary>
    public static string[] SplitProcessNames(string text)
    {
        ArgumentNullException.ThrowIfNull(text);
        return text.Split([',', ';', '\r', '\n'], StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries);
    }

    /// <summary>
    /// Tray flyout second line: one ochre fact the status strip's single sentence cannot hold.
    /// Firewall first (it blocks the phone outright), then a Bluetooth fallback that is on but
    /// has no adapter. Empty when nothing needs the user.
    /// </summary>
    public static SettingStatus FlyoutDetail(bool firewallRuleMissing, bool bluetoothEnabled, bool bluetoothUnavailable, string bluetoothText)
    {
        ArgumentNullException.ThrowIfNull(bluetoothText);
        if (firewallRuleMissing)
        {
            return SettingStatus.Attention(Strings.Flyout_Detail_FirewallMissing);
        }

        return bluetoothEnabled && bluetoothUnavailable
            ? SettingStatus.Attention(bluetoothText)
            : SettingStatus.None;
    }
}

using System.Globalization;
using System.Text;

namespace ClipSync.App;

/// <summary>
/// Single home for Windows user-facing copy. Values are Simplified Chinese so a
/// later language switch only swaps this class (or its source).
/// </summary>
internal static class Strings
{
    // --- MainWindow ---
    public const string AppTitle = "剪剪相传";
    public const string HistorySubtitle = "本地剪贴板历史";
    public const string PauseCapture = "暂停捕获";
    public const string PrivateMode = "隐私模式";
    public const string Search = "搜索";
    public const string Copy = "复制";
    public const string View = "查看";
    public const string Delete = "删除";
    public const string ClearAll = "全部清空";
    public const string Refresh = "刷新";
    public const string CaptureSettings = "捕获设置";
    public const string RetentionDays = "保留天数";
    public const string BlockedProcesses = "屏蔽进程";
    public const string Sync = "同步";
    public const string AutoApplyRemote = "将远程剪贴内容写入剪贴板";
    public const string ExtraBindAddresses = "额外绑定地址（例如 Tailscale IP）";
    public const string AddressRestartHint = "修改地址后需重启才能生效。";
    public const string SaveSettings = "保存设置";
    public const string ExportHistory = "导出历史（JSONL）…";
    public const string ImportHistory = "导入历史 (JSONL)…";
    public const string ExportPlaintextWarning = "导出文件包含明文剪贴板内容。";
    public const string PairedDevices = "已配对设备";
    public const string PairNewDevice = "配对新设备…";
    public const string RenameSelectedDevice = "重命名所选设备";
    public const string Rename = "重命名";
    public const string RevokeSelectedDevice = "撤销所选设备";
    public const string RevokeWarning = "撤销将立即断开该设备，并使其配对密钥失效。";
    public const string LocalCaptureRunning = "本地捕获服务运行中";

    // --- Detail ---
    public const string DetailTitle = "剪贴板详情";
    public const string Close = "关闭";
    public const string DetailSourceFormat = "来源：{0}";
    public const string DetailTimeFormat = "时间：{0}";

    // --- Pairing ---
    public const string PairingQrTitle = "配对新设备";
    public const string PairingQrHeader = "请用「剪剪相传」Android 应用扫描";
    public const string PairingQrHint = "二维码包含本机地址、证书指纹和一次性令牌，绝不包含配对密钥。";
    public const string PairingNoHosts = "未检测到局域网地址。请将本机与手机连到同一网络（或在设置中添加 Tailscale 地址），然后重新打开此窗口。";
    public const string PairingVerifyHeader = "确认前请在手机上核对：";
    public const string ComputerNameFormat = "计算机名称：{0}";
    public const string CertificateFormat = "证书：{0}";
    public const string PairingCountdownFormat = "二维码将在 {0} 后刷新";
    public const string PairingNewCode = "重新生成";
    public const string PairingRequestTitle = "配对请求";
    public const string PairingAllowHeader = "允许此设备配对？";
    public const string PairingAllowBody = "有设备正在使用本机显示的二维码请求配对。仅当下方名称与你手中的手机一致时再批准。";
    public const string PairingRepairWarning = "此设备已配对。批准将替换其原有配对密钥，并使旧安装失效。";
    public const string PairingReject = "拒绝";
    public const string PairingApprove = "批准";
    public const string PlatformAndroidDevice = "Android 设备";
    public const string PlatformWindowsDevice = "Windows 设备";

    // --- Tray ---
    public const string TrayOpen = "打开剪剪相传";
    public const string TrayExit = "退出";
    public const string TrayTooltip = "剪剪相传";

    // --- Status ---
    public const string SyncStatusNotRunning = "对端服务未运行";
    public const string SyncStatusRunningFormat = "对端服务端口 {0}\n设备 {1}\n证书 {2}…";
    public const string SyncStatusStartFailed = "对端服务启动失败，本次会话不同步。";
    public const string ExportedClipsFormat = "已导出 {0} 条";
    public const string ExportFailed = "导出失败";
    public const string ExportDialogTitle = "导出历史";
    public const string ExportDialogFilter = "JSON Lines 文件 (*.jsonl)|*.jsonl";
    public const string ImportedClipsFormat = "已导入 {0} 条（跳过 {1} 条）";
    public const string ImportFailed = "导入失败";
    public const string ImportDialogTitle = "导入历史";
    public const string ImportDialogFilter = "JSON Lines 文件 (*.jsonl)|*.jsonl";
    public const string PairingUnavailableMessage = "对端服务未运行，本次会话无法配对。";
    public const string UnknownSource = "未知来源";
    public const string LastSeenFormat = "最近在线 {0}";
    public const string NeverConnected = "从未连接";
    public const string DeviceRevokedState = "已撤销 — 请扫描新的二维码重新配对";
    public const string DevicePairedState = "已配对";
    public const string PlatformAndroid = "Android";
    public const string PlatformWindows = "Windows";

    private static readonly CompositeFormat DetailSourceComposite = CompositeFormat.Parse(DetailSourceFormat);
    private static readonly CompositeFormat DetailTimeComposite = CompositeFormat.Parse(DetailTimeFormat);
    private static readonly CompositeFormat ComputerNameComposite = CompositeFormat.Parse(ComputerNameFormat);
    private static readonly CompositeFormat CertificateComposite = CompositeFormat.Parse(CertificateFormat);
    private static readonly CompositeFormat PairingCountdownComposite = CompositeFormat.Parse(PairingCountdownFormat);
    private static readonly CompositeFormat SyncStatusRunningComposite = CompositeFormat.Parse(SyncStatusRunningFormat);
    private static readonly CompositeFormat ExportedClipsComposite = CompositeFormat.Parse(ExportedClipsFormat);
    private static readonly CompositeFormat ImportedClipsComposite = CompositeFormat.Parse(ImportedClipsFormat);
    private static readonly CompositeFormat LastSeenComposite = CompositeFormat.Parse(LastSeenFormat);

    internal static string FormatDetailSource(string source) =>
        string.Format(CultureInfo.CurrentCulture, DetailSourceComposite, source);

    internal static string FormatDetailTime(string time) =>
        string.Format(CultureInfo.CurrentCulture, DetailTimeComposite, time);

    internal static string FormatComputerName(string name) =>
        string.Format(CultureInfo.CurrentCulture, ComputerNameComposite, name);

    internal static string FormatCertificate(string fingerprint) =>
        string.Format(CultureInfo.CurrentCulture, CertificateComposite, fingerprint);

    internal static string FormatPairingCountdown(TimeSpan remaining) =>
        string.Format(
            CultureInfo.CurrentCulture,
            PairingCountdownComposite,
            remaining.ToString(@"m\:ss", CultureInfo.InvariantCulture));

    internal static string FormatSyncStatusRunning(int port, string deviceId, string certPrefix) =>
        string.Format(CultureInfo.CurrentCulture, SyncStatusRunningComposite, port, deviceId, certPrefix);

    internal static string FormatExportedClips(int count) =>
        string.Format(CultureInfo.CurrentCulture, ExportedClipsComposite, count);

    internal static string FormatImportedClips(int imported, int skipped) =>
        string.Format(CultureInfo.CurrentCulture, ImportedClipsComposite, imported, skipped);

    internal static string FormatLastSeen(string when) =>
        string.Format(CultureInfo.CurrentCulture, LastSeenComposite, when);

    internal static string PlatformDeviceLabel(string platform) => platform switch
    {
        "android" => PlatformAndroidDevice,
        "windows" => PlatformWindowsDevice,
        _ => platform
    };

    internal static string PlatformLabel(string platform) => platform switch
    {
        "android" => PlatformAndroid,
        "windows" => PlatformWindows,
        _ => platform
    };
}

using ClipSync.App.Ui;
using System.Globalization;
using System.Windows;

namespace ClipSync.App.Localization;

/// <summary>
/// 语言（settings-roadmap P1#16）：把存储的 <c>ui_language</c> 应用为进程的 UI 文化。
/// 必须发生在第一个视图模型或窗口构造之前——之后的每次 <see cref="Strings"/> 取用
/// 都按它解析；运行中改语言只落库，重启后生效（restart-to-apply）。
/// 「跟随系统」不动进程文化，交给系统账户语言；此时若系统语言不在目录里，
/// 资源回落 zh-Hans（中立资源），依旧如实可用。
/// </summary>
public static class LocalizationManager
{
    /// <summary>The applied language key: 跟随系统 or a catalog tag; set once at startup.</summary>
    public static string CurrentKey { get; private set; } = LanguageCatalog.FollowSystemKey;

    /// <summary>
    /// Applies a stored <c>ui_language</c> value process-wide. Off-catalog values read as
    /// 跟随系统 (per the roadmap key contract), which leaves the OS account language in charge.
    /// </summary>
    public static void ApplyLanguage(string? storedLanguage)
    {
        var key = LanguageCatalog.KeyForStored(storedLanguage);
        CurrentKey = key;
        if (key == LanguageCatalog.FollowSystemKey)
        {
            return;
        }

        var culture = CultureInfo.GetCultureInfo(key);
        // Both: the default covers every thread that never pinned its own culture
        // (dispatcher, thread-pool workers resolving tray strings), the explicit set
        // covers the already-running startup thread itself.
        CultureInfo.DefaultThreadCurrentUICulture = culture;
        CultureInfo.CurrentUICulture = culture;
    }

    /// <summary>
    /// RTL when the chosen language is flagged RTL in the catalog — or, while following the
    /// system, when the OS UI language itself is right-to-left (ar 之外的 RTL 系统语言没有
    /// 目录译文，但布局方向仍如实跟随).
    /// </summary>
    public static bool IsRightToLeft => ResolveIsRightToLeft(CurrentKey, CultureInfo.CurrentUICulture);

    /// <summary>The pure half of <see cref="IsRightToLeft"/>, separated so tests never mutate process culture.</summary>
    public static bool ResolveIsRightToLeft(string key, CultureInfo systemUiCulture) =>
        key == LanguageCatalog.FollowSystemKey
            ? systemUiCulture.TextInfo.IsRightToLeft
            : LanguageCatalog.ByTag(key)?.RightToLeft == true;

    /// <summary>
    /// 阿拉伯语 RTL（roadmap P1#16 注记）：整窗镜像的分层承诺。每个窗口在构造时取用；
    /// 二维码、证书指纹、快捷键字串等机器文本在 XAML 里各自钉回 LeftToRight。
    /// </summary>
    public static FlowDirection WindowFlowDirection =>
        IsRightToLeft ? FlowDirection.RightToLeft : FlowDirection.LeftToRight;
}

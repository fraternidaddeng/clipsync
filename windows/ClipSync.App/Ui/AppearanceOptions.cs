namespace ClipSync.App.Ui;

/// <summary>
/// 外观（settings-roadmap P1#6）：跟随系统 / 日间 / 夜间 三选一的手动主题覆盖。
/// 存储键 <c>ui_theme</c>（<c>system</c> | <c>day</c> | <c>night</c>），无法解读的存值一律
/// 回落「跟随系统」，不报错。已知边界（路线图 P1#6 注记）：托盘图标按任务栏深浅采样，
/// 永远不受本设置影响——托盘活在任务栏里，只有窗口 chrome 跟随覆盖值。
/// Android 端对应键为 <c>ui.theme</c>，取值相同。
/// </summary>
public static class AppearanceOptions
{
    public const string SystemKey = "system";
    public const string DayKey = "day";
    public const string NightKey = "night";

    public const string DefaultKey = SystemKey;

    /// <summary>Maps a stored <c>ui_theme</c> value back to its key; anything else reads as 跟随系统.</summary>
    public static string KeyForStored(string? stored) => stored switch
    {
        DayKey or NightKey => stored,
        _ => SystemKey,
    };

    /// <summary>The wire form of a theme key — the key itself, per the roadmap key contract.</summary>
    public static string StoredFor(string? themeKey) => KeyForStored(themeKey);

    /// <summary>
    /// The forced palette of a mode: true = day tokens, false = night tokens,
    /// null = no override (follow the Windows theme). Only the two existing charter
    /// palettes are reachable — a mode is never a colour.
    /// </summary>
    public static bool? ForcedLight(string? themeKey) => KeyForStored(themeKey) switch
    {
        DayKey => true,
        NightKey => false,
        _ => null,
    };
}

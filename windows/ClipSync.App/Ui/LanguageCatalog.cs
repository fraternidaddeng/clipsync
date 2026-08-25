namespace ClipSync.App.Ui;

/// <summary>
/// One selectable UI language（settings-roadmap P1#16）: the BCP-47 tag（也是 <c>ui_language</c>
/// 的存储值，一经发布只增不改）, the endonym（选择器里永远显示语言自己的母语名，不随界面
/// 语言翻译）, and the RTL flag（目前仅阿拉伯语；布局镜像策略见路线图 P1#16 的 RTL 注记）.
/// </summary>
public sealed record AppLanguage(string Tag, string NativeName, bool RightToLeft = false);

/// <summary>
/// 语言目录（settings-roadmap P1#16）：偏好「语言」可选的全部界面语言，与 Android 端
/// （<c>com.clipsync.android.i18n.LanguageCatalog</c>）逐条对应——同一 BCP-47 标签、同一顺序、
/// 同一母语名。目录内容以路线图 §五 P1#16 的表为唯一权威，任何改动必须双端同步落地。
/// 存储键 <c>ui_language</c>：值为语言标签本身，或 <see cref="FollowSystemKey"/>（跟随系统，
/// 默认）；无法解读的存值（含未来目录不再提供的旧标签）一律回落「跟随系统」，不报错。
/// </summary>
public static class LanguageCatalog
{
    /// <summary>「跟随系统」的存储值——默认项；它不是语言，故不在 <see cref="Languages"/> 里。</summary>
    public const string FollowSystemKey = "system";

    public static IReadOnlyList<AppLanguage> Languages { get; } =
    [
        new("zh-Hans", "简体中文"),
        new("zh-Hant", "繁體中文"),
        new("en", "English"),
        new("ja", "日本語"),
        new("ko", "한국어"),
        new("es", "Español"),
        new("fr", "Français"),
        new("de", "Deutsch"),
        new("pt-BR", "Português (Brasil)"),
        new("ru", "Русский"),
        new("ar", "العربية", RightToLeft: true),
        new("it", "Italiano"),
        new("vi", "Tiếng Việt"),
        new("th", "ไทย"),
        new("id", "Bahasa Indonesia"),
        new("hi", "हिन्दी"),
        new("tr", "Türkçe"),
        new("pl", "Polski"),
        new("nl", "Nederlands"),
    ];

    private static readonly Dictionary<string, AppLanguage> ByTagIndex =
        Languages.ToDictionary(language => language.Tag, StringComparer.Ordinal);

    /// <summary>The catalog entry for a tag, or null — including for 「跟随系统」 and stale tags.</summary>
    public static AppLanguage? ByTag(string? tag) =>
        tag is not null && ByTagIndex.TryGetValue(tag, out var language) ? language : null;

    /// <summary>Whether the value is a legal <c>ui_language</c> value: 「跟随系统」 or a catalog tag (exact case).</summary>
    public static bool IsValidStoredValue(string? value) =>
        value == FollowSystemKey || ByTag(value) is not null;

    /// <summary>Maps a stored <c>ui_language</c> value back to its key; anything off-catalog reads as 跟随系统.</summary>
    public static string KeyForStored(string? stored) =>
        IsValidStoredValue(stored) ? stored! : FollowSystemKey;

    /// <summary>The wire form of a language key — the key itself, per the roadmap key contract.</summary>
    public static string StoredFor(string? languageKey) => KeyForStored(languageKey);
}

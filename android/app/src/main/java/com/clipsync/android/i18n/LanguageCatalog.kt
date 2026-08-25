package com.clipsync.android.i18n

/**
 * One selectable UI language (settings-roadmap P1#16).
 *
 * @property tag BCP-47 tag — also the stored `ui.language` value (a published key value is
 *   never renamed, same rule as protocol fields).
 * @property nativeName The endonym shown in the picker. Every language always displays its
 *   own native name and is never translated into the current UI language.
 * @property rightToLeft Right-to-left script (currently only Arabic). The layout-mirroring
 *   policy lives in the roadmap's P1#16 RTL note: locale selection is always supported;
 *   mirroring follows if Compose allows it with reasonable effort, else LTR layout fallback.
 */
data class AppLanguage(
    val tag: String,
    val nativeName: String,
    val rightToLeft: Boolean = false,
)

/**
 * 语言目录 (settings-roadmap P1#16): every UI language the 偏好·显示·语言 picker offers,
 * kept entry-for-entry identical (same BCP-47 tags, same order, same endonyms) with the
 * Windows side (`windows/ClipSync.App/Ui/LanguageCatalog.cs`). The roadmap's P1#16 table is
 * the single authority for the catalog contents; any change must land on both platforms.
 *
 * The stored `ui.language` value is either a catalog [AppLanguage.tag] or [FOLLOW_SYSTEM]
 * (跟随系统, the default). 「跟随系统」 is a stored value, not a language, so it is
 * deliberately absent from [LANGUAGES].
 */
object LanguageCatalog {
    /** Stored value for 「跟随系统」 — the default; not a language, so not in [LANGUAGES]. */
    const val FOLLOW_SYSTEM = "system"

    val LANGUAGES: List<AppLanguage> =
        listOf(
            AppLanguage("zh-Hans", "简体中文"),
            AppLanguage("zh-Hant", "繁體中文"),
            AppLanguage("en", "English"),
            AppLanguage("ja", "日本語"),
            AppLanguage("ko", "한국어"),
            AppLanguage("es", "Español"),
            AppLanguage("fr", "Français"),
            AppLanguage("de", "Deutsch"),
            AppLanguage("pt-BR", "Português (Brasil)"),
            AppLanguage("ru", "Русский"),
            AppLanguage("ar", "العربية", rightToLeft = true),
            AppLanguage("it", "Italiano"),
            AppLanguage("vi", "Tiếng Việt"),
            AppLanguage("th", "ไทย"),
            AppLanguage("id", "Bahasa Indonesia"),
            AppLanguage("hi", "हिन्दी"),
            AppLanguage("tr", "Türkçe"),
            AppLanguage("pl", "Polski"),
            AppLanguage("nl", "Nederlands"),
        )

    private val languagesByTag: Map<String, AppLanguage> = LANGUAGES.associateBy { it.tag }

    /** The catalog entry for [tag], or null — including for [FOLLOW_SYSTEM] and stale tags. */
    fun byTag(tag: String?): AppLanguage? = tag?.let(languagesByTag::get)

    /** Whether [value] is a legal `ui.language` value: 「跟随系统」 or a catalog tag (exact case). */
    fun isValidStoredValue(value: String?): Boolean = value == FOLLOW_SYSTEM || byTag(value) != null
}

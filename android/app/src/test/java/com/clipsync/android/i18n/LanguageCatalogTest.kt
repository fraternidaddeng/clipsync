package com.clipsync.android.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the P1#16 language catalog exactly — tags, order, endonyms and the RTL flag. The
 * Windows `LanguageCatalogTests` pins the same list against the same roadmap table; if
 * either side drifts, one of the two tests fails and the catalogs must be re-aligned.
 */
class LanguageCatalogTest {
    @Test
    fun catalogMatchesTheRoadmapTableExactly() {
        val expected =
            listOf(
                "zh-Hans" to "简体中文",
                "zh-Hant" to "繁體中文",
                "en" to "English",
                "ja" to "日本語",
                "ko" to "한국어",
                "es" to "Español",
                "fr" to "Français",
                "de" to "Deutsch",
                "pt-BR" to "Português (Brasil)",
                "ru" to "Русский",
                "ar" to "العربية",
                "it" to "Italiano",
                "vi" to "Tiếng Việt",
                "th" to "ไทย",
                "id" to "Bahasa Indonesia",
                "hi" to "हिन्दी",
                "tr" to "Türkçe",
                "pl" to "Polski",
                "nl" to "Nederlands",
            )
        assertEquals(expected, LanguageCatalog.LANGUAGES.map { it.tag to it.nativeName })
    }

    @Test
    fun onlyArabicIsRightToLeft() {
        assertEquals(
            listOf("ar"),
            LanguageCatalog.LANGUAGES.filter { it.rightToLeft }.map { it.tag },
        )
    }

    @Test
    fun tagsAreUniqueAndFollowSystemIsNotALanguage() {
        assertEquals(
            LanguageCatalog.LANGUAGES.size,
            LanguageCatalog.LANGUAGES.map { it.tag }.toSet().size,
        )
        // 「跟随系统」 is a stored value, never a picker language entry.
        assertNull(LanguageCatalog.byTag(LanguageCatalog.FOLLOW_SYSTEM))
    }

    @Test
    fun storedValueValidationAcceptsSystemAndCatalogTagsOnly() {
        assertTrue(LanguageCatalog.isValidStoredValue(LanguageCatalog.FOLLOW_SYSTEM))
        assertTrue(LanguageCatalog.isValidStoredValue("zh-Hans"))
        assertTrue(LanguageCatalog.isValidStoredValue("ar"))

        assertFalse(LanguageCatalog.isValidStoredValue(null))
        assertFalse(LanguageCatalog.isValidStoredValue(""))
        // Only the explicit script/region forms the catalog offers are legal stored values.
        assertFalse(LanguageCatalog.isValidStoredValue("zh"))
        assertFalse(LanguageCatalog.isValidStoredValue("pt"))
        assertFalse(LanguageCatalog.isValidStoredValue("ZH-HANS"))
    }
}

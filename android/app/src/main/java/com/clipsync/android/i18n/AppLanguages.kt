package com.clipsync.android.i18n

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.clipsync.android.storage.SyncSettingsStore

/**
 * Applies the stored `ui.language` (P1#16) through AppCompat's per-app locales.
 *
 * `ui.language` in [SyncSettingsStore] is the single authority; AppCompat is only the
 * application mechanism. [applyStored] must run in `Application.onCreate` — before any
 * activity attaches — so every UI surface (including notification text rendered through
 * a localized context) speaks the chosen language from the first frame. On a change from
 * the picker, [select] persists first and then re-applies; AppCompat recreates started
 * activities, which makes the switch take effect immediately.
 */
object AppLanguages {
    /** Applies the persisted choice before any UI exists. Idempotent. */
    fun applyStored(settings: SyncSettingsStore) = apply(settings.languageTag)

    /**
     * Persists a picker choice and applies it. [tag] must be a catalog tag or
     * [LanguageCatalog.FOLLOW_SYSTEM]; the store enforces that invariant.
     */
    fun select(
        tag: String,
        settings: SyncSettingsStore,
    ) {
        settings.languageTag = tag
        apply(tag)
    }

    private fun apply(tag: String) {
        val locales =
            if (tag == LanguageCatalog.FOLLOW_SYSTEM) {
                LocaleListCompat.getEmptyLocaleList()
            } else {
                LocaleListCompat.forLanguageTags(tag)
            }
        AppCompatDelegate.setApplicationLocales(locales)
    }
}

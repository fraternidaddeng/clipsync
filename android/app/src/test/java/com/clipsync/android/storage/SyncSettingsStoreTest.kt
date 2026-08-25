package com.clipsync.android.storage

import com.clipsync.android.i18n.LanguageCatalog
import com.clipsync.android.pairing.KeyValueStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncSettingsStoreTest {
    private class FakeKeyValueStore : KeyValueStore {
        val values = mutableMapOf<String, String>()

        override fun read(key: String): String? = values[key]

        override fun write(values: Map<String, String?>) {
            for ((key, value) in values) {
                if (value == null) this.values.remove(key) else this.values[key] = value
            }
        }
    }

    private val store = SyncSettingsStore(FakeKeyValueStore())

    @Test
    fun defaultsFollowTheImplementationPlan() {
        assertTrue(store.autoApplyRemote)
        assertFalse(store.syncPaused)
        assertFalse(store.privateMode)
        assertEquals(2_000, store.retentionMaxEntries)
        assertEquals(30, store.retentionMaxAgeDays)
        assertEquals(1_048_576, store.maxSyncTextBytes)
    }

    @Test
    fun valuesRoundTrip() {
        store.autoApplyRemote = false
        store.syncPaused = true
        store.privateMode = true
        store.retentionMaxEntries = 500
        store.retentionMaxAgeDays = 7
        store.maxSyncTextBytes = 2_048

        assertFalse(store.autoApplyRemote)
        assertTrue(store.syncPaused)
        assertTrue(store.privateMode)
        assertEquals(500, store.retentionMaxEntries)
        assertEquals(7, store.retentionMaxAgeDays)
        assertEquals(2_048, store.maxSyncTextBytes)
    }

    @Test
    fun retentionPolicyDerivesFromSettings() {
        store.retentionMaxEntries = 100
        store.retentionMaxAgeDays = 2

        val policy = store.retentionPolicy()
        assertEquals(100, policy.maximumEntries)
        assertEquals(2L * 24 * 60 * 60 * 1_000, policy.maximumAgeMs)
    }

    @Test
    fun invalidValuesAreRejected() {
        try {
            store.retentionMaxEntries = 0
            throw AssertionError("Expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // The history limit must stay positive.
        }
        assertEquals(2_000, store.retentionMaxEntries)
    }

    @Test
    fun autoExpireDefaultsOnAndRoundTripsWithoutTouchingTheDuration() {
        assertTrue(store.autoExpireEnabled)

        store.retentionMaxAgeDays = 14
        store.autoExpireEnabled = false

        assertFalse(store.autoExpireEnabled)
        assertEquals(14, store.retentionMaxAgeDays)

        store.autoExpireEnabled = true
        assertTrue(store.autoExpireEnabled)
        assertEquals(14, store.retentionMaxAgeDays)
    }

    @Test
    fun corruptPersistedValuesFallBackToDefaults() {
        val backing = FakeKeyValueStore()
        backing.values["sync.retention.max_entries"] = "not-a-number"
        backing.values["sync.auto_apply_remote"] = "maybe"

        val corrupted = SyncSettingsStore(backing)
        assertEquals(2_000, corrupted.retentionMaxEntries)
        assertTrue(corrupted.autoApplyRemote)
    }

    @Test
    fun bootRestoreDefaultsOffAndRoundTrips() {
        // Plan 5.2: 开机恢复 is opt-in; nothing registers at boot until the user says so.
        assertFalse(store.bootRestoreEnabled)

        store.bootRestoreEnabled = true
        assertTrue(store.bootRestoreEnabled)

        store.bootRestoreEnabled = false
        assertFalse(store.bootRestoreEnabled)
    }

    @Test
    fun effectiveRetentionPolicyKeepsAgeOnlyWhileAutoExpireIsOn() {
        store.retentionMaxEntries = 100
        store.retentionMaxAgeDays = 2

        val withExpiry = store.effectiveRetentionPolicy()
        assertEquals(100, withExpiry.maximumEntries)
        assertEquals(2L * 24 * 60 * 60 * 1_000, withExpiry.maximumAgeMs)

        store.autoExpireEnabled = false
        val withoutExpiry = store.effectiveRetentionPolicy()
        // The entry cap survives; the age limit becomes unreachably large (matches no row).
        assertEquals(100, withoutExpiry.maximumEntries)
        assertTrue(withoutExpiry.maximumAgeMs > System.currentTimeMillis())
    }

    @Test
    fun effectiveMaxSyncTextBytesNeverExceedsTheProtocolCap() {
        assertEquals(1_048_576, store.effectiveMaxSyncTextBytes)

        store.maxSyncTextBytes = 2_048
        assertEquals(2_048, store.effectiveMaxSyncTextBytes)

        store.maxSyncTextBytes = 8_388_608 // stored, but the wire cap still rules
        assertEquals(1_048_576, store.effectiveMaxSyncTextBytes)
    }

    @Test
    fun basicSettingsDefaultsFollowTheRoadmap() {
        // settings-roadmap P0-1 / P1-7: 标准字号, 4 preview lines.
        assertEquals(SyncSettingsStore.HISTORY_FONT_SCALE_STANDARD, store.historyFontScale, 0f)
        assertEquals(SyncSettingsStore.DEFAULT_PREVIEW_LINES, store.previewLines)
        // P0-4: 跳过敏感内容 is on by default (a privacy promise, not an opt-in).
        assertTrue(store.skipSensitiveEnabled)
        // P1-8: 收到内容通知 defaults on; only the user turns the surface off.
        assertTrue(store.inboxNotifyEnabled)
    }

    @Test
    fun basicSettingsRoundTripUnderTheRoadmapKeys() {
        val backing = FakeKeyValueStore()
        val settings = SyncSettingsStore(backing)

        settings.historyFontScale = SyncSettingsStore.HISTORY_FONT_SCALE_LARGE
        settings.previewLines = 6
        settings.skipSensitiveEnabled = false
        settings.inboxNotifyEnabled = false

        // The roadmap prefix rule: ui. / capture. / notify. — never new sync.* keys.
        assertEquals("1.15", backing.values["ui.history_font_scale"])
        assertEquals("6", backing.values["ui.preview_lines"])
        assertEquals("false", backing.values["capture.skip_sensitive"])
        assertEquals("false", backing.values["notify.inbox"])

        val reloaded = SyncSettingsStore(backing)
        assertEquals(SyncSettingsStore.HISTORY_FONT_SCALE_LARGE, reloaded.historyFontScale, 0f)
        assertEquals(6, reloaded.previewLines)
        assertFalse(reloaded.skipSensitiveEnabled)
        assertFalse(reloaded.inboxNotifyEnabled)
    }

    @Test
    fun appearanceAndLanguageDefaultToFollowSystem() {
        // settings-roadmap P1-6 / P1-16: both default to 跟随系统.
        assertEquals(SyncSettingsStore.THEME_SYSTEM, store.themeOverride)
        assertEquals(LanguageCatalog.FOLLOW_SYSTEM, store.languageTag)
    }

    @Test
    fun appearanceAndLanguageRoundTripUnderTheRoadmapKeys() {
        val backing = FakeKeyValueStore()
        val settings = SyncSettingsStore(backing)

        settings.themeOverride = SyncSettingsStore.THEME_NIGHT
        settings.languageTag = "pt-BR"

        // The roadmap prefix rule: ui. keys, values stored verbatim.
        assertEquals("night", backing.values["ui.theme"])
        assertEquals("pt-BR", backing.values["ui.language"])

        val reloaded = SyncSettingsStore(backing)
        assertEquals(SyncSettingsStore.THEME_NIGHT, reloaded.themeOverride)
        assertEquals("pt-BR", reloaded.languageTag)
    }

    @Test
    fun offCatalogThemeAndLanguageValuesAreRejectedAndCorruptOnesFallBack() {
        try {
            store.themeOverride = "dark"
            throw AssertionError("Expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // Only system / day / night are valid.
        }
        try {
            store.languageTag = "eo"
            throw AssertionError("Expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // Only 跟随系统 or a catalog tag is valid.
        }

        val backing = FakeKeyValueStore()
        backing.values["ui.theme"] = "blue"
        backing.values["ui.language"] = "zz-ZZ"
        val corrupted = SyncSettingsStore(backing)
        assertEquals(SyncSettingsStore.THEME_SYSTEM, corrupted.themeOverride)
        assertEquals(LanguageCatalog.FOLLOW_SYSTEM, corrupted.languageTag)
    }

    @Test
    fun offStepValuesForFontScaleAndPreviewLinesAreRejectedAndCorruptOnesFallBack() {
        try {
            store.historyFontScale = 2.0f
            throw AssertionError("Expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // Only the three roadmap steps are valid.
        }
        try {
            store.previewLines = 3
            throw AssertionError("Expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // Only 2 / 4 / 6 are valid.
        }

        val backing = FakeKeyValueStore()
        backing.values["ui.history_font_scale"] = "999"
        backing.values["ui.preview_lines"] = "0"
        val corrupted = SyncSettingsStore(backing)
        assertEquals(SyncSettingsStore.HISTORY_FONT_SCALE_STANDARD, corrupted.historyFontScale, 0f)
        assertEquals(SyncSettingsStore.DEFAULT_PREVIEW_LINES, corrupted.previewLines)
    }
}

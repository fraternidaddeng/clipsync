package com.clipsync.android.storage

import com.clipsync.android.pairing.KeyValueStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Manual-QA limitation #5 (2026-08-25): the image-sync default must be the same on both
 * platforms. Image sync is opt-in everywhere (ADR 0004 / DESIGN-CHARTER §5.9): this pins the
 * Android default to off, mirroring the Windows side where the persisted `image_sync` setting
 * parses fail-closed and `SyncSessionOptions.ImageSyncEnabled` now defaults to off too.
 */
class ImageSyncDefaultAlignmentTest {
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
    fun imageSyncDefaultsOffAndRoundTrips() {
        assertFalse(store.imageSyncEnabled)

        store.imageSyncEnabled = true
        assertTrue(store.imageSyncEnabled)

        store.imageSyncEnabled = false
        assertFalse(store.imageSyncEnabled)
    }

    @Test
    fun autoApplyImagesDefaultsOffIndependentlyOfTheTextGate() {
        // ADR 0004: the image auto-apply gate is independent of text auto-apply, which
        // defaults on; the image gates must stay off until the user opts in.
        assertTrue(store.autoApplyRemote)
        assertFalse(store.autoApplyImages)
    }

    @Test
    fun corruptPersistedImageSyncValueFallsBackToOff() {
        val backing = FakeKeyValueStore()
        backing.values["sync.image_sync"] = "maybe"

        val corrupted = SyncSettingsStore(backing)
        assertFalse(corrupted.imageSyncEnabled)
    }
}

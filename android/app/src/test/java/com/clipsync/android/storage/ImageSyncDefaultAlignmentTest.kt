package com.clipsync.android.storage

import com.clipsync.android.pairing.KeyValueStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The image-sync default must be the same on both platforms (manual-QA limitation #5,
 * 2026-08-25). Since the 2026-08-28 product decision (ADR 0004 修订:「图片同步这种功能应该
 * 默认打开，这是产品的完整体验」), image sync defaults ON everywhere: this pins the Android
 * default to on, mirroring the Windows side where an absent/corrupt persisted `image_sync`
 * setting now also resolves to on. Auto-applying remote images to the local clipboard stays
 * a separate, opt-in gate (`auto_apply_images`), and the unwired session/library gates keep
 * failing closed — only the product default flipped, not the safety checks.
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
    fun imageSyncDefaultsOnAndRoundTrips() {
        assertTrue(store.imageSyncEnabled)

        store.imageSyncEnabled = false
        assertFalse(store.imageSyncEnabled)

        store.imageSyncEnabled = true
        assertTrue(store.imageSyncEnabled)
    }

    @Test
    fun autoApplyImagesDefaultsOffIndependentlyOfTheTextGate() {
        // ADR 0004: the image auto-apply gate is independent of text auto-apply. Even with
        // image sync itself defaulting on, automatically writing remote images into the
        // local clipboard remains opt-in for privacy — received images land in history only.
        assertTrue(store.autoApplyRemote)
        assertFalse(store.autoApplyImages)
    }

    @Test
    fun corruptPersistedImageSyncValueFallsBackToTheOnDefault() {
        // An unparseable persisted value resolves to the product default (on), the same
        // rule every other boolean setting follows (e.g. auto_apply_remote). An explicit
        // persisted "false" from a user who opted out is still honored.
        val backing = FakeKeyValueStore()
        backing.values["sync.image_sync"] = "maybe"

        val corrupted = SyncSettingsStore(backing)
        assertTrue(corrupted.imageSyncEnabled)

        backing.values["sync.image_sync"] = "false"
        assertFalse(corrupted.imageSyncEnabled)
    }
}

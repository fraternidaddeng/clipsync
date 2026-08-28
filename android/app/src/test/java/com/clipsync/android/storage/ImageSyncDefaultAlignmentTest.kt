package com.clipsync.android.storage

import com.clipsync.android.pairing.KeyValueStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The image-sync default must be the same on both platforms (manual-QA limitation #5,
 * 2026-08-25). Since the 2026-08-28 product decisions (ADR 0004 修订:「图片同步这种功能应该
 * 默认打开，这是产品的完整体验」and, same day,「本来截图就是我自己截的，默认开开」for
 * `auto_apply_images`), both image sync and auto-applying remote images to the local
 * clipboard default ON everywhere: this pins the Android defaults to on, mirroring the
 * Windows side where absent/corrupt persisted `image_sync` / `auto_apply_images` settings
 * now also resolve to on. The two gates stay independent of each other and of the text
 * gate, explicit persisted opt-outs are still honored, and the unwired session/library
 * gates keep failing closed — only the product defaults flipped, not the safety checks.
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
    fun autoApplyImagesDefaultsOnIndependentlyOfTheTextGate() {
        // ADR 0004: the image auto-apply gate stays independent of text auto-apply even
        // though both now default on (2026-08-28 修订) — turning one off never drags the
        // other along.
        assertTrue(store.autoApplyRemote)
        assertTrue(store.autoApplyImages)

        store.autoApplyImages = false
        assertFalse(store.autoApplyImages)
        assertTrue(store.autoApplyRemote)
    }

    @Test
    fun corruptPersistedAutoApplyImagesValueFallsBackToTheOnDefault() {
        // Same rule as image_sync and auto_apply_remote: unparseable resolves to the
        // product default (on); an explicit persisted "false" opt-out is still honored.
        val backing = FakeKeyValueStore()
        backing.values["sync.auto_apply_images"] = "maybe"

        val corrupted = SyncSettingsStore(backing)
        assertTrue(corrupted.autoApplyImages)

        backing.values["sync.auto_apply_images"] = "false"
        assertFalse(corrupted.autoApplyImages)
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

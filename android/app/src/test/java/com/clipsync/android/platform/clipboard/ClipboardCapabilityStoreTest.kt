package com.clipsync.android.platform.clipboard

import com.clipsync.android.pairing.KeyValueStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardCapabilityStoreTest {
    private class InMemoryKeyValueStore : KeyValueStore {
        val values = mutableMapOf<String, String>()

        override fun read(key: String): String? = values[key]

        override fun write(values: Map<String, String?>) {
            for ((key, value) in values) {
                if (value == null) this.values.remove(key) else this.values[key] = value
            }
        }
    }

    @Test
    fun `defaults are shizuku-first with auto fallback and untested write`() {
        val store = ClipboardCapabilityStore(InMemoryKeyValueStore())
        assertEquals(ClipboardReadMode.SHIZUKU_EVENT, store.preferredReadMode())
        assertTrue(store.autoFallbackAllowed())
        assertEquals(CapabilityState.UNKNOWN, store.publicWriteState())
        assertNull(store.publicWriteErrorCode())
        assertNull(store.lastWriteTestAtMs())
    }

    @Test
    fun `preferred read mode round-trips through the store`() {
        val store = ClipboardCapabilityStore(InMemoryKeyValueStore())
        store.setPreferredReadMode(ClipboardReadMode.OVERLAY_POLLING)
        assertEquals(ClipboardReadMode.OVERLAY_POLLING, store.preferredReadMode())
    }

    @Test
    fun `corrupted stored values fall back to defaults`() {
        val backing = InMemoryKeyValueStore()
        backing.values["capability.preferred_read_mode"] = "NOT_A_MODE"
        backing.values["capability.auto_fallback"] = "maybe"
        backing.values["capability.public_write_state"] = "SORT_OF"
        val store = ClipboardCapabilityStore(backing)
        assertEquals(ClipboardReadMode.SHIZUKU_EVENT, store.preferredReadMode())
        assertTrue(store.autoFallbackAllowed())
        assertEquals(CapabilityState.UNKNOWN, store.publicWriteState())
    }

    @Test
    fun `write test outcome persists state code and time together`() {
        val store = ClipboardCapabilityStore(InMemoryKeyValueStore())
        store.recordWriteTest(CapabilityState.UNAVAILABLE, errorCode = "CLIPBOARD_WRITE_REJECTED", atMs = 42L)
        assertEquals(CapabilityState.UNAVAILABLE, store.publicWriteState())
        assertEquals("CLIPBOARD_WRITE_REJECTED", store.publicWriteErrorCode())
        assertEquals(42L, store.lastWriteTestAtMs())

        store.recordWriteTest(CapabilityState.READY, errorCode = null, atMs = 43L)
        assertEquals(CapabilityState.READY, store.publicWriteState())
        assertNull(store.publicWriteErrorCode())
        assertEquals(43L, store.lastWriteTestAtMs())
    }

    @Test
    fun `auto fallback preference round-trips`() {
        val store = ClipboardCapabilityStore(InMemoryKeyValueStore())
        store.setAutoFallbackAllowed(false)
        assertEquals(false, store.autoFallbackAllowed())
    }
}

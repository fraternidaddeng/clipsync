package com.clipsync.android.platform.clipboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardCapabilityStoreTest {
    @Test
    fun `read and write snapshots round trip on separate keys`() {
        val keys = InMemoryCapabilityKeyValueStore()
        val store = KeyValueClipboardCapabilityStore(keys)

        store.saveRead(
            ReadCapabilitySnapshot(
                requestedReadMode = ClipboardReadMode.SHIZUKU_EVENT,
                activeReadMode = ClipboardReadMode.OVERLAY_POLLING,
                autoFallbackAllowed = false,
                lastErrorCode = "SHIZUKU_NOT_AUTHORIZED",
                lastHealthAtEpochMillis = 99L,
                modeEpoch = 4L,
                lastReadState = CapabilityState.NEEDS_USER_ACTION,
            ),
        )
        store.saveWrite(
            WriteCapabilitySnapshot(
                writeMode = ClipboardWriteMode.SHIZUKU_FALLBACK,
                publicLastSuccessAtEpochMillis = 10L,
                publicLastErrorCode = "PUBLIC_WRITE_REJECTED",
                fallbackLastSuccessAtEpochMillis = 20L,
                fallbackLastErrorCode = "SHIZUKU_WRITE_DENIED",
            ),
        )

        val reloaded = KeyValueClipboardCapabilityStore(keys)
        assertEquals(
            ReadCapabilitySnapshot(
                requestedReadMode = ClipboardReadMode.SHIZUKU_EVENT,
                activeReadMode = ClipboardReadMode.OVERLAY_POLLING,
                autoFallbackAllowed = false,
                lastErrorCode = "SHIZUKU_NOT_AUTHORIZED",
                lastHealthAtEpochMillis = 99L,
                modeEpoch = 4L,
                lastReadState = CapabilityState.NEEDS_USER_ACTION,
            ),
            reloaded.loadRead(),
        )
        assertEquals(
            WriteCapabilitySnapshot(
                writeMode = ClipboardWriteMode.SHIZUKU_FALLBACK,
                publicLastSuccessAtEpochMillis = 10L,
                publicLastErrorCode = "PUBLIC_WRITE_REJECTED",
                fallbackLastSuccessAtEpochMillis = 20L,
                fallbackLastErrorCode = "SHIZUKU_WRITE_DENIED",
            ),
            reloaded.loadWrite(),
        )

        val readKeys = keys.map.keys.filter { it.startsWith(KeyValueClipboardCapabilityStore.PREFIX_READ) }
        val writeKeys = keys.map.keys.filter { it.startsWith(KeyValueClipboardCapabilityStore.PREFIX_WRITE) }
        assertTrue(readKeys.isNotEmpty())
        assertTrue(writeKeys.isNotEmpty())
        assertTrue(readKeys.intersect(writeKeys.toSet()).isEmpty())
    }

    @Test
    fun `store never persists clipboard text on read or write sides`() {
        val keys = InMemoryCapabilityKeyValueStore()
        val store = KeyValueClipboardCapabilityStore(keys)
        val secret = "SECRET_CLIPBOARD_BODY_DO_NOT_STORE"

        val readNames = ReadCapabilitySnapshot::class.java.declaredFields.map { it.name.lowercase() }
        val writeNames = WriteCapabilitySnapshot::class.java.declaredFields.map { it.name.lowercase() }
        assertFalse(readNames.any { "text" in it || "content" in it || "payload" in it })
        assertFalse(writeNames.any { "text" in it || "content" in it || "payload" in it })

        val shizuku = FakeBackgroundClipboardBackend(
            mode = ClipboardReadMode.SHIZUKU_EVENT,
            readResult = ClipboardReadResult.Success(secret),
        )
        val readCoordinator = ClipboardAccessCoordinator(
            backends = listOf(shizuku),
            capabilityStore = store,
        )
        readCoordinator.start { }

        val publicWriter = FakeClipboardWriter()
        val fallbackWriter = FakeClipboardWriter()
        val writeCoordinator = ClipboardWriteCoordinator(
            publicWriter = publicWriter,
            fallbackWriter = fallbackWriter,
            capabilityStore = store,
            nowEpochMillis = { 1_000L },
        )
        writeCoordinator.writeText(secret, "origin-secret")

        assertFalse(keys.map.values.any { it.contains(secret) })
        assertFalse(keys.map.keys.any { it.contains(secret) })
        assertNull(keys.map.values.firstOrNull { it.contains("SECRET") })
    }

    @Test
    fun `access coordinator restores read snapshot without clipboard text`() {
        val keys = InMemoryCapabilityKeyValueStore()
        val store = KeyValueClipboardCapabilityStore(keys)
        store.saveRead(
            ReadCapabilitySnapshot(
                requestedReadMode = ClipboardReadMode.ADB_LOG_OVERLAY,
                activeReadMode = ClipboardReadMode.FOREGROUND_ONLY,
                autoFallbackAllowed = false,
                lastErrorCode = "ADB_SIGNAL_UNVERIFIED",
                lastHealthAtEpochMillis = 7L,
                modeEpoch = 3L,
                lastReadState = CapabilityState.DEGRADED,
            ),
        )

        val coordinator = ClipboardAccessCoordinator(
            backends = listOf(
                FakeBackgroundClipboardBackend(ClipboardReadMode.ADB_LOG_OVERLAY),
                FakeBackgroundClipboardBackend(ClipboardReadMode.FOREGROUND_ONLY),
            ),
            capabilityStore = store,
        )

        assertEquals(ClipboardReadMode.ADB_LOG_OVERLAY, coordinator.state.requestedReadMode)
        assertEquals(false, coordinator.state.autoFallbackAllowed)
        assertEquals("ADB_SIGNAL_UNVERIFIED", coordinator.state.lastErrorCode)
        assertEquals(7L, coordinator.state.lastHealthAtEpochMillis)
        assertEquals(3L, coordinator.modeEpoch)
        assertEquals(CapabilityState.DEGRADED, coordinator.lastReadState)
        assertFalse(keys.map.values.any { it.contains("clip") && it.contains(" ") })
    }
}

package com.clipsync.android.platform.clipboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardWriteCoordinatorTest {
    @Test
    fun `public writer is always attempted first`() {
        val publicWriter = FakeClipboardWriter()
        val fallbackWriter = FakeClipboardWriter()
        val coordinator = ClipboardWriteCoordinator(publicWriter, fallbackWriter)

        val outcome = coordinator.writeText("remote text", "event-1")

        assertEquals(ClipboardWriteResult.Success, outcome.result)
        assertEquals(ClipboardWriterKind.PUBLIC_API, outcome.writerKind)
        assertEquals(1, publicWriter.writes.size)
        assertEquals(0, fallbackWriter.writes.size)
    }

    @Test
    fun `ready fallback writer runs only after public failure`() {
        val publicWriter = FakeClipboardWriter().apply {
            enqueue(ClipboardWriteResult.Failure("PUBLIC_WRITE_REJECTED"))
        }
        val fallbackWriter = FakeClipboardWriter()
        val coordinator = ClipboardWriteCoordinator(publicWriter, fallbackWriter)

        val outcome = coordinator.writeText("remote text", "event-2")

        assertEquals(ClipboardWriteResult.Success, outcome.result)
        assertEquals(ClipboardWriterKind.PRIVILEGED_FALLBACK, outcome.writerKind)
        assertEquals(1, publicWriter.writes.size)
        assertEquals(1, fallbackWriter.writes.size)
    }

    @Test
    fun `successful remote write suppresses one matching callback`() {
        val coordinator = ClipboardWriteCoordinator(
            publicWriter = FakeClipboardWriter(),
            hasher = ContentHasher { "hash:$it" },
        )
        coordinator.writeText("remote text", "event-3")

        assertFalse(coordinator.shouldSuppress("event-3", "different"))
        assertTrue(coordinator.shouldSuppress("event-3", "remote text"))
        assertFalse(coordinator.shouldSuppress("event-3", "remote text"))
    }

    @Test
    fun `expired write suppression does not hide a later local copy`() {
        var now = 1_000L
        val coordinator = ClipboardWriteCoordinator(
            publicWriter = FakeClipboardWriter(),
            hasher = ContentHasher { "hash:$it" },
            nowEpochMillis = { now },
            suppressionWindowMillis = 500L,
        )
        coordinator.writeText("same text", "event-4")
        now = 1_500L

        assertFalse(coordinator.shouldSuppress("event-4", "same text"))
    }

    @Test
    fun `failed write does not leave a suppression marker`() {
        val publicWriter = FakeClipboardWriter().apply {
            enqueue(ClipboardWriteResult.Failure("PUBLIC_WRITE_REJECTED"))
        }
        val coordinator = ClipboardWriteCoordinator(
            publicWriter = publicWriter,
            hasher = ContentHasher { "hash:$it" },
        )
        coordinator.writeText("remote text", "event-5")

        assertFalse(coordinator.shouldSuppress("event-5", "remote text"))
    }

    @Test
    fun `capture-side suppression matches by hash once and expires with the window`() {
        var now = 1_000L
        val coordinator = ClipboardWriteCoordinator(
            publicWriter = FakeClipboardWriter(),
            hasher = ContentHasher { "hash:$it" },
            nowEpochMillis = { now },
            suppressionWindowMillis = 500L,
        )
        coordinator.writeText("inbound text", "event-a")

        assertFalse(coordinator.shouldSuppressCapture("other text"))
        assertTrue(coordinator.shouldSuppressCapture("inbound text"))
        assertFalse(coordinator.shouldSuppressCapture("inbound text"))

        coordinator.writeText("late text", "event-b")
        now = 2_000L
        assertFalse(coordinator.shouldSuppressCapture("late text"))
    }

    @Test
    fun `public ready never reports manual only even if store has manual only`() {
        val keys = InMemoryCapabilityKeyValueStore()
        val store = KeyValueClipboardCapabilityStore(keys)
        store.saveWrite(WriteCapabilitySnapshot(writeMode = ClipboardWriteMode.MANUAL_ONLY))
        val publicWriter = FakeClipboardWriter(state = CapabilityState.READY)
        val coordinator = ClipboardWriteCoordinator(
            publicWriter = publicWriter,
            capabilityStore = store,
        )

        assertEquals(ClipboardWriteMode.PUBLIC_API, coordinator.writeMode())
        assertTrue(coordinator.writeMode() != ClipboardWriteMode.MANUAL_ONLY)
        assertEquals(ClipboardWriteMode.PUBLIC_API, store.loadWrite()?.writeMode)
    }

    @Test
    fun `fallback probe and last error persist separately from public and never store clip text`() {
        val keys = InMemoryCapabilityKeyValueStore()
        val store = KeyValueClipboardCapabilityStore(keys)
        val secret = "SECRET_WRITE_CLIP_TEXT"
        val publicWriter = FakeClipboardWriter(state = CapabilityState.UNAVAILABLE).apply {
            enqueue(ClipboardWriteResult.Failure("PUBLIC_WRITE_REJECTED"))
        }
        val fallbackWriter = FakeClipboardWriter(state = CapabilityState.READY).apply {
            enqueue(ClipboardWriteResult.Failure("SHIZUKU_WRITE_DENIED"))
        }
        val coordinator = ClipboardWriteCoordinator(
            publicWriter = publicWriter,
            fallbackWriter = fallbackWriter,
            capabilityStore = store,
            fallbackWriteMode = ClipboardWriteMode.SHIZUKU_FALLBACK,
            nowEpochMillis = { 2_000L },
        )

        val outcome = coordinator.writeText(secret, "event-6")

        assertEquals(ClipboardWriteResult.Failure("SHIZUKU_WRITE_DENIED"), outcome.result)
        val snapshot = store.loadWrite()
        assertEquals(ClipboardWriteMode.SHIZUKU_FALLBACK, snapshot?.writeMode)
        assertEquals("PUBLIC_WRITE_REJECTED", snapshot?.publicLastErrorCode)
        assertEquals("SHIZUKU_WRITE_DENIED", snapshot?.fallbackLastErrorCode)
        assertNull(snapshot?.publicLastSuccessAtEpochMillis)
        assertNull(snapshot?.fallbackLastSuccessAtEpochMillis)
        assertFalse(keys.map.values.any { it.contains(secret) })
        assertFalse(coordinator.shouldSuppress("event-6", secret))
    }

    @Test
    fun `public success persists last success independently of fallback error`() {
        val keys = InMemoryCapabilityKeyValueStore()
        val store = KeyValueClipboardCapabilityStore(keys)
        store.saveWrite(
            WriteCapabilitySnapshot(
                writeMode = ClipboardWriteMode.MANUAL_ONLY,
                fallbackLastErrorCode = "SHIZUKU_WRITE_DENIED",
                fallbackLastSuccessAtEpochMillis = null,
            ),
        )
        val coordinator = ClipboardWriteCoordinator(
            publicWriter = FakeClipboardWriter(state = CapabilityState.READY),
            fallbackWriter = FakeClipboardWriter(state = CapabilityState.UNAVAILABLE),
            capabilityStore = store,
            nowEpochMillis = { 3_000L },
        )

        val outcome = coordinator.writeText("ok text", "event-7")

        assertEquals(ClipboardWriteResult.Success, outcome.result)
        assertEquals(ClipboardWriterKind.PUBLIC_API, outcome.writerKind)
        val snapshot = coordinator.writeCapability()
        assertEquals(ClipboardWriteMode.PUBLIC_API, snapshot.writeMode)
        assertEquals(3_000L, snapshot.publicLastSuccessAtEpochMillis)
        assertNull(snapshot.publicLastErrorCode)
        assertEquals("SHIZUKU_WRITE_DENIED", snapshot.fallbackLastErrorCode)
        assertEquals(ClipboardWriteMode.PUBLIC_API, store.loadWrite()?.writeMode)
        assertTrue(coordinator.shouldSuppress("event-7", "ok text"))
    }
}

package com.clipsync.android.platform.clipboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardSelfTestTest {
    private val token = "clipsync-selftest-fixed-token"

    @Test
    fun `write test passes through the public writer and clears the token`() {
        val writer = FakeClipboardWriter()
        var cleared = 0
        val selfTest = selfTest(writer, backend = null, clear = { cleared += 1; true })

        val result = selfTest.runWriteTest()

        assertTrue(result.passed)
        assertEquals(SelfTestKind.BACKGROUND_WRITE, result.kind)
        assertEquals(ClipboardWriterKind.PUBLIC_API, result.writerKind)
        assertNull(result.errorCode)
        assertEquals(1, cleared)
        assertEquals(token, writer.writes.single().text)
        assertFalse(result.toString().contains(token))
    }

    @Test
    fun `failed write reports the code and never clears the user clipboard`() {
        val writer = FakeClipboardWriter().apply {
            enqueue(ClipboardWriteResult.Failure("PUBLIC_WRITE_REJECTED"))
        }
        var cleared = 0
        val selfTest = selfTest(writer, backend = null, clear = { cleared += 1; true })

        val result = selfTest.runWriteTest()

        assertFalse(result.passed)
        assertEquals("PUBLIC_WRITE_REJECTED", result.errorCode)
        assertEquals(0, cleared)
    }

    @Test
    fun `read test passes when the backend returns the seeded token`() {
        val writer = FakeClipboardWriter()
        val coordinator = ClipboardWriteCoordinator(writer)
        val backend = FakeBackgroundClipboardBackend(
            mode = ClipboardReadMode.SHIZUKU_EVENT,
            readResult = ClipboardReadResult.Success(token),
        )
        var cleared = 0
        val selfTest = ClipboardSelfTest(
            writeCoordinator = coordinator,
            readBackend = { backend },
            clearClipboard = { cleared += 1; true },
            tokenGenerator = { token },
        )

        val result = selfTest.runReadTest()

        assertTrue(result.passed)
        assertEquals(SelfTestKind.BACKGROUND_READ, result.kind)
        assertEquals(ClipboardReadMode.SHIZUKU_EVENT, result.readMode)
        assertEquals(1, cleared)
        // The seed write registered a suppression marker: the capture path must
        // treat the echoed token as our own write, exactly once.
        assertTrue(coordinator.shouldSuppressCapture(token))
        assertFalse(coordinator.shouldSuppressCapture(token))
    }

    @Test
    fun `read mismatch fails with a stable code and leaks no text`() {
        val userSecret = "user secret that must never leave"
        val backend = FakeBackgroundClipboardBackend(
            mode = ClipboardReadMode.OVERLAY_POLLING,
            readResult = ClipboardReadResult.Success(userSecret),
        )
        val selfTest = selfTest(FakeClipboardWriter(), backend, clear = { true })

        val result = selfTest.runReadTest()

        assertFalse(result.passed)
        assertEquals(ClipboardSelfTest.ERROR_READ_MISMATCH, result.errorCode)
        assertFalse(result.toString().contains(userSecret))
    }

    @Test
    fun `read test without a backend neither writes nor clears`() {
        val writer = FakeClipboardWriter()
        var cleared = 0
        val selfTest = selfTest(writer, backend = null, clear = { cleared += 1; true })

        val result = selfTest.runReadTest()

        assertFalse(result.passed)
        assertEquals(ClipboardSelfTest.ERROR_NO_READ_BACKEND, result.errorCode)
        assertTrue(writer.writes.isEmpty())
        assertEquals(0, cleared)
    }

    @Test
    fun `failed seed write aborts the read test without touching the clipboard`() {
        val writer = FakeClipboardWriter().apply {
            enqueue(ClipboardWriteResult.Failure("PUBLIC_WRITE_REJECTED"))
        }
        val backend = FakeBackgroundClipboardBackend(
            mode = ClipboardReadMode.SHIZUKU_EVENT,
            readResult = ClipboardReadResult.Success(token),
        )
        var cleared = 0
        var reads = 0
        backend.onRead = { reads += 1 }
        val selfTest = selfTest(writer, backend, clear = { cleared += 1; true })

        val result = selfTest.runReadTest()

        assertFalse(result.passed)
        assertEquals(ClipboardSelfTest.ERROR_SEED_WRITE_FAILED, result.errorCode)
        assertEquals(0, reads)
        assertEquals(0, cleared)
    }

    @Test
    fun `empty and failed reads map to stable codes`() {
        val emptyBackend = FakeBackgroundClipboardBackend(
            mode = ClipboardReadMode.SHIZUKU_EVENT,
            readResult = ClipboardReadResult.Empty,
        )
        val emptyResult = selfTest(FakeClipboardWriter(), emptyBackend, clear = { true }).runReadTest()
        assertEquals(ClipboardSelfTest.ERROR_READ_EMPTY, emptyResult.errorCode)
        assertFalse(emptyResult.passed)

        val failingBackend = FakeBackgroundClipboardBackend(
            mode = ClipboardReadMode.SHIZUKU_EVENT,
            readResult = ClipboardReadResult.Failure("SHIZUKU_BINDER_DEAD"),
        )
        val failedResult = selfTest(FakeClipboardWriter(), failingBackend, clear = { true }).runReadTest()
        assertEquals("SHIZUKU_BINDER_DEAD", failedResult.errorCode)
        assertFalse(failedResult.passed)
    }

    @Test
    fun `clear failure keeps the pass but reports it`() {
        val backend = FakeBackgroundClipboardBackend(
            mode = ClipboardReadMode.SHIZUKU_EVENT,
            readResult = ClipboardReadResult.Success(token),
        )
        val readResult = selfTest(FakeClipboardWriter(), backend, clear = { false }).runReadTest()
        assertTrue(readResult.passed)
        assertEquals(ClipboardSelfTest.ERROR_CLEAR_FAILED, readResult.errorCode)

        val writeResult = selfTest(FakeClipboardWriter(), backend = null, clear = { false }).runWriteTest()
        assertTrue(writeResult.passed)
        assertEquals(ClipboardSelfTest.ERROR_CLEAR_FAILED, writeResult.errorCode)
    }

    private fun selfTest(
        writer: FakeClipboardWriter,
        backend: BackgroundClipboardBackend?,
        clear: () -> Boolean,
    ): ClipboardSelfTest = ClipboardSelfTest(
        writeCoordinator = ClipboardWriteCoordinator(writer),
        readBackend = { backend },
        clearClipboard = clear,
        tokenGenerator = { token },
    )
}

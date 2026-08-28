package com.clipsync.android.platform.clipboard

import com.clipsync.android.platform.clipboard.shizuku.ShizukuErrorCodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The read test must exercise the route's bind-aware verification read, not a plain read that
 * races 特权直读's cold UserService bind. Otherwise the very action meant to prove the channel
 * would itself report PRIV_HOST_USERSERVICE_DEAD every first time — the loop that trapped the
 * card even when the host was perfectly alive.
 */
class ClipboardSelfTestTest {
    private val token = "clipsync-selftest-fixed"

    private fun selfTest(
        backend: BackgroundClipboardBackend,
        writer: FakeClipboardWriter = FakeClipboardWriter(),
    ): ClipboardSelfTest =
        ClipboardSelfTest(
            writeCoordinator = ClipboardWriteCoordinator(publicWriter = writer),
            readBackend = { backend },
            clearClipboard = { true },
            tokenGenerator = { token },
        )

    @Test
    fun `read test verifies through the bind-aware verification read, not a cold plain read`() {
        // A cold channel: a plain read would fail (USERSERVICE_DEAD), but the verification read
        // waits out the bind and returns the seeded token. The test must use the latter and pass.
        val backend =
            FakeBackgroundClipboardBackend(
                mode = ClipboardReadMode.SHIZUKU_EVENT,
                readResult = ClipboardReadResult.Failure(ShizukuErrorCodes.USERSERVICE_DEAD),
                verificationReadResult = ClipboardReadResult.Success(token),
            )

        val result = selfTest(backend).runReadTest()

        assertTrue("cold-but-recoverable channel must verify", result.passed)
        assertEquals(ClipboardReadMode.SHIZUKU_EVENT, result.readMode)
    }

    @Test
    fun `a genuinely dead channel still fails honestly with its code`() {
        // Verification read waited and the channel truly never came up: honest failure, its code.
        val backend =
            FakeBackgroundClipboardBackend(
                mode = ClipboardReadMode.SHIZUKU_EVENT,
                verificationReadResult = ClipboardReadResult.Failure(ShizukuErrorCodes.USERSERVICE_DEAD),
            )

        val result = selfTest(backend).runReadTest()

        assertFalse(result.passed)
        assertEquals(ShizukuErrorCodes.USERSERVICE_DEAD, result.errorCode)
    }

    @Test
    fun `a failed seed write is reported without reading the user's clipboard`() {
        val writer = FakeClipboardWriter().apply { enqueue(ClipboardWriteResult.Failure("WRITE_BLOCKED")) }
        val backend = FakeBackgroundClipboardBackend(ClipboardReadMode.SHIZUKU_EVENT)

        val result = selfTest(backend, writer).runReadTest()

        assertFalse(result.passed)
        assertEquals(ClipboardSelfTest.ERROR_SEED_WRITE_FAILED, result.errorCode)
    }
}

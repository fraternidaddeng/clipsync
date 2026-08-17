package com.clipsync.android.platform.clipboard.shizuku

import com.clipsync.android.platform.clipboard.CapabilityState
import com.clipsync.android.platform.clipboard.ClipboardWriteResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShizukuClipboardWriterTest {
    @Test
    fun `probe ready only after healthy privileged session`() {
        val runtime = FakeShizukuRuntime()
        val writer = ShizukuClipboardWriter(runtime)
        assertEquals(CapabilityState.READY, writer.probe())

        runtime.authorized = false
        assertEquals(CapabilityState.NEEDS_USER_ACTION, writer.probe())
    }

    @Test
    fun `write success maps to coordinator success`() {
        val runtime = FakeShizukuRuntime()
        val writer = ShizukuClipboardWriter(runtime)
        assertEquals(ClipboardWriteResult.Success, writer.writeText("remote", "event-1"))
        assertEquals(listOf("remote"), runtime.session!!.writes)
    }

    @Test
    fun `write fallback maps all seven error codes`() {
        val cases = listOf(
            WriteCase(
                mutate = { presenceState = ShizukuPresence.NOT_INSTALLED },
                code = ShizukuErrorCodes.NOT_INSTALLED,
                probe = CapabilityState.NEEDS_USER_ACTION,
            ),
            WriteCase(
                mutate = { presenceState = ShizukuPresence.NOT_RUNNING },
                code = ShizukuErrorCodes.NOT_RUNNING,
                probe = CapabilityState.NEEDS_USER_ACTION,
            ),
            WriteCase(
                mutate = { authorized = false },
                code = ShizukuErrorCodes.NOT_AUTHORIZED,
                probe = CapabilityState.NEEDS_USER_ACTION,
            ),
            WriteCase(
                mutate = { bindError = ShizukuErrorCodes.BINDER_DEAD },
                code = ShizukuErrorCodes.BINDER_DEAD,
                probe = CapabilityState.UNAVAILABLE,
            ),
            WriteCase(
                mutate = { session = null },
                code = ShizukuErrorCodes.USERSERVICE_DEAD,
                probe = CapabilityState.DEGRADED,
            ),
            WriteCase(
                mutate = {
                    session!!.writeResult =
                        SessionWrite.Failed(ShizukuErrorCodes.CLIPBOARD_BINDER_DEAD)
                    session!!.healthError = ShizukuErrorCodes.CLIPBOARD_BINDER_DEAD
                },
                code = ShizukuErrorCodes.CLIPBOARD_BINDER_DEAD,
                probe = CapabilityState.DEGRADED,
            ),
            WriteCase(
                mutate = { preV11 = true },
                code = ShizukuErrorCodes.API_MISMATCH,
                probe = CapabilityState.UNAVAILABLE,
            ),
        )
        for (case in cases) {
            val runtime = FakeShizukuRuntime()
            case.mutate(runtime)
            val writer = ShizukuClipboardWriter(runtime)
            assertEquals(case.probe, writer.probe())
            assertEquals(ClipboardWriteResult.Failure(case.code), writer.writeText("x", "e"))
        }
    }

    @Test
    fun `write failure does not embed clip text in the error code`() {
        val secret = "super-secret-clip-body"
        val runtime = FakeShizukuRuntime()
        runtime.session!!.writeResult = SessionWrite.Failed(ShizukuErrorCodes.API_MISMATCH)
        val result = ShizukuClipboardWriter(runtime).writeText(secret, "event-9")
            as ClipboardWriteResult.Failure
        assertEquals(ShizukuErrorCodes.API_MISMATCH, result.errorCode)
        assertTrue(!result.errorCode.contains(secret))
        assertTrue(!result.toString().contains(secret))
    }

    private data class WriteCase(
        val mutate: FakeShizukuRuntime.() -> Unit,
        val code: String,
        val probe: CapabilityState,
    )
}

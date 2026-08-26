package com.clipsync.android.platform.clipboard

import com.clipsync.android.platform.clipboard.ClipboardCaptureSession.Owner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardCaptureSessionTest {
    private val calls = mutableListOf<String>()
    private val emitted = mutableListOf<String>()

    private val shizuku =
        FakeBackgroundClipboardBackend(
            mode = ClipboardReadMode.SHIZUKU_EVENT,
            callLog = calls,
        )
    private val foreground =
        FakeBackgroundClipboardBackend(
            mode = ClipboardReadMode.FOREGROUND_ONLY,
            callLog = calls,
        )

    private var captureAllowed = true

    private val session =
        ClipboardCaptureSession(
            coordinator =
                ClipboardAccessCoordinator(
                    backends = listOf(shizuku, foreground),
                    hasher = ContentHasher { "hash:$it" },
                ),
            onChanged = { emitted += it.text },
            captureAllowed = { captureAllowed },
        )

    @Test
    fun `service ownership keeps the backend running after the activity leaves`() {
        // The plan-5.2 acceptance shape: FGS promoted, main UI visible, then backgrounded.
        session.acquire(Owner.FOREGROUND_SERVICE)
        session.acquire(Owner.ACTIVITY)
        session.release(Owner.ACTIVITY)

        assertTrue(session.isRunning)
        assertTrue(session.serviceOwned)
        // Exactly one backend start, no stop: ownership changed hands without churn.
        assertEquals(
            listOf("SHIZUKU_EVENT.probe", "SHIZUKU_EVENT.read", "SHIZUKU_EVENT.start"),
            calls,
        )

        // A copy made with the app backgrounded still flows to the capture callback.
        shizuku.emit("copied while backgrounded", "hash:copied while backgrounded")
        assertEquals(listOf("copied while backgrounded"), emitted)
    }

    @Test
    fun `without a service the activity lifecycle alone drives the coordinator`() {
        session.acquire(Owner.ACTIVITY)
        assertTrue(session.isRunning)
        assertFalse(session.serviceOwned)

        session.release(Owner.ACTIVITY)
        assertFalse(session.isRunning)
        assertEquals("SHIZUKU_EVENT.stop", calls.last())
    }

    @Test
    fun `a stopping service hands the session back to a visible activity`() {
        session.acquire(Owner.FOREGROUND_SERVICE)
        session.acquire(Owner.ACTIVITY)

        session.release(Owner.FOREGROUND_SERVICE)
        assertTrue(session.isRunning)
        assertFalse(session.serviceOwned)

        session.release(Owner.ACTIVITY)
        assertFalse(session.isRunning)
    }

    @Test
    fun `acquire and release are idempotent per owner`() {
        session.acquire(Owner.ACTIVITY)
        session.acquire(Owner.ACTIVITY)
        assertEquals(1, calls.count { it.endsWith(".start") })

        // Releasing an owner that never acquired (the FGS-denied teardown) changes nothing.
        session.release(Owner.FOREGROUND_SERVICE)
        assertTrue(session.isRunning)

        session.release(Owner.ACTIVITY)
        assertFalse(session.isRunning)
    }

    @Test
    fun `closed gates keep every backend stopped even under service ownership`() {
        captureAllowed = false

        session.acquire(Owner.FOREGROUND_SERVICE)

        // 暂停/私密 must prevent the read itself, not just the upload: no probe, no start.
        assertFalse(session.isRunning)
        assertTrue(session.serviceOwned)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `refreshGates stops a running session and resumes it when the gate reopens`() {
        session.acquire(Owner.FOREGROUND_SERVICE)
        assertTrue(session.isRunning)

        captureAllowed = false
        session.refreshGates()
        assertFalse(session.isRunning)
        assertEquals("SHIZUKU_EVENT.stop", calls.last())

        captureAllowed = true
        session.refreshGates()
        assertTrue(session.isRunning)

        shizuku.emit("after resume", "hash:after resume")
        assertEquals(listOf("after resume"), emitted)
    }

    @Test
    fun `checkHealth consults the coordinator only while running`() {
        session.checkHealth()
        assertTrue(calls.isEmpty())

        session.acquire(Owner.FOREGROUND_SERVICE)
        calls.clear()
        session.checkHealth()
        assertEquals(listOf("SHIZUKU_EVENT.health"), calls)
    }

    @Test
    fun `a failed backend falls down the ladder on the service-driven health check`() {
        session.acquire(Owner.FOREGROUND_SERVICE)
        shizuku.backendHealth =
            BackendHealth(
                state = BackendHealthState.FAILED,
                checkedAtEpochMillis = 50L,
                errorCode = "PRIV_HOST_DISCONNECTED",
            )

        session.checkHealth()

        // The coordinator's fallback policy applies unchanged under session ownership.
        shizuku.emit("stale", "hash:stale")
        foreground.emit("from the fallback", "hash:from the fallback")
        assertEquals(listOf("from the fallback"), emitted)
    }
}

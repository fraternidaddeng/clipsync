package com.clipsync.android.platform.clipboard

import com.clipsync.android.platform.clipboard.adblog.AdbLogOverlayBackend as RealAdbLogOverlayBackend
import com.clipsync.android.platform.clipboard.adblog.LogcatClipboardEventReader
import com.clipsync.android.platform.clipboard.adblog.support.ManualScheduler
import com.clipsync.android.platform.clipboard.adblog.support.SequenceLogcatLineSource
import com.clipsync.android.platform.clipboard.overlay.FakeOverlayPlatform
import com.clipsync.android.platform.clipboard.overlay.OverlayClipRead
import com.clipsync.android.platform.clipboard.overlay.OverlayFocusController
import com.clipsync.android.platform.clipboard.overlay.OverlayPollingBackend as RealOverlayPollingBackend
import com.clipsync.android.platform.clipboard.shizuku.FakeShizukuRuntime
import com.clipsync.android.platform.clipboard.shizuku.SessionRead
import com.clipsync.android.platform.clipboard.shizuku.ShizukuClipboardBackend as RealShizukuClipboardBackend
import com.clipsync.android.platform.clipboard.shizuku.ShizukuErrorCodes
import com.clipsync.android.platform.clipboard.shizuku.VerifyBindBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JVM wiring tests for the flat capability-ladder adapters delegating to the stage-4
 * device backends. Binder I/O still needs a physical device; these only prove the
 * coordinator and adapters forward start/read/health to the real implementations.
 */
class PrivilegedReadWiringTest {
    private class FixedProbes(private val prerequisites: RoutePrerequisites) : RouteProbes {
        override fun probe(): RoutePrerequisites = prerequisites
    }

    private fun authorizedProbes() = FixedProbes(
        RoutePrerequisites(
            shizukuInstalled = true,
            shizukuRunning = true,
            shizukuAuthorized = true,
        ),
    )

    @Test
    fun `flat privileged adapter delegates read to the real shizuku backend`() {
        val runtime = FakeShizukuRuntime()
        runtime.session!!.clip = SessionRead.Text("privileged-text")
        val real = RealShizukuClipboardBackend(runtime)
        val adapter = ShizukuClipboardBackend(
            probes = authorizedProbes(),
            systemVersion = "test",
            delegate = real,
            readVerified = { true },
        )

        val result = adapter.readText()

        assertEquals(ClipboardReadResult.Success("privileged-text"), result)
    }

    @Test
    fun `coordinator starts verified privileged backend and reads through delegate`() {
        val runtime = FakeShizukuRuntime()
        runtime.session!!.clip = SessionRead.Text("baseline")
        val real = RealShizukuClipboardBackend(runtime)
        val privileged = ShizukuClipboardBackend(
            probes = authorizedProbes(),
            systemVersion = "test",
            delegate = real,
            readVerified = { true },
        )
        val foreground = FakeBackgroundClipboardBackend(ClipboardReadMode.FOREGROUND_ONLY)
        val emitted = mutableListOf<String>()

        val state = ClipboardAccessCoordinator(
            backends = listOf(privileged, foreground),
            hasher = ContentHasher { "hash:$it" },
        ).start { emitted += it.text }

        assertEquals(ClipboardReadMode.SHIZUKU_EVENT, state.activeReadMode)
        assertNull(state.lastErrorCode)

        runtime.session!!.clip = SessionRead.Text("copied on device")
        runtime.session!!.emitChanged()
        assertEquals(listOf("copied on device"), emitted)
    }

    @Test
    fun `flat privileged adapter's verification read waits out a cold bind via the delegate`() {
        // The exact stuck loop: a plain read races the cold UserService bind (USERSERVICE_DEAD),
        // but the verification read forwarded to the delegate waits for it and succeeds — the only
        // way the route ever verifies.
        val runtime = FakeShizukuRuntime()
        runtime.binding = true
        runtime.session!!.clip = SessionRead.Text("verified-after-wait")
        val real =
            RealShizukuClipboardBackend(
                runtime,
                verifyBind = VerifyBindBudget(polls = 10, stepMillis = 0L),
                sleeper = { runtime.binding = false },
            )
        val adapter =
            ShizukuClipboardBackend(
                probes = authorizedProbes(),
                systemVersion = "test",
                delegate = real,
                readVerified = { false },
            )

        assertEquals(
            ClipboardReadResult.Failure(ShizukuErrorCodes.USERSERVICE_DEAD),
            adapter.readText(),
        )
        assertEquals(ClipboardReadResult.Success("verified-after-wait"), adapter.readTextForVerification())
    }

    @Test
    fun `flat adb log adapter delegates read to the real overlay reader`() {
        var overlayResult = ClipboardReadResult.Success("adb-overlay-read")
        val real = RealAdbLogOverlayBackend(
            readOverlayText = { overlayResult },
            readLogsGranted = { true },
            reader = LogcatClipboardEventReader(
                lineSourceFactory = { SequenceLogcatLineSource(emptyList()) },
                scheduler = ManualScheduler(),
                flightDispatcher = { it.run() },
            ),
            systemVersion = "test",
        )
        val adapter = AdbLogOverlayBackend(
            probes = FixedProbes(
                RoutePrerequisites(readLogsGranted = true, overlayGranted = true),
            ),
            systemVersion = "test",
            delegate = real,
            readVerified = { true },
        )

        overlayResult = ClipboardReadResult.Success("delegated-adb-read")
        assertEquals(ClipboardReadResult.Success("delegated-adb-read"), adapter.readText())
    }

    @Test
    fun `flat overlay polling adapter delegates read to the real polling backend`() {
        val platform = FakeOverlayPlatform()
        platform.clip = OverlayClipRead.Text("polled")
        val controller = OverlayFocusController(platform = platform)
        val real = RealOverlayPollingBackend(
            controller = controller,
            canPollNow = { true },
        )
        val adapter = OverlayPollingBackend(
            probes = FixedProbes(
                RoutePrerequisites(overlayGranted = true, batteryUnrestricted = true),
            ),
            systemVersion = "test",
            delegate = real,
            readVerified = { true },
        )

        assertEquals(ClipboardReadResult.Success("polled"), adapter.readText())
    }
}

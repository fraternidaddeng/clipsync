package com.clipsync.android.platform.clipboard.adblog

import com.clipsync.android.platform.clipboard.BackendHealthState
import com.clipsync.android.platform.clipboard.CapabilityState
import com.clipsync.android.platform.clipboard.ClipboardChange
import com.clipsync.android.platform.clipboard.ClipboardReadMode
import com.clipsync.android.platform.clipboard.ClipboardReadResult
import com.clipsync.android.platform.clipboard.ContentHasher
import com.clipsync.android.platform.clipboard.adblog.fixtures.ClipboardLogFixtures
import com.clipsync.android.platform.clipboard.adblog.support.ManualScheduler
import com.clipsync.android.platform.clipboard.adblog.support.SequenceLogcatLineSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbLogOverlayBackendTest {
    @Test
    fun `mode is adb log overlay`() {
        val env = Env()
        assertEquals(ClipboardReadMode.ADB_LOG_OVERLAY, env.backend.mode)
    }

    @Test
    fun `probe is ready when read logs is granted even before the reader starts`() {
        val env = Env(granted = true)
        val report = env.backend.probe()

        assertEquals(CapabilityState.READY, report.readState)
        assertNull(report.errorCode)
        assertTrue(report.authorizations.any { it.name == "read_logs" && it.granted })
    }

    @Test
    fun `probe needs user action when read logs is missing`() {
        val env = Env(granted = false)
        val report = env.backend.probe()

        assertEquals(CapabilityState.NEEDS_USER_ACTION, report.readState)
        assertEquals(AdbLogOverlayBackend.ERROR_READ_LOGS_NOT_GRANTED, report.errorCode)
        assertTrue(report.authorizations.any { it.name == "read_logs" && !it.granted })
    }

    @Test
    fun `probe becomes ready only after a real recent match`() {
        val env = Env(granted = true, now = 10_000L)
        env.backend.start { }
        env.feedMatch()

        val ready = env.backend.probe()
        assertEquals(CapabilityState.READY, ready.readState)
        assertNull(ready.errorCode)
        assertEquals(10_000L, ready.lastReadSuccessAtEpochMillis)
    }

    @Test
    fun `permission revoked reports degraded with stable code`() {
        val env = Env(granted = true, now = 10_000L)
        env.backend.start { }
        env.feedMatch()
        assertEquals(CapabilityState.READY, env.backend.probe().readState)

        env.granted = false
        val report = env.backend.probe()
        assertEquals(CapabilityState.DEGRADED, report.readState)
        assertEquals(AdbLogOverlayBackend.ERROR_READ_LOGS_REVOKED, report.errorCode)

        val health = env.backend.health()
        assertEquals(BackendHealthState.DEGRADED, health.state)
        assertEquals(AdbLogOverlayBackend.ERROR_READ_LOGS_REVOKED, health.errorCode)
    }

    @Test
    fun `ten seconds without a healthy signal degrades`() {
        val env = Env(granted = true, now = 1_000L)
        env.backend.start { }
        env.feedMatch()
        assertEquals(CapabilityState.READY, env.backend.probe().readState)

        env.now = 11_001L
        val report = env.backend.probe()
        assertEquals(CapabilityState.DEGRADED, report.readState)
        assertEquals(AdbLogOverlayBackend.ERROR_NO_HEALTHY_SIGNAL, report.errorCode)
        assertEquals(BackendHealthState.DEGRADED, env.backend.health().state)
    }

    @Test
    fun `signal reads overlay text hashes and emits`() {
        val env = Env(granted = true, now = 42L, overlayText = "overlay-body")
        val changes = mutableListOf<ClipboardChange>()
        env.backend.start(changes::add)
        env.feedMatch()

        assertEquals(1, env.overlayReads)
        assertEquals(1, changes.size)
        assertEquals("overlay-body", changes[0].text)
        assertEquals("hash:overlay-body", changes[0].contentHash)
        assertEquals(42L, changes[0].observedAtEpochMillis)
    }

    @Test
    fun `unknown format never emits or flips ready`() {
        val env = Env(granted = true, now = 7L)
        val changes = mutableListOf<ClipboardChange>()
        env.backend.start(changes::add)

        for (line in ClipboardLogFixtures.UNKNOWN_UNMATCHED) {
            env.reader.acceptLine(line)
        }
        env.scheduler.runDue()

        assertEquals(emptyList<ClipboardChange>(), changes)
        assertEquals(0, env.overlayReads)
        assertEquals(CapabilityState.DEGRADED, env.backend.probe().readState)
        assertEquals(AdbLogOverlayBackend.ERROR_NO_HEALTHY_SIGNAL, env.backend.probe().errorCode)
    }

    @Test
    fun `readText delegates to injected overlay function`() {
        val env = Env(overlayText = "from-overlay")
        val result = env.backend.readText()
        assertEquals(ClipboardReadResult.Success("from-overlay"), result)
        assertEquals(1, env.overlayReads)
    }

    @Test
    fun `failed overlay read after signal does not emit`() {
        val env = Env(granted = true, now = 9L)
        env.overlayResult = ClipboardReadResult.Failure("OVERLAY_PERMISSION_MISSING")
        val changes = mutableListOf<ClipboardChange>()
        env.backend.start(changes::add)
        env.feedMatch()

        assertEquals(1, env.overlayReads)
        assertEquals(emptyList<ClipboardChange>(), changes)
        assertEquals(CapabilityState.READY, env.backend.probe().readState)
    }

    @Test
    fun `stop leaves health stopped and does not emit later matches`() {
        val env = Env(granted = true, now = 8L)
        val changes = mutableListOf<ClipboardChange>()
        env.backend.start(changes::add)
        env.backend.stop()

        assertEquals(BackendHealthState.STOPPED, env.backend.health().state)
        env.feedMatch()
        assertEquals(emptyList<ClipboardChange>(), changes)
    }

    @Test
    fun `stop invokes the injected overlay closer`() {
        var closeCount = 0
        val env = Env(granted = true, releaseOverlay = { closeCount += 1 })
        env.backend.start { }
        env.backend.stop()
        env.backend.stop()

        assertEquals(2, closeCount)
    }

    private class Env(
        granted: Boolean = true,
        now: Long = 0L,
        overlayText: String = "clip",
        releaseOverlay: () -> Unit = {},
    ) {
        var granted: Boolean = granted
        var now: Long = now
        var overlayReads: Int = 0
        var overlayResult: ClipboardReadResult = ClipboardReadResult.Success(overlayText)
        val scheduler = ManualScheduler()
        val reader = LogcatClipboardEventReader(
            lineSourceFactory = { SequenceLogcatLineSource(emptyList()) },
            scheduler = scheduler,
            nowEpochMillis = { this.now },
            flightDispatcher = { it.run() },
        )
        val backend = AdbLogOverlayBackend(
            readOverlayText = {
                overlayReads += 1
                overlayResult
            },
            readLogsGranted = { this.granted },
            reader = reader,
            hasher = ContentHasher { "hash:$it" },
            nowEpochMillis = { this.now },
            systemVersion = "35",
            releaseOverlay = releaseOverlay,
        )

        fun feedMatch(line: String = ClipboardLogFixtures.AOSP_MATCHED.first().line) {
            reader.acceptLine(line)
            scheduler.runDue()
        }
    }
}

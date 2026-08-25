package com.clipsync.android.platform.clipboard.adblog

import com.clipsync.android.platform.clipboard.BackendHealthState
import com.clipsync.android.platform.clipboard.CapabilityState
import com.clipsync.android.platform.clipboard.ClipboardReadResult
import com.clipsync.android.platform.clipboard.adblog.fixtures.ClipboardLogFixtures
import com.clipsync.android.platform.clipboard.adblog.support.ManualScheduler
import com.clipsync.android.platform.clipboard.adblog.support.SequenceLogcatLineSource
import com.clipsync.android.platform.clipboard.adblog.support.assertNoRetainedRawLine
import java.io.File
import java.io.FileOutputStream
import java.io.FileWriter
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks READ_LOGS minimization: no persistence handles on the adblog types,
 * raw lines do not enter CapabilityReport / reader state, unknown format
 * does not trigger, and revoke leaves READY in one probe.
 */
class AdbLogMinimizationInvariantTest {
    @Test
    fun `adblog types hold no file or writer persistence handles`() {
        val types = listOf(
            AdbLogOverlayBackend::class.java,
            LogcatClipboardEventReader::class.java,
            ProcessLogcatLineSourceFactory::class.java,
        )
        for (type in types) {
            for (field in type.declaredFields) {
                val fieldType = field.type
                assertFalse(
                    "${type.simpleName}.${field.name} must not be a persistence handle",
                    isPersistenceHandle(fieldType),
                )
            }
        }
    }

    @Test
    fun `probe report and reader retain no raw logcat line after a match`() {
        val env = Env()
        env.backend.start { }
        val fixture = ClipboardLogFixtures.AOSP_MATCHED.first()
        env.reader.acceptLine(fixture.line)
        env.scheduler.runDue()

        val report = env.backend.probe()
        assertEquals(CapabilityState.READY, report.readState)
        assertNoRetainedRawLine(report, fixture.line)
        assertNoRetainedRawLine(env.backend, fixture.line)
        assertNoRetainedRawLine(env.reader, fixture.line)

        val match = env.reader.lastMatch
        assertTrue(match != null)
        assertEquals(fixture.family, match!!.family)
        assertEquals(fixture.parserVersion, match.parserVersion)
    }

    @Test
    fun `unknown format never matches so probe stays off READY`() {
        val env = Env()
        env.backend.start { }
        for (line in ClipboardLogFixtures.UNKNOWN_UNMATCHED) {
            env.reader.acceptLine(line)
        }
        env.scheduler.runDue()

        assertEquals(0, env.reader.matchedCount)
        assertTrue(env.backend.probe().readState != CapabilityState.READY)
        assertEquals(AdbLogOverlayBackend.ERROR_NO_HEALTHY_SIGNAL, env.backend.probe().errorCode)
    }

    @Test
    fun `revoking READ_LOGS leaves READY within one probe and health check`() {
        val env = Env()
        env.backend.start { }
        env.reader.acceptLine(ClipboardLogFixtures.AOSP_MATCHED.first().line)
        env.scheduler.runDue()
        assertEquals(CapabilityState.READY, env.backend.probe().readState)

        env.granted = false
        val report = env.backend.probe()
        assertTrue(report.readState != CapabilityState.READY)
        assertEquals(AdbLogOverlayBackend.ERROR_READ_LOGS_REVOKED, report.errorCode)

        val health = env.backend.health()
        assertTrue(health.state != BackendHealthState.HEALTHY)
        assertEquals(AdbLogOverlayBackend.ERROR_READ_LOGS_REVOKED, health.errorCode)
    }

    private fun isPersistenceHandle(type: Class<*>): Boolean {
        if (type == File::class.java ||
            type == FileOutputStream::class.java ||
            type == FileWriter::class.java ||
            type == RandomAccessFile::class.java ||
            type == FileChannel::class.java ||
            type == Path::class.java
        ) {
            return true
        }
        return type.name == "java.io.FileOutputStream" ||
            type.name.startsWith("java.io.File") && type != File::class.java
    }

    private class Env {
        var granted: Boolean = true
        var now: Long = 10_000L
        val scheduler = ManualScheduler()
        val reader = LogcatClipboardEventReader(
            lineSourceFactory = { SequenceLogcatLineSource(emptyList()) },
            scheduler = scheduler,
            nowEpochMillis = { now },
            flightDispatcher = { it.run() },
        )
        val backend = AdbLogOverlayBackend(
            readOverlayText = { ClipboardReadResult.Empty },
            readLogsGranted = { granted },
            reader = reader,
            nowEpochMillis = { now },
            systemVersion = "35",
        )
    }
}

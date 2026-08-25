package com.clipsync.android.platform.clipboard.adblog

import com.clipsync.android.platform.clipboard.adblog.fixtures.ClipboardLogFixtures
import com.clipsync.android.platform.clipboard.adblog.support.ManualScheduler
import com.clipsync.android.platform.clipboard.adblog.support.QueueLogcatLineSource
import com.clipsync.android.platform.clipboard.adblog.support.RecordingLineSourceFactory
import com.clipsync.android.platform.clipboard.adblog.support.SequenceLogcatLineSource
import com.clipsync.android.platform.clipboard.adblog.support.assertNoRetainedRawLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class LogcatClipboardEventReaderTest {
    @Test
    fun `canned stream match emits once after 150ms debounce`() {
        val scheduler = ManualScheduler()
        val source = SequenceLogcatLineSource(listOf(ClipboardLogFixtures.AOSP_MATCHED.first().line))
        val factory = RecordingLineSourceFactory(source)
        val signals = mutableListOf<ClipboardLogMatch>()
        val reader = LogcatClipboardEventReader(
            lineSourceFactory = factory,
            scheduler = scheduler,
            nowEpochMillis = { 1_000L },
            flightDispatcher = { it.run() },
        )

        reader.start(signals::add)
        reader.awaitIdle(1_000L)

        assertEquals(1, factory.openCount)
        assertEquals(listOf(150L), scheduler.pendingDelays())
        assertEquals(emptyList<ClipboardLogMatch>(), signals)

        scheduler.runDue()
        assertEquals(1, signals.size)
        assertEquals(ClipboardLogRomFamily.AOSP, signals[0].family)
        assertEquals(1_000L, reader.lastMatchAtEpochMillis)
        reader.stop()
    }

    @Test
    fun `burst of matches collapses to a single flight after debounce`() {
        val scheduler = ManualScheduler()
        val reader = LogcatClipboardEventReader(
            lineSourceFactory = { SequenceLogcatLineSource(emptyList()) },
            scheduler = scheduler,
            nowEpochMillis = { 2_000L },
            flightDispatcher = { it.run() },
        )
        val signals = mutableListOf<ClipboardLogMatch>()
        reader.start(signals::add)

        reader.acceptLine(ClipboardLogFixtures.AOSP_MATCHED[0].line)
        reader.acceptLine(ClipboardLogFixtures.AOSP_MATCHED[1].line)
        reader.acceptLine(ClipboardLogFixtures.ONE_UI_MATCHED[0].line)

        assertEquals(listOf(150L), scheduler.pendingDelays())
        scheduler.runDue()
        assertEquals(1, signals.size)
        assertEquals(ClipboardLogRomFamily.ONE_UI, signals[0].family)
        reader.stop()
    }

    @Test
    fun `single-flight coalesces overlapping overlay work`() {
        val scheduler = ManualScheduler()
        val reader = LogcatClipboardEventReader(
            lineSourceFactory = { SequenceLogcatLineSource(emptyList()) },
            scheduler = scheduler,
            nowEpochMillis = { 3_000L },
        )
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger(0)
        val signals = CopyOnWriteArrayList<ClipboardLogMatch>()

        reader.start {
            calls.incrementAndGet()
            signals += it
            started.countDown()
            check(release.await(2, TimeUnit.SECONDS))
        }

        reader.acceptLine(ClipboardLogFixtures.AOSP_MATCHED[0].line)
        scheduler.runDue()
        assertTrue(started.await(2, TimeUnit.SECONDS))

        reader.acceptLine(ClipboardLogFixtures.MIUI_HYPEROS_MATCHED[0].line)
        scheduler.runDue()
        assertEquals(1, calls.get())

        release.countDown()
        assertTrue(reader.awaitFlights(expected = 2, timeoutMillis = 1_000L))
        assertEquals(2, calls.get())
        assertEquals(ClipboardLogRomFamily.MIUI_HYPEROS, signals.last().family)
        reader.stop()
    }

    @Test
    fun `unknown format never emits`() {
        val scheduler = ManualScheduler()
        val reader = LogcatClipboardEventReader(
            lineSourceFactory = { SequenceLogcatLineSource(emptyList()) },
            scheduler = scheduler,
            flightDispatcher = { it.run() },
        )
        val signals = mutableListOf<ClipboardLogMatch>()
        reader.start(signals::add)

        for (line in ClipboardLogFixtures.UNKNOWN_UNMATCHED + ClipboardLogFixtures.ALL_UNMATCHED) {
            reader.acceptLine(line)
        }

        assertEquals(emptyList<Long>(), scheduler.pendingDelays())
        scheduler.runDue()
        assertEquals(emptyList<ClipboardLogMatch>(), signals)
        assertNull(reader.lastMatchAtEpochMillis)
        reader.stop()
    }

    @Test
    fun `reader keeps only in-memory counters and never retains raw lines`() {
        val scheduler = ManualScheduler()
        val line = ClipboardLogFixtures.AOSP_MATCHED.first().line
        val reader = LogcatClipboardEventReader(
            lineSourceFactory = { SequenceLogcatLineSource(emptyList()) },
            scheduler = scheduler,
            nowEpochMillis = { 4_000L },
            flightDispatcher = { it.run() },
        )
        reader.start { }
        reader.acceptLine(line)
        scheduler.runDue()

        assertEquals(1, reader.matchedCount)
        assertEquals(1, reader.acceptedLineCount)
        assertNoRetainedRawLine(reader, line)
        reader.stop()
    }

    @Test
    fun `queue-backed factory never starts a real logcat process`() {
        val scheduler = ManualScheduler()
        val source = QueueLogcatLineSource()
        val factory = RecordingLineSourceFactory(source)
        val reader = LogcatClipboardEventReader(
            lineSourceFactory = factory,
            scheduler = scheduler,
            nowEpochMillis = { 5_000L },
            flightDispatcher = { it.run() },
        )
        val signals = mutableListOf<ClipboardLogMatch>()
        reader.start(signals::add)
        source.emit(ClipboardLogFixtures.COLOROS_ORIGINOS_MATCHED.first().line)
        assertTrue(reader.awaitAccepted(1, 1_000L))
        scheduler.runDue()

        assertEquals(1, signals.size)
        assertEquals(ClipboardLogRomFamily.COLOROS_ORIGINOS, signals[0].family)
        source.finish()
        reader.stop()
        assertTrue(source.closed)
    }
}

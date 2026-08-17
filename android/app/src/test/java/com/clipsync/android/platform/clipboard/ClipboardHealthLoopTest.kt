package com.clipsync.android.platform.clipboard

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ClipboardHealthLoopTest {
    @Test
    fun `checks immediately then every interval until cancelled`() = runTest {
        val checks = mutableListOf<Long>()
        val loop = ClipboardHealthLoop(intervalMillis = 10_000L) {
            checks += testScheduler.currentTime
        }
        val job = loop.start(this)
        runCurrent()
        assertEquals(listOf(0L), checks)

        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(listOf(0L, 10_000L), checks)

        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(listOf(0L, 10_000L, 20_000L), checks)

        job.cancel()
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(listOf(0L, 10_000L, 20_000L), checks)
    }

    @Test
    fun `cancelled loop does not check again`() = runTest {
        var checks = 0
        val loop = ClipboardHealthLoop(intervalMillis = 10_000L) { checks += 1 }
        val job = loop.start(this)
        runCurrent()
        assertEquals(1, checks)
        job.cancel()
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(1, checks)
    }

    @Test
    fun `default interval is ten seconds`() {
        assertEquals(10_000L, ClipboardHealthLoop.DEFAULT_INTERVAL_MS)
    }
}

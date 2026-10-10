package com.clipsync.android.sync

import com.clipsync.android.media.ImageChunks
import com.clipsync.android.media.MediaLimits
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SendBackpressureTest {
    @Test
    fun waitsUntilQueueDrainsBelowLimit() = runTest {
        var queued = 10L * 1024 * 1024
        SendBackpressure.awaitBelow(limitBytes = 4L * 1024 * 1024, pollMs = 20) {
            queued -= 1024 * 1024 // the socket writes 1 MiB per poll
            queued
        }
        assertEquals(true, queued <= 4L * 1024 * 1024)
        assertEquals(100L, currentTime) // 5 extra polls of 20 ms
    }

    @Test
    fun returnsImmediatelyWhenQueueIsSmall() = runTest {
        SendBackpressure.awaitBelow(limitBytes = 1, pollMs = 20) { 0L }
        assertEquals(0L, currentTime)
    }

    @Test
    fun lazyChunksMatchEagerSplitForAMaxSizeImage() {
        val bytes = ByteArray(MediaLimits.MAX_ENCODED_BYTES) { (it * 31).toByte() }
        val eager = ImageChunks.split(bytes)
        val count = ImageChunks.chunkCount(bytes)
        assertEquals(eager.size, count)
        for (index in 0 until count) {
            assertEquals(eager[index], ImageChunks.chunkAt(bytes, index, count))
        }
    }
}

package com.clipsync.android.platform.clipboard.adblog

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ProcessLogcatLineSourceFactoryTest {
    @Test
    fun `closing source unblocks a reader waiting for the logcat producer`() {
        val process = BlockingPipeProcess()
        val source =
            ProcessLogcatLineSourceFactory(
                commandFactory = { listOf("fake-logcat") },
                processStarter = { process },
            ).open()
        val workers = Executors.newFixedThreadPool(2)
        try {
            val read = workers.submit<String?> { source.readLine() }
            assertTrue(process.readStarted.await(2, TimeUnit.SECONDS))
            // BufferedReader holds its monitor across this read. close() can only finish
            // once the source stops its producer and releases the pipe, before closing it.
            workers.submit { source.close() }.get(2, TimeUnit.SECONDS)
            assertTrue(process.destroyed)
            assertNull(read.get(2, TimeUnit.SECONDS))
        } finally {
            process.destroy()
            workers.shutdownNow()
        }
    }

    private class BlockingPipeProcess : Process() {
        val readStarted = CountDownLatch(1)
        private val pipe = PipedInputStream()
        private val producer = PipedOutputStream(pipe)
        private val input = object : InputStream() {
            override fun read(): Int {
                readStarted.countDown()
                return pipe.read()
            }

            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                readStarted.countDown()
                return pipe.read(bytes, offset, length)
            }

            override fun close() = pipe.close()
        }

        @Volatile
        var destroyed = false
            private set

        override fun getInputStream(): InputStream = input

        override fun getOutputStream() = ByteArrayOutputStream()

        override fun getErrorStream() =
            ByteArrayInputStream(byteArrayOf())

        override fun waitFor(): Int = 0

        override fun exitValue(): Int = 0

        override fun destroy() {
            destroyed = true
            producer.close()
        }
    }
}

package com.clipsync.android.platform.clipboard.adblog

import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

fun interface CancelHandle {
    fun cancel()
}

fun interface TaskScheduler {
    fun schedule(delayMillis: Long, action: () -> Unit): CancelHandle
}

interface LogcatLineSource {
    fun readLine(): String?

    fun close()
}

fun interface LogcatLineSourceFactory {
    fun open(): LogcatLineSource
}

/**
 * Bounded logcat reader. The process/stream is injected so JVM tests can
 * feed canned lines. Raw lines stay off disk and out of memory after parse.
 */
class LogcatClipboardEventReader(
    private val lineSourceFactory: LogcatLineSourceFactory,
    private val scheduler: TaskScheduler = ThreadTaskScheduler(),
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val debounceMillis: Long = DEFAULT_DEBOUNCE_MILLIS,
    flightDispatcher: ((Runnable) -> Unit)? = null,
) {
    @Volatile
    var lastMatchAtEpochMillis: Long? = null
        private set

    @Volatile
    var lastMatch: ClipboardLogMatch? = null
        private set

    private val acceptedLines = AtomicInteger(0)
    private val matchedLines = AtomicInteger(0)
    private val completedFlights = AtomicInteger(0)

    val acceptedLineCount: Int get() = acceptedLines.get()
    val matchedCount: Int get() = matchedLines.get()

    private val lock = Any()
    private var onSignal: ((ClipboardLogMatch) -> Unit)? = null
    private var started: Boolean = false
    private var readerThread: Thread? = null
    private var source: LogcatLineSource? = null
    private var debounceHandle: CancelHandle? = null
    private var inFlight: Boolean = false
    private var pendingFlight: Boolean = false
    private val flightExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "adblog-flight").apply { isDaemon = true }
    }
    private val flightDispatcher: (Runnable) -> Unit =
        flightDispatcher ?: { runnable -> this.flightExecutor.execute(runnable) }

    fun start(onSignal: (ClipboardLogMatch) -> Unit) {
        synchronized(lock) {
            if (started) {
                this.onSignal = onSignal
                return
            }
            started = true
            this.onSignal = onSignal
            val opened = lineSourceFactory.open()
            source = opened
            val thread = Thread({ drain(opened) }, "adblog-logcat").apply { isDaemon = true }
            readerThread = thread
            thread.start()
        }
    }

    fun stop() {
        val thread: Thread?
        val opened: LogcatLineSource?
        synchronized(lock) {
            started = false
            onSignal = null
            debounceHandle?.cancel()
            debounceHandle = null
            pendingFlight = false
            opened = source
            source = null
            thread = readerThread
            readerThread = null
        }
        opened?.close()
        thread?.interrupt()
        if (scheduler is ThreadTaskScheduler) {
            scheduler.shutdown()
        }
        flightExecutor.shutdownNow()
    }

    fun acceptLine(line: String) {
        acceptedLines.incrementAndGet()
        val match = ClipboardLogParsers.matchKnownChange(line) ?: return
        matchedLines.incrementAndGet()
        lastMatch = match
        lastMatchAtEpochMillis = nowEpochMillis()
        synchronized(lock) {
            if (!started) {
                return
            }
            debounceHandle?.cancel()
            debounceHandle = scheduler.schedule(debounceMillis, ::requestFlight)
        }
    }

    fun awaitIdle(timeoutMillis: Long) {
        readerThread?.join(timeoutMillis)
    }

    fun awaitAccepted(count: Int, timeoutMillis: Long): Boolean =
        awaitCounter(acceptedLines, count, timeoutMillis)

    fun awaitFlights(expected: Int, timeoutMillis: Long): Boolean =
        awaitCounter(completedFlights, expected, timeoutMillis)

    private fun drain(opened: LogcatLineSource) {
        try {
            while (started) {
                val line = opened.readLine() ?: break
                acceptLine(line)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun requestFlight() {
        synchronized(lock) {
            if (!started) {
                return
            }
            if (inFlight) {
                pendingFlight = true
                return
            }
            inFlight = true
        }
        try {
            flightDispatcher(::runFlights)
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            synchronized(lock) {
                inFlight = false
            }
        }
    }

    private fun runFlights() {
        try {
            while (true) {
                val match: ClipboardLogMatch
                val callback: ((ClipboardLogMatch) -> Unit)?
                synchronized(lock) {
                    pendingFlight = false
                    match = lastMatch ?: return
                    callback = onSignal
                }
                if (callback == null) {
                    return
                }
                callback.invoke(match)
                completedFlights.incrementAndGet()
                val again = synchronized(lock) { pendingFlight && started }
                if (!again) {
                    return
                }
            }
        } finally {
            val reschedule = synchronized(lock) {
                inFlight = false
                pendingFlight && started
            }
            if (reschedule) {
                requestFlight()
            }
        }
    }

    private fun awaitCounter(counter: AtomicInteger, count: Int, timeoutMillis: Long): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (counter.get() < count && System.nanoTime() < deadline) {
            Thread.sleep(5L)
        }
        return counter.get() >= count
    }

    companion object {
        const val DEFAULT_DEBOUNCE_MILLIS: Long = 150L
    }
}

internal class ThreadTaskScheduler : TaskScheduler {
    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "adblog-debounce").apply { isDaemon = true }
    }

    override fun schedule(delayMillis: Long, action: () -> Unit): CancelHandle {
        val future = executor.schedule(action, delayMillis, TimeUnit.MILLISECONDS)
        return CancelHandle { future.cancel(false) }
    }

    fun shutdown() {
        executor.shutdownNow()
    }
}

/**
 * Production logcat seam. Unit tests must inject a fake factory instead.
 * Stream is bounded to known clipboard tags from process start (`-T`).
 */
class ProcessLogcatLineSourceFactory(
    private val commandFactory: () -> List<String> = { defaultCommand() },
    private val processStarter: (List<String>) -> Process = { args ->
        ProcessBuilder(args).redirectErrorStream(true).start()
    },
) : LogcatLineSourceFactory {
    override fun open(): LogcatLineSource {
        val process = processStarter(commandFactory())
        val reader = BufferedReader(InputStreamReader(process.inputStream))
        return object : LogcatLineSource {
            override fun readLine(): String? = reader.readLine()

            override fun close() {
                reader.close()
                process.destroy()
            }
        }
    }

    companion object {
        fun defaultCommand(now: Date = Date()): List<String> {
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(now)
            return listOf(
                "logcat",
                "-T",
                stamp,
                "ClipboardService:I",
                "SemClipboardService:I",
                "MiuiClipboardService:I",
                "MiuiClipboardManager:I",
                "HyperClipboardService:I",
                "OplusClipboardService:I",
                "ColorClipboardService:I",
                "ClipboardServiceExtImpl:I",
                "*:S",
            )
        }
    }
}


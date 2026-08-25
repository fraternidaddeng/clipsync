package com.clipsync.android.platform.clipboard.adblog.support

import com.clipsync.android.platform.clipboard.adblog.CancelHandle
import com.clipsync.android.platform.clipboard.adblog.LogcatLineSource
import com.clipsync.android.platform.clipboard.adblog.LogcatLineSourceFactory
import com.clipsync.android.platform.clipboard.adblog.TaskScheduler
import java.util.ArrayDeque
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class ManualScheduler : TaskScheduler {
    data class Task(
        val delayMillis: Long,
        val action: () -> Unit,
        var cancelled: Boolean = false,
    )

    private val tasks = mutableListOf<Task>()

    override fun schedule(delayMillis: Long, action: () -> Unit): CancelHandle {
        val task = Task(delayMillis, action)
        tasks += task
        return CancelHandle { task.cancelled = true }
    }

    fun pendingDelays(): List<Long> = tasks.filter { !it.cancelled }.map { it.delayMillis }

    fun runDue() {
        val snapshot = tasks.toList()
        tasks.clear()
        snapshot.filter { !it.cancelled }.forEach { it.action() }
    }
}

class QueueLogcatLineSource : LogcatLineSource {
    private val queue = LinkedBlockingQueue<String>()
    var closed: Boolean = false
        private set

    fun emit(line: String) {
        queue.put(line)
    }

    fun finish() {
        queue.put(EOF)
    }

    override fun readLine(): String? {
        val next = queue.poll(2, TimeUnit.SECONDS) ?: return null
        return if (next == EOF) null else next
    }

    override fun close() {
        closed = true
        queue.put(EOF)
    }

    private companion object {
        const val EOF = "<eof>"
    }
}

class SequenceLogcatLineSource(
    lines: List<String>,
) : LogcatLineSource {
    private val remaining = ArrayDeque(lines)
    var closed: Boolean = false
        private set

    override fun readLine(): String? = remaining.pollFirst()

    override fun close() {
        closed = true
    }
}

class RecordingLineSourceFactory(
    private val source: LogcatLineSource,
) : LogcatLineSourceFactory {
    var openCount: Int = 0
        private set

    override fun open(): LogcatLineSource {
        openCount += 1
        return source
    }
}

fun assertNoRetainedRawLine(target: Any, rawLine: String) {
    val seen = IdentityHashSet()
    val queue = ArrayDeque<Any>()
    queue.add(target)
    while (queue.isNotEmpty()) {
        val current = queue.removeFirst()
        if (!seen.add(current)) {
            continue
        }
        when (current) {
            is String -> check(!current.contains(rawLine)) { "raw logcat line retained" }
            is CharSequence -> check(!current.toString().contains(rawLine)) { "raw logcat line retained" }
            is Array<*> -> current.filterNotNull().forEach(queue::add)
            is Collection<*> -> current.filterNotNull().forEach(queue::add)
            is Map<*, *> -> {
                current.keys.filterNotNull().forEach(queue::add)
                current.values.filterNotNull().forEach(queue::add)
            }
            else -> {
                var type: Class<*>? = current.javaClass
                val root = type ?: continue
                if (!shouldWalk(root)) {
                    continue
                }
                while (type != null && type != Any::class.java) {
                    if (!shouldWalk(type)) {
                        break
                    }
                    for (field in type.declaredFields) {
                        try {
                            field.isAccessible = true
                        } catch (_: Exception) {
                            continue
                        }
                        val value = try {
                            field.get(current)
                        } catch (_: Exception) {
                            continue
                        } ?: continue
                        if (value is Number || value is Boolean || value is Enum<*>) {
                            continue
                        }
                        queue.add(value)
                    }
                    type = type.superclass
                }
            }
        }
    }
}

private fun shouldWalk(type: Class<*>): Boolean {
    val name = type.name
    return !name.startsWith("java.") &&
        !name.startsWith("javax.") &&
        !name.startsWith("jdk.") &&
        !name.startsWith("sun.") &&
        !name.startsWith("kotlin.")
}

private class IdentityHashSet {
    private val map = java.util.IdentityHashMap<Any, Boolean>()

    fun add(value: Any): Boolean = map.put(value, true) == null
}

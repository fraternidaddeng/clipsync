package com.clipsync.android.sync

import kotlinx.coroutines.delay

/** Suspends until a non-blocking transport's outgoing queue drains below a high-water mark. */
internal object SendBackpressure {
    suspend fun awaitBelow(limitBytes: Long, pollMs: Long, queued: () -> Long) {
        while (queued() > limitBytes) {
            delay(pollMs)
        }
    }
}

package com.clipsync.android.sync

import android.content.Context
import com.clipsync.android.platform.SharedPrefsKeyValueStore

/** Hook the WebSocket sync engine implements to be nudged after a local enqueue. */
fun interface SyncRequester {
    fun requestSyncNow()
}

/**
 * Process-wide wiring for the system entry points (share target, Quick Settings tile,
 * notification copy action). Those components can run in a fresh process with no Activity, so
 * they resolve their dependencies here after [ClipSyncApplication][com.clipsync.android.ClipSyncApplication]
 * has initialized the store. When the Room storage and the sync engine land, [install] swaps in
 * the real implementations without touching any entry point.
 */
object SyncServices {
    @Volatile
    private var services: Services? = null

    val outbox: ClipOutbox
        get() = require().outbox

    val inbox: ClipInbox
        get() = require().inbox

    val syncRequester: SyncRequester
        get() = require().syncRequester

    fun initialize(context: Context) {
        if (services != null) {
            return
        }
        synchronized(this) {
            if (services != null) {
                return
            }
            val store = SharedPrefsKeyValueStore(context, name = "clipsync.sync")
            services = Services(
                outbox = KeyValueClipOutbox(store),
                inbox = KeyValueClipInbox(store),
                // The Stage-4 WebSocket engine registers itself via install(); until then a
                // queued entry simply waits for the next connection.
                syncRequester = SyncRequester { },
            )
        }
    }

    fun install(outbox: ClipOutbox, inbox: ClipInbox, syncRequester: SyncRequester) {
        synchronized(this) {
            services = Services(outbox, inbox, syncRequester)
        }
    }

    private fun require(): Services =
        checkNotNull(services) { "SyncServices.initialize must run in Application.onCreate before use." }

    private data class Services(
        val outbox: ClipOutbox,
        val inbox: ClipInbox,
        val syncRequester: SyncRequester,
    )
}

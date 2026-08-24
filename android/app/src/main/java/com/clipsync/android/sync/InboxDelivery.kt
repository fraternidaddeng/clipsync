package com.clipsync.android.sync

import android.content.Context
import com.clipsync.android.platform.notify.SyncNotifications

/**
 * Single entry point the sync engine calls when a remote clip event has been persisted:
 * record it in the inbox, then surface the copy-action notification (which never contains
 * the text itself). Auto-apply via ClipboardWriteCoordinator hooks in ahead of this call
 * once the engine lands; delivery to the inbox stays the default path (plan 阶段 4).
 */
object InboxDelivery {
    fun deliver(
        context: Context,
        eventId: String,
        text: String,
        receivedAtEpochMillis: Long = System.currentTimeMillis(),
    ) {
        SyncServices.inbox.record(eventId, text, receivedAtEpochMillis)
        SyncNotifications.notifyInboxItem(context, eventId)
    }
}

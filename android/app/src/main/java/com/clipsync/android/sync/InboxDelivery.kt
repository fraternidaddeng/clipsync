package com.clipsync.android.sync

import android.content.Context
import com.clipsync.android.platform.clipboard.AndroidPublicClipboardWriter
import com.clipsync.android.platform.clipboard.ClipboardWriteResult
import com.clipsync.android.platform.clipboard.ClipboardWriter
import com.clipsync.android.platform.notify.SyncNotifications

/**
 * Single entry point the sync engine calls when a remote clip event has been persisted.
 * The inbox record always happens first, so a disabled or failed apply degrades to the
 * manual copy path without losing the event (plan 5.6). With [autoApply] on, the public
 * writer runs next (plan 阶段 4: 先走公开写入); on success the user sees a content-free
 * "applied" status notification, otherwise the copy-action notification. Neither
 * notification ever contains the text itself.
 */
object InboxDelivery {
    /**
     * Replaceable seam: production uses the public-API writer (plan 0.1.2 rule 5 — public
     * write first). When a privileged write fallback or an active capture pipeline lands,
     * this should route through the process-shared ClipboardWriteCoordinator instead so
     * loop suppression covers auto-applied clips.
     */
    var writerFactory: (Context) -> ClipboardWriter = ::AndroidPublicClipboardWriter

    /** Returns true when the clip reached the system clipboard automatically. */
    fun deliver(
        context: Context,
        eventId: String,
        text: String,
        receivedAtEpochMillis: Long = System.currentTimeMillis(),
        autoApply: Boolean = false,
    ): Boolean {
        SyncServices.inbox.record(eventId, text, receivedAtEpochMillis)
        if (autoApply && writerFactory(context).writeText(text, eventId) is ClipboardWriteResult.Success) {
            SyncNotifications.notifyAutoApplied(context, eventId)
            return true
        }
        SyncNotifications.notifyInboxItem(context, eventId)
        return false
    }
}

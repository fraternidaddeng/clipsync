package com.clipsync.android.sync

import android.content.Context
import com.clipsync.android.platform.clipboard.CapabilityState
import com.clipsync.android.platform.clipboard.ClipboardWriteResult
import com.clipsync.android.platform.clipboard.ClipboardWriter
import com.clipsync.android.platform.clipboard.SharedClipboardWrites
import com.clipsync.android.platform.notify.SyncNotifications
import com.clipsync.android.storage.SyncSettingsStore

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
     * Production writes go through the process-shared [ClipboardWriteCoordinator]
     * [com.clipsync.android.platform.clipboard.ClipboardWriteCoordinator] so the foreground
     * capture pipeline suppresses auto-applied clips instead of echoing them back to the peer.
     */
    val defaultWriterFactory: (Context) -> ClipboardWriter = { context ->
        val coordinator = SharedClipboardWrites.coordinator(context)
        object : ClipboardWriter {
            override fun probe(): CapabilityState = coordinator.publicWriteState

            override fun writeText(text: String, originEventId: String): ClipboardWriteResult =
                coordinator.writeText(text, originEventId).result

            override fun writeImage(
                encoded: ByteArray,
                mimeType: String,
                originEventId: String,
            ): ClipboardWriteResult =
                coordinator.writeImage(encoded, mimeType, originEventId).result
        }
    }

    /** Replaceable seam for tests; production keeps [defaultWriterFactory]. */
    var writerFactory: (Context) -> ClipboardWriter = defaultWriterFactory

    /**
     * Plan 3.4 gate: inbound auto-apply obeys both the auto_apply_remote preference and the
     * pause switch. Receiving into the inbox is never gated — only the automatic write is.
     */
    fun autoApplyAllowed(settings: SyncSettingsStore): Boolean =
        settings.autoApplyRemote && !settings.syncPaused

    /**
     * Image counterpart of [autoApplyAllowed]: per ADR 0004 the image write gate is its own
     * opt-in (default off) — the text auto_apply_remote preference never writes pixel bytes
     * to the clipboard on its own. Pause still stops both, matching Windows.
     */
    fun autoApplyImagesAllowed(settings: SyncSettingsStore): Boolean =
        settings.autoApplyImages && !settings.syncPaused

    /**
     * 收到内容通知 (settings-roadmap P1-8): the in-app switch for the inbox notification
     * surface. Recording and auto-apply are never gated by it — only the notification.
     */
    fun inboxNotificationsAllowed(settings: SyncSettingsStore): Boolean = settings.inboxNotifyEnabled

    /** Returns true when the clip reached the system clipboard automatically. */
    fun deliver(
        context: Context,
        eventId: String,
        text: String,
        receivedAtEpochMillis: Long = System.currentTimeMillis(),
        autoApply: Boolean = false,
        notify: Boolean = true,
    ): Boolean {
        SyncServices.inbox.record(eventId, text, receivedAtEpochMillis)
        if (autoApply && writerFactory(context).writeText(text, eventId) is ClipboardWriteResult.Success) {
            if (notify) {
                SyncNotifications.notifyAutoApplied(context, eventId)
            }
            return true
        }
        if (notify) {
            SyncNotifications.notifyInboxItem(context, eventId)
        }
        return false
    }

    /**
     * Image counterpart of [deliver]. Images never enter the text inbox (its records and the
     * notification copy action are text-only); the event is already in Room history with its
     * thumbnail, so a skipped or failed auto-apply simply leaves it there for manual use.
     * Returns true when the image reached the system clipboard automatically.
     */
    fun deliverImage(
        context: Context,
        eventId: String,
        contentHash: String?,
        mimeType: String?,
        autoApply: Boolean = false,
        notify: Boolean = true,
    ): Boolean {
        if (!autoApply || contentHash == null || mimeType == null) {
            return false
        }
        val media = SyncStore.repository(context).media ?: return false
        val bytes = runCatching { media.readAllBytes(contentHash) }.getOrNull() ?: return false
        if (writerFactory(context).writeImage(bytes, mimeType, eventId) is ClipboardWriteResult.Success) {
            if (notify) {
                SyncNotifications.notifyAutoApplied(context, eventId)
            }
            return true
        }
        return false
    }
}

package com.clipsync.android.sync

import android.content.Context
import com.clipsync.android.platform.clipboard.CapabilityState
import com.clipsync.android.platform.clipboard.ClipboardWriteResult
import com.clipsync.android.platform.clipboard.ClipboardWriter
import com.clipsync.android.platform.clipboard.SharedClipboardWrites
import com.clipsync.android.platform.notify.SyncNotifications
import com.clipsync.android.storage.SyncSettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What happened the last time this process tried to write a received clip to the system
 * clipboard automatically. Only real attempts are recorded — a clip that the gates kept from
 * being applied (auto-apply off, paused, not the newest of its batch) leaves no outcome, so the
 * 自动写入剪贴板 fact line never claims a write that was never tried. Never carries content.
 */
data class InboxApplyOutcome(
    val applied: Boolean,
    val isImage: Boolean,
    /** Stable error code from the writer (or a delivery-side code) when [applied] is false. */
    val errorCode: String?,
    val atEpochMillis: Long,
) {
    companion object {
        /** The image blob or its mime type was missing when the write was attempted. */
        const val ERROR_IMAGE_UNAVAILABLE = "IMAGE_UNAVAILABLE"
    }
}

/**
 * The last real auto-apply attempt per kind. The preferences page renders 自动写入剪贴板 and
 * 自动写入远端图片 as two independent fact lines, so a text arrival must not erase what the
 * image line has to say (and vice versa); each slot only moves when a clip of its own kind
 * is actually written.
 */
data class InboxApplyOutcomes(
    val text: InboxApplyOutcome? = null,
    val image: InboxApplyOutcome? = null,
) {
    fun forKind(isImage: Boolean): InboxApplyOutcome? = if (isImage) image else text

    fun with(outcome: InboxApplyOutcome): InboxApplyOutcomes =
        if (outcome.isImage) {
            copy(image = outcome)
        } else {
            copy(text = outcome)
        }
}

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

            override fun writeText(
                text: String,
                originEventId: String,
            ): ClipboardWriteResult = coordinator.writeText(text, originEventId).result

            override fun writeImage(
                encoded: ByteArray,
                mimeType: String,
                originEventId: String,
            ): ClipboardWriteResult = coordinator.writeImage(encoded, mimeType, originEventId).result
        }
    }

    /** Replaceable seam for tests; production keeps [defaultWriterFactory]. */
    var writerFactory: (Context) -> ClipboardWriter = defaultWriterFactory

    /**
     * Flood cap for the whole inbox notification surface (hardening): per-event
     * notifications post up to the window budget, the rest of a burst coalesces into
     * one counting card. Replaceable seam for tests; production keeps the defaults.
     */
    var notificationGate: InboxNotificationGate = InboxNotificationGate()

    private val mutableLastApplyOutcomes = MutableStateFlow(InboxApplyOutcomes())

    /** The most recent real auto-apply attempt of each kind this process; a slot stays null until one has run. */
    val lastApplyOutcomes: StateFlow<InboxApplyOutcomes> = mutableLastApplyOutcomes.asStateFlow()

    /** Test seam: the outcomes are process-wide state on this object, like the notification gate. */
    fun clearLastApplyOutcome() {
        mutableLastApplyOutcomes.value = InboxApplyOutcomes()
    }

    private fun recordApply(
        result: ClipboardWriteResult,
        isImage: Boolean,
        atEpochMillis: Long,
    ): Boolean {
        val applied = result is ClipboardWriteResult.Success
        mutableLastApplyOutcomes.value =
            mutableLastApplyOutcomes.value.with(
                InboxApplyOutcome(
                    applied = applied,
                    isImage = isImage,
                    errorCode = (result as? ClipboardWriteResult.Failure)?.errorCode,
                    atEpochMillis = atEpochMillis,
                ),
            )
        return applied
    }

    /**
     * Plan 3.4 gate: inbound auto-apply obeys both the auto_apply_remote preference and the
     * pause switch. Receiving into the inbox is never gated — only the automatic write is.
     */
    fun autoApplyAllowed(settings: SyncSettingsStore): Boolean = settings.autoApplyRemote && !settings.syncPaused

    /**
     * Image counterpart of [autoApplyAllowed]: per ADR 0004 the image write gate is its own
     * switch (default on since the 2026-08-28 revision), independent of the text gate — the
     * text auto_apply_remote preference never writes pixel bytes to the clipboard on its
     * own, and turning either off never affects the other. Pause still stops both, matching
     * Windows.
     */
    fun autoApplyImagesAllowed(settings: SyncSettingsStore): Boolean = settings.autoApplyImages && !settings.syncPaused

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
        if (autoApply &&
            recordApply(writerFactory(context).writeText(text, eventId), isImage = false, receivedAtEpochMillis)
        ) {
            if (notify) {
                notifyGated(context) { SyncNotifications.notifyAutoApplied(context, eventId) }
            }
            return true
        }
        if (notify) {
            notifyGated(context) { SyncNotifications.notifyInboxItem(context, eventId) }
        }
        return false
    }

    /** Routes one announcement through the flood gate: per-event card or the counting card. */
    private fun notifyGated(
        context: Context,
        post: () -> Unit,
    ) {
        when (val verdict = notificationGate.admit(System.currentTimeMillis())) {
            InboxNotificationGate.Verdict.Post -> post()
            is InboxNotificationGate.Verdict.Coalesce ->
                SyncNotifications.notifyInboxFlood(context, verdict.suppressedInWindow)
        }
    }

    /**
     * Image counterpart of [deliver]. Images never enter the text inbox (its records and the
     * notification copy action are text-only) — the event is already in Room history with its
     * thumbnail. But an arrival never lands silently (plan 5.6): a skipped or failed
     * auto-apply posts the content-free 收到图片 card (no copy action, opens the app), while
     * a successful write posts the same "applied" status card as text. Returns true when the
     * image reached the system clipboard automatically.
     */
    fun deliverImage(
        context: Context,
        eventId: String,
        contentHash: String?,
        mimeType: String?,
        autoApply: Boolean = false,
        notify: Boolean = true,
    ): Boolean {
        val applied = autoApply && applyImage(context, eventId, contentHash, mimeType)
        if (notify) {
            notifyGated(context) {
                if (applied) {
                    SyncNotifications.notifyAutoApplied(context, eventId)
                } else {
                    SyncNotifications.notifyInboxImage(context, eventId)
                }
            }
        }
        return applied
    }

    /** Loads the blob and writes it to the system clipboard; false on any missing piece. */
    private fun applyImage(
        context: Context,
        eventId: String,
        contentHash: String?,
        mimeType: String?,
    ): Boolean {
        val now = System.currentTimeMillis()
        val bytes =
            if (contentHash == null || mimeType == null) {
                null
            } else {
                SyncStore.repository(context).media?.let { media ->
                    runCatching { media.readAllBytes(contentHash) }.getOrNull()
                }
            }
        val result =
            if (bytes == null || mimeType == null) {
                ClipboardWriteResult.Failure(InboxApplyOutcome.ERROR_IMAGE_UNAVAILABLE)
            } else {
                writerFactory(context).writeImage(bytes, mimeType, eventId)
            }
        return recordApply(result, isImage = true, now)
    }
}

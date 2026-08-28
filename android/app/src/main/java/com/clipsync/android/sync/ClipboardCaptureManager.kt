package com.clipsync.android.sync

import com.clipsync.android.platform.clipboard.ClipboardChange
import com.clipsync.android.platform.clipboard.ClipboardWriteCoordinator
import com.clipsync.android.storage.SyncSettingsStore

/** Why a clipboard change did or did not reach the outbox; drives tests and future UI hints. */
enum class CaptureOutcome {
    /** Enqueued for upload and the sync engine was nudged. */
    CAPTURED,

    /** Private mode is on: local clipboard content must not be captured at all. */
    SKIPPED_PRIVATE_MODE,

    /** Sync is paused: nothing is auto-captured while the user has sync off. */
    SKIPPED_SYNC_PAUSED,

    /** 仅暂停自动捕获 (plan 5.2): auto-capture is off while sync and inbound keep running. */
    SKIPPED_CAPTURE_PAUSED,

    /**
     * 跳过敏感内容 (settings-roadmap P0-4): the source app marked this clip sensitive and the
     * preference (default on) says such clips enter neither history nor sync.
     */
    SKIPPED_SENSITIVE,

    /** The change is a clip this app just wrote itself (auto-apply, history copy). */
    SKIPPED_OWN_WRITE,

    /** The change is an image but the image-sync preference has been turned off. */
    SKIPPED_IMAGE_SYNC_OFF,

    /** The outbox rejected the text (empty or a recent duplicate). */
    REJECTED_BY_OUTBOX,

    /**
     * The text exceeds the 1 MiB protocol cap: kept on the local clipboard, never truncated,
     * never queued. Split from [REJECTED_BY_OUTBOX] because this one must reach the user —
     * 超限内容本机保留 + 明确提示，不得静默 (plan 3.3 rule 9, manual-qa-checklist §3).
     */
    REJECTED_TOO_LARGE,
}

/**
 * Stage-4 foreground auto-capture: bridges [ClipboardAccessCoordinator]
 * [com.clipsync.android.platform.clipboard.ClipboardAccessCoordinator] change callbacks into
 * the upload pipeline the share sheet and quick tile already use — outbox enqueue, then a
 * sync nudge — so the service drains the entry into the Room store and announces it.
 *
 * Gate order follows plan 3.4 (暂停/私密 → 回环 → 大小/去重): the pause and private switches
 * are re-read on every event so toggling them applies to the very next copy, and clips this
 * app wrote itself are dropped via the shared [ClipboardWriteCoordinator] suppression table.
 */
class ClipboardCaptureManager(
    private val settings: SyncSettingsStore,
    private val writeCoordinator: ClipboardWriteCoordinator,
    /** Resolved lazily so [SyncServices.install] swaps stay visible to a live manager. */
    private val outbox: () -> ClipOutbox = { SyncServices.outbox },
    private val syncRequester: () -> SyncRequester = { SyncServices.syncRequester },
    /**
     * Where captured image bytes go (validation + blob commit + Room event). Injectable so
     * unit tests need no Room database; production passes [ImageClipSink.submit].
     */
    private val imageSink: (ByteArray) -> Boolean = { false },
) {
    fun onClipboardChanged(change: ClipboardChange): CaptureOutcome {
        if (settings.privateMode) {
            return CaptureOutcome.SKIPPED_PRIVATE_MODE
        }
        if (settings.syncPaused) {
            return CaptureOutcome.SKIPPED_SYNC_PAUSED
        }
        if (settings.autoCapturePaused) {
            // The narrower gate sits after the global ones: 暂停全部同步 wins the outcome
            // when both are set, and this one stops only local auto-capture — explicit
            // share/tile sends and inbound delivery are gated elsewhere and keep working.
            return CaptureOutcome.SKIPPED_CAPTURE_PAUSED
        }
        if (change.isSensitive && settings.skipSensitiveEnabled) {
            // 跳过敏感内容 (settings-roadmap P0-4): a source-app sensitive marker keeps the
            // clip out of history and sync. Only auto-capture is gated here — the share
            // panel is an explicit user action and bypasses this manager entirely.
            return CaptureOutcome.SKIPPED_SENSITIVE
        }
        if (change.isImage) {
            if (!settings.imageSyncEnabled) {
                return CaptureOutcome.SKIPPED_IMAGE_SYNC_OFF
            }
            // Suppression is hash-keyed: an image this app just auto-applied must not echo.
            if (writeCoordinator.shouldSuppressContentHash(change.contentHash)) {
                return CaptureOutcome.SKIPPED_OWN_WRITE
            }
            return if (imageSink(change.imageBytes!!)) {
                CaptureOutcome.CAPTURED
            } else {
                CaptureOutcome.REJECTED_BY_OUTBOX
            }
        }
        if (writeCoordinator.shouldSuppressContent(change.text)) {
            return CaptureOutcome.SKIPPED_OWN_WRITE
        }
        return when (outbox().enqueue(change.text, ClipSource.FOREGROUND_APP)) {
            is EnqueueResult.Accepted -> {
                syncRequester().requestSyncNow()
                CaptureOutcome.CAPTURED
            }
            EnqueueResult.TooLarge -> CaptureOutcome.REJECTED_TOO_LARGE
            else -> CaptureOutcome.REJECTED_BY_OUTBOX
        }
    }
}

package com.clipsync.android.tile

import com.clipsync.android.platform.clipboard.ClipboardReadResult
import com.clipsync.android.storage.CaptureRejectReason
import com.clipsync.android.storage.CaptureResult
import com.clipsync.android.storage.ClipRepository
import com.clipsync.android.storage.SETTING_PAIRED_PEER_ID
import com.clipsync.android.ui.settings.LocalCapturePolicy

sealed class TileSendOutcome {
    data class Stored(val eventId: String) : TileSendOutcome()

    data class Rejected(val reason: CaptureRejectReason) : TileSendOutcome()

    data object EmptyClipboard : TileSendOutcome()

    data class ReadFailed(val errorCode: String) : TileSendOutcome()

    data object SkippedPolicy : TileSendOutcome()
}

class TileClipboardSender(
    private val repository: ClipRepository,
    private val readText: () -> ClipboardReadResult,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    suspend fun send(): TileSendOutcome {
        if (LocalCapturePolicy.isBlocked(repository)) {
            return TileSendOutcome.SkippedPolicy
        }
        return when (val read = readText()) {
            ClipboardReadResult.Empty -> TileSendOutcome.EmptyClipboard
            is ClipboardReadResult.Failure -> TileSendOutcome.ReadFailed(read.errorCode)
            is ClipboardReadResult.Success -> capture(read.text)
        }
    }

    private suspend fun capture(text: String): TileSendOutcome {
        val peerId = repository.getSetting(SETTING_PAIRED_PEER_ID)?.takeIf { it.isNotBlank() }
        return when (val result = repository.captureLocalText(text, SOURCE_TILE, nowMs(), peerId)) {
            is CaptureResult.Stored -> TileSendOutcome.Stored(result.eventId)
            is CaptureResult.Rejected -> TileSendOutcome.Rejected(result.reason)
        }
    }

    companion object {
        const val SOURCE_TILE = "qs_tile"
    }
}

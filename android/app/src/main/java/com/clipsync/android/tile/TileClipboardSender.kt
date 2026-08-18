package com.clipsync.android.tile

import com.clipsync.android.platform.clipboard.ClipboardReadResult
import com.clipsync.android.storage.CaptureRejectReason
import com.clipsync.android.storage.CaptureResult
import com.clipsync.android.storage.ClipRepository
import com.clipsync.android.ui.settings.LocalCapturePolicy

/** Peer id must come from PairingStore, never the Room mirror. */
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
    private val peerDeviceId: () -> String? = { null },
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    suspend fun send(peerId: String? = peerDeviceId()): TileSendOutcome {
        if (LocalCapturePolicy.isBlocked(repository)) {
            return TileSendOutcome.SkippedPolicy
        }
        return when (val read = readText()) {
            ClipboardReadResult.Empty -> TileSendOutcome.EmptyClipboard
            is ClipboardReadResult.Failure -> TileSendOutcome.ReadFailed(read.errorCode)
            is ClipboardReadResult.Success -> capture(read.text, peerId)
        }
    }

    private suspend fun capture(
        text: String,
        peerId: String?,
    ): TileSendOutcome {
        val target = peerId?.takeIf { it.isNotBlank() }
        return when (val result = repository.captureLocalText(text, SOURCE_TILE, nowMs(), target)) {
            is CaptureResult.Stored -> TileSendOutcome.Stored(result.eventId)
            is CaptureResult.Rejected -> TileSendOutcome.Rejected(result.reason)
        }
    }

    companion object {
        const val SOURCE_TILE = "qs_tile"
    }
}

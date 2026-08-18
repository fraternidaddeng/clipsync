package com.clipsync.android.tile

import android.content.ClipboardManager
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import com.clipsync.android.R
import com.clipsync.android.platform.clipboard.ForegroundClipboardBackend
import com.clipsync.android.storage.CaptureRejectReason
import com.clipsync.android.ui.settings.ClipServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SendClipboardTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartListening() {
        qsTile?.apply {
            state = Tile.STATE_ACTIVE
            label = getString(R.string.tile_send_clipboard)
            updateTile()
        }
    }

    override fun onClick() {
        val clipboard = getSystemService(ClipboardManager::class.java) ?: return
        val backend = ForegroundClipboardBackend(
            clipboardManager = clipboard,
            isVisible = { true },
        )
        val sender = TileClipboardSender(
            repository = ClipServices.repository(this),
            readText = { backend.readText() },
        )
        scope.launch {
            val outcome = sender.send(ClipServices.pairingStore(this@SendClipboardTileService).peer()?.deviceId)
            val message = when (outcome) {
                is TileSendOutcome.Stored -> getString(R.string.tile_sent)
                TileSendOutcome.EmptyClipboard -> getString(R.string.tile_empty)
                is TileSendOutcome.ReadFailed -> getString(R.string.tile_read_failed)
                is TileSendOutcome.Rejected -> when (outcome.reason) {
                    CaptureRejectReason.TOO_LARGE -> getString(R.string.share_oversized)
                    CaptureRejectReason.EMPTY_TEXT -> getString(R.string.tile_empty)
                    CaptureRejectReason.DUPLICATE -> getString(R.string.share_duplicate)
                    CaptureRejectReason.BLOCKED_SOURCE -> getString(R.string.share_blocked)
                    CaptureRejectReason.POLICY_PAUSED -> getString(R.string.share_paused)
                }
                TileSendOutcome.SkippedPolicy -> getString(R.string.share_paused)
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

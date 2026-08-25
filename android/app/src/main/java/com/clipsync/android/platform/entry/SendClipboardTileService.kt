package com.clipsync.android.platform.entry

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.clipsync.android.R

/**
 * Quick Settings tile "发送剪贴板": one-shot action that launches the transparent
 * [SendClipboardActivity], which reads the clipboard with real window focus and enqueues it.
 * On a locked device the read would fail, so the tile asks for an unlock first.
 */
class SendClipboardTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_ACTIVE
            label = getString(R.string.qs_tile_label)
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        if (isLocked) {
            unlockAndRun { launchSendActivity() }
        } else {
            launchSendActivity()
        }
    }

    private fun launchSendActivity() {
        val intent = Intent(this, SendClipboardActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}

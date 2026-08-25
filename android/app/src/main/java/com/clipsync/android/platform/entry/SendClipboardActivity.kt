package com.clipsync.android.platform.entry

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.clipsync.android.R
import com.clipsync.android.sync.ClipSource
import com.clipsync.android.sync.EnqueueResult
import com.clipsync.android.sync.SyncServices

/**
 * Invisible helper the Quick Settings tile launches. Android 10+ only lets the focused app
 * read the clipboard, and a TileService never holds focus, so this transparent activity gains
 * window focus for one read (the FOREGROUND_ONLY capability rung), enqueues the text, reports
 * the result as a toast, and finishes.
 */
class SendClipboardActivity : Activity() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var handled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Fallback for ROMs that never deliver window focus to a fully transparent activity.
        mainHandler.postDelayed({ sendOnce() }, FOCUS_TIMEOUT_MILLIS)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            sendOnce()
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun sendOnce() {
        if (handled) {
            return
        }
        handled = true
        Toast.makeText(applicationContext, sendCurrentClipboard(), Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun sendCurrentClipboard(): Int {
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return R.string.toast_clipboard_unavailable
        val clip = manager.primaryClip ?: return R.string.toast_clipboard_empty
        if (clip.itemCount == 0) {
            return R.string.toast_clipboard_empty
        }
        val text = clip.getItemAt(0).coerceToText(this)?.toString()
        if (text.isNullOrEmpty()) {
            return R.string.toast_clipboard_empty
        }
        return when (SyncServices.outbox.enqueue(text, ClipSource.QUICK_TILE)) {
            is EnqueueResult.Accepted -> {
                SyncServices.syncRequester.requestSyncNow()
                R.string.toast_share_enqueued
            }
            EnqueueResult.DuplicateRecent -> R.string.toast_share_duplicate
            EnqueueResult.TooLarge -> R.string.toast_share_too_large
            EnqueueResult.EmptyText -> R.string.toast_clipboard_empty
            EnqueueResult.SyncPaused -> R.string.toast_share_paused
            EnqueueResult.PrivateMode -> R.string.toast_share_private_mode
        }
    }

    private companion object {
        const val FOCUS_TIMEOUT_MILLIS = 800L
    }
}

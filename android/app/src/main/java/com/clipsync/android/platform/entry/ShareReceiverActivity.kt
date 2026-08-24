package com.clipsync.android.platform.entry

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.clipsync.android.R
import com.clipsync.android.sync.ClipSource
import com.clipsync.android.sync.EnqueueResult
import com.clipsync.android.sync.SyncServices

/**
 * ACTION_SEND (text/plain) target: writes the shared text into the local outbox and finishes
 * immediately without any UI (Theme.NoDisplay). The user never needs to open the app or the
 * Windows side; the sync engine uploads the entry on the next connection.
 */
class ShareReceiverActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val outcome = ShareTextIntentHandler.classify(
            action = intent?.action,
            mimeType = intent?.type,
            text = intent?.getCharSequenceExtra(Intent.EXTRA_TEXT),
        )
        val messageRes = when (outcome) {
            is ShareTextIntentHandler.Outcome.ShareText -> enqueue(outcome.text)
            ShareTextIntentHandler.Outcome.NotAShare,
            ShareTextIntentHandler.Outcome.UnsupportedContent,
            -> R.string.toast_share_unsupported
            ShareTextIntentHandler.Outcome.MissingText -> R.string.toast_share_empty
        }
        Toast.makeText(applicationContext, messageRes, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun enqueue(text: String): Int =
        when (SyncServices.outbox.enqueue(text, ClipSource.SHARE_SHEET)) {
            is EnqueueResult.Accepted -> {
                SyncServices.syncRequester.requestSyncNow()
                R.string.toast_share_enqueued
            }
            EnqueueResult.DuplicateRecent -> R.string.toast_share_duplicate
            EnqueueResult.TooLarge -> R.string.toast_share_too_large
            EnqueueResult.EmptyText -> R.string.toast_share_empty
            EnqueueResult.SyncPaused -> R.string.toast_share_paused
            EnqueueResult.PrivateMode -> R.string.toast_share_private_mode
        }
}

package com.clipsync.android.platform.entry

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.clipsync.android.R
import com.clipsync.android.platform.clipboard.ClipboardMediaReader
import com.clipsync.android.sync.ClipSource
import com.clipsync.android.sync.EnqueueResult
import com.clipsync.android.sync.ImageClipSink
import com.clipsync.android.sync.SyncServices

/**
 * ACTION_SEND target with no UI (Theme.NoDisplay). text/plain goes to the local outbox as
 * before; a PNG/JPEG stream (gallery share) is materialized into bytes and committed straight
 * into the Room store via [ImageClipSink] — image sync must be enabled or the share is
 * refused with an honest toast. The user never needs to open the app; the sync engine
 * uploads the entry on the next connection.
 */
class ShareReceiverActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.action == Intent.ACTION_SEND && intent?.type.looksLikeImageShare()) {
            handleImageShare(intent.sharedStream())
            finish()
            return
        }
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

    /**
     * Reads the shared stream off the main thread (a gallery URI can be megabytes), then
     * submits it through the shared image sink. The activity finishes immediately; the toast
     * uses the application context so it still shows after finish.
     */
    private fun handleImageShare(stream: Uri?) {
        val appContext = applicationContext
        if (stream == null) {
            Toast.makeText(appContext, R.string.toast_share_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val mainHandler = Handler(Looper.getMainLooper())
        Thread({
            val bytes = ClipboardMediaReader.readBounded(appContext, stream)
            val messageRes = if (bytes == null) {
                R.string.toast_share_image_invalid
            } else {
                when (ImageClipSink.submit(appContext, bytes, SOURCE_SHARE)) {
                    ImageClipSink.Outcome.Accepted -> R.string.toast_share_image_enqueued
                    ImageClipSink.Outcome.ImageSyncOff -> R.string.toast_share_image_disabled
                    ImageClipSink.Outcome.PrivateMode -> R.string.toast_share_private_mode
                    ImageClipSink.Outcome.SyncPaused -> R.string.toast_share_paused
                    ImageClipSink.Outcome.Invalid -> R.string.toast_share_image_invalid
                }
            }
            mainHandler.post {
                Toast.makeText(appContext, messageRes, Toast.LENGTH_SHORT).show()
            }
        }, "clipsync-share-image").start()
    }

    private companion object {
        const val SOURCE_SHARE = "android.share_sheet"
    }
}

internal fun Intent.sharedStream(): Uri? {
    if (action != Intent.ACTION_SEND) {
        return null
    }
    val extra = streamExtra()
    if (extra != null) {
        return extra
    }
    val clip = clipData ?: return null
    if (clip.itemCount <= 0) {
        return null
    }
    return clip.getItemAt(0).uri
}

private fun Intent.streamExtra(): Uri? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(Intent.EXTRA_STREAM) as Uri?
    }

internal fun String?.looksLikeImageShare(): Boolean {
    val mime = this?.lowercase()?.substringBefore(';')?.trim() ?: return false
    return mime == "image/*" || mime.startsWith("image/")
}

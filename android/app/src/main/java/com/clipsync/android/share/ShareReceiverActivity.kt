package com.clipsync.android.share

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.clipsync.android.R
import com.clipsync.android.platform.clipboard.ClipboardMediaReader
import com.clipsync.android.storage.CaptureRejectReason
import com.clipsync.android.ui.settings.ClipServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ShareReceiverActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent.sharedText()
        val stream = intent.sharedStream()
        val imageShare = stream != null || intent.type.looksLikeImageShare()
        if (text == null && stream == null) {
            Toast.makeText(this, getString(R.string.share_empty), Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        if (stream != null) {
            takeShareReadAccess(stream)
        }
        val repository = ClipServices.repository(this)
        val helper = ShareCaptureHelper(repository)
        val peerId = ClipServices.pairingStore(this).peer()?.deviceId
        val source = callingPackage?.takeIf { it.isNotBlank() } ?: ShareCaptureHelper.SOURCE_SHARE
        lifecycleScope.launch {
            val (outcome, imageAttempt) = withContext(Dispatchers.IO) {
                val bytes = stream?.let { uri -> ClipboardMediaReader.readBounded(this@ShareReceiverActivity, uri) }
                val payload = SharePayloadResolver.resolve(text, bytes, imageShare)
                val result = when (payload) {
                    is SharePayload.Image -> helper.captureImage(payload.encoded, source, peerId)
                    is SharePayload.Text -> helper.capture(payload.value, source, peerId)
                    SharePayload.Empty -> ShareCaptureOutcome.Rejected(CaptureRejectReason.DECODE_FAILED)
                }
                result to (payload is SharePayload.Image || imageShare)
            }
            Toast.makeText(this@ShareReceiverActivity, messageFor(outcome, imageAttempt), Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun messageFor(outcome: ShareCaptureOutcome, imageAttempt: Boolean): String =
        when (outcome) {
            is ShareCaptureOutcome.Stored -> getString(R.string.share_captured)
            is ShareCaptureOutcome.Rejected -> when (outcome.reason) {
                CaptureRejectReason.TOO_LARGE ->
                    if (imageAttempt) getString(R.string.share_image_oversized) else getString(R.string.share_oversized)
                CaptureRejectReason.EMPTY_TEXT -> getString(R.string.share_empty)
                CaptureRejectReason.DUPLICATE -> getString(R.string.share_duplicate)
                CaptureRejectReason.BLOCKED_SOURCE -> getString(R.string.share_blocked)
                CaptureRejectReason.POLICY_PAUSED -> getString(R.string.share_paused)
                CaptureRejectReason.UNSUPPORTED_MEDIA -> getString(R.string.share_image_disabled)
                CaptureRejectReason.DECODE_FAILED ->
                    if (imageAttempt) getString(R.string.share_image_failed) else getString(R.string.share_empty)
            }
            ShareCaptureOutcome.SkippedPolicy -> getString(R.string.share_paused)
        }

    private fun takeShareReadAccess(uri: Uri) {
        try {
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: RuntimeException) {
            // Some OEM share intents reject mutating flags after delivery.
        }
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // One-shot gallery grants are enough; persistable is optional.
        } catch (_: RuntimeException) {
        }
    }
}

internal fun Intent.sharedText(): String? {
    if (action != Intent.ACTION_SEND) {
        return null
    }
    return getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotEmpty() }
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

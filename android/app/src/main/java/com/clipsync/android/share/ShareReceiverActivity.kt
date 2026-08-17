package com.clipsync.android.share

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.clipsync.android.R
import com.clipsync.android.storage.CaptureRejectReason
import com.clipsync.android.ui.settings.ClipServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ShareReceiverActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent.sharedText()
        if (text == null) {
            finish()
            return
        }
        val repository = ClipServices.repository(this)
        val helper = ShareCaptureHelper(repository)
        val source = callingPackage?.takeIf { it.isNotBlank() } ?: ShareCaptureHelper.SOURCE_SHARE
        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) { helper.capture(text, source) }
            val message = when (outcome) {
                is ShareCaptureOutcome.Stored -> getString(R.string.share_captured)
                is ShareCaptureOutcome.Rejected -> when (outcome.reason) {
                    CaptureRejectReason.TOO_LARGE -> getString(R.string.share_oversized)
                    CaptureRejectReason.EMPTY_TEXT -> getString(R.string.share_empty)
                    CaptureRejectReason.DUPLICATE -> getString(R.string.share_duplicate)
                    CaptureRejectReason.BLOCKED_SOURCE -> getString(R.string.share_blocked)
                    CaptureRejectReason.POLICY_PAUSED -> getString(R.string.share_paused)
                }
                ShareCaptureOutcome.SkippedPolicy -> getString(R.string.share_paused)
            }
            Toast.makeText(this@ShareReceiverActivity, message, Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}

internal fun Intent.sharedText(): String? {
    if (action != Intent.ACTION_SEND) {
        return null
    }
    return getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotEmpty() }
}

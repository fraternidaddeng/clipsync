package com.clipsync.android.platform.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.clipsync.android.R
import com.clipsync.android.platform.clipboard.AndroidPublicClipboardWriter
import com.clipsync.android.platform.clipboard.ClipboardWriteResult
import com.clipsync.android.sync.SyncServices

/**
 * Handles the 复制 action of an inbox notification. The intent carries only the event id;
 * the text is looked up locally so it never travels inside the notification.
 */
class CopyInboxItemReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_COPY) {
            return
        }
        val eventId = intent.getStringExtra(EXTRA_EVENT_ID) ?: return
        val text = SyncServices.inbox.textFor(eventId)
        val messageRes = if (text == null) {
            R.string.toast_copy_missing
        } else {
            when (AndroidPublicClipboardWriter(context).writeText(text, eventId)) {
                is ClipboardWriteResult.Success -> {
                    SyncNotifications.cancelInboxItem(context, eventId)
                    R.string.toast_copied
                }
                is ClipboardWriteResult.Failure -> R.string.toast_copy_failed
            }
        }
        Toast.makeText(context, messageRes, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val ACTION_COPY = "com.clipsync.android.action.COPY_INBOX_ITEM"
        const val EXTRA_EVENT_ID = "event_id"

        fun intent(context: Context, eventId: String): Intent =
            Intent(context, CopyInboxItemReceiver::class.java)
                .setAction(ACTION_COPY)
                .putExtra(EXTRA_EVENT_ID, eventId)
    }
}

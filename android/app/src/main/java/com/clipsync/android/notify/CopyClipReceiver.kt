package com.clipsync.android.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.clipsync.android.capture.ClipboardCaptureRuntime
import com.clipsync.android.ui.settings.ClipServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class CopyClipReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val eventId = intent.getStringExtra(InboundClipNotifier.EXTRA_EVENT_ID) ?: return
        val pending = goAsync()
        scope.launch {
            try {
                ClipboardCaptureRuntime.ensureStarted(context.applicationContext)
                val repository = ClipServices.repository(context)
                val entry = repository.findVisibleEntry(eventId) ?: return@launch
                val coordinator = ClipServices.writeCoordinator(context)
                if (entry.isImage) {
                    val mime = entry.mimeType ?: return@launch
                    val bytes = runCatching { repository.media.readAllBytes(entry.contentHash) }.getOrNull()
                        ?: return@launch
                    coordinator.writeImage(bytes, mime, eventId)
                } else {
                    coordinator.writeText(entry.content, eventId)
                }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}

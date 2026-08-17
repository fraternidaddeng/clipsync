package com.clipsync.android.notify

import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import com.clipsync.android.platform.clipboard.AndroidPublicClipboardWriter
import com.clipsync.android.platform.clipboard.ClipboardWriteCoordinator
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
                val repository = ClipServices.repository(context)
                val entry = repository.search("").firstOrNull { it.eventId == eventId } ?: return@launch
                val clipboard = context.applicationContext.getSystemService(ClipboardManager::class.java)
                    ?: return@launch
                val coordinator = ClipboardWriteCoordinator(AndroidPublicClipboardWriter(clipboard))
                coordinator.writeText(entry.content, eventId)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}

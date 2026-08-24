package com.clipsync.android.sync

import android.content.Context
import com.clipsync.android.media.ImageCodec
import com.clipsync.android.media.ImageCodecError
import com.clipsync.android.platform.SharedPrefsKeyValueStore
import com.clipsync.android.storage.SyncSettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Shared entry point that commits a locally captured image (gallery share, foreground
 * clipboard capture) into the Room store: validate the encoded bytes, write the blob into the
 * media store, persist the event with outbox fan-out, and nudge the sync engine. Gates are
 * checked synchronously so entry points can toast an honest verdict; the disk/database work
 * runs on IO because both callers sit on the main thread.
 */
object ImageClipSink {
    sealed interface Outcome {
        /** Passed the gates and validation; the commit is running in the background. */
        data object Accepted : Outcome

        data object ImageSyncOff : Outcome

        data object PrivateMode : Outcome

        data object SyncPaused : Outcome

        /** Not a PNG/JPEG within the protocol limits. */
        data object Invalid : Outcome
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun submit(context: Context, encoded: ByteArray, sourceApp: String?): Outcome {
        val appContext = context.applicationContext
        val settings = SyncSettingsStore(
            SharedPrefsKeyValueStore(appContext, name = SyncSettingsStore.PREFERENCES_NAME),
        )
        if (!settings.imageSyncEnabled) {
            return Outcome.ImageSyncOff
        }
        if (settings.privateMode) {
            return Outcome.PrivateMode
        }
        if (settings.syncPaused) {
            return Outcome.SyncPaused
        }
        val (inspect, validated) = ImageCodec.tryInspect(encoded)
        if (inspect != ImageCodecError.OK || validated == null) {
            return Outcome.Invalid
        }
        scope.launch {
            runCatching {
                val repository = ClipboardSyncService.repositoryProvider(appContext)
                val media = repository.media ?: return@runCatching
                media.commitBytes(encoded, validated.contentHash)
                repository.recordLocalImageClip(validated, sourceApp, System.currentTimeMillis())
                SyncServices.syncRequester.requestSyncNow()
            }
        }
        return Outcome.Accepted
    }
}

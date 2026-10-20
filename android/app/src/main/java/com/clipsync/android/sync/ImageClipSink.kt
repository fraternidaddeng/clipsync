package com.clipsync.android.sync

import android.content.Context
import com.clipsync.android.media.ImageCodec
import com.clipsync.android.media.ImageCodecError
import com.clipsync.android.platform.SharedPrefsKeyValueStore
import com.clipsync.android.storage.SyncSettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Shared entry point that commits a locally captured image (gallery share, foreground
 * clipboard capture) into the Room store: validate the encoded bytes, write the blob into the
 * media store, persist the event with outbox fan-out, and nudge the sync engine. Gates are
 * checked on every submission. Validation and persistence run on IO; callers must await the
 * result before announcing success. An interrupted submission is not acknowledged.
 */
object ImageClipSink {
    sealed interface Outcome {
        /** The validated blob and Room event (including outbox fan-out) are committed. */
        data object Accepted : Outcome

        data object ImageSyncOff : Outcome

        data object PrivateMode : Outcome

        data object SyncPaused : Outcome

        /** Not a PNG/JPEG within the protocol limits. */
        data object Invalid : Outcome

        /** The blob or event could not be persisted; nothing has been acknowledged. */
        data object SaveFailed : Outcome
    }

    suspend fun submit(context: Context, encoded: ByteArray, sourceApp: String?): Outcome =
        withContext(Dispatchers.IO) {
            try {
                val appContext = context.applicationContext
                val settings = SyncSettingsStore(
                    SharedPrefsKeyValueStore(appContext, name = SyncSettingsStore.PREFERENCES_NAME),
                )
                if (!settings.imageSyncEnabled) {
                    return@withContext Outcome.ImageSyncOff
                }
                if (settings.privateMode) {
                    return@withContext Outcome.PrivateMode
                }
                if (settings.syncPaused) {
                    return@withContext Outcome.SyncPaused
                }
                val (inspect, validated) = ImageCodec.tryInspect(encoded)
                if (inspect != ImageCodecError.OK || validated == null) {
                    return@withContext Outcome.Invalid
                }
                val repository = ClipboardSyncService.repositoryProvider(appContext)
                val media = repository.media ?: return@withContext Outcome.SaveFailed
                val committed = media.commitBytes(encoded, validated.contentHash)
                currentCoroutineContext().ensureActive()
                repository.recordLocalImageClip(committed, sourceApp, System.currentTimeMillis())
                    ?: return@withContext Outcome.SaveFailed
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return@withContext Outcome.SaveFailed
            }
            // The durable outbox can be drained on the next connection even if this nudge fails.
            try {
                SyncServices.syncRequester.requestSyncNow()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A failed wake-up must not turn an already saved event into a failed share.
            }
            Outcome.Accepted
        }
}

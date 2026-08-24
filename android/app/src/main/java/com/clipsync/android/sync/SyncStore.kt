package com.clipsync.android.sync

import android.content.Context
import com.clipsync.android.media.MediaBlobStore
import com.clipsync.android.pairing.PairingStore
import com.clipsync.android.platform.KeystoreSecretProtector
import com.clipsync.android.platform.SharedPrefsKeyValueStore
import com.clipsync.android.storage.ClipSyncDatabase
import com.clipsync.android.storage.ClipSyncRepository

/**
 * Process-wide Room store handle. The foreground sync service and the UI must share one
 * database instance: Room's invalidation tracker only notifies observers registered on the
 * same instance, so a second handle would leave the 一屏 history blind to the engine's writes.
 */
object SyncStore {
    @Volatile
    private var repository: ClipSyncRepository? = null

    fun repository(context: Context): ClipSyncRepository =
        repository ?: synchronized(this) {
            repository ?: create(context.applicationContext).also { repository = it }
        }

    private fun create(appContext: Context): ClipSyncRepository {
        val pairing = PairingStore(SharedPrefsKeyValueStore(appContext), KeystoreSecretProtector())
        val media = MediaBlobStore(
            MediaBlobStore.defaultRootForDatabase(appContext.getDatabasePath(ClipSyncDatabase.DEFAULT_NAME)),
        )
        return ClipSyncRepository(ClipSyncDatabase.build(appContext), pairing.localDeviceId(), media)
    }
}

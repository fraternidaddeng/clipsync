package com.clipsync.android.ui.settings

import android.content.ClipboardManager
import android.content.Context
import com.clipsync.android.pairing.PairingStore
import com.clipsync.android.platform.KeystoreSecretProtector
import com.clipsync.android.platform.SharedPrefsKeyValueStore
import com.clipsync.android.platform.clipboard.AndroidPublicClipboardWriter
import com.clipsync.android.platform.clipboard.CapabilityState
import com.clipsync.android.platform.clipboard.ClipboardWriteCoordinator
import com.clipsync.android.platform.clipboard.ForegroundClipboardBackend
import com.clipsync.android.storage.ClipRepository
import com.clipsync.android.storage.createClipRepository
import com.clipsync.android.ui.HealthTone
import com.clipsync.android.ui.HealthValue

/**
 * Process-scoped accessors for UI, share, tile, and notification entry points.
 * Does not start a ForegroundService.
 */
object ClipServices {
    @Volatile
    private var repository: ClipRepository? = null
    private val lock = Any()

    fun pairingStore(context: Context): PairingStore =
        PairingStore(SharedPrefsKeyValueStore(context.applicationContext), KeystoreSecretProtector())

    fun repository(context: Context): ClipRepository {
        repository?.let { return it }
        synchronized(lock) {
            repository?.let { return it }
            val created = createClipRepository(
                context.applicationContext,
                pairingStore(context).localDeviceId(),
            )
            repository = created
            return created
        }
    }

    fun writeCoordinator(context: Context): ClipboardWriteCoordinator {
        val clipboard = clipboardManager(context) ?: return ClipboardWriteCoordinator(
            publicWriter = UnavailableClipboardWriter,
        )
        return ClipboardWriteCoordinator(AndroidPublicClipboardWriter(clipboard))
    }

    fun foregroundBackend(context: Context, isVisible: () -> Boolean): ForegroundClipboardBackend? {
        val clipboard = clipboardManager(context) ?: return null
        return ForegroundClipboardBackend(clipboard, isVisible)
    }

    fun syncStatus(context: Context): SyncStatusProvider =
        PairingAwareSyncStatusProvider(
            isPaired = { pairingStore(context).peer() != null },
        )

    fun capabilities(context: Context, isVisible: () -> Boolean): CapabilityStatusProvider =
        LiveCapabilityStatus(
            read = {
                val backend = foregroundBackend(context, isVisible)
                val report = backend?.probe()
                when (report?.readState) {
                    CapabilityState.READY -> HealthValue("Foreground ready", HealthTone.GOOD)
                    CapabilityState.DEGRADED -> HealthValue("Degraded", HealthTone.WARNING)
                    CapabilityState.UNAVAILABLE -> HealthValue("Unavailable", HealthTone.WARNING)
                    CapabilityState.UNKNOWN, null -> HealthValue("Foreground only", HealthTone.NEUTRAL)
                }
            },
            write = {
                val clipboard = clipboardManager(context)
                val state = if (clipboard == null) {
                    CapabilityState.UNAVAILABLE
                } else {
                    AndroidPublicClipboardWriter(clipboard).probe()
                }
                when (state) {
                    CapabilityState.READY -> HealthValue("Public write ready", HealthTone.GOOD)
                    CapabilityState.DEGRADED -> HealthValue("Degraded", HealthTone.WARNING)
                    CapabilityState.UNAVAILABLE -> HealthValue("Unavailable", HealthTone.WARNING)
                    CapabilityState.UNKNOWN -> HealthValue("Not probed", HealthTone.NEUTRAL)
                }
            },
        )

    private fun clipboardManager(context: Context): ClipboardManager? =
        context.applicationContext.getSystemService(ClipboardManager::class.java)
}

private object UnavailableClipboardWriter : com.clipsync.android.platform.clipboard.ClipboardWriter {
    override fun probe(): CapabilityState = CapabilityState.UNAVAILABLE

    override fun writeText(
        text: String,
        originEventId: String,
    ): com.clipsync.android.platform.clipboard.ClipboardWriteResult =
        com.clipsync.android.platform.clipboard.ClipboardWriteResult.Failure(
            AndroidPublicClipboardWriter.ERROR_UNAVAILABLE,
        )
}

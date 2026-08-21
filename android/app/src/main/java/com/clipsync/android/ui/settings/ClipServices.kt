package com.clipsync.android.ui.settings

import android.content.ClipboardManager
import android.content.Context
import com.clipsync.android.capture.ClipboardCaptureRuntime
import com.clipsync.android.pairing.PairingStore
import com.clipsync.android.platform.KeystoreSecretProtector
import com.clipsync.android.platform.SharedPrefsKeyValueStore
import com.clipsync.android.service.ServiceSettingsStore
import com.clipsync.android.platform.clipboard.AndroidPublicClipboardWriter
import com.clipsync.android.platform.clipboard.CapabilityState
import com.clipsync.android.platform.clipboard.ClipboardWriteCoordinator
import com.clipsync.android.platform.clipboard.ClipboardWriteMode
import com.clipsync.android.platform.clipboard.ClipboardWriteResult
import com.clipsync.android.platform.clipboard.ClipboardWriter
import com.clipsync.android.platform.clipboard.ForegroundClipboardBackend
import com.clipsync.android.platform.clipboard.PublicClipboardWriter
import com.clipsync.android.storage.ClipRepository
import com.clipsync.android.storage.createClipRepository

/**
 * Process-scoped accessors for UI, share, tile, notification, and service entry points.
 */
object ClipServices {
    @Volatile
    private var repository: ClipRepository? = null

    @Volatile
    private var writeCoordinator: ClipboardWriteCoordinator? = null

    private val lock = Any()

    /**
     * Privileged write-fallback provider. Default null keeps public-only writes.
     *
     * Consolidation / MainActivity must assign a lambda that returns
     * `ShizukuClipboardBackend.fallbackWriter()` from the **same** backend
     * instance used for Shizuku reads (the one in `BackgroundClipboardBackends`).
     * Do **not** construct `ShizukuClipboardWriter(context)` here — that opens
     * a second `AndroidShizukuRuntime` and a second UserService bind.
     *
     * Call after `BackgroundClipboardBackends.build(...)`. The coordinator
     * singleton already holds [DeferredWriteFallback], whose `probe()` is
     * non-READY until this provider returns a READY writer, so the frozen
     * coordinator never invokes fallback write in the meantime.
     */
    @Volatile
    var writeFallbackProvider: (() -> ClipboardWriter?)? = null

    fun pairingStore(context: Context): PairingStore =
        PairingStore(SharedPrefsKeyValueStore(context.applicationContext), KeystoreSecretProtector())

    fun serviceSettings(context: Context): ServiceSettingsStore =
        ServiceSettingsStore(SharedPrefsKeyValueStore(context.applicationContext, "clipsync.service"))

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

    /**
     * Process singleton: the write-suppression markers must be visible to every
     * capture path (Activity listener, service, History copy, self-test), so all
     * writers and capture guards share one coordinator instance.
     */
    fun writeCoordinator(context: Context): ClipboardWriteCoordinator {
        writeCoordinator?.let { return it }
        val clipboard = clipboardManager(context)
        val publicWriter = if (clipboard == null) {
            UnavailableClipboardWriter
        } else {
            AndroidPublicClipboardWriter(clipboard, context = context.applicationContext)
        }
        return writeCoordinator(publicWriter)
    }

    /**
     * Test / assembly seam. [fallbackWriter] defaults to [DeferredWriteFallback]
     * so consolidation can attach a shared Shizuku writer after first creation.
     * Passing a fake writer in tests replaces that slot for the singleton.
     */
    fun writeCoordinator(
        publicWriter: PublicClipboardWriter,
        fallbackWriter: ClipboardWriter? = DeferredWriteFallback,
    ): ClipboardWriteCoordinator {
        writeCoordinator?.let { return it }
        synchronized(lock) {
            writeCoordinator?.let { return it }
            val created = ClipboardWriteCoordinator(
                publicWriter = publicWriter,
                fallbackWriter = fallbackWriter,
                fallbackWriteMode = ClipboardWriteMode.SHIZUKU_FALLBACK,
            )
            writeCoordinator = created
            return created
        }
    }

    internal fun resetWriteCoordinator() {
        synchronized(lock) {
            writeCoordinator = null
            writeFallbackProvider = null
        }
    }

    fun foregroundBackend(context: Context, isVisible: () -> Boolean): ForegroundClipboardBackend? {
        val clipboard = clipboardManager(context) ?: return null
        return ForegroundClipboardBackend(context.applicationContext, clipboard, isVisible)
    }

    fun syncStatus(context: Context): SyncStatusProvider =
        PairingAwareSyncStatusProvider(
            isPaired = { pairingStore(context).peer() != null },
        )

    @Suppress("UNUSED_PARAMETER") // Signature kept for callers; parked stacks must not probe foreground.
    fun capabilities(context: Context, isVisible: () -> Boolean): CapabilityStatusProvider =
        LiveCapabilityStatus(
            read = {
                // Prefer the live process capture stack: the card must name the
                // ACTIVE backend (Shizuku/ADB/overlay/foreground), not always the
                // foreground probe (device finding on MIUI).
                val access = ClipboardCaptureRuntime.currentAccess()
                val activeMode = access?.state?.activeReadMode
                when {
                    access != null && activeMode != null ->
                        healthReadForActiveMode(activeMode, access.lastReadState)
                    // Parked stack: do not probe ForegroundClipboardBackend or the
                    // card claims "Foreground ready" while capture is waiting.
                    access != null && activeMode == null ->
                        healthRead(access.lastReadState)
                    else -> healthRead(null)
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
                    CapabilityState.READY -> healthWrite(CapabilityState.READY)
                    CapabilityState.DEGRADED -> healthWrite(CapabilityState.DEGRADED)
                    CapabilityState.UNAVAILABLE -> healthWrite(CapabilityState.UNAVAILABLE)
                    CapabilityState.NEEDS_USER_ACTION -> healthWrite(CapabilityState.NEEDS_USER_ACTION)
                    CapabilityState.UNKNOWN -> healthWrite(CapabilityState.UNKNOWN)
                }
            },
        )

    private fun clipboardManager(context: Context): ClipboardManager? =
        context.applicationContext.getSystemService(ClipboardManager::class.java)
}

private object UnavailableClipboardWriter : ClipboardWriter {
    override fun probe(): CapabilityState = CapabilityState.UNAVAILABLE

    override fun writeText(
        text: String,
        originEventId: String,
    ): ClipboardWriteResult =
        ClipboardWriteResult.Failure(AndroidPublicClipboardWriter.ERROR_UNAVAILABLE)

    override fun writeImage(
        encoded: ByteArray,
        mimeType: String,
        originEventId: String,
    ): ClipboardWriteResult =
        ClipboardWriteResult.Failure(AndroidPublicClipboardWriter.ERROR_UNAVAILABLE)
}

/**
 * Fallback slot held by the process-singleton coordinator. `probe()` is
 * [CapabilityState.UNAVAILABLE] until [ClipServices.writeFallbackProvider]
 * returns a writer, so the coordinator never calls [writeText] in that state.
 */
internal object DeferredWriteFallback : ClipboardWriter {
    override fun probe(): CapabilityState =
        ClipServices.writeFallbackProvider?.invoke()?.probe() ?: CapabilityState.UNAVAILABLE

    override fun writeText(text: String, originEventId: String): ClipboardWriteResult =
        ClipServices.writeFallbackProvider?.invoke()?.writeText(text, originEventId)
            ?: ClipboardWriteResult.Failure(AndroidPublicClipboardWriter.ERROR_UNAVAILABLE)

    override fun writeImage(
        encoded: ByteArray,
        mimeType: String,
        originEventId: String,
    ): ClipboardWriteResult =
        ClipServices.writeFallbackProvider?.invoke()?.writeImage(encoded, mimeType, originEventId)
            ?: ClipboardWriteResult.Failure(AndroidPublicClipboardWriter.ERROR_UNAVAILABLE)
}

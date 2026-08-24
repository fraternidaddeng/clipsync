package com.clipsync.android.platform.clipboard.adblog

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.clipsync.android.platform.clipboard.BackendHealth
import com.clipsync.android.platform.clipboard.BackendHealthState
import com.clipsync.android.platform.clipboard.BackgroundClipboardBackend
import com.clipsync.android.platform.clipboard.CapabilityReport
import com.clipsync.android.platform.clipboard.CapabilityState
import com.clipsync.android.platform.clipboard.ClipboardAuthorization
import com.clipsync.android.platform.clipboard.ClipboardChange
import com.clipsync.android.platform.clipboard.ClipboardReadMode
import com.clipsync.android.platform.clipboard.ClipboardReadResult
import com.clipsync.android.platform.clipboard.ContentHasher
import com.clipsync.android.platform.clipboard.Sha256ContentHasher

/**
 * ADB [READ_LOGS] change-signal backend. The log reader only signals
 * "changed"; the body is read through [readOverlayText]. The constructor
 * takes that function value so this class compiles without the overlay
 * controller type; the orchestrator injects it later.
 */
class AdbLogOverlayBackend(
    private val readOverlayText: () -> ClipboardReadResult,
    private val readLogsGranted: () -> Boolean,
    private val reader: LogcatClipboardEventReader,
    private val hasher: ContentHasher = Sha256ContentHasher,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val healthySignalTtlMillis: Long = HEALTHY_SIGNAL_TTL_MILLIS,
    private val systemVersion: String = "unknown",
    private val releaseOverlay: () -> Unit = {},
) : BackgroundClipboardBackend {
    constructor(
        context: Context,
        readOverlayText: () -> ClipboardReadResult,
        releaseOverlay: () -> Unit = {},
    ) : this(
        readOverlayText = readOverlayText,
        readLogsGranted = {
            context.checkSelfPermission(Manifest.permission.READ_LOGS) ==
                PackageManager.PERMISSION_GRANTED
        },
        reader = LogcatClipboardEventReader(
            lineSourceFactory = ProcessLogcatLineSourceFactory(),
            flightDispatcher = { runnable ->
                val main = Looper.getMainLooper()
                if (Looper.myLooper() == main) {
                    runnable.run()
                } else {
                    Handler(main).post(runnable)
                }
            },
        ),
        systemVersion = Build.VERSION.SDK_INT.toString(),
        releaseOverlay = releaseOverlay,
    )

    override val mode: ClipboardReadMode = ClipboardReadMode.ADB_LOG_OVERLAY

    private var callback: ((ClipboardChange) -> Unit)? = null
    private var started: Boolean = false
    private var sawGrant: Boolean = false
    private var lastErrorCode: String? = null
    private var lastReadSuccessAtEpochMillis: Long? = null

    override fun probe(): CapabilityReport {
        val granted = readLogsGranted()
        if (granted) {
            sawGrant = true
        }
        val (state, code) = diagnose(granted)
        lastErrorCode = code
        return CapabilityReport(
            readMode = ClipboardReadMode.ADB_LOG_OVERLAY,
            readState = state,
            writeState = CapabilityState.UNKNOWN,
            systemVersion = systemVersion,
            authorizations = listOf(ClipboardAuthorization("read_logs", granted)),
            lastReadSuccessAtEpochMillis = lastReadSuccessAtEpochMillis,
            errorCode = code,
        )
    }

    override fun start(onChanged: (ClipboardChange) -> Unit) {
        callback = onChanged
        started = true
        if (readLogsGranted()) {
            sawGrant = true
        }
        reader.start(::onLogSignal)
    }

    override fun stop() {
        started = false
        callback = null
        reader.stop()
        releaseOverlay()
    }

    override fun readText(): ClipboardReadResult = readOverlayText()

    override fun health(): BackendHealth {
        val checkedAt = nowEpochMillis()
        if (!started) {
            return BackendHealth(BackendHealthState.STOPPED, checkedAt)
        }
        val granted = readLogsGranted()
        if (granted) {
            sawGrant = true
        }
        val (state, code) = diagnose(granted)
        lastErrorCode = code
        val healthState = when (state) {
            CapabilityState.READY -> BackendHealthState.HEALTHY
            CapabilityState.DEGRADED -> BackendHealthState.DEGRADED
            CapabilityState.UNAVAILABLE -> BackendHealthState.FAILED
            CapabilityState.UNKNOWN -> BackendHealthState.DEGRADED
        }
        return BackendHealth(healthState, checkedAt, code)
    }

    private fun onLogSignal(@Suppress("UNUSED_PARAMETER") match: ClipboardLogMatch) {
        if (!started || !readLogsGranted()) {
            return
        }
        when (val result = readOverlayText()) {
            is ClipboardReadResult.Success -> {
                if (result.text.isEmpty()) {
                    return
                }
                val observedAt = nowEpochMillis()
                lastReadSuccessAtEpochMillis = observedAt
                lastErrorCode = null
                callback?.invoke(
                    ClipboardChange(
                        text = result.text,
                        contentHash = hasher.hash(result.text),
                        observedAtEpochMillis = observedAt,
                    ),
                )
            }
            ClipboardReadResult.Empty -> Unit
            is ClipboardReadResult.Failure -> {
                lastErrorCode = result.errorCode
            }
        }
    }

    private fun diagnose(granted: Boolean): Pair<CapabilityState, String?> {
        if (!granted) {
            val code = if (sawGrant) ERROR_READ_LOGS_REVOKED else ERROR_READ_LOGS_NOT_GRANTED
            val state = if (sawGrant) CapabilityState.DEGRADED else CapabilityState.UNAVAILABLE
            return state to code
        }
        if (!started) {
            // Granted READ_LOGS is enough to offer this backend as an upgrade
            // target; a live match can only exist while the reader is running.
            return CapabilityState.READY to null
        }
        val matchedAt = reader.lastMatchAtEpochMillis
        if (matchedAt == null || nowEpochMillis() - matchedAt > healthySignalTtlMillis) {
            return CapabilityState.DEGRADED to ERROR_NO_HEALTHY_SIGNAL
        }
        return CapabilityState.READY to null
    }

    companion object {
        const val ERROR_READ_LOGS_NOT_GRANTED = "ADB_LOG_READ_LOGS_NOT_GRANTED"
        const val ERROR_READ_LOGS_REVOKED = "ADB_LOG_READ_LOGS_REVOKED"
        const val ERROR_NO_HEALTHY_SIGNAL = "ADB_LOG_NO_HEALTHY_SIGNAL"
        const val ERROR_UNKNOWN_FORMAT = "ADB_LOG_UNKNOWN_FORMAT"
        const val HEALTHY_SIGNAL_TTL_MILLIS: Long = 10_000L
    }
}

package com.clipsync.android.platform.clipboard

enum class ClipboardReadMode {
    /**
     * 特权直读 (privileged read) through the built-in privileged host. The enum name is a
     * legacy internal identifier persisted in preference keys — renaming it would drop the
     * user's verified-read state on update, so it stays; every user-visible surface says
     * 特权直读 instead.
     */
    SHIZUKU_EVENT,
    ADB_LOG_OVERLAY,
    OVERLAY_POLLING,
    FOREGROUND_ONLY,
}

enum class CapabilityState {
    UNKNOWN,
    READY,
    DEGRADED,
    UNAVAILABLE,

    /** Blocked until the user grants or restores something (特权直读 host, overlay, READ_LOGS). */
    NEEDS_USER_ACTION,
}

data class ClipboardAuthorization(
    val name: String,
    val granted: Boolean,
)

data class CapabilityReport(
    val readMode: ClipboardReadMode,
    val readState: CapabilityState,
    val writeState: CapabilityState,
    val systemVersion: String,
    val authorizations: List<ClipboardAuthorization> = emptyList(),
    val lastReadSuccessAtEpochMillis: Long? = null,
    val lastWriteSuccessAtEpochMillis: Long? = null,
    val errorCode: String? = null,
)

enum class BackendHealthState {
    HEALTHY,
    DEGRADED,
    FAILED,
    STOPPED,
}

data class BackendHealth(
    val state: BackendHealthState,
    val checkedAtEpochMillis: Long,
    val errorCode: String? = null,
)

sealed interface ClipboardReadResult {
    data class Success(
        val text: String,
        /** The source app marked this clip sensitive (ClipDescription extra); see [ClipboardChange.isSensitive]. */
        val isSensitive: Boolean = false,
    ) : ClipboardReadResult

    data object Empty : ClipboardReadResult

    data class Failure(val errorCode: String) : ClipboardReadResult
}

data class ClipboardChange(
    val text: String,
    val contentHash: String,
    val observedAtEpochMillis: Long,
    val imageBytes: ByteArray? = null,
    val imageMimeType: String? = null,
    /**
     * The source app flagged this clip as sensitive on its ClipDescription (password
     * managers do). Carried so the capture policy can honour 跳过敏感内容; honest limit:
     * the flag only exists when the source app sets it.
     */
    val isSensitive: Boolean = false,
) {
    val isImage: Boolean get() = imageBytes != null
}

sealed interface ClipboardWriteResult {
    data object Success : ClipboardWriteResult

    data class Failure(val errorCode: String) : ClipboardWriteResult
}

enum class ClipboardWriterKind {
    PUBLIC_API,
    PRIVILEGED_FALLBACK,
}

data class ClipboardWriteOutcome(
    val result: ClipboardWriteResult,
    val writerKind: ClipboardWriterKind?,
)

/** Why the active read route sits below the requested one. */
enum class ReadRouteShortfallCause {
    /** The requested route did not probe READY when the coordinator (re)selected a backend. */
    PREFERRED_NOT_READY,

    /** A health check found the running backend FAILED and the ladder fell to a lower rung. */
    HEALTH_FALLBACK,
}

/**
 * The active route is lower on the capability ladder than [ClipboardAccessState.requestedReadMode].
 * Carried in the access state so the conduit can say which route actually runs, since when, and
 * why — the fact the 特权直读 channel-stuck bug hid for weeks. Null when the active route is the
 * requested one or nothing runs.
 */
data class ReadRouteShortfall(
    val cause: ReadRouteShortfallCause,
    /** The route the ladder fell from ([ReadRouteShortfallCause.HEALTH_FALLBACK]) or the requested one. */
    val fromMode: ClipboardReadMode,
    /** Stable error code of the failing health check or the failed probe; null when the probe gave none. */
    val errorCode: String?,
    val sinceEpochMillis: Long,
)

data class ClipboardAccessState(
    val requestedReadMode: ClipboardReadMode,
    val activeReadMode: ClipboardReadMode?,
    val autoFallbackAllowed: Boolean,
    val lastErrorCode: String?,
    val lastHealthAtEpochMillis: Long?,
    val shortfall: ReadRouteShortfall? = null,
    /** When the coordinator last climbed back to a higher rung; null until a recovery happened. */
    val lastRecoveryAtEpochMillis: Long? = null,
) {
    /** True while a backend runs below the requested rung — the state recovery probes try to leave. */
    val belowRequested: Boolean
        get() = shortfall != null
}

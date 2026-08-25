package com.clipsync.android.platform.clipboard

enum class ClipboardReadMode {
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

    /** Blocked until the user grants or restores something (Shizuku, overlay, READ_LOGS). */
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

data class ClipboardAccessState(
    val requestedReadMode: ClipboardReadMode,
    val activeReadMode: ClipboardReadMode?,
    val autoFallbackAllowed: Boolean,
    val lastErrorCode: String?,
    val lastHealthAtEpochMillis: Long?,
)

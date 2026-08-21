package com.clipsync.android.sync

/**
 * Tuning knobs for one dialer session. Defaults follow protocol v1 and the
 * Stage 4 contract (30s application ping, 3 missed pongs, 15s handshake).
 */
data class SyncSessionOptions(
    val clientVersion: String = DEFAULT_CLIENT_VERSION,
    val platform: String = PLATFORM_ANDROID,
    val handshakeTimeoutMs: Long = 15_000,
    val pingIntervalMs: Long = 30_000,
    val maxMissedPings: Int = 3,
    val outboxDrainIntervalMs: Long = 2_000,
    val wantSequencesPerOrigin: Long = 1_024,
    val maxRequestedSequencesPerMessage: Long = 16_384,
    val nowMs: () -> Long = { System.currentTimeMillis() },
    val delayMs: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
    val protocolVersion: Int = com.clipsync.android.protocol.ProtocolLimits.PROTOCOL_VERSION,
) {
    val imageClipEnabled: Boolean
        get() = protocolVersion >= com.clipsync.android.protocol.ProtocolLimits.PROTOCOL_VERSION_V2
    companion object {
        const val DEFAULT_CLIENT_VERSION = "0.1.0"
        const val PLATFORM_ANDROID = "android"
        const val MAX_BACKOFF_MS = 300_000L
    }
}

/** Why the session ended. [errorCode] is a protocol code when one applies. */
data class SyncSessionResult(
    val authenticated: Boolean,
    val errorCode: String?,
    val detail: String,
)

/** A remote clip body that committed locally during this session. */
data class RemoteClipApplied(
    val eventId: String,
    val originDeviceId: String,
    val originSeq: Long,
    val content: String,
    val createdAtMs: Long,
    val kind: String = "text",
    val contentHash: String? = null,
    val mimeType: String? = null,
) {
    val isImage: Boolean get() = kind == "image"
}

/** Safe session diagnostics. Callers must never pass content, nonce, proof, or secret. */
fun interface SyncLogger {
    fun event(name: String, detail: String)

    companion object {
        val NoOp: SyncLogger = SyncLogger { _, _ -> }
    }
}

enum class SyncStatus {
    STOPPED,
    IDLE_UNPAIRED,
    CONNECTING,
    AUTHENTICATING,
    READY,
    BACKING_OFF,
    CERTIFICATE_MISMATCH,
    FAILED,
}

data class SyncControllerState(
    val status: SyncStatus,
    val peerDeviceId: String? = null,
    val lastErrorCode: String? = null,
    val lastDetail: String? = null,
    val nextRetryAtMs: Long? = null,
    val authenticated: Boolean = false,
)

sealed interface SyncConnectResult {
    data class Connected(
        val transport: ISyncTransport,
        val release: () -> Unit = {},
    ) : SyncConnectResult

    data class CertificateMismatch(val host: String) : SyncConnectResult

    data class Unreachable(val attemptedHosts: List<String>) : SyncConnectResult
}

fun interface SyncConnector {
    suspend fun connect(hosts: List<String>, port: Int, certSha256: String): SyncConnectResult
}

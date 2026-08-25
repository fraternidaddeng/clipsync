package com.clipsync.android.pairing

import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request

/**
 * The peer's self-reported clipboard apply posture from `/v1/peer/health`
 * (`clipboard_apply_text`). Posture states (OFF/PAUSED) are the peer user's choices;
 * the rest is the peer's evidence from its most recent real clipboard write.
 */
enum class PeerClipboardApply {
    /** 自动写入 is off on the peer: inbound text lands in its history only. */
    OFF,

    /** The peer paused sync: it still stores to history but never auto-applies. */
    PAUSED,

    /** Auto-apply is on but the peer has not applied any remote text yet this session. */
    UNVERIFIED,

    /** The peer's most recent remote text apply reached its system clipboard. */
    APPLIED,

    /** The peer's most recent remote text apply failed; content stayed in its history. */
    FAILED,
}

/** Result of one reachability probe against the paired Windows peer. */
sealed interface PeerHealthOutcome {
    data class Reachable(
        val viaHost: String,
        /**
         * Null when the peer did not report (older build, unreadable body, or an unknown
         * future token). Absence is "not reported", never bad news.
         */
        val clipboardApplyText: PeerClipboardApply? = null,
    ) : PeerHealthOutcome

    /**
     * A host answered TLS with a certificate that does not match the pinned fingerprint.
     * This must surface loudly (charter: red is reserved for errors like certificate change)
     * and blocks the probe; the pin is only replaced by a fresh, user-confirmed pairing.
     */
    data object CertificateMismatch : PeerHealthOutcome

    data object Unreachable : PeerHealthOutcome
}

interface PeerHealthApi {
    suspend fun probe(peer: PairedPeer): PeerHealthOutcome
}

/**
 * Calls `GET /v1/peer/health` over the pinned TLS identity saved at pairing time. Any HTTP
 * answer from the pinned certificate counts as reachable — the TLS handshake already proved
 * the peer's identity. Hosts are tried in saved order.
 */
class PeerHealthClient(
    private val connectTimeoutMs: Long = 3_000,
    private val readTimeoutMs: Long = 5_000,
    private val ioContext: CoroutineContext = Dispatchers.IO,
) : PeerHealthApi {
    override suspend fun probe(peer: PairedPeer): PeerHealthOutcome = withContext(ioContext) {
        for (host in peer.hosts) {
            val client = PinnedTls.client(peer.certSha256, connectTimeoutMs, readTimeoutMs)
            val request = Request.Builder()
                .url("https://$host:${peer.port}/v1/peer/health")
                .header("X-Protocol-Version", "1")
                .get()
                .build()
            try {
                client.newCall(request).execute().use { response ->
                    // Any HTTP answer from the pinned certificate proves reachability; the
                    // body is a bonus. A read failure must not turn good news into bad.
                    val body = runCatching { response.body?.string() }.getOrNull()
                    return@withContext PeerHealthOutcome.Reachable(
                        viaHost = host,
                        clipboardApplyText = parseClipboardApply(body),
                    )
                }
            } catch (exception: IOException) {
                if (PinnedTls.isPinRejection(exception)) {
                    return@withContext PeerHealthOutcome.CertificateMismatch
                }
                // Connectivity failures fall through to the next saved host.
            } finally {
                PinnedTls.shutdown(client)
            }
        }
        PeerHealthOutcome.Unreachable
    }

    companion object {
        /**
         * Tolerant read of the health payload's `clipboard_apply_text`: a missing field,
         * malformed body, or a token from a newer peer all map to null ("not reported") —
         * the conduit must state the absence honestly rather than guess.
         */
        fun parseClipboardApply(body: String?): PeerClipboardApply? {
            val token =
                body?.takeUnless { it.isBlank() }?.let { payload ->
                    runCatching {
                        Json
                            .parseToJsonElement(payload)
                            .jsonObject["clipboard_apply_text"]
                            ?.jsonPrimitive
                            ?.contentOrNull
                    }.getOrNull()
                }
            return when (token) {
                "off" -> PeerClipboardApply.OFF
                "paused" -> PeerClipboardApply.PAUSED
                "unverified" -> PeerClipboardApply.UNVERIFIED
                "applied" -> PeerClipboardApply.APPLIED
                "failed" -> PeerClipboardApply.FAILED
                else -> null
            }
        }
    }
}

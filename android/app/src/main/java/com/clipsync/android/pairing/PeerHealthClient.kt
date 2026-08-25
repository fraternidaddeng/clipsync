package com.clipsync.android.pairing

import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/** Result of one reachability probe against the paired Windows peer. */
sealed interface PeerHealthOutcome {
    data class Reachable(val viaHost: String) : PeerHealthOutcome

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
                client.newCall(request).execute().use {
                    return@withContext PeerHealthOutcome.Reachable(host)
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
}

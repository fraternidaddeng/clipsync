package com.clipsync.android.sync

import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/** One frame as the session engine sees it, mirroring the Windows TransportFrame shapes. */
sealed interface TransportFrame {
    data class Text(val payload: String) : TransportFrame

    data object Binary : TransportFrame

    data object TooLarge : TransportFrame

    data object Closed : TransportFrame
}

/** The connected socket as the engine drives it; faked in unit tests. */
interface SyncTransport {
    /** Suspends for the next frame; returns [TransportFrame.Closed] once the peer is gone. */
    suspend fun receive(): TransportFrame

    /** Sends one text frame; throws [IOException] when the socket cannot accept it. */
    suspend fun send(text: String)

    /** Starts a graceful WebSocket close. */
    suspend fun close(code: Int, reason: String)

    /** Releases the socket immediately; safe to call more than once. */
    fun dispose()
}

/** Dials one WebSocket to a candidate host; implementations pin the peer certificate. */
fun interface SyncConnector {
    /**
     * Connects with the given wire version (1 -> `/v1/peer/sync`, 2 -> `/v2/peer/sync`) or
     * throws [IOException]; [PinMismatchException] means a wrong certificate.
     */
    suspend fun connect(host: String, port: Int, certSha256: String, protocolVersion: Int): SyncTransport
}

/** The presented TLS certificate did not match the pinned pairing fingerprint. */
class PinMismatchException(host: String) : IOException("certificate pin mismatch for $host")

/**
 * OkHttp WebSocket dial side of the peer connection, mirroring the Windows PeerSyncClient:
 * connects to wss://host:port/v{n}/peer/sync with the matching protocol version header over
 * TLS that trusts exactly one certificate fingerprint. Chain and hostname are ignored by
 * design; the pin from pairing is the whole trust decision.
 */
class OkHttpSyncConnector(
    private val connectTimeoutMs: Long = 6_000,
) : SyncConnector {
    override suspend fun connect(host: String, port: Int, certSha256: String, protocolVersion: Int): SyncTransport {
        require(protocolVersion == 1 || protocolVersion == 2) { "Unknown protocol version." }
        val trustManager = PinnedTrustManager(certSha256)
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf(trustManager), SecureRandom())
        val client = OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            // The pin is the whole identity; hostnames are meaningless for LAN IPs.
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
            // The session runs its own protocol-level pings, so reads may be idle for long.
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .build()
        val request = Request.Builder()
            .url("https://$host:$port/v$protocolVersion/peer/sync")
            .header("X-Protocol-Version", protocolVersion.toString())
            .build()

        val transport = OkHttpSyncTransport(client)
        try {
            transport.awaitOpen(request)
        } catch (failure: Throwable) {
            transport.dispose()
            throw when {
                trustManager.sawPinMismatch -> PinMismatchException(host)
                failure is IOException -> failure
                else -> IOException("websocket connect failed", failure)
            }
        }
        return transport
    }

    private class PinnedTrustManager(pin: String) : X509TrustManager {
        private val expected = pin.lowercase()

        @Volatile
        var sawPinMismatch = false
            private set

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String): Unit =
            throw CertificateException("client certificates are not used")

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            val leaf = chain.firstOrNull()
            val fingerprint = leaf?.let {
                MessageDigest.getInstance("SHA-256").digest(it.encoded)
                    .joinToString(separator = "") { byte -> "%02x".format(byte) }
            }
            if (fingerprint != expected) {
                sawPinMismatch = true
                throw CertificateException("certificate does not match the pinned fingerprint")
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}

/**
 * Frame-size and buffering discipline for the inbound direction of one socket, extracted so
 * the rules are unit-testable without OkHttp:
 *
 * - a text message whose UTF-8 form exceeds the protocol frame limit maps to
 *   [TransportFrame.TooLarge] (measured without allocating the encoding);
 * - accepted-but-not-yet-consumed text is accounted in UTF-16 units, and once the backlog
 *   passes [maxBufferedChars] the verdict is [Verdict.OVERFLOW]: the caller must kill the
 *   socket, because a peer outrunning the engine that far is misbehaving and the buffer
 *   must stay bounded.
 *
 * OkHttp assembles each WebSocket message fully before `onMessage` fires, so a single frame
 * cannot be rejected mid-read here; that exposure is bounded to one message and only the
 * pin-verified paired peer can speak on the socket at all (TLS pin fails anyone else during
 * the handshake, before WebSocket data flows).
 */
internal class InboundFrameGate(
    private val maxMessageBytes: Int = SyncLimits.MAX_WEBSOCKET_TEXT_MESSAGE_BYTES,
    private val maxBufferedChars: Long = MAX_BUFFERED_INBOUND_CHARS,
) {
    enum class Verdict { ACCEPT, TOO_LARGE, OVERFLOW }

    private val bufferedChars = java.util.concurrent.atomic.AtomicLong(0)

    fun onText(text: String): Verdict {
        if (SyncLimits.utf8BytesExceed(text, maxMessageBytes)) {
            return Verdict.TOO_LARGE
        }
        if (bufferedChars.addAndGet(text.length.toLong()) > maxBufferedChars) {
            // The rejected frame is never queued, so its cost is rolled back.
            bufferedChars.addAndGet(-text.length.toLong())
            return Verdict.OVERFLOW
        }
        return Verdict.ACCEPT
    }

    /** Call when a previously accepted text frame leaves the buffer. */
    fun onConsumed(text: String) {
        bufferedChars.addAndGet(-text.length.toLong())
    }

    companion object {
        /** A few frame-limits' worth of backlog; far more than a healthy engine ever queues. */
        const val MAX_BUFFERED_INBOUND_CHARS = 4L * SyncLimits.MAX_WEBSOCKET_TEXT_MESSAGE_BYTES
    }
}

/**
 * Bridges OkHttp's callback WebSocket into the suspend [SyncTransport] shape. Frames land in
 * a channel whose queued text is byte-bounded by [InboundFrameGate]; a peer that overruns the
 * bound is disconnected instead of growing the queue.
 */
internal class OkHttpSyncTransport(private val client: OkHttpClient) : SyncTransport {
    private val frames = Channel<TransportFrame>(Channel.UNLIMITED)
    private val opened = CompletableDeferred<Unit>()
    private val inboundGate = InboundFrameGate()

    @Volatile
    private var socket: WebSocket? = null

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            opened.complete(Unit)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            when (inboundGate.onText(text)) {
                InboundFrameGate.Verdict.ACCEPT -> frames.trySend(TransportFrame.Text(text))
                InboundFrameGate.Verdict.TOO_LARGE -> frames.trySend(TransportFrame.TooLarge)
                InboundFrameGate.Verdict.OVERFLOW -> {
                    // The engine sees TooLarge and reports PAYLOAD_TOO_LARGE; the socket is
                    // cancelled right away so the flood stops at the TCP layer.
                    frames.trySend(TransportFrame.TooLarge)
                    webSocket.cancel()
                }
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            frames.trySend(TransportFrame.Binary)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            // Complete the close handshake, then surface the closure to the engine.
            webSocket.close(code, null)
            frames.trySend(TransportFrame.Closed)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            frames.trySend(TransportFrame.Closed)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            opened.completeExceptionally(t)
            frames.trySend(TransportFrame.Closed)
        }
    }

    suspend fun awaitOpen(request: Request) {
        socket = client.newWebSocket(request, listener)
        opened.await()
    }

    override suspend fun receive(): TransportFrame {
        val frame = frames.receive()
        if (frame is TransportFrame.Text) {
            inboundGate.onConsumed(frame.payload)
        }
        return frame
    }

    override suspend fun send(text: String) {
        val accepted = socket?.send(text) ?: false
        if (!accepted) {
            throw IOException("websocket rejected the outgoing frame")
        }
    }

    override suspend fun close(code: Int, reason: String) {
        socket?.close(code, reason.take(120))
    }

    override fun dispose() {
        socket?.cancel()
        frames.trySend(TransportFrame.Closed)
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}

/** WebSocket close codes the engine uses; the wire protocol carries its own error frames. */
object SyncCloseCodes {
    const val NORMAL = 1000
    const val POLICY_VIOLATION = 1008
}

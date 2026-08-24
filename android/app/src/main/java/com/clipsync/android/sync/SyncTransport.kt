package com.clipsync.android.sync

import java.io.IOException
import java.nio.charset.StandardCharsets
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
    /** Connects or throws [IOException]; [PinMismatchException] means a wrong certificate. */
    suspend fun connect(host: String, port: Int, certSha256: String): SyncTransport
}

/** The presented TLS certificate did not match the pinned pairing fingerprint. */
class PinMismatchException(host: String) : IOException("certificate pin mismatch for $host")

/**
 * OkHttp WebSocket dial side of the peer connection, mirroring the Windows PeerSyncClient:
 * connects to wss://host:port/v1/peer/sync with the protocol version header over TLS that
 * trusts exactly one certificate fingerprint. Chain and hostname are ignored by design; the
 * pin from pairing is the whole trust decision.
 */
class OkHttpSyncConnector(
    private val connectTimeoutMs: Long = 6_000,
) : SyncConnector {
    override suspend fun connect(host: String, port: Int, certSha256: String): SyncTransport {
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
            .url("https://$host:$port/v1/peer/sync")
            .header("X-Protocol-Version", "1")
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
 * Bridges OkHttp's callback WebSocket into the suspend [SyncTransport] shape: frames land in
 * an unbounded channel (the engine consumes promptly and message sizes are protocol-capped).
 */
internal class OkHttpSyncTransport(private val client: OkHttpClient) : SyncTransport {
    private val frames = Channel<TransportFrame>(Channel.UNLIMITED)
    private val opened = CompletableDeferred<Unit>()

    @Volatile
    private var socket: WebSocket? = null

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            opened.complete(Unit)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val oversized = text.length > SyncLimits.MAX_WEBSOCKET_TEXT_MESSAGE_BYTES ||
                text.toByteArray(StandardCharsets.UTF_8).size > SyncLimits.MAX_WEBSOCKET_TEXT_MESSAGE_BYTES
            frames.trySend(if (oversized) TransportFrame.TooLarge else TransportFrame.Text(text))
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

    override suspend fun receive(): TransportFrame = frames.receive()

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

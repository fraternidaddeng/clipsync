package com.clipsync.android.sync

import com.clipsync.android.protocol.ProtocolLimits
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/**
 * Dial side of the peer connection: WebSocket `/v1/peer/sync` with
 * `X-Protocol-Version: 1`, trusting exactly the pairing certificate pin.
 * Chain and hostname are ignored; a pin mismatch blocks the whole attempt
 * (same policy as [com.clipsync.android.pairing.PairingConfirmClient]).
 */
class OkHttpSyncConnector(
    private val connectTimeoutMs: Long = 6_000,
    private val ioContext: CoroutineContext = Dispatchers.IO,
    private val protocolVersion: Int = ProtocolLimits.PROTOCOL_VERSION,
) : SyncConnector {
    override suspend fun connect(
        hosts: List<String>,
        port: Int,
        certSha256: String,
    ): SyncConnectResult = withContext(ioContext) {
        val attempted = mutableListOf<String>()
        for (host in hosts) {
            attempted += host
            when (val outcome = connectHost(host, port, certSha256)) {
                is HostOutcome.Opened ->
                    return@withContext SyncConnectResult.Connected(outcome.transport, outcome.release)
                is HostOutcome.PinRejected ->
                    return@withContext SyncConnectResult.CertificateMismatch(host)
                is HostOutcome.NotReachable -> continue
            }
        }
        SyncConnectResult.Unreachable(attempted)
    }

    private suspend fun connectHost(host: String, port: Int, pin: String): HostOutcome {
        val client = pinnedClient(pin)
        val incoming = Channel<TransportFrame>(Channel.UNLIMITED)
        val opened = CompletableDeferred<WebSocket>()
        val failed = CompletableDeferred<Throwable>()
        val path = if (protocolVersion == ProtocolLimits.PROTOCOL_VERSION_V2) {
            "/v2/peer/sync"
        } else {
            "/v1/peer/sync"
        }
        val request = Request.Builder()
            .url("wss://$host:$port$path")
            .header("X-Protocol-Version", protocolVersion.toString())
            .build()
        val webSocket = client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    opened.complete(webSocket)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val bytes = text.toByteArray(Charsets.UTF_8)
                    incoming.trySend(
                        if (bytes.size > ProtocolLimits.MAX_WEBSOCKET_TEXT_MESSAGE_BYTES) {
                            TransportFrame.TooLarge
                        } else {
                            TransportFrame.Text(text)
                        },
                    )
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    incoming.trySend(TransportFrame.Binary)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    incoming.trySend(TransportFrame.Closed)
                    webSocket.close(1000, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    incoming.trySend(TransportFrame.Closed)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (!opened.isCompleted) {
                        failed.complete(t)
                    }
                    incoming.trySend(TransportFrame.Closed)
                }
            },
        )
        return try {
            val socket = withTimeout(connectTimeoutMs) {
                val winner = kotlinx.coroutines.selects.select<Any> {
                    opened.onAwait { it }
                    failed.onAwait { it }
                }
                if (winner is Throwable) {
                    throw winner
                }
                winner as WebSocket
            }
            HostOutcome.Opened(
                OkHttpSyncTransport(socket, incoming),
                release = { shutdownClient(client) },
            )
        } catch (exception: Exception) {
            webSocket.cancel()
            shutdownClient(client)
            when {
                isPinRejection(exception) || isTlsFailure(exception) -> HostOutcome.PinRejected
                isConnectivityFailure(exception) -> HostOutcome.NotReachable
                else -> HostOutcome.NotReachable
            }
        }
    }

    private fun pinnedClient(pin: String): OkHttpClient {
        val trustManager = PinnedTrustManager(pin)
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf(trustManager), SecureRandom())
        return OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    private fun shutdownClient(client: OkHttpClient) {
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    private fun isPinRejection(exception: Throwable): Boolean {
        var current: Throwable? = exception
        while (current != null) {
            if (current is PinMismatchException) {
                return true
            }
            current = current.cause
        }
        return false
    }

    private fun isTlsFailure(exception: Throwable): Boolean {
        var current: Throwable? = exception
        while (current != null) {
            if (current is SSLException || current is CertificateException) {
                return true
            }
            current = current.cause
        }
        return false
    }

    private fun isConnectivityFailure(exception: Throwable): Boolean {
        val io = exception as? IOException ?: exception.cause as? IOException ?: return false
        return when (io) {
            is ConnectException, is UnknownHostException, is NoRouteToHostException -> true
            is SocketTimeoutException -> io.message?.contains("connect", ignoreCase = true) == true
            is SSLException -> false
            is SocketException -> true
            else -> false
        }
    }

    private sealed interface HostOutcome {
        data class Opened(val transport: ISyncTransport, val release: () -> Unit) : HostOutcome
        data object PinRejected : HostOutcome
        data object NotReachable : HostOutcome
    }

    private class PinnedTrustManager(pin: String) : X509TrustManager {
        private val expected = pin.lowercase()

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
            throw CertificateException("client certificates are not used")

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            val leaf = chain.firstOrNull() ?: throw PinMismatchException()
            val digest = MessageDigest.getInstance("SHA-256").digest(leaf.encoded)
            val fingerprint = digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
            if (fingerprint != expected) {
                throw PinMismatchException()
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

}

/** Dedicated pin-failure type so TLS wrappers cannot hide a certificate mismatch. */
class PinMismatchException : CertificateException("certificate pin mismatch")

internal class OkHttpSyncTransport(
    private val webSocket: WebSocket,
    private val incoming: Channel<TransportFrame>,
) : ISyncTransport {
    override suspend fun receive(): TransportFrame =
        incoming.receiveCatching().getOrNull() ?: TransportFrame.Closed

    override suspend fun sendText(payload: String) {
        if (!webSocket.send(payload)) {
            incoming.trySend(TransportFrame.Closed)
        }
    }

    override suspend fun close(reason: String) {
        try {
            webSocket.cancel()
        } catch (_: Exception) {
        }
        incoming.trySend(TransportFrame.Closed)
        incoming.close()
    }
}

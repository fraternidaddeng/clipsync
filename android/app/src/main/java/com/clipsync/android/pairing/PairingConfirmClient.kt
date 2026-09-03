package com.clipsync.android.pairing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.EventListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.SocketTimeoutException
import java.nio.charset.CodingErrorAction
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLException
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resumeWithException

/** One QR host that refused a TCP/TLS connection, and why. */
data class HostAttempt(
    val host: String,
    val reason: UnreachableReason,
)

/** Stable outcome of one confirm attempt; never carries the raw payload or token. */
sealed interface PairingConfirmOutcome {
    data class Approved(
        val response: PairingConfirmResponse,
        val viaHost: String,
    ) : PairingConfirmOutcome

    /** The listener answered with a pairing_error document. */
    data class Denied(
        val errorCode: String,
    ) : PairingConfirmOutcome

    /**
     * A host presented a certificate that does not match the QR fingerprint. This aborts the
     * whole attempt instead of trying other hosts: a changed certificate must block, never be
     * silently accepted (plan stage 3).
     */
    data class CertificateMismatch(
        val host: String,
    ) : PairingConfirmOutcome

    /** No candidate host accepted a TCP/TLS connection; [attempts] keeps QR order. */
    data class Unreachable(
        val attempts: List<HostAttempt>,
    ) : PairingConfirmOutcome {
        val attemptedHosts: List<String> get() = attempts.map { it.host }
    }

    /**
     * TCP reached [host] but no confirm answer came back: the read timed out with the request
     * on the wire, or TLS broke for a reason other than the pin. The token may be spent, so
     * this is terminal — and it is a stalled network, not a version mismatch.
     */
    data class NoResponse(
        val host: String,
        val kind: NoResponseKind,
    ) : PairingConfirmOutcome

    /** The listener answered outside the frozen contract. */
    data class ProtocolViolation(
        val detail: String,
    ) : PairingConfirmOutcome
}

enum class NoResponseKind {
    /** Connected and sent, then silence for the whole read timeout. */
    READ_TIMEOUT,

    /** The TLS handshake or record layer failed without the pin being the cause. */
    TLS_FAILURE,
}

/** The confirm exchange as the ViewModel sees it; faked in unit tests. */
interface PairingConfirmApi {
    /**
     * [onPhase] is told when the attempt starts dialing and again once a host has the request
     * on the wire ([PairingPhase.AWAITING_APPROVAL]); it may be called from an IO thread.
     */
    suspend fun confirm(
        qr: PairingQrPayload,
        request: PairingConfirmRequest,
        onPhase: (PairingPhase) -> Unit = {},
    ): PairingConfirmOutcome
}

/**
 * Calls `POST /v1/pair/confirm` over TLS that trusts exactly one certificate: the one whose
 * lowercase SHA-256 fingerprint came out of the QR code. Chain and hostname are ignored by
 * design; the pin is the identity. Hosts are tried in QR order until one connects.
 */
class PairingConfirmClient(
    private val connectTimeoutMs: Long = 6_000,
    private val readTimeoutMs: Long = 100_000,
    private val ioContext: CoroutineContext = Dispatchers.IO,
    private val classifyUnreachable: (IOException) -> UnreachableReason = AndroidConnectFailures::classify,
) : PairingConfirmApi {
    override suspend fun confirm(
        qr: PairingQrPayload,
        request: PairingConfirmRequest,
        onPhase: (PairingPhase) -> Unit,
    ): PairingConfirmOutcome =
        withContext(ioContext) {
            val body = PairingJson.serialize(request)
            val attempts = mutableListOf<HostAttempt>()
            for (host in qr.hosts) {
                onPhase(PairingPhase.CONNECTING)
                when (val outcome = confirmViaHost(host, qr.port, qr.certSha256, body, onPhase)) {
                    is HostOutcome.Answered -> return@withContext outcome.outcome
                    is HostOutcome.PinRejected -> return@withContext PairingConfirmOutcome.CertificateMismatch(host)
                    is HostOutcome.NotReachable -> attempts += HostAttempt(host, outcome.reason)
                }
            }
            PairingConfirmOutcome.Unreachable(attempts)
        }

    private sealed interface HostOutcome {
        data class Answered(
            val outcome: PairingConfirmOutcome,
        ) : HostOutcome

        data object PinRejected : HostOutcome

        data class NotReachable(
            val reason: UnreachableReason,
        ) : HostOutcome
    }

    private suspend fun confirmViaHost(
        host: String,
        port: Int,
        pin: String,
        body: String,
        onPhase: (PairingPhase) -> Unit,
    ): HostOutcome {
        val client = PinnedTls.client(pin, connectTimeoutMs, readTimeoutMs, ConnectedOnceListener(onPhase))
        val request =
            Request
                .Builder()
                .url("https://$host:$port/v1/pair/confirm")
                .header("X-Protocol-Version", "1")
                .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
        return try {
            client.newCall(request).await().use { response ->
                HostOutcome.Answered(mapResponse(host, response.code, readBounded(response)))
            }
        } catch (exception: IOException) {
            classifyTransport(host, exception)
        } finally {
            PinnedTls.shutdown(client)
        }
    }

    /**
     * Connect-phase failures roll over to the next host; everything after the socket is up is
     * terminal, because the request may already have spent the one-time token. A read timeout
     * or a non-pin TLS failure is a stalled transport, not a peer speaking another protocol.
     */
    private fun classifyTransport(
        host: String,
        exception: IOException,
    ): HostOutcome =
        when {
            PinnedTls.isPinRejection(exception) -> HostOutcome.PinRejected
            PinnedTls.isConnectivityFailure(exception) -> HostOutcome.NotReachable(classifyUnreachable(exception))
            exception is SocketTimeoutException ->
                HostOutcome.Answered(PairingConfirmOutcome.NoResponse(host, NoResponseKind.READ_TIMEOUT))
            exception is SSLException ->
                HostOutcome.Answered(PairingConfirmOutcome.NoResponse(host, NoResponseKind.TLS_FAILURE))
            else ->
                HostOutcome.Answered(
                    PairingConfirmOutcome.ProtocolViolation("transport failed: ${exception.javaClass.simpleName}"),
                )
        }

    /**
     * Runs the call on OkHttp's dispatcher and ties it to the coroutine: cancelling the
     * submission cancels the socket too, instead of leaving a thread parked in a 100 s read.
     */
    private suspend fun Call.await(): okhttp3.Response =
        suspendCancellableCoroutine { continuation ->
            enqueue(
                object : Callback {
                    override fun onResponse(
                        call: Call,
                        response: okhttp3.Response,
                    ) {
                        continuation.resume(response) { _, _, _ -> response.close() }
                    }

                    override fun onFailure(
                        call: Call,
                        e: IOException,
                    ) {
                        if (!continuation.isCancelled) {
                            continuation.resumeWithException(e)
                        }
                    }
                },
            )
            continuation.invokeOnCancellation { cancel() }
        }

    /**
     * Reports [PairingPhase.AWAITING_APPROVAL] once the socket (TLS included, so the pin has
     * already been verified) is up: from here on the PC has the request and a person decides.
     */
    private class ConnectedOnceListener(
        private val onPhase: (PairingPhase) -> Unit,
    ) : EventListener() {
        private val reported = AtomicBoolean(false)

        override fun connectEnd(
            call: Call,
            inetSocketAddress: InetSocketAddress,
            proxy: Proxy,
            protocol: Protocol?,
        ) {
            if (reported.compareAndSet(false, true)) {
                onPhase(PairingPhase.AWAITING_APPROVAL)
            }
        }
    }

    private fun mapResponse(
        host: String,
        status: Int,
        bodyText: String?,
    ): PairingConfirmOutcome {
        if (bodyText == null) {
            return PairingConfirmOutcome.ProtocolViolation("response exceeded the document limit or was not UTF-8")
        }
        return when (status) {
            HTTP_OK ->
                try {
                    PairingConfirmOutcome.Approved(PairingJson.parseConfirmResponse(bodyText), viaHost = host)
                } catch (_: SerializationException) {
                    PairingConfirmOutcome.ProtocolViolation("confirm response failed strict validation")
                }
            HTTP_BAD_REQUEST, HTTP_FORBIDDEN, HTTP_GONE ->
                try {
                    PairingConfirmOutcome.Denied(PairingJson.parseError(bodyText).error)
                } catch (_: SerializationException) {
                    PairingConfirmOutcome.ProtocolViolation("error body failed strict validation")
                }
            // 429 is unambiguous on its own: a listener that throttles without a readable
            // error document is still telling the phone to slow down, not speaking another protocol.
            HTTP_TOO_MANY_REQUESTS ->
                try {
                    PairingConfirmOutcome.Denied(PairingJson.parseError(bodyText).error)
                } catch (_: SerializationException) {
                    PairingConfirmOutcome.Denied(PairingErrorCodes.RATE_LIMITED)
                }
            else -> PairingConfirmOutcome.ProtocolViolation("unexpected HTTP status $status")
        }
    }

    /** Reads at most the pairing document limit; longer or non-UTF-8 bodies become null. */
    private fun readBounded(response: okhttp3.Response): String? {
        val source = response.body?.byteStream() ?: return ""
        val limit = PairingJson.MAX_DOCUMENT_BYTES + 1
        val buffer = ByteArray(limit)
        var total = 0
        while (total < limit) {
            val read = source.read(buffer, total, limit - total)
            if (read < 0) {
                break
            }
            total += read
        }
        if (total > PairingJson.MAX_DOCUMENT_BYTES) {
            return null
        }
        val decoder =
            Charsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
        return runCatching { decoder.decode(java.nio.ByteBuffer.wrap(buffer, 0, total)).toString() }.getOrNull()
    }

    private companion object {
        const val HTTP_OK = 200
        const val HTTP_BAD_REQUEST = 400
        const val HTTP_FORBIDDEN = 403
        const val HTTP_GONE = 410
        const val HTTP_TOO_MANY_REQUESTS = 429
    }
}

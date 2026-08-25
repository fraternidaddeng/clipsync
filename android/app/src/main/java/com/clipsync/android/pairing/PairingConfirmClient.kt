package com.clipsync.android.pairing

import java.io.IOException
import java.nio.charset.CodingErrorAction
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Stable outcome of one confirm attempt; never carries the raw payload or token. */
sealed interface PairingConfirmOutcome {
    data class Approved(val response: PairingConfirmResponse, val viaHost: String) : PairingConfirmOutcome

    /** The listener answered with a pairing_error document. */
    data class Denied(val errorCode: String) : PairingConfirmOutcome

    /**
     * A host presented a certificate that does not match the QR fingerprint. This aborts the
     * whole attempt instead of trying other hosts: a changed certificate must block, never be
     * silently accepted (plan stage 3).
     */
    data class CertificateMismatch(val host: String) : PairingConfirmOutcome

    /** No candidate host accepted a TCP/TLS connection. */
    data class Unreachable(val attemptedHosts: List<String>) : PairingConfirmOutcome

    /** The listener answered outside the frozen contract. */
    data class ProtocolViolation(val detail: String) : PairingConfirmOutcome
}

/** The confirm exchange as the ViewModel sees it; faked in unit tests. */
interface PairingConfirmApi {
    suspend fun confirm(qr: PairingQrPayload, request: PairingConfirmRequest): PairingConfirmOutcome
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
) : PairingConfirmApi {
    override suspend fun confirm(
        qr: PairingQrPayload,
        request: PairingConfirmRequest,
    ): PairingConfirmOutcome = withContext(ioContext) {
        val body = PairingJson.serialize(request)
        val attempted = mutableListOf<String>()
        for (host in qr.hosts) {
            attempted += host
            when (val outcome = confirmViaHost(host, qr.port, qr.certSha256, body)) {
                is HostOutcome.Answered -> return@withContext outcome.outcome
                is HostOutcome.PinRejected -> return@withContext PairingConfirmOutcome.CertificateMismatch(host)
                is HostOutcome.NotReachable -> continue
            }
        }
        PairingConfirmOutcome.Unreachable(attempted)
    }

    private sealed interface HostOutcome {
        data class Answered(val outcome: PairingConfirmOutcome) : HostOutcome
        data object PinRejected : HostOutcome
        data object NotReachable : HostOutcome
    }

    private fun confirmViaHost(host: String, port: Int, pin: String, body: String): HostOutcome {
        val client = PinnedTls.client(pin, connectTimeoutMs, readTimeoutMs)
        val request = Request.Builder()
            .url("https://$host:$port/v1/pair/confirm")
            .header("X-Protocol-Version", "1")
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                HostOutcome.Answered(mapResponse(host, response.code, readBounded(response)))
            }
        } catch (exception: IOException) {
            when {
                PinnedTls.isPinRejection(exception) -> HostOutcome.PinRejected
                PinnedTls.isConnectivityFailure(exception) -> HostOutcome.NotReachable
                else -> HostOutcome.Answered(
                    PairingConfirmOutcome.ProtocolViolation("transport failed: ${exception.javaClass.simpleName}"),
                )
            }
        } finally {
            PinnedTls.shutdown(client)
        }
    }

    private fun mapResponse(host: String, status: Int, bodyText: String?): PairingConfirmOutcome {
        if (bodyText == null) {
            return PairingConfirmOutcome.ProtocolViolation("response exceeded the document limit or was not UTF-8")
        }
        return when (status) {
            200 -> try {
                PairingConfirmOutcome.Approved(PairingJson.parseConfirmResponse(bodyText), viaHost = host)
            } catch (_: SerializationException) {
                PairingConfirmOutcome.ProtocolViolation("confirm response failed strict validation")
            }
            400, 403, 410 -> try {
                PairingConfirmOutcome.Denied(PairingJson.parseError(bodyText).error)
            } catch (_: SerializationException) {
                PairingConfirmOutcome.ProtocolViolation("error body failed strict validation")
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
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return runCatching { decoder.decode(java.nio.ByteBuffer.wrap(buffer, 0, total)).toString() }.getOrNull()
    }

}

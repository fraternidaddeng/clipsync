package com.clipsync.android.pairing

import java.net.InetAddress
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Exercises the confirm client against a real TLS server. The pin from the QR payload is the
 * whole trust decision: a matching fingerprint talks, anything else blocks before HTTP.
 */
class PairingConfirmClientTest {
    private lateinit var server: MockWebServer
    private lateinit var certificate: HeldCertificate
    private lateinit var fingerprint: String

    @Before
    fun startServer() {
        certificate = HeldCertificate.Builder()
            .addSubjectAlternativeName("127.0.0.1")
            .build()
        val handshake = HandshakeCertificates.Builder()
            .heldCertificate(certificate)
            .build()
        server = MockWebServer()
        server.useHttps(handshake.sslSocketFactory(), false)
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        fingerprint = MessageDigest.getInstance("SHA-256")
            .digest(certificate.certificate.encoded)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    @After
    fun stopServer() {
        server.shutdown()
    }

    private fun qr(
        hosts: List<String> = listOf("127.0.0.1"),
        pin: String = fingerprint,
    ) = PairingQrPayload(
        kind = PairingDocumentKinds.QR,
        version = 1,
        hosts = hosts,
        port = server.port,
        deviceId = WINDOWS_ID,
        displayName = "DESKTOP-WIN",
        certSha256 = pin,
        token = TOKEN,
        expiresAtMs = 1_755_064_500_000,
    )

    private fun request() = PairingConfirmRequest(
        kind = PairingDocumentKinds.CONFIRM_REQUEST,
        version = 1,
        token = TOKEN,
        deviceId = ANDROID_ID,
        displayName = "Pixel 8",
        platform = "android",
    )

    private fun approvedBody(): String = PairingJson.serialize(
        PairingConfirmResponse(
            kind = PairingDocumentKinds.CONFIRM_RESPONSE,
            version = 1,
            deviceId = WINDOWS_ID,
            displayName = "DESKTOP-WIN",
            platform = "windows",
            pairSecret = SECRET,
            trustEpoch = 2,
        ),
    )

    private fun confirm(qr: PairingQrPayload, client: PairingConfirmClient = PairingConfirmClient()) =
        runBlocking { client.confirm(qr, request()) }

    @Test
    fun `matching pin completes the exchange and carries the protocol header`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(approvedBody()))

        val outcome = confirm(qr()) as PairingConfirmOutcome.Approved
        assertEquals(WINDOWS_ID, outcome.response.deviceId)
        assertEquals(2, outcome.response.trustEpoch)
        assertEquals("127.0.0.1", outcome.viaHost)

        val recorded = server.takeRequest()
        assertEquals("/v1/pair/confirm", recorded.path)
        assertEquals("1", recorded.getHeader("X-Protocol-Version"))
        // The request body is the strict confirm document, no secret material inside.
        val sent = PairingJson.parseConfirmRequest(recorded.body.readUtf8())
        assertEquals(ANDROID_ID, sent.deviceId)
    }

    @Test
    fun `wrong pin blocks before any request reaches the server`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(approvedBody()))

        val outcome = confirm(qr(pin = "ab".repeat(32)))
        assertTrue(outcome is PairingConfirmOutcome.CertificateMismatch)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `pairing errors map to denied with the contract code`() {
        server.enqueue(
            MockResponse().setResponseCode(403).setBody(
                PairingJson.serialize(
                    PairingErrorBody(
                        kind = PairingDocumentKinds.ERROR,
                        version = 1,
                        error = PairingErrorCodes.REJECTED,
                    ),
                ),
            ),
        )
        val outcome = confirm(qr()) as PairingConfirmOutcome.Denied
        assertEquals(PairingErrorCodes.REJECTED, outcome.errorCode)
    }

    @Test
    fun `http 429 maps to denied with PAIRING_RATE_LIMITED`() {
        server.enqueue(
            MockResponse().setResponseCode(429).setBody(
                PairingJson.serialize(
                    PairingErrorBody(
                        kind = PairingDocumentKinds.ERROR,
                        version = 1,
                        error = PairingErrorCodes.RATE_LIMITED,
                    ),
                ),
            ),
        )
        val pairingError = confirm(qr()) as PairingConfirmOutcome.Denied
        assertEquals(PairingErrorCodes.RATE_LIMITED, pairingError.errorCode)

        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":"RATE_LIMITED"}"""))
        val compact = confirm(qr()) as PairingConfirmOutcome.Denied
        assertEquals(PairingErrorCodes.RATE_LIMITED, compact.errorCode)
    }

    @Test
    fun `unknown status codes and malformed bodies are protocol violations`() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        assertTrue(confirm(qr()) is PairingConfirmOutcome.ProtocolViolation)

        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"kind\":\"pairing_confirm_response\""))
        assertTrue(confirm(qr()) is PairingConfirmOutcome.ProtocolViolation)

        // A body above the 8 KiB pairing document cap is refused without parsing.
        server.enqueue(MockResponse().setResponseCode(200).setBody("x".repeat(9 * 1024)))
        assertTrue(confirm(qr()) is PairingConfirmOutcome.ProtocolViolation)
    }

    @Test
    fun `unreachable hosts fail over in QR order until one answers`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(approvedBody()))

        // Nothing listens on this loopback address at the same port; connect is refused fast.
        val outcome = confirm(qr(hosts = listOf("127.0.0.99", "127.0.0.1")))
        val approved = outcome as PairingConfirmOutcome.Approved
        assertEquals("127.0.0.1", approved.viaHost)
    }

    @Test
    fun `when no host answers the outcome lists every attempt`() {
        val port = server.port
        server.shutdown()
        // Rebind guard: the QR still points at the now-dead port on two addresses.
        val dead = PairingQrPayload(
            kind = PairingDocumentKinds.QR,
            version = 1,
            hosts = listOf("127.0.0.99", "127.0.0.98"),
            port = port,
            deviceId = WINDOWS_ID,
            displayName = "DESKTOP-WIN",
            certSha256 = fingerprint,
            token = TOKEN,
            expiresAtMs = 1_755_064_500_000,
        )
        val outcome = confirm(dead) as PairingConfirmOutcome.Unreachable
        assertEquals(listOf("127.0.0.99", "127.0.0.98"), outcome.attemptedHosts)
    }

    @Test
    fun `a stalled approval wait never rolls over to another host`() {
        // The server accepts the request and then never answers, like an approval that
        // exceeds the client's patience. The token is already consumed, so trying the
        // next host would be wrong — the outcome must be terminal instead.
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.enqueue(MockResponse().setResponseCode(200).setBody(approvedBody()))

        val outcome = confirm(
            qr(hosts = listOf("127.0.0.1", "127.0.0.1")),
            client = PairingConfirmClient(readTimeoutMs = 400),
        )
        assertTrue(outcome is PairingConfirmOutcome.ProtocolViolation)
        assertEquals(1, server.requestCount)
    }

    private companion object {
        const val WINDOWS_ID = "11111111-1111-4111-8111-111111111111"
        const val ANDROID_ID = "22222222-2222-4222-8222-222222222222"
        const val TOKEN = "vJ8kAqhFRWDdiWvUuJ9lPCS0jBSJ73dP9-b1JzW5Qk4"
        const val SECRET = "gY7L0N6a-C0GZWx1Vb3f9YIhE2n4q8s_x5TdKJMuwao"
    }
}

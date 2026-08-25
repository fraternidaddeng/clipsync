package com.clipsync.android.pairing

import com.clipsync.android.sync.FakeProxyEnvironment
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.Proxy
import java.security.MessageDigest

/**
 * Proves pairing traffic (confirm exchange and health probe) dials the paired host directly
 * even when the process has a proxy configured; see [PinnedTls] for the rationale.
 */
class PairingProxyBypassTest {
    private lateinit var proxyEnvironment: FakeProxyEnvironment
    private lateinit var server: MockWebServer
    private lateinit var fingerprint: String

    @Before
    fun setUp() {
        proxyEnvironment = FakeProxyEnvironment()
        val certificate =
            HeldCertificate
                .Builder()
                .addSubjectAlternativeName("127.0.0.1")
                .build()
        val handshake =
            HandshakeCertificates
                .Builder()
                .heldCertificate(certificate)
                .build()
        server = MockWebServer()
        server.useHttps(handshake.sslSocketFactory(), false)
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        fingerprint =
            MessageDigest
                .getInstance("SHA-256")
                .digest(certificate.certificate.encoded)
                .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    @After
    fun tearDown() {
        server.shutdown()
        proxyEnvironment.close()
    }

    @Test
    fun `pinned clients are built with an explicit direct connection`() {
        val client = PinnedTls.client("ab".repeat(32), connectTimeoutMs = 1_000, readTimeoutMs = 1_000)
        try {
            assertEquals(Proxy.NO_PROXY, client.proxy)
        } finally {
            PinnedTls.shutdown(client)
        }
    }

    @Test
    fun `pairing confirm to a direct IP ignores the poisoned proxy environment`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(approvedBody()))

        val outcome = runBlocking { PairingConfirmClient().confirm(qr(), request()) }

        assertTrue("Expected Approved, got $outcome", outcome is PairingConfirmOutcome.Approved)
        assertEquals(0, proxyEnvironment.proxyConnectionCount)
        assertTrue(
            "NO_PROXY must never consult the ProxySelector: ${proxyEnvironment.selectedUris}",
            proxyEnvironment.selectedUris.isEmpty(),
        )
    }

    @Test
    fun `peer health probe to a direct IP ignores the poisoned proxy environment`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        val outcome = runBlocking { PeerHealthClient().probe(pairedPeer()) }

        assertTrue("Expected Reachable, got $outcome", outcome is PeerHealthOutcome.Reachable)
        assertEquals(0, proxyEnvironment.proxyConnectionCount)
        assertTrue(proxyEnvironment.selectedUris.isEmpty())
    }

    private fun qr() =
        PairingQrPayload(
            kind = PairingDocumentKinds.QR,
            version = 1,
            hosts = listOf("127.0.0.1"),
            port = server.port,
            deviceId = WINDOWS_ID,
            displayName = "DESKTOP-WIN",
            certSha256 = fingerprint,
            token = TOKEN,
            expiresAtMs = 1_755_064_500_000,
        )

    private fun request() =
        PairingConfirmRequest(
            kind = PairingDocumentKinds.CONFIRM_REQUEST,
            version = 1,
            token = TOKEN,
            deviceId = ANDROID_ID,
            displayName = "Pixel 8",
            platform = "android",
        )

    private fun pairedPeer() =
        PairedPeer(
            deviceId = WINDOWS_ID,
            displayName = "DESKTOP-WIN",
            platform = "windows",
            certSha256 = fingerprint,
            trustEpoch = 1,
            hosts = listOf("127.0.0.1"),
            port = server.port,
            pairedAtMs = 1_755_064_000_000,
        )

    private fun approvedBody(): String =
        PairingJson.serialize(
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

    private companion object {
        const val WINDOWS_ID = "11111111-1111-4111-8111-111111111111"
        const val ANDROID_ID = "22222222-2222-4222-8222-222222222222"
        const val TOKEN = "vJ8kAqhFRWDdiWvUuJ9lPCS0jBSJ73dP9-b1JzW5Qk4"
        const val SECRET = "gY7L0N6a-C0GZWx1Vb3f9YIhE2n4q8s_x5TdKJMuwao"
    }
}

package com.clipsync.android.sync

import java.net.InetAddress
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pinned-TLS dialer against MockWebServer. Handshake logic is covered by the
 * fake-transport engine tests; this only proves the pin and protocol header.
 */
class PeerSyncDialerTest {
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
        try {
            server.shutdown()
        } catch (_: java.io.IOException) {
            // OkHttp may still be draining the upgrade; the pin assertions already ran.
        }
    }

    @Test
    fun `matching pin upgrades to sync websocket with the protocol header`() {
        val opened = CountDownLatch(1)
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        opened.countDown()
                    }
                },
            ),
        )

        val result = runBlocking {
            OkHttpSyncConnector().connect(listOf("127.0.0.1"), server.port, fingerprint)
        }
        assertTrue(result is SyncConnectResult.Connected)
        assertTrue(opened.await(5, TimeUnit.SECONDS))

        val recorded = server.takeRequest()
        assertEquals("/v1/peer/sync", recorded.path)
        assertEquals("1", recorded.getHeader("X-Protocol-Version"))

        val connected = result as SyncConnectResult.Connected
        runBlocking { connected.transport.close("test") }
        connected.release()
    }

    @Test
    fun `wrong pin blocks before any request reaches the server`() {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {},
            ),
        )

        val result = runBlocking {
            OkHttpSyncConnector().connect(listOf("127.0.0.1"), server.port, "ab".repeat(32))
        }
        assertTrue(result is SyncConnectResult.CertificateMismatch)
        assertEquals(0, server.requestCount)
    }
}

package com.clipsync.android.sync

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
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
import java.io.IOException
import java.net.InetAddress
import java.security.MessageDigest

/**
 * Proves the sync connector dials paired hosts directly even when the process has a proxy
 * configured: peers are private LAN/Tailscale addresses that a device-wide HTTP(S) proxy
 * (Wi-Fi proxy setting, Clash/Surge in system-proxy mode) can neither reach nor be allowed
 * to observe.
 */
class SyncConnectorProxyBypassTest {
    private lateinit var proxyEnvironment: FakeProxyEnvironment

    @Before
    fun poisonProxyEnvironment() {
        proxyEnvironment = FakeProxyEnvironment()
    }

    @After
    fun restoreProxyEnvironment() {
        proxyEnvironment.close()
    }

    @Test
    fun `sync connect to a direct IP ignores the poisoned proxy environment`() {
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
        val server = MockWebServer()
        server.useHttps(handshake.sslSocketFactory(), false)
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onMessage(
                        webSocket: WebSocket,
                        text: String,
                    ) {
                        webSocket.send("echo:$text")
                    }
                },
            ),
        )
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        val pin =
            MessageDigest
                .getInstance("SHA-256")
                .digest(certificate.certificate.encoded)
                .joinToString(separator = "") { byte -> "%02x".format(byte) }
        try {
            runBlocking {
                val transport =
                    OkHttpSyncConnector()
                        .connect("127.0.0.1", server.port, pin, protocolVersion = 1)
                try {
                    transport.send("hello")
                    val frame = withTimeout(10_000) { transport.receive() }
                    assertEquals("echo:hello", (frame as TransportFrame.Text).payload)
                } finally {
                    transport.dispose()
                }
            }
            assertEquals(0, proxyEnvironment.proxyConnectionCount)
            assertTrue(
                "NO_PROXY must never consult the ProxySelector: ${proxyEnvironment.selectedUris}",
                proxyEnvironment.selectedUris.isEmpty(),
            )
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `a client without the bypass is captured by the same environment`() {
        // Contrast case proving the harness bites: a plain OkHttpClient consults the default
        // ProxySelector, lands on the fake proxy, and the refused tunnel fails the call. This
        // is exactly what the NO_PROXY tests above would look like without the bypass.
        val client = OkHttpClient.Builder().build()
        val request = Request.Builder().url("https://192.0.2.1:47654/v1/peer/sync").build()
        val failure = runCatching { client.newCall(request).execute() }.exceptionOrNull()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        assertTrue("Expected the proxied call to fail, got $failure", failure is IOException)
        assertTrue(proxyEnvironment.proxyConnectionCount >= 1)
        assertTrue(proxyEnvironment.selectedUris.isNotEmpty())
    }
}

package com.clipsync.android.sync

import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real TLS + WebSocket upgrade against MockWebServer, with the pairing-style pin. */
class OkHttpSyncConnectorTest {
    private fun heldCertificate(): HeldCertificate = HeldCertificate.Builder()
        .commonName("clipsync-test-peer")
        .addSubjectAlternativeName("localhost")
        .build()

    private fun pinOf(certificate: HeldCertificate): String = MessageDigest.getInstance("SHA-256")
        .digest(certificate.certificate.encoded)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private fun startServer(certificate: HeldCertificate, listener: WebSocketListener): MockWebServer {
        val certificates = HandshakeCertificates.Builder()
            .heldCertificate(certificate)
            .build()
        val server = MockWebServer()
        server.useHttps(certificates.sslSocketFactory(), false)
        server.enqueue(MockResponse().withWebSocketUpgrade(listener))
        server.start()
        return server
    }

    @Test
    fun `connects with the pinned certificate, sends the version header, and exchanges frames`() {
        val certificate = heldCertificate()
        val server = startServer(
            certificate,
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    webSocket.send("echo:$text")
                }
            },
        )
        try {
            runBlocking {
                val transport = OkHttpSyncConnector().connect(server.hostName, server.port, pinOf(certificate))
                try {
                    transport.send("hello")
                    val frame = withTimeout(10_000) { transport.receive() }
                    assertEquals("echo:hello", (frame as TransportFrame.Text).payload)
                } finally {
                    transport.dispose()
                }
            }
            val recorded = server.takeRequest()
            assertEquals("/v1/peer/sync", recorded.path)
            assertEquals("1", recorded.getHeader("X-Protocol-Version"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `a server certificate that does not match the pin is rejected`() {
        val certificate = heldCertificate()
        val server = startServer(certificate, object : WebSocketListener() {})
        try {
            val wrongPin = "0".repeat(64)
            val failure = runCatching {
                runBlocking { OkHttpSyncConnector().connect(server.hostName, server.port, wrongPin) }
            }.exceptionOrNull()
            assertTrue("Expected PinMismatchException, got $failure", failure is PinMismatchException)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `a closed port fails with a connectivity error, not a pin error`() {
        val failure = runCatching {
            runBlocking {
                OkHttpSyncConnector(connectTimeoutMs = 1_500)
                    .connect("127.0.0.1", 1, "0".repeat(64))
            }
        }.exceptionOrNull()
        assertTrue("Expected IOException, got $failure", failure is IOException && failure !is PinMismatchException)
    }

    @Test
    fun `server-initiated close surfaces as a closed frame`() {
        val certificate = heldCertificate()
        val server = startServer(
            certificate,
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    webSocket.close(1000, "bye")
                }
            },
        )
        try {
            runBlocking {
                val transport = OkHttpSyncConnector().connect(server.hostName, server.port, pinOf(certificate))
                try {
                    transport.send("trigger-close")
                    val frame = withTimeout(10_000) { transport.receive() }
                    assertTrue(frame is TransportFrame.Closed)
                } finally {
                    transport.dispose()
                }
            }
        } finally {
            server.shutdown()
        }
    }
}

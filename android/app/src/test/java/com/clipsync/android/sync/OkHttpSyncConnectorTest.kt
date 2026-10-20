package com.clipsync.android.sync

import java.io.IOException
import java.net.InetAddress
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
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

    private fun tlsServer(certificate: HeldCertificate): MockWebServer {
        val certificates = HandshakeCertificates.Builder()
            .heldCertificate(certificate)
            .build()
        val server = MockWebServer()
        server.useHttps(certificates.sslSocketFactory(), false)
        return server
    }

    private fun startServer(certificate: HeldCertificate, listener: WebSocketListener): MockWebServer {
        val server = tlsServer(certificate)
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
                val transport = OkHttpSyncConnector()
                    .connect(server.hostName, server.port, pinOf(certificate), protocolVersion = 1)
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
    fun `protocol v2 dials the v2 path with the matching version header`() {
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
                val transport = OkHttpSyncConnector()
                    .connect(server.hostName, server.port, pinOf(certificate), protocolVersion = 2)
                try {
                    transport.send("hello")
                    withTimeout(10_000) { transport.receive() }
                } finally {
                    transport.dispose()
                }
            }
            val recorded = server.takeRequest()
            assertEquals("/v2/peer/sync", recorded.path)
            assertEquals("2", recorded.getHeader("X-Protocol-Version"))
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
                runBlocking {
                    OkHttpSyncConnector().connect(server.hostName, server.port, wrongPin, protocolVersion = 1)
                }
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
                    .connect("127.0.0.1", 1, "0".repeat(64), protocolVersion = 1)
            }
        }.exceptionOrNull()
        assertTrue("Expected IOException, got $failure", failure is IOException && failure !is PinMismatchException)
    }

    @Test
    fun `malformed direct host and port arguments fail as IOExceptions`() {
        val hosts = listOf(
            "", "https://localhost", "user@localhost", "localhost:443", "localhost/path", "localhost\\path",
            "localhost?query", "localhost#fragment", "local\nhost", "localhost%2fpath",
            "[::1]:443", "2001:db8:::1", "[::1", "fe80::1%wlan0", "127.0.0.256",
        )
        hosts.forEachIndexed { index, host ->
            val failure = runCatching {
                runBlocking { OkHttpSyncConnector().connect(host, 47_654, "0".repeat(64), protocolVersion = 1) }
            }.exceptionOrNull()
            assertTrue("Invalid host case #$index escaped: $failure", failure is IOException && failure !is PinMismatchException)
            assertTrue("Invalid host case #$index reached the network", failure?.cause is IllegalArgumentException)
        }
        listOf(-1, 0, 65_536, Int.MAX_VALUE).forEach { port ->
            val failure = runCatching {
                runBlocking { OkHttpSyncConnector().connect("127.0.0.1", port, "0".repeat(64), protocolVersion = 2) }
            }.exceptionOrNull()
            assertTrue("Invalid port escaped: $failure", failure is IOException && failure !is PinMismatchException)
            assertTrue("Invalid port reached the network", failure?.cause is IllegalArgumentException)
        }
    }

    @Test
    fun `bare and bracketed IPv6 hosts use the intended WebSocket endpoint`() {
        val certificate = heldCertificate()
        val server = tlsServer(certificate)
        try {
            server.start(InetAddress.getByName("::1"), 0)
        } catch (failure: IOException) {
            assumeNoException("IPv6 loopback is unavailable", failure)
            return
        }
        try {
            listOf("::1", "[::1]").forEach { host ->
                server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {}))
                runBlocking {
                    val transport = OkHttpSyncConnector().connect(host, server.port, pinOf(certificate), protocolVersion = 2)
                    transport.dispose()
                }
                val recorded = server.takeRequest()
                assertEquals("/v2/peer/sync", recorded.path)
                assertEquals("2", recorded.getHeader("X-Protocol-Version"))
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `cancelling a pending upgrade preserves coroutine cancellation`() {
        val certificate = heldCertificate()
        val server = tlsServer(certificate)
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.start()
        try {
            runBlocking {
                val observedFailure = CompletableDeferred<Throwable>()
                val job = launch {
                    try {
                        OkHttpSyncConnector().connect(server.hostName, server.port, pinOf(certificate), protocolVersion = 1)
                            .dispose()
                    } catch (failure: Throwable) {
                        observedFailure.complete(failure)
                        throw failure
                    }
                }
                try {
                    assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) })
                } finally {
                    withTimeout(5_000) { job.cancelAndJoin() }
                }
                assertTrue(observedFailure.await() is CancellationException)
            }
        } finally {
            server.shutdown()
        }
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
                val transport = OkHttpSyncConnector()
                    .connect(server.hostName, server.port, pinOf(certificate), protocolVersion = 1)
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

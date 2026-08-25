package com.clipsync.android.sync

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets

class Bt1SyncTransportTest {
    private val clientToListenerKey = ByteArray(32) { (it + 1).toByte() }
    private val listenerToClientKey = ByteArray(32) { (it + 101).toByte() }

    private fun clientChannel() =
        Bt1SecureChannel(
            encryptor = Bt1FrameEncryptor(clientToListenerKey),
            decryptor = Bt1FrameDecryptor(listenerToClientKey),
        )

    /** One direction cipher as the listener would hold it. */
    private fun listenerEncryptor() = Bt1FrameEncryptor(listenerToClientKey)

    private fun listenerDecryptor() = Bt1FrameDecryptor(clientToListenerKey)

    @Test
    fun `sent text arrives as framed ciphertext the peer can decrypt in order`() =
        runBlocking {
            val outBytes = ByteArrayOutputStream()
            val transport =
                Bt1SyncTransport(
                    input = ByteArrayInputStream(ByteArray(0)),
                    output = outBytes,
                    channel = clientChannel(),
                    closeLink = {},
                )
            transport.send("""{"version":1,"type":"ping"}""")
            transport.send("second frame")

            val wire = ByteArrayInputStream(outBytes.toByteArray())
            val peer = listenerDecryptor()
            val first = peer.tryDecryptPayload(Bt1StreamFraming.readEncryptedPayload(wire)!!)
            assertEquals("""{"version":1,"type":"ping"}""", String(first!!, StandardCharsets.UTF_8))
            val second = peer.tryDecryptPayload(Bt1StreamFraming.readEncryptedPayload(wire)!!)
            assertEquals("second frame", String(second!!, StandardCharsets.UTF_8))
        }

    @Test
    fun `inbound frames surface as text then closed on clean EOF`() =
        runBlocking {
            val wire = ByteArrayOutputStream()
            wire.write(listenerEncryptor().encryptFrame("收到的一条".toByteArray(StandardCharsets.UTF_8)))
            var linkClosed = false
            val transport =
                Bt1SyncTransport(
                    input = ByteArrayInputStream(wire.toByteArray()),
                    output = ByteArrayOutputStream(),
                    channel = clientChannel(),
                    closeLink = { linkClosed = true },
                )
            assertEquals(TransportFrame.Text("收到的一条"), transport.receive())
            assertEquals(TransportFrame.Closed, transport.receive())
            assertTrue(linkClosed)
        }

    @Test
    fun `a tampered inbound frame kills the link instead of delivering garbage`() =
        runBlocking {
            val frame = listenerEncryptor().encryptFrame("about to be corrupted".toByteArray(StandardCharsets.UTF_8))
            frame[Bt1Frames.LENGTH_PREFIX_LENGTH + 2] =
                (frame[Bt1Frames.LENGTH_PREFIX_LENGTH + 2].toInt() xor 0x40).toByte()
            var linkClosed = false
            val transport =
                Bt1SyncTransport(
                    input = ByteArrayInputStream(frame),
                    output = ByteArrayOutputStream(),
                    channel = clientChannel(),
                    closeLink = { linkClosed = true },
                )
            assertEquals(TransportFrame.Closed, transport.receive())
            assertTrue(linkClosed)
        }

    @Test
    fun `a replayed inbound frame kills the link`() =
        runBlocking {
            val frame = listenerEncryptor().encryptFrame("once only".toByteArray(StandardCharsets.UTF_8))
            val wire = ByteArrayOutputStream()
            wire.write(frame)
            wire.write(frame)
            var linkClosed = false
            val transport =
                Bt1SyncTransport(
                    input = ByteArrayInputStream(wire.toByteArray()),
                    output = ByteArrayOutputStream(),
                    channel = clientChannel(),
                    closeLink = { linkClosed = true },
                )
            assertEquals(TransportFrame.Text("once only"), transport.receive())
            // The duplicate decrypts against counter 1 and fails the tag: fatal, not garbage.
            assertEquals(TransportFrame.Closed, transport.receive())
            assertTrue(linkClosed)
        }

    @Test
    fun `an oversize declared frame length kills the link without decryption`() =
        runBlocking {
            // UINT32_BE far above the 7 MiB + tag window.
            val wire = byteArrayOf(0x7f, -1, -1, -1)
            var linkClosed = false
            val transport =
                Bt1SyncTransport(
                    input = ByteArrayInputStream(wire),
                    output = ByteArrayOutputStream(),
                    channel = clientChannel(),
                    closeLink = { linkClosed = true },
                )
            assertEquals(TransportFrame.Closed, transport.receive())
            assertTrue(linkClosed)
        }

    @Test
    fun `send after dispose fails instead of writing to a dead socket`() {
        val transport =
            Bt1SyncTransport(
                input = ByteArrayInputStream(ByteArray(0)),
                output = ByteArrayOutputStream(),
                channel = clientChannel(),
                closeLink = {},
            )
        transport.dispose()
        assertThrows(IOException::class.java) {
            runBlocking { transport.send("too late") }
        }
    }

    @Test
    fun `close closes the stream without writing any post-handshake plaintext`() =
        runBlocking {
            val wire = ByteArrayOutputStream()
            var linkClosed = false
            val transport =
                Bt1SyncTransport(
                    input = ByteArrayInputStream(ByteArray(0)),
                    output = wire,
                    channel = clientChannel(),
                    closeLink = { linkClosed = true },
                )
            transport.close(1000, "session complete")
            // bt1 has no wire close message: the stream close is the close.
            assertEquals(0, wire.size())
            assertTrue(linkClosed)
        }
}

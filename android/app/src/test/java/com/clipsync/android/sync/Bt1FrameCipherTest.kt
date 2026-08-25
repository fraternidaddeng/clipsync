package com.clipsync.android.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The bt1 frame layer must be byte-identical with the Windows implementation over the
 * shared vectors in protocol/bt1/fixtures/frames/vectors.json, and must fail closed on
 * tampered, replayed, reordered, truncated, and oversized frames.
 */
class Bt1FrameCipherTest {
    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { index ->
            hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }

    private fun bytesToHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private fun loadVectors(): List<JsonObject> {
        val file =
            File(
                requireNotNull(System.getProperty("protocol.bt1.fixtures.dir")),
                "frames/vectors.json",
            )
        assertTrue("Shared bt1 frame vectors are missing: $file", file.isFile)
        val root = Json.parseToJsonElement(file.readText()).jsonObject
        assertEquals("aes-256-gcm", root.getValue("cipher").jsonPrimitive.content)
        return root.getValue("vectors").jsonArray.map { it.jsonObject }
    }

    private fun payload(frame: ByteArray): ByteArray = frame.copyOfRange(Bt1Frames.LENGTH_PREFIX_LENGTH, frame.size)

    @Test
    fun `the shared vector file contains at least five vectors`() {
        assertTrue(loadVectors().size >= 5)
    }

    @Test
    fun `encrypt reproduces every shared vector`() {
        loadVectors().forEach { vector ->
            val name = vector.getValue("name").jsonPrimitive.content
            val encryptor =
                Bt1FrameEncryptor(
                    hexToBytes(vector.getValue("key_hex").jsonPrimitive.content),
                    vector
                        .getValue("sequence")
                        .jsonPrimitive.content
                        .toULong(),
                )
            val frame =
                encryptor.encryptFrame(
                    vector
                        .getValue("plaintext_utf8")
                        .jsonPrimitive.content
                        .toByteArray(Charsets.UTF_8),
                )
            assertEquals(
                "frame failed: $name",
                vector.getValue("frame_hex").jsonPrimitive.content,
                bytesToHex(frame),
            )
        }
    }

    @Test
    fun `decrypt reproduces every shared vector plaintext`() {
        loadVectors().forEach { vector ->
            val name = vector.getValue("name").jsonPrimitive.content
            val frame = hexToBytes(vector.getValue("frame_hex").jsonPrimitive.content)
            val declaredLength = Bt1Frames.readDeclaredPayloadLength(frame)
            assertTrue(name, Bt1Frames.isAcceptableEncryptedPayloadLength(declaredLength))
            assertEquals(
                name,
                (frame.size - Bt1Frames.LENGTH_PREFIX_LENGTH).toLong(),
                declaredLength,
            )

            val decryptor =
                Bt1FrameDecryptor(
                    hexToBytes(vector.getValue("key_hex").jsonPrimitive.content),
                    vector
                        .getValue("sequence")
                        .jsonPrimitive.content
                        .toULong(),
                )
            val plaintext = decryptor.tryDecryptPayload(payload(frame))
            assertEquals(
                "plaintext failed: $name",
                vector.getValue("plaintext_utf8").jsonPrimitive.content,
                plaintext?.toString(Charsets.UTF_8),
            )
        }
    }

    @Test
    fun `a round trip carries consecutive frames in order`() {
        val key = ByteArray(32).also { it[0] = 0x42 }
        val encryptor = Bt1FrameEncryptor(key)
        val decryptor = Bt1FrameDecryptor(key)

        repeat(5) { index ->
            val plaintext = "frame number $index".toByteArray(Charsets.UTF_8)
            val decrypted = decryptor.tryDecryptPayload(payload(encryptor.encryptFrame(plaintext)))
            assertEquals(plaintext.toList(), decrypted?.toList())
        }
    }

    @Test
    fun `a tampered ciphertext fails and poisons the decryptor`() {
        val key = ByteArray(32)
        val encryptor = Bt1FrameEncryptor(key)
        val decryptor = Bt1FrameDecryptor(key)

        val tampered = payload(encryptor.encryptFrame("attacker target".toByteArray(Charsets.UTF_8)))
        tampered[3] = (tampered[3].toInt() xor 1).toByte()
        assertNull(decryptor.tryDecryptPayload(tampered))
        assertTrue(decryptor.hasFailed)

        // Even the untampered original is refused afterwards: failure is fatal.
        tampered[3] = (tampered[3].toInt() xor 1).toByte()
        assertNull(decryptor.tryDecryptPayload(tampered))
    }

    @Test
    fun `a tampered tag fails`() {
        val key = ByteArray(32)
        val encryptor = Bt1FrameEncryptor(key)
        val decryptor = Bt1FrameDecryptor(key)

        val tampered = payload(encryptor.encryptFrame("tag matters".toByteArray(Charsets.UTF_8)))
        tampered[tampered.size - 1] = (tampered.last().toInt() xor 0x80).toByte()
        assertNull(decryptor.tryDecryptPayload(tampered))
        assertTrue(decryptor.hasFailed)
    }

    @Test
    fun `a replayed frame fails because the counter advanced`() {
        val key = ByteArray(32)
        val encryptor = Bt1FrameEncryptor(key)
        val decryptor = Bt1FrameDecryptor(key)

        val once = payload(encryptor.encryptFrame("replay me".toByteArray(Charsets.UTF_8)))
        assertTrue(decryptor.tryDecryptPayload(once) != null)
        assertNull(decryptor.tryDecryptPayload(once))
        assertTrue(decryptor.hasFailed)
    }

    @Test
    fun `out-of-order frames fail`() {
        val key = ByteArray(32)
        val encryptor = Bt1FrameEncryptor(key)
        val decryptor = Bt1FrameDecryptor(key)

        val first = payload(encryptor.encryptFrame("first".toByteArray(Charsets.UTF_8)))
        val second = payload(encryptor.encryptFrame("second".toByteArray(Charsets.UTF_8)))

        assertNull(decryptor.tryDecryptPayload(second))
        assertTrue(decryptor.hasFailed)
        assertNull(decryptor.tryDecryptPayload(first))
    }

    @Test
    fun `truncated and undersized payloads fail`() {
        val key = ByteArray(32)
        val encryptor = Bt1FrameEncryptor(key)

        val whole = payload(encryptor.encryptFrame("truncate me".toByteArray(Charsets.UTF_8)))
        assertNull(Bt1FrameDecryptor(key).tryDecryptPayload(whole.copyOf(whole.size - 1)))

        // A tag-only payload would imply zero-length plaintext, which bt1 forbids.
        assertNull(Bt1FrameDecryptor(key).tryDecryptPayload(ByteArray(Bt1Frames.TAG_LENGTH)))
    }

    @Test
    fun `oversize plaintext and declared lengths are rejected`() {
        val key = ByteArray(32)
        val encryptor = Bt1FrameEncryptor(key)
        assertTrue(
            runCatching {
                encryptor.encryptFrame(ByteArray(Bt1Frames.MAX_PLAINTEXT_LENGTH + 1))
            }.exceptionOrNull() is IllegalArgumentException,
        )
        assertTrue(
            runCatching { encryptor.encryptFrame(ByteArray(0)) }
                .exceptionOrNull() is IllegalArgumentException,
        )

        // The receiver rejects an oversize declared length before allocating or decrypting.
        val oversize = (Bt1Frames.MAX_ENCRYPTED_PAYLOAD_LENGTH + 1).toLong()
        assertFalse(Bt1Frames.isAcceptableEncryptedPayloadLength(oversize))
        assertFalse(Bt1Frames.isAcceptableEncryptedPayloadLength(0xFFFF_FFFFL))
        val prefix = byteArrayOf(-1, -1, -1, -1)
        assertEquals(0xFFFF_FFFFL, Bt1Frames.readDeclaredPayloadLength(prefix))

        val decryptor = Bt1FrameDecryptor(key)
        assertNull(decryptor.tryDecryptPayload(ByteArray(Bt1Frames.MAX_ENCRYPTED_PAYLOAD_LENGTH + 1)))
        assertTrue(decryptor.hasFailed)
    }

    @Test
    fun `direction keys are not interchangeable`() {
        val vector = loadVectors().first()
        val key = hexToBytes(vector.getValue("key_hex").jsonPrimitive.content)
        val otherKey = key.copyOf().also { it[0] = (it[0].toInt() xor 0xFF).toByte() }

        val encryptor = Bt1FrameEncryptor(key)
        val decryptor = Bt1FrameDecryptor(otherKey)
        assertNull(
            decryptor.tryDecryptPayload(
                payload(encryptor.encryptFrame("wrong direction".toByteArray(Charsets.UTF_8))),
            ),
        )
    }

    @Test
    fun `the sender counter exhausts after the maximum sequence`() {
        val encryptor = Bt1FrameEncryptor(ByteArray(32), ULong.MAX_VALUE)
        encryptor.encryptFrame("last one".toByteArray(Charsets.UTF_8))
        assertTrue(
            runCatching { encryptor.encryptFrame("one too many".toByteArray(Charsets.UTF_8)) }
                .exceptionOrNull() is IllegalStateException,
        )
    }

    @Test
    fun `the handshake length window is enforced`() {
        assertFalse(Bt1Frames.isAcceptableHandshakePayloadLength(0))
        assertFalse(Bt1Frames.isAcceptableHandshakePayloadLength(1))
        assertTrue(Bt1Frames.isAcceptableHandshakePayloadLength(2))
        assertTrue(Bt1Frames.isAcceptableHandshakePayloadLength(4096))
        assertFalse(Bt1Frames.isAcceptableHandshakePayloadLength(4097))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a wrong-size encryptor key is rejected`() {
        Bt1FrameEncryptor(ByteArray(16))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a wrong-size decryptor key is rejected`() {
        Bt1FrameDecryptor(ByteArray(16))
    }
}

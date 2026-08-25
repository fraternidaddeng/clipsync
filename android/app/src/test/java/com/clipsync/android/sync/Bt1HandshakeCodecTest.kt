package com.clipsync.android.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * The handshake codec must accept every shared valid fixture and reject every invalid
 * one, exactly like the Windows parser over the same files.
 */
class Bt1HandshakeCodecTest {
    private fun fixtureFiles(subset: String): List<File> {
        val root =
            File(requireNotNull(System.getProperty("protocol.bt1.fixtures.dir")))
                .resolve("handshake")
                .resolve(subset)
        assertTrue("bt1 handshake fixture directory is missing: $root", root.isDirectory)
        val files = root.listFiles { file -> file.isFile && file.extension == "json" }?.toList().orEmpty()
        assertTrue("bt1 handshake $subset fixture set must not be empty.", files.isNotEmpty())
        return files
    }

    private fun fixture(
        subset: String,
        name: String,
    ): String =
        File(requireNotNull(System.getProperty("protocol.bt1.fixtures.dir")))
            .resolve("handshake")
            .resolve(subset)
            .resolve(name)
            .readText()

    @Test
    fun `every valid fixture parses`() {
        fixtureFiles("valid").forEach { file ->
            runCatching { Bt1HandshakeCodec.parse(file.readText()) }
                .onFailure { fail("valid fixture rejected: ${file.name}: $it") }
        }
    }

    @Test
    fun `every invalid fixture is rejected`() {
        fixtureFiles("invalid").forEach { file ->
            val outcome = runCatching { Bt1HandshakeCodec.parse(file.readText()) }
            assertTrue("invalid fixture accepted: ${file.name}", outcome.isFailure)
            assertTrue(
                "wrong exception for: ${file.name}",
                outcome.exceptionOrNull() is Bt1HandshakeException,
            )
        }
    }

    @Test
    fun `valid fixtures carry the expected typed values`() {
        val clientHello =
            Bt1HandshakeCodec.parse(fixture("valid", "client-hello.json"))
                as Bt1HandshakeMessage.Hello
        assertEquals(Bt1Role.CLIENT, clientHello.senderRole)
        assertEquals("11111111-1111-4111-8111-111111111111", clientHello.deviceId)
        assertEquals(1L, clientHello.trustEpoch)
        assertEquals(ByteArray(32).toList(), clientHello.nonce.toList())

        val listenerHello =
            Bt1HandshakeCodec.parse(fixture("valid", "listener-hello.json"))
                as Bt1HandshakeMessage.Hello
        assertEquals(Bt1Role.LISTENER, listenerHello.senderRole)
        assertEquals(Long.MAX_VALUE, listenerHello.trustEpoch)

        val clientAuth =
            Bt1HandshakeCodec.parse(fixture("valid", "client-auth.json"))
                as Bt1HandshakeMessage.Auth
        assertEquals(Bt1Role.CLIENT, clientAuth.senderRole)
        assertEquals(32, clientAuth.proof.size)

        val error =
            Bt1HandshakeCodec.parse(fixture("valid", "error-auth-failed.json"))
                as Bt1HandshakeMessage.ChannelError
        assertEquals(Bt1ErrorCodes.AUTH_FAILED, error.code)
    }

    @Test
    fun `an unsupported version gets its dedicated error code`() {
        val outcome =
            runCatching {
                Bt1HandshakeCodec.parse(fixture("invalid", "listener-hello-bad-version.json"))
            }
        val exception = outcome.exceptionOrNull() as Bt1HandshakeException
        assertEquals(Bt1ErrorCodes.VERSION_UNSUPPORTED, exception.errorCode)
    }

    @Test
    fun `an oversized handshake payload is rejected as too large`() {
        val padding = " ".repeat(Bt1Frames.MAX_HANDSHAKE_PAYLOAD_LENGTH)
        val outcome = runCatching { Bt1HandshakeCodec.parse(padding + "{}") }
        val exception = outcome.exceptionOrNull() as Bt1HandshakeException
        assertEquals(Bt1ErrorCodes.FRAME_TOO_LARGE, exception.errorCode)
    }

    @Test
    fun `serialized messages round-trip through the parser`() {
        val deviceId = "aaaabbbb-cccc-4ddd-8eee-ffff00001111"
        val nonce = ByteArray(32) { it.toByte() }

        val hello =
            Bt1HandshakeCodec.parse(
                Bt1HandshakeCodec.serializeHello(Bt1Role.CLIENT, deviceId, 42, nonce),
            ) as Bt1HandshakeMessage.Hello
        assertEquals(deviceId, hello.deviceId)
        assertEquals(42L, hello.trustEpoch)
        assertEquals(nonce.toList(), hello.nonce.toList())

        val proof = ByteArray(32) { (it * 3).toByte() }
        val auth =
            Bt1HandshakeCodec.parse(
                Bt1HandshakeCodec.serializeAuth(Bt1Role.LISTENER, proof),
            ) as Bt1HandshakeMessage.Auth
        assertEquals(Bt1Role.LISTENER, auth.senderRole)
        assertEquals(proof.toList(), auth.proof.toList())

        val error =
            Bt1HandshakeCodec.parse(
                Bt1HandshakeCodec.serializeError(Bt1ErrorCodes.RATE_LIMITED),
            ) as Bt1HandshakeMessage.ChannelError
        assertEquals(Bt1ErrorCodes.RATE_LIMITED, error.code)

        // Serialized handshake frames always fit the plaintext handshake window.
        val helloBytes =
            Bt1HandshakeCodec
                .serializeHello(Bt1Role.CLIENT, deviceId, 42, nonce)
                .toByteArray(Charsets.UTF_8)
                .size
        assertTrue(helloBytes in Bt1Frames.MIN_HANDSHAKE_PAYLOAD_LENGTH..Bt1Frames.MAX_HANDSHAKE_PAYLOAD_LENGTH)
    }

    @Test
    fun `the serializer refuses illegal inputs`() {
        assertTrue(
            runCatching {
                Bt1HandshakeCodec.serializeHello(
                    Bt1Role.CLIENT,
                    "11111111-1111-4111-8111-111111111111",
                    0,
                    ByteArray(32),
                )
            }.exceptionOrNull() is IllegalArgumentException,
        )
        assertTrue(
            runCatching {
                Bt1HandshakeCodec.serializeHello(
                    Bt1Role.CLIENT,
                    "11111111-1111-4111-8111-111111111111",
                    1,
                    ByteArray(16),
                )
            }.exceptionOrNull() is IllegalArgumentException,
        )
        assertTrue(
            runCatching { Bt1HandshakeCodec.serializeAuth(Bt1Role.CLIENT, ByteArray(31)) }
                .exceptionOrNull() is IllegalArgumentException,
        )
        assertTrue(
            runCatching { Bt1HandshakeCodec.serializeError(Bt1ErrorCodes.DECRYPT_FAILED) }
                .exceptionOrNull() is IllegalArgumentException,
        )
    }

    // Shared inputs of handshake vector 1, reused by the transcript test below.
    private val transcriptSecret = ByteArray(32) { it.toByte() }

    private fun parsedHello(
        role: Bt1Role,
        deviceId: String,
        nonce: ByteArray,
    ): Bt1HandshakeMessage.Hello =
        Bt1HandshakeCodec.parse(
            Bt1HandshakeCodec.serializeHello(role, deviceId, 1, nonce),
        ) as Bt1HandshakeMessage.Hello

    /** Computes one side's proof, round-trips it through the codec, and verifies it. */
    private fun roundTrippedVerifiedAuth(
        role: Bt1Role,
        clientHello: Bt1HandshakeMessage.Hello,
        listenerHello: Bt1HandshakeMessage.Hello,
    ): Bt1HandshakeMessage.Auth {
        val proof =
            Bt1AuthProof.compute(
                transcriptSecret,
                role,
                clientHello.nonce,
                listenerHello.nonce,
                clientHello.deviceId,
                listenerHello.deviceId,
                1,
            )
        val auth =
            Bt1HandshakeCodec.parse(
                Bt1HandshakeCodec.serializeAuth(role, proof),
            ) as Bt1HandshakeMessage.Auth
        assertTrue(
            Bt1AuthProof.verify(
                transcriptSecret,
                role,
                clientHello.nonce,
                listenerHello.nonce,
                clientHello.deviceId,
                listenerHello.deviceId,
                1,
                auth.proof,
            ),
        )
        return auth
    }

    @Test
    fun `a full handshake transcript over shared vector one authenticates both sides`() {
        // Drives the four-message sequence of docs/protocol-bt1.md section 3 end to end
        // without any transport: serialize -> parse -> verify both proofs -> derive
        // identical direction keys on both sides -> carry one encrypted frame across.
        val clientHello =
            parsedHello(Bt1Role.CLIENT, "11111111-1111-4111-8111-111111111111", ByteArray(32))
        val listenerHello =
            parsedHello(
                Bt1Role.LISTENER,
                "22222222-2222-4222-8222-222222222222",
                ByteArray(32) { it.toByte() },
            )

        val clientAuth = roundTrippedVerifiedAuth(Bt1Role.CLIENT, clientHello, listenerHello)
        roundTrippedVerifiedAuth(Bt1Role.LISTENER, clientHello, listenerHello)
        // Handshake vector 1 pins this exact proof cross-platform.
        assertEquals("hc9jAV2xtP7yyYyhaahlm6L_jC0HCvvOnKfnkJYMVOQ", Base64Url.encode(clientAuth.proof))

        val clientKeys =
            Bt1KeySchedule.derive(transcriptSecret, clientHello.nonce, listenerHello.nonce)
        val listenerKeys =
            Bt1KeySchedule.derive(transcriptSecret, clientHello.nonce, listenerHello.nonce)

        val encryptor = Bt1FrameEncryptor(clientKeys.clientToListener)
        val decryptor = Bt1FrameDecryptor(listenerKeys.clientToListener)
        val frame = encryptor.encryptFrame("{\"hello\":\"bt1\"}".toByteArray(Charsets.UTF_8))
        val plaintext =
            decryptor.tryDecryptPayload(
                frame.copyOfRange(Bt1Frames.LENGTH_PREFIX_LENGTH, frame.size),
            )
        assertEquals("{\"hello\":\"bt1\"}", plaintext?.toString(Charsets.UTF_8))
    }
}

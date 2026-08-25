package com.clipsync.android.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import kotlin.concurrent.thread

private const val CLIENT_ID = "11111111-1111-4111-8111-111111111111"
private const val LISTENER_ID = "22222222-2222-4222-8222-222222222222"
private const val TRUST_EPOCH = 7L

/**
 * Drives [Bt1ClientHandshake] against a scripted listener over in-memory pipes — the same
 * stream shape an RFCOMM socket presents, without any Bluetooth hardware. The listener half
 * is built from the same codec/crypto primitives the shared fixtures already pin, so these
 * tests cover the stream sequencing, not the byte layouts.
 */
class Bt1ClientHandshakeTest {
    /** A dead scripted listener must fail the test, not hang the suite on a pipe read. */
    @get:Rule
    val hangGuard: Timeout = Timeout.seconds(60)

    private class Wire : AutoCloseable {
        val clientIn = PipedInputStream(64 * 1024)
        val listenerOut = PipedOutputStream(clientIn)
        val listenerIn = PipedInputStream(64 * 1024)
        val clientOut = PipedOutputStream(listenerIn)

        override fun close() {
            listOf(clientIn, listenerOut, listenerIn, clientOut).forEach {
                runCatching { it.close() }
            }
        }
    }

    /** The listener half of the bt1 handshake, honestly reimplemented for the test. */
    @Suppress("LongParameterList")
    private fun runListener(
        input: InputStream,
        output: OutputStream,
        pairSecret: ByteArray,
        listenerId: String = LISTENER_ID,
        answeredEpoch: Long = TRUST_EPOCH,
        rawListenerHello: String? = null,
        refuseWith: String? = null,
        wrongProof: Boolean = false,
    ): Bt1SecureChannel? {
        val hello = Bt1HandshakeCodec.parse(Bt1StreamFraming.readHandshakePayload(input))
        check(hello is Bt1HandshakeMessage.Hello && hello.senderRole == Bt1Role.CLIENT)
        val nonceClient = hello.nonce

        if (refuseWith != null) {
            Bt1StreamFraming.writeHandshakeFrame(output, Bt1HandshakeCodec.serializeError(refuseWith))
            return null
        }

        val nonceListener = ByteArray(Bt1AuthProof.NONCE_LENGTH).also { SecureRandom().nextBytes(it) }
        Bt1StreamFraming.writeHandshakeFrame(
            output,
            rawListenerHello
                ?: Bt1HandshakeCodec.serializeHello(Bt1Role.LISTENER, listenerId, answeredEpoch, nonceListener),
        )

        val clientAuth = Bt1HandshakeCodec.parse(Bt1StreamFraming.readHandshakePayload(input))
        if (clientAuth !is Bt1HandshakeMessage.Auth) {
            return null
        }
        val verified =
            Bt1AuthProof.verify(
                pairSecret = pairSecret,
                role = Bt1Role.CLIENT,
                nonceClient = nonceClient,
                nonceListener = nonceListener,
                clientDeviceId = hello.deviceId,
                listenerDeviceId = listenerId,
                trustEpoch = hello.trustEpoch,
                proof = clientAuth.proof,
            )
        assertTrue("The listener must accept the real client proof.", verified)

        val listenerProof =
            if (wrongProof) {
                ByteArray(Bt1AuthProof.PROOF_LENGTH) { 0x5a }
            } else {
                Bt1AuthProof.compute(
                    pairSecret = pairSecret,
                    role = Bt1Role.LISTENER,
                    nonceClient = nonceClient,
                    nonceListener = nonceListener,
                    clientDeviceId = hello.deviceId,
                    listenerDeviceId = listenerId,
                    trustEpoch = hello.trustEpoch,
                )
            }
        Bt1StreamFraming.writeHandshakeFrame(output, Bt1HandshakeCodec.serializeAuth(Bt1Role.LISTENER, listenerProof))

        val keys = Bt1KeySchedule.derive(pairSecret, nonceClient, nonceListener)
        return Bt1SecureChannel(
            encryptor = Bt1FrameEncryptor(keys.listenerToClient),
            decryptor = Bt1FrameDecryptor(keys.clientToListener),
        )
    }

    private fun dial(
        wire: Wire,
        secret: ByteArray,
    ): Bt1SecureChannel =
        Bt1ClientHandshake.run(
            input = wire.clientIn,
            output = wire.clientOut,
            localDeviceId = CLIENT_ID,
            peerDeviceId = LISTENER_ID,
            trustEpoch = TRUST_EPOCH,
            pairSecret = secret,
        )

    @Test
    fun `a successful handshake yields channels that interoperate end to end`() {
        val secret = ByteArray(32) { (it * 7).toByte() }
        Wire().use { wire ->
            var listenerChannel: Bt1SecureChannel? = null
            val listener =
                thread {
                    listenerChannel = runListener(wire.listenerIn, wire.listenerOut, secret)
                }
            val clientChannel = dial(wire, secret)
            listener.join(10_000)

            // Client -> listener and listener -> client, through independently derived keys.
            val toListener =
                clientChannel.encryptor.encryptFrame("手机复制的一段文本".toByteArray(StandardCharsets.UTF_8))
            val listenerSeen =
                listenerChannel!!.decryptor.tryDecryptPayload(
                    toListener.copyOfRange(Bt1Frames.LENGTH_PREFIX_LENGTH, toListener.size),
                )
            assertEquals("手机复制的一段文本", String(listenerSeen!!, StandardCharsets.UTF_8))

            val toClient =
                listenerChannel!!.encryptor.encryptFrame("ack from the listener".toByteArray(StandardCharsets.UTF_8))
            val clientSeen =
                clientChannel.decryptor.tryDecryptPayload(
                    toClient.copyOfRange(Bt1Frames.LENGTH_PREFIX_LENGTH, toClient.size),
                )
            assertEquals("ack from the listener", String(clientSeen!!, StandardCharsets.UTF_8))
        }
    }

    @Test
    fun `a listener with the wrong device id is refused before the client proves`() {
        val secret = ByteArray(32) { 3 }
        Wire().use { wire ->
            val listener =
                thread {
                    runCatching {
                        runListener(
                            wire.listenerIn,
                            wire.listenerOut,
                            secret,
                            listenerId = "99999999-9999-4999-8999-999999999999",
                        )
                    }
                }
            val failure = assertThrows(Bt1HandshakeException::class.java) { dial(wire, secret) }
            assertEquals(Bt1ErrorCodes.AUTH_FAILED, failure.errorCode)
            listener.join(10_000)
        }
    }

    @Test
    fun `a listener trust epoch mismatch is refused`() {
        val secret = ByteArray(32) { 8 }
        Wire().use { wire ->
            val listener =
                thread {
                    runCatching {
                        runListener(wire.listenerIn, wire.listenerOut, secret, answeredEpoch = TRUST_EPOCH + 1)
                    }
                }
            val failure = assertThrows(Bt1HandshakeException::class.java) { dial(wire, secret) }
            assertEquals(Bt1ErrorCodes.AUTH_FAILED, failure.errorCode)
            listener.join(10_000)
        }
    }

    @Test
    fun `a wrong listener proof fails authentication`() {
        val secret = ByteArray(32) { 9 }
        Wire().use { wire ->
            val listener =
                thread {
                    runCatching { runListener(wire.listenerIn, wire.listenerOut, secret, wrongProof = true) }
                }
            val failure = assertThrows(Bt1HandshakeException::class.java) { dial(wire, secret) }
            assertEquals(Bt1ErrorCodes.AUTH_FAILED, failure.errorCode)
            listener.join(10_000)
        }
    }

    @Test
    fun `a listener refusal surfaces its stable error code`() {
        val secret = ByteArray(32) { 1 }
        Wire().use { wire ->
            val listener =
                thread {
                    runCatching {
                        runListener(wire.listenerIn, wire.listenerOut, secret, refuseWith = Bt1ErrorCodes.RATE_LIMITED)
                    }
                }
            val failure = assertThrows(Bt1HandshakeException::class.java) { dial(wire, secret) }
            assertEquals(Bt1ErrorCodes.RATE_LIMITED, failure.errorCode)
            listener.join(10_000)
        }
    }

    @Test
    fun `an unsupported bt1 version is refused`() {
        val secret = ByteArray(32) { 2 }
        val nonce = Base64Url.encode(ByteArray(32) { 6 })
        val badHello =
            """{"kind":"bt1_listener_hello","version":2,"device_id":"$LISTENER_ID",""" +
                """"trust_epoch":$TRUST_EPOCH,"nonce":"$nonce"}"""
        Wire().use { wire ->
            val listener =
                thread {
                    runCatching {
                        runListener(wire.listenerIn, wire.listenerOut, secret, rawListenerHello = badHello)
                    }
                }
            val failure = assertThrows(Bt1HandshakeException::class.java) { dial(wire, secret) }
            assertEquals(Bt1ErrorCodes.VERSION_UNSUPPORTED, failure.errorCode)
            listener.join(10_000)
        }
    }

    @Test
    fun `a peer that closes mid-handshake is an IO failure, not a hang`() {
        val secret = ByteArray(32) { 4 }
        Wire().use { wire ->
            val listener =
                thread {
                    runCatching { Bt1StreamFraming.readHandshakePayload(wire.listenerIn) }
                    runCatching { wire.listenerOut.close() }
                }
            assertThrows(IOException::class.java) { dial(wire, secret) }
            listener.join(10_000)
        }
    }

    @Test
    fun `a non-JSON handshake frame is a schema violation`() {
        val secret = ByteArray(32) { 5 }
        Wire().use { wire ->
            val listener =
                thread {
                    runCatching {
                        runListener(
                            wire.listenerIn,
                            wire.listenerOut,
                            secret,
                            rawListenerHello = "not json at all",
                        )
                    }
                }
            val failure = assertThrows(Bt1HandshakeException::class.java) { dial(wire, secret) }
            assertEquals(Bt1ErrorCodes.SCHEMA_VIOLATION, failure.errorCode)
            listener.join(10_000)
        }
    }
}

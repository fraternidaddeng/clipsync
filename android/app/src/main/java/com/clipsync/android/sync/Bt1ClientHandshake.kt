package com.clipsync.android.sync

import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom

/** Both direction ciphers of one established bt1 channel (send = client to listener). */
class Bt1SecureChannel(
    val encryptor: Bt1FrameEncryptor,
    val decryptor: Bt1FrameDecryptor,
)

/**
 * The dial side of the bt1 handshake (docs/protocol-bt1.md section 3): client hello,
 * listener hello, client auth, listener auth — no business byte in either direction until
 * both proofs verified. Pure stream logic so unit tests drive it over in-memory pipes; the
 * RFCOMM connector supplies socket streams and owns the 30-second abort by closing the
 * socket. Never logs nonces, proofs, or secrets.
 */
object Bt1ClientHandshake {
    /**
     * Runs the handshake and returns the session's direction ciphers. Throws
     * [Bt1HandshakeException] on a protocol-level refusal (local or from the listener's
     * bt1_error) and [java.io.IOException] on transport failure. The caller owns
     * [pairSecret] and zeroes it afterwards.
     */
    @Suppress("LongParameterList")
    fun run(
        input: InputStream,
        output: OutputStream,
        localDeviceId: String,
        peerDeviceId: String,
        trustEpoch: Long,
        pairSecret: ByteArray,
        random: SecureRandom = SecureRandom(),
    ): Bt1SecureChannel {
        val nonceClient = ByteArray(Bt1AuthProof.NONCE_LENGTH)
        random.nextBytes(nonceClient)
        Bt1StreamFraming.writeHandshakeFrame(
            output,
            Bt1HandshakeCodec.serializeHello(Bt1Role.CLIENT, localDeviceId, trustEpoch, nonceClient),
        )

        val listenerHello = readMessage(input, output)
        if (listenerHello !is Bt1HandshakeMessage.Hello || listenerHello.senderRole != Bt1Role.LISTENER) {
            fail(output, Bt1ErrorCodes.SCHEMA_VIOLATION, "expected bt1_listener_hello")
        }
        if (listenerHello.deviceId != peerDeviceId || listenerHello.deviceId == localDeviceId) {
            // The bonded radio answered, but it is not the ClipSync peer this device trusts.
            fail(output, Bt1ErrorCodes.AUTH_FAILED, "listener device id does not match the pairing")
        }
        if (listenerHello.trustEpoch != trustEpoch) {
            fail(output, Bt1ErrorCodes.AUTH_FAILED, "listener trust epoch does not match")
        }
        val nonceListener = listenerHello.nonce

        val clientProof =
            Bt1AuthProof.compute(
                pairSecret = pairSecret,
                role = Bt1Role.CLIENT,
                nonceClient = nonceClient,
                nonceListener = nonceListener,
                clientDeviceId = localDeviceId,
                listenerDeviceId = peerDeviceId,
                trustEpoch = trustEpoch,
            )
        Bt1StreamFraming.writeHandshakeFrame(
            output,
            Bt1HandshakeCodec.serializeAuth(Bt1Role.CLIENT, clientProof),
        )

        val listenerAuth = readMessage(input, output)
        if (listenerAuth !is Bt1HandshakeMessage.Auth || listenerAuth.senderRole != Bt1Role.LISTENER) {
            fail(output, Bt1ErrorCodes.SCHEMA_VIOLATION, "expected bt1_listener_auth")
        }
        val verified =
            Bt1AuthProof.verify(
                pairSecret = pairSecret,
                role = Bt1Role.LISTENER,
                nonceClient = nonceClient,
                nonceListener = nonceListener,
                clientDeviceId = localDeviceId,
                listenerDeviceId = peerDeviceId,
                trustEpoch = trustEpoch,
                proof = listenerAuth.proof,
            )
        if (!verified) {
            fail(output, Bt1ErrorCodes.AUTH_FAILED, "listener proof verification failed")
        }

        val keys = Bt1KeySchedule.derive(pairSecret, nonceClient, nonceListener)
        val channel =
            Bt1SecureChannel(
                encryptor = Bt1FrameEncryptor(keys.clientToListener),
                decryptor = Bt1FrameDecryptor(keys.listenerToClient),
            )
        keys.clientToListener.fill(0)
        keys.listenerToClient.fill(0)
        return channel
    }

    /**
     * Reads and parses the next handshake message. A listener bt1_error surfaces as a
     * [Bt1HandshakeException] carrying the listener's code; a locally detected violation
     * answers with a best-effort bt1_error before throwing.
     */
    private fun readMessage(
        input: InputStream,
        output: OutputStream,
    ): Bt1HandshakeMessage {
        val message =
            try {
                Bt1HandshakeCodec.parse(Bt1StreamFraming.readHandshakePayload(input))
            } catch (refusal: Bt1HandshakeException) {
                sendError(output, refusal.errorCode)
                throw refusal
            }
        if (message is Bt1HandshakeMessage.ChannelError) {
            // The listener already refused; answering with another error would be noise.
            throw Bt1HandshakeException(message.code, "bt1 listener refused the handshake")
        }
        return message
    }

    /** Sends a best-effort bt1_error and throws; the caller closes the socket. */
    private fun fail(
        output: OutputStream,
        code: String,
        reason: String,
    ): Nothing {
        sendError(output, code)
        throw Bt1HandshakeException(code, reason)
    }

    private fun sendError(
        output: OutputStream,
        code: String,
    ) {
        val wireCode = if (code in Bt1ErrorCodes.WIRE_CODES) code else Bt1ErrorCodes.SCHEMA_VIOLATION
        runCatching { Bt1StreamFraming.writeHandshakeFrame(output, Bt1HandshakeCodec.serializeError(wireCode)) }
    }
}

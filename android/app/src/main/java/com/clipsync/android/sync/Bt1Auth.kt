package com.clipsync.android.sync

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Sender role in the bt1 handshake: the side that dialed the stream is the client. */
enum class Bt1Role {
    CLIENT,
    LISTENER,
}

/**
 * bt1 mutual-authentication proof from docs/protocol-bt1.md section 3. Must stay
 * byte-identical with the Windows Bt1AuthProof; the shared reference vectors live in
 * protocol/bt1/fixtures/handshake/vectors.json. The "ClipSync/bt1/auth\n" domain prefix
 * keeps bt1 proofs and v1/v2 challenge proofs mutually non-replayable; the role string
 * keeps the two directions distinct.
 */
object Bt1AuthProof {
    const val SECRET_LENGTH = 32
    const val NONCE_LENGTH = 32
    const val PROOF_LENGTH = 32

    private const val UUID_LENGTH = 16
    private const val EPOCH_LENGTH = 8
    private val PREFIX = "ClipSync/bt1/auth\n".toByteArray(StandardCharsets.UTF_8)

    // The seven parameters mirror the Windows Bt1AuthProof.Compute signature one-to-one.
    @Suppress("LongParameterList")
    fun compute(
        pairSecret: ByteArray,
        role: Bt1Role,
        nonceClient: ByteArray,
        nonceListener: ByteArray,
        clientDeviceId: String,
        listenerDeviceId: String,
        trustEpoch: Long,
    ): ByteArray {
        require(pairSecret.size == SECRET_LENGTH) { "The pair secret must be exactly 32 bytes." }
        require(nonceClient.size == NONCE_LENGTH) { "The client nonce must be exactly 32 bytes." }
        require(nonceListener.size == NONCE_LENGTH) { "The listener nonce must be exactly 32 bytes." }

        val roleBytes =
            (if (role == Bt1Role.CLIENT) "client" else "listener")
                .toByteArray(StandardCharsets.UTF_8)
        val message =
            ByteBuffer.allocate(
                PREFIX.size + roleBytes.size + 1 +
                    NONCE_LENGTH + NONCE_LENGTH + UUID_LENGTH + UUID_LENGTH + EPOCH_LENGTH,
            )
        message.put(PREFIX)
        message.put(roleBytes)
        message.put(0x00)
        message.put(nonceClient)
        message.put(nonceListener)
        putUuidBigEndian(message, clientDeviceId)
        putUuidBigEndian(message, listenerDeviceId)
        message.putLong(trustEpoch)

        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(pairSecret, "HmacSHA256"))
        return mac.doFinal(message.array())
    }

    // The eight parameters mirror the Windows Bt1AuthProof.Verify signature one-to-one.
    @Suppress("LongParameterList")
    fun verify(
        pairSecret: ByteArray,
        role: Bt1Role,
        nonceClient: ByteArray,
        nonceListener: ByteArray,
        clientDeviceId: String,
        listenerDeviceId: String,
        trustEpoch: Long,
        proof: ByteArray,
    ): Boolean {
        if (proof.size != PROOF_LENGTH) {
            return false
        }
        val expected =
            compute(
                pairSecret,
                role,
                nonceClient,
                nonceListener,
                clientDeviceId,
                listenerDeviceId,
                trustEpoch,
            )
        return MessageDigest.isEqual(expected, proof)
    }

    /** RFC 4122 big-endian byte order, matching UUID_BYTES in the protocol documents. */
    private fun putUuidBigEndian(
        buffer: ByteBuffer,
        deviceId: String,
    ) {
        val uuid = UUID.fromString(deviceId)
        buffer.putLong(uuid.mostSignificantBits)
        buffer.putLong(uuid.leastSignificantBits)
    }
}

/** Per-direction AES-256-GCM session keys derived for one bt1 connection. */
class Bt1SessionKeys(
    val clientToListener: ByteArray,
    val listenerToClient: ByteArray,
)

/**
 * bt1 session-key derivation from docs/protocol-bt1.md section 4:
 * HKDF-SHA-256(ikm=pair_secret, salt=nonce_c||nonce_l, info="ClipSync/bt1/keys") expanded
 * to 64 bytes; the first half keys client-to-listener, the second half listener-to-client.
 * Must stay byte-identical with the Windows Bt1KeySchedule over the shared vectors.
 */
object Bt1KeySchedule {
    const val KEY_LENGTH = 32

    private const val HMAC_ALGORITHM = "HmacSHA256"
    private val INFO = "ClipSync/bt1/keys".toByteArray(StandardCharsets.UTF_8)

    fun derive(
        pairSecret: ByteArray,
        nonceClient: ByteArray,
        nonceListener: ByteArray,
    ): Bt1SessionKeys {
        require(pairSecret.size == Bt1AuthProof.SECRET_LENGTH) {
            "The pair secret must be exactly 32 bytes."
        }
        require(nonceClient.size == Bt1AuthProof.NONCE_LENGTH) {
            "The client nonce must be exactly 32 bytes."
        }
        require(nonceListener.size == Bt1AuthProof.NONCE_LENGTH) {
            "The listener nonce must be exactly 32 bytes."
        }

        val okm = hkdfSha256(pairSecret, nonceClient + nonceListener, INFO, KEY_LENGTH * 2)
        return Bt1SessionKeys(
            clientToListener = okm.copyOfRange(0, KEY_LENGTH),
            listenerToClient = okm.copyOfRange(KEY_LENGTH, KEY_LENGTH * 2),
        )
    }

    /** RFC 5869 extract-then-expand with HMAC-SHA-256. */
    private fun hkdfSha256(
        ikm: ByteArray,
        salt: ByteArray,
        info: ByteArray,
        length: Int,
    ): ByteArray {
        val extractMac = Mac.getInstance(HMAC_ALGORITHM)
        extractMac.init(SecretKeySpec(salt, HMAC_ALGORITHM))
        val pseudoRandomKey = extractMac.doFinal(ikm)

        val expandMac = Mac.getInstance(HMAC_ALGORITHM)
        expandMac.init(SecretKeySpec(pseudoRandomKey, HMAC_ALGORITHM))
        val output = ByteArray(length)
        var block = ByteArray(0)
        var written = 0
        var counter = 1
        while (written < length) {
            expandMac.update(block)
            expandMac.update(info)
            expandMac.update(counter.toByte())
            block = expandMac.doFinal()
            val chunk = minOf(block.size, length - written)
            block.copyInto(output, written, 0, chunk)
            written += chunk
            counter++
        }
        return output
    }
}

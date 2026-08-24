package com.clipsync.android.sync

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Pair-secret challenge-response proof from docs/protocol-v1.md section 3. Must stay
 * byte-identical with the Windows PairAuthProof; the shared reference vectors live in
 * protocol/v1/fixtures/auth/vectors.json.
 */
object PairAuthProof {
    const val SECRET_LENGTH = 32
    const val NONCE_LENGTH = 32
    const val PROOF_LENGTH = 32

    private val PREFIX = "ClipSync/v1/auth\n".toByteArray(StandardCharsets.UTF_8)

    fun compute(
        pairSecret: ByteArray,
        challengeRequestId: String,
        nonce: ByteArray,
        challengerDeviceId: String,
        responderDeviceId: String,
        trustEpoch: Long,
    ): ByteArray {
        require(pairSecret.size == SECRET_LENGTH) { "The pair secret must be exactly 32 bytes." }
        require(nonce.size == NONCE_LENGTH) { "The challenge nonce must be exactly 32 bytes." }

        // Canonical lowercase form, matching Guid.ToString("D") on Windows.
        val requestIdBytes = UUID.fromString(challengeRequestId).toString()
            .toByteArray(StandardCharsets.UTF_8)
        val message = ByteBuffer.allocate(
            PREFIX.size + requestIdBytes.size + 1 + NONCE_LENGTH + 16 + 16 + 8,
        )
        message.put(PREFIX)
        message.put(requestIdBytes)
        message.put(0x00)
        message.put(nonce)
        putUuidBigEndian(message, challengerDeviceId)
        putUuidBigEndian(message, responderDeviceId)
        message.putLong(trustEpoch)

        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(pairSecret, "HmacSHA256"))
        return mac.doFinal(message.array())
    }

    fun verify(
        pairSecret: ByteArray,
        challengeRequestId: String,
        nonce: ByteArray,
        challengerDeviceId: String,
        responderDeviceId: String,
        trustEpoch: Long,
        proof: ByteArray,
    ): Boolean {
        if (proof.size != PROOF_LENGTH) {
            return false
        }
        val expected = compute(pairSecret, challengeRequestId, nonce, challengerDeviceId, responderDeviceId, trustEpoch)
        return MessageDigest.isEqual(expected, proof)
    }

    /** RFC 4122 big-endian byte order, matching UUID_BYTES in the protocol document. */
    private fun putUuidBigEndian(buffer: ByteBuffer, deviceId: String) {
        val uuid = UUID.fromString(deviceId)
        buffer.putLong(uuid.mostSignificantBits)
        buffer.putLong(uuid.leastSignificantBits)
    }
}

/** Unpadded base64url helpers used for nonces, proofs, and pair secrets on the wire. */
object Base64Url {
    fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    /** Decodes an unpadded base64url string, or null when it is malformed or padded. */
    fun decode(value: String): ByteArray? {
        if (value.contains('=')) {
            return null
        }
        return runCatching { Base64.getUrlDecoder().decode(value) }.getOrNull()
    }

    /** Decodes exactly [expectedLength] bytes, or null. */
    fun decodeExact(value: String, expectedLength: Int): ByteArray? =
        decode(value)?.takeIf { it.size == expectedLength }
}

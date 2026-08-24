package com.clipsync.android.sync

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Pair-secret challenge-response proof from docs/protocol-v1.md section 3 and, for a v2
 * session, docs/protocol-v2.md section 3 (v2 prefix plus a `0x00 || INT64_BE(2)` suffix, so a
 * proof is only valid on the version it was computed for). Must stay byte-identical with the
 * Windows PairAuthProof; the shared reference vectors live in
 * protocol/v1/fixtures/auth/vectors.json and protocol/v2/fixtures/auth/vectors.json.
 */
object PairAuthProof {
    const val SECRET_LENGTH = 32
    const val NONCE_LENGTH = 32
    const val PROOF_LENGTH = 32

    private val PREFIX_V1 = "ClipSync/v1/auth\n".toByteArray(StandardCharsets.UTF_8)
    private val PREFIX_V2 = "ClipSync/v2/auth\n".toByteArray(StandardCharsets.UTF_8)

    fun compute(
        pairSecret: ByteArray,
        challengeRequestId: String,
        nonce: ByteArray,
        challengerDeviceId: String,
        responderDeviceId: String,
        trustEpoch: Long,
        protocolVersion: Int = 1,
    ): ByteArray {
        require(pairSecret.size == SECRET_LENGTH) { "The pair secret must be exactly 32 bytes." }
        require(nonce.size == NONCE_LENGTH) { "The challenge nonce must be exactly 32 bytes." }
        require(protocolVersion == 1 || protocolVersion == 2) { "Unknown protocol version." }

        // Canonical lowercase form, matching Guid.ToString("D") on Windows.
        val requestIdBytes = UUID.fromString(challengeRequestId).toString()
            .toByteArray(StandardCharsets.UTF_8)
        val prefix = if (protocolVersion == 2) PREFIX_V2 else PREFIX_V1
        val versionSuffixLength = if (protocolVersion == 2) 1 + 8 else 0
        val message = ByteBuffer.allocate(
            prefix.size + requestIdBytes.size + 1 + NONCE_LENGTH + 16 + 16 + 8 + versionSuffixLength,
        )
        message.put(prefix)
        message.put(requestIdBytes)
        message.put(0x00)
        message.put(nonce)
        putUuidBigEndian(message, challengerDeviceId)
        putUuidBigEndian(message, responderDeviceId)
        message.putLong(trustEpoch)
        if (protocolVersion == 2) {
            message.put(0x00)
            message.putLong(protocolVersion.toLong())
        }

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
        protocolVersion: Int = 1,
    ): Boolean {
        if (proof.size != PROOF_LENGTH) {
            return false
        }
        val expected = compute(
            pairSecret,
            challengeRequestId,
            nonce,
            challengerDeviceId,
            responderDeviceId,
            trustEpoch,
            protocolVersion,
        )
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

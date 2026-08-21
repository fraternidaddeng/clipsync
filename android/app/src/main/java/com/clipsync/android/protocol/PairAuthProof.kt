package com.clipsync.android.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Pair-secret challenge-response proof from docs/protocol-v1.md section 3.
 * Port of windows/ClipSync.Core/Security/PairAuthProof.cs. Shared vectors live in
 * protocol/v1/fixtures/auth/vectors.json.
 *
 * UUID_BYTES is RFC 4122 big-endian from the 32 hex chars of the canonical UUID,
 * not Java UUID most/least-significant-bit layout.
 */
object PairAuthProof {
    const val ALGORITHM = "hmac-sha256"
    const val SECRET_LENGTH = 32
    const val NONCE_LENGTH = 32
    const val PROOF_LENGTH = 32

    private val PREFIX_V1 = "ClipSync/v1/auth\n".toByteArray(Charsets.UTF_8)
    private val PREFIX_V2 = "ClipSync/v2/auth\n".toByteArray(Charsets.UTF_8)

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
        require(protocolVersion == 1 || protocolVersion == 2) { "Protocol version must be 1 or 2." }

        val prefix = if (protocolVersion == 2) PREFIX_V2 else PREFIX_V1
        val requestIdBytes = canonicalUuid(challengeRequestId).toByteArray(Charsets.UTF_8)
        val extra = if (protocolVersion == 2) 1 + 8 else 0
        val message = ByteArray(
            prefix.size + requestIdBytes.size + 1 + NONCE_LENGTH + 16 + 16 + 8 + extra,
        )
        var offset = 0
        prefix.copyInto(message, offset)
        offset += prefix.size
        requestIdBytes.copyInto(message, offset)
        offset += requestIdBytes.size
        message[offset] = 0x00
        offset += 1
        nonce.copyInto(message, offset)
        offset += NONCE_LENGTH
        uuidBytes(challengerDeviceId).copyInto(message, offset)
        offset += 16
        uuidBytes(responderDeviceId).copyInto(message, offset)
        offset += 16
        ByteBuffer.wrap(message, offset, 8).order(ByteOrder.BIG_ENDIAN).putLong(trustEpoch)
        offset += 8
        if (protocolVersion == 2) {
            message[offset] = 0x00
            offset += 1
            ByteBuffer.wrap(message, offset, 8).order(ByteOrder.BIG_ENDIAN).putLong(protocolVersion.toLong())
        }

        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(pairSecret, "HmacSHA256"))
        return mac.doFinal(message)
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

    fun encodeBase64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    /**
     * RFC 4122 big-endian: one byte per pair of hex digits from the canonical UUID,
     * matching C# WriteUuidBigEndian (Guid.ToString("N") then parse hex).
     */
    internal fun uuidBytes(canonical: String): ByteArray {
        val hex = canonicalUuid(canonical).replace("-", "")
        return ByteArray(16) { index ->
            hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private fun canonicalUuid(value: String): String {
        val lowered = value.lowercase()
        require(CANONICAL_UUID.matches(lowered)) { "UUID must be a canonical lowercase RFC 4122 string." }
        return lowered
    }

    private val CANONICAL_UUID = Regex(
        "^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
    )
}

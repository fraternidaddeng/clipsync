package com.clipsync.android.protocol

import java.io.File
import java.util.Base64
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PairAuthProofTest {
    @Test
    fun `algorithm constant is lowercase hmac-sha256`() {
        assertEquals("hmac-sha256", PairAuthProof.ALGORITHM)
        assertEquals("hmac-sha256", loadDocument().algorithm)
    }

    @Test
    fun `shared vector file contains at least three vectors`() {
        assertTrue(loadVectors().size >= 3)
    }

    @Test
    fun `compute reproduces every shared vector byte for byte`() {
        for (vector in loadVectors()) {
            val secret = hexToBytes(vector.pairSecretHex)
            val nonce = decodeBase64Url(vector.nonceBase64Url)
            val proof = PairAuthProof.compute(
                pairSecret = secret,
                challengeRequestId = vector.challengeRequestId,
                nonce = nonce,
                challengerDeviceId = vector.challengerDeviceId,
                responderDeviceId = vector.responderDeviceId,
                trustEpoch = vector.trustEpoch,
            )
            assertEquals(
                "proof mismatch for ${vector.name}",
                vector.proofBase64Url,
                PairAuthProof.encodeBase64Url(proof),
            )
            assertArrayEquals(
                "raw proof bytes mismatch for ${vector.name}",
                decodeBase64Url(vector.proofBase64Url),
                proof,
            )
        }
    }

    @Test
    fun `verify accepts matching proof and rejects tampered inputs`() {
        val vector = loadVectors().first()
        val secret = hexToBytes(vector.pairSecretHex)
        val nonce = decodeBase64Url(vector.nonceBase64Url)
        val proof = decodeBase64Url(vector.proofBase64Url)

        assertTrue(
            PairAuthProof.verify(
                secret,
                vector.challengeRequestId,
                nonce,
                vector.challengerDeviceId,
                vector.responderDeviceId,
                vector.trustEpoch,
                proof,
            ),
        )

        assertFalse(
            PairAuthProof.verify(
                secret,
                vector.challengeRequestId,
                nonce,
                vector.challengerDeviceId,
                vector.responderDeviceId,
                vector.trustEpoch + 1,
                proof,
            ),
        )
        assertFalse(
            PairAuthProof.verify(
                secret,
                "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaab",
                nonce,
                vector.challengerDeviceId,
                vector.responderDeviceId,
                vector.trustEpoch,
                proof,
            ),
        )
        assertFalse(
            PairAuthProof.verify(
                secret,
                vector.challengeRequestId,
                nonce,
                vector.responderDeviceId,
                vector.challengerDeviceId,
                vector.trustEpoch,
                proof,
            ),
        )

        val wrongSecret = secret.copyOf()
        wrongSecret[0] = (wrongSecret[0].toInt() xor 0x01).toByte()
        assertFalse(
            PairAuthProof.verify(
                wrongSecret,
                vector.challengeRequestId,
                nonce,
                vector.challengerDeviceId,
                vector.responderDeviceId,
                vector.trustEpoch,
                proof,
            ),
        )

        val wrongNonce = nonce.copyOf()
        wrongNonce[31] = (wrongNonce[31].toInt() xor 0x80).toByte()
        assertFalse(
            PairAuthProof.verify(
                secret,
                vector.challengeRequestId,
                wrongNonce,
                vector.challengerDeviceId,
                vector.responderDeviceId,
                vector.trustEpoch,
                proof,
            ),
        )

        assertFalse(
            PairAuthProof.verify(
                secret,
                vector.challengeRequestId,
                nonce,
                vector.challengerDeviceId,
                vector.responderDeviceId,
                vector.trustEpoch,
                proof.copyOf(16),
            ),
        )
    }

    @Test
    fun `compute rejects wrong secret or nonce length`() {
        val id = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        try {
            PairAuthProof.compute(ByteArray(16), id, ByteArray(32), id, id, 1)
            fail("expected IllegalArgumentException for short secret")
        } catch (_: IllegalArgumentException) {
        }
        try {
            PairAuthProof.compute(ByteArray(32), id, ByteArray(16), id, id, 1)
            fail("expected IllegalArgumentException for short nonce")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun `uuid bytes follow RFC 4122 hex order not Java UUID bit layout`() {
        val vector = loadVectors().first { it.name.contains("mixed") }
        val secret = hexToBytes(vector.pairSecretHex)
        val nonce = decodeBase64Url(vector.nonceBase64Url)
        val proof = PairAuthProof.compute(
            secret,
            vector.challengeRequestId,
            nonce,
            vector.challengerDeviceId,
            vector.responderDeviceId,
            vector.trustEpoch,
        )
        assertEquals(vector.proofBase64Url, PairAuthProof.encodeBase64Url(proof))
    }

    private fun loadDocument(): AuthVectorsDocument {
        val file = resolveAuthVectors()
        assertTrue("Shared auth vectors are missing: $file", file.isFile)
        return Json { ignoreUnknownKeys = true }.decodeFromString(AuthVectorsDocument.serializer(), file.readText())
    }

    private fun loadVectors(): List<AuthVector> = loadDocument().vectors

    companion object {
        private fun resolveAuthVectors(): File {
            val fromProperty = System.getProperty("protocol.fixtures.dir")
            val candidates = listOfNotNull(
                fromProperty?.let { File(it).resolve("auth").resolve("vectors.json") },
                fromProperty?.let { File(it).parentFile?.resolve("fixtures")?.resolve("auth")?.resolve("vectors.json") },
                File("protocol/v1/fixtures/auth/vectors.json"),
                File("../protocol/v1/fixtures/auth/vectors.json"),
            )
            return candidates.firstOrNull { it.isFile }
                ?: error("Cannot resolve protocol/v1/fixtures/auth/vectors.json")
        }

        private fun hexToBytes(hex: String): ByteArray =
            hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        private fun decodeBase64Url(value: String): ByteArray =
            Base64.getUrlDecoder().decode(value)
    }
}

@Serializable
private data class AuthVectorsDocument(
    val algorithm: String,
    val vectors: List<AuthVector>,
)

@Serializable
private data class AuthVector(
    val name: String,
    @SerialName("pair_secret_hex") val pairSecretHex: String,
    @SerialName("challenge_request_id") val challengeRequestId: String,
    @SerialName("nonce_base64url") val nonceBase64Url: String,
    @SerialName("challenger_device_id") val challengerDeviceId: String,
    @SerialName("responder_device_id") val responderDeviceId: String,
    @SerialName("trust_epoch") val trustEpoch: Long,
    @SerialName("proof_base64url") val proofBase64Url: String,
)

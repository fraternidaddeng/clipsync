package com.clipsync.android.sync

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The proof must be byte-identical with the Windows PairAuthProof over the shared vectors. */
class PairAuthProofTest {
    private fun vectorsFile(): File =
        File(requireNotNull(System.getProperty("protocol.fixtures.dir")), "auth/vectors.json")

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { index ->
            hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }

    @Test
    fun `all shared auth vectors produce the reference proofs`() {
        val root = Json.parseToJsonElement(vectorsFile().readText()).jsonObject
        val vectors = root.getValue("vectors").jsonArray
        assertTrue("The shared vector set must not be empty.", vectors.isNotEmpty())

        vectors.forEach { element ->
            val vector = element.jsonObject
            val secret = hexToBytes(vector.getValue("pair_secret_hex").jsonPrimitive.content)
            val nonce = requireNotNull(
                Base64Url.decodeExact(
                    vector.getValue("nonce_base64url").jsonPrimitive.content,
                    PairAuthProof.NONCE_LENGTH,
                ),
            )
            val proof = PairAuthProof.compute(
                pairSecret = secret,
                challengeRequestId = vector.getValue("challenge_request_id").jsonPrimitive.content,
                nonce = nonce,
                challengerDeviceId = vector.getValue("challenger_device_id").jsonPrimitive.content,
                responderDeviceId = vector.getValue("responder_device_id").jsonPrimitive.content,
                trustEpoch = vector.getValue("trust_epoch").jsonPrimitive.long,
            )
            assertEquals(
                "Vector failed: ${vector.getValue("name").jsonPrimitive.content}",
                vector.getValue("proof_base64url").jsonPrimitive.content,
                Base64Url.encode(proof),
            )
        }
    }

    @Test
    fun `verify accepts the right proof and rejects a tampered one`() {
        val secret = ByteArray(32) { it.toByte() }
        val nonce = ByteArray(32) { (it * 3).toByte() }
        val requestId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        val challenger = "11111111-1111-4111-8111-111111111111"
        val responder = "22222222-2222-4222-8222-222222222222"

        val proof = PairAuthProof.compute(secret, requestId, nonce, challenger, responder, 7)
        assertTrue(PairAuthProof.verify(secret, requestId, nonce, challenger, responder, 7, proof))

        val tampered = proof.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertFalse(PairAuthProof.verify(secret, requestId, nonce, challenger, responder, 7, tampered))
        assertFalse(PairAuthProof.verify(secret, requestId, nonce, challenger, responder, 8, proof))
        assertFalse(PairAuthProof.verify(secret, requestId, nonce, responder, challenger, 7, proof))
        assertFalse(PairAuthProof.verify(secret, requestId, nonce, challenger, responder, 7, proof.copyOf(31)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a wrong-size secret is rejected`() {
        PairAuthProof.compute(
            ByteArray(16),
            "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
            ByteArray(32),
            "11111111-1111-4111-8111-111111111111",
            "22222222-2222-4222-8222-222222222222",
            1,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a wrong-size nonce is rejected`() {
        PairAuthProof.compute(
            ByteArray(32),
            "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
            ByteArray(16),
            "11111111-1111-4111-8111-111111111111",
            "22222222-2222-4222-8222-222222222222",
            1,
        )
    }

    @Test
    fun `base64url helpers reject padding and wrong lengths`() {
        assertEquals(null, Base64Url.decode("AAECAw=="))
        assertEquals(null, Base64Url.decodeExact("AAECAw", 32))
        assertEquals(null, Base64Url.decode("!!!"))
    }
}

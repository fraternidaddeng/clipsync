package com.clipsync.android.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The bt1 proof and key schedule must be byte-identical with the Windows implementation
 * over the shared vectors in protocol/bt1/fixtures/handshake/vectors.json.
 */
class Bt1AuthProofTest {
    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { index ->
            hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }

    private fun bytesToHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private fun firstVectorClientProof(vector: JsonObject): ByteArray =
        requireNotNull(
            Base64Url.decodeExact(
                vector.getValue("client_proof_base64url").jsonPrimitive.content,
                Bt1AuthProof.PROOF_LENGTH,
            ),
        )

    private fun loadVectors(): List<JsonObject> {
        val file =
            File(
                requireNotNull(System.getProperty("protocol.bt1.fixtures.dir")),
                "handshake/vectors.json",
            )
        assertTrue("Shared bt1 handshake vectors are missing: $file", file.isFile)
        val root = Json.parseToJsonElement(file.readText()).jsonObject
        assertEquals("hmac-sha256 + hkdf-sha256", root.getValue("algorithm").jsonPrimitive.content)
        return root.getValue("vectors").jsonArray.map { it.jsonObject }
    }

    private fun nonce(
        vector: JsonObject,
        field: String,
    ): ByteArray =
        requireNotNull(
            Base64Url.decodeExact(
                vector.getValue(field).jsonPrimitive.content,
                Bt1AuthProof.NONCE_LENGTH,
            ),
        )

    @Test
    fun `the shared vector file contains at least three vectors`() {
        assertTrue(loadVectors().size >= 3)
    }

    @Test
    fun `compute reproduces both proofs of every shared vector`() {
        loadVectors().forEach { vector ->
            val name = vector.getValue("name").jsonPrimitive.content
            val secret = hexToBytes(vector.getValue("pair_secret_hex").jsonPrimitive.content)
            val nonceClient = nonce(vector, "nonce_client_base64url")
            val nonceListener = nonce(vector, "nonce_listener_base64url")
            val clientDeviceId = vector.getValue("client_device_id").jsonPrimitive.content
            val listenerDeviceId = vector.getValue("listener_device_id").jsonPrimitive.content
            val trustEpoch = vector.getValue("trust_epoch").jsonPrimitive.long

            val clientProof =
                Bt1AuthProof.compute(
                    secret,
                    Bt1Role.CLIENT,
                    nonceClient,
                    nonceListener,
                    clientDeviceId,
                    listenerDeviceId,
                    trustEpoch,
                )
            val listenerProof =
                Bt1AuthProof.compute(
                    secret,
                    Bt1Role.LISTENER,
                    nonceClient,
                    nonceListener,
                    clientDeviceId,
                    listenerDeviceId,
                    trustEpoch,
                )

            assertEquals(
                "client proof failed: $name",
                vector.getValue("client_proof_base64url").jsonPrimitive.content,
                Base64Url.encode(clientProof),
            )
            assertEquals(
                "listener proof failed: $name",
                vector.getValue("listener_proof_base64url").jsonPrimitive.content,
                Base64Url.encode(listenerProof),
            )
        }
    }

    @Test
    fun `verify accepts the matching proof but never for the other role`() {
        val vector = loadVectors().first()
        val secret = hexToBytes(vector.getValue("pair_secret_hex").jsonPrimitive.content)
        val nonceClient = nonce(vector, "nonce_client_base64url")
        val nonceListener = nonce(vector, "nonce_listener_base64url")
        val client = vector.getValue("client_device_id").jsonPrimitive.content
        val listener = vector.getValue("listener_device_id").jsonPrimitive.content
        val epoch = vector.getValue("trust_epoch").jsonPrimitive.long
        val proof = firstVectorClientProof(vector)

        assertTrue(
            Bt1AuthProof.verify(
                secret,
                Bt1Role.CLIENT,
                nonceClient,
                nonceListener,
                client,
                listener,
                epoch,
                proof,
            ),
        )
        // A client proof never verifies as a listener proof (reflection defense).
        assertFalse(
            Bt1AuthProof.verify(
                secret,
                Bt1Role.LISTENER,
                nonceClient,
                nonceListener,
                client,
                listener,
                epoch,
                proof,
            ),
        )
    }

    @Test
    fun `verify rejects a wrong epoch and swapped nonces or identities`() {
        val vector = loadVectors().first()
        val secret = hexToBytes(vector.getValue("pair_secret_hex").jsonPrimitive.content)
        val nonceClient = nonce(vector, "nonce_client_base64url")
        val nonceListener = nonce(vector, "nonce_listener_base64url")
        val client = vector.getValue("client_device_id").jsonPrimitive.content
        val listener = vector.getValue("listener_device_id").jsonPrimitive.content
        val epoch = vector.getValue("trust_epoch").jsonPrimitive.long
        val proof = firstVectorClientProof(vector)

        assertFalse(
            Bt1AuthProof.verify(
                secret,
                Bt1Role.CLIENT,
                nonceClient,
                nonceListener,
                client,
                listener,
                epoch + 1,
                proof,
            ),
        )
        assertFalse(
            Bt1AuthProof.verify(
                secret,
                Bt1Role.CLIENT,
                nonceListener,
                nonceClient,
                client,
                listener,
                epoch,
                proof,
            ),
        )
        assertFalse(
            Bt1AuthProof.verify(
                secret,
                Bt1Role.CLIENT,
                nonceClient,
                nonceListener,
                listener,
                client,
                epoch,
                proof,
            ),
        )
    }

    @Test
    fun `verify rejects a wrong secret and a tampered or truncated proof`() {
        val vector = loadVectors().first()
        val secret = hexToBytes(vector.getValue("pair_secret_hex").jsonPrimitive.content)
        val nonceClient = nonce(vector, "nonce_client_base64url")
        val nonceListener = nonce(vector, "nonce_listener_base64url")
        val client = vector.getValue("client_device_id").jsonPrimitive.content
        val listener = vector.getValue("listener_device_id").jsonPrimitive.content
        val epoch = vector.getValue("trust_epoch").jsonPrimitive.long
        val proof = firstVectorClientProof(vector)

        val wrongSecret = secret.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertFalse(
            Bt1AuthProof.verify(
                wrongSecret,
                Bt1Role.CLIENT,
                nonceClient,
                nonceListener,
                client,
                listener,
                epoch,
                proof,
            ),
        )

        val tampered = proof.copyOf().also { it[31] = (it[31].toInt() xor 0x80).toByte() }
        assertFalse(
            Bt1AuthProof.verify(
                secret,
                Bt1Role.CLIENT,
                nonceClient,
                nonceListener,
                client,
                listener,
                epoch,
                tampered,
            ),
        )
        assertFalse(
            Bt1AuthProof.verify(
                secret,
                Bt1Role.CLIENT,
                nonceClient,
                nonceListener,
                client,
                listener,
                epoch,
                proof.copyOf(16),
            ),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a wrong-size secret is rejected`() {
        Bt1AuthProof.compute(
            ByteArray(16),
            Bt1Role.CLIENT,
            ByteArray(32),
            ByteArray(32),
            "11111111-1111-4111-8111-111111111111",
            "22222222-2222-4222-8222-222222222222",
            1,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a wrong-size client nonce is rejected`() {
        Bt1AuthProof.compute(
            ByteArray(32),
            Bt1Role.CLIENT,
            ByteArray(16),
            ByteArray(32),
            "11111111-1111-4111-8111-111111111111",
            "22222222-2222-4222-8222-222222222222",
            1,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a wrong-size listener nonce is rejected`() {
        Bt1AuthProof.compute(
            ByteArray(32),
            Bt1Role.CLIENT,
            ByteArray(32),
            ByteArray(16),
            "11111111-1111-4111-8111-111111111111",
            "22222222-2222-4222-8222-222222222222",
            1,
        )
    }

    @Test
    fun `the key schedule reproduces both direction keys of every shared vector`() {
        loadVectors().forEach { vector ->
            val name = vector.getValue("name").jsonPrimitive.content
            val keys =
                Bt1KeySchedule.derive(
                    hexToBytes(vector.getValue("pair_secret_hex").jsonPrimitive.content),
                    nonce(vector, "nonce_client_base64url"),
                    nonce(vector, "nonce_listener_base64url"),
                )
            assertEquals(
                "client-to-listener key failed: $name",
                vector.getValue("key_client_to_listener_hex").jsonPrimitive.content,
                bytesToHex(keys.clientToListener),
            )
            assertEquals(
                "listener-to-client key failed: $name",
                vector.getValue("key_listener_to_client_hex").jsonPrimitive.content,
                bytesToHex(keys.listenerToClient),
            )
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `the key schedule rejects a wrong-size secret`() {
        Bt1KeySchedule.derive(ByteArray(16), ByteArray(32), ByteArray(32))
    }

    @Test
    fun `keys depend on both nonces and differ per direction`() {
        val vector = loadVectors().first()
        val secret = hexToBytes(vector.getValue("pair_secret_hex").jsonPrimitive.content)
        val nonceClient = nonce(vector, "nonce_client_base64url")
        val nonceListener = nonce(vector, "nonce_listener_base64url")

        val keys = Bt1KeySchedule.derive(secret, nonceClient, nonceListener)
        assertNotEquals(bytesToHex(keys.clientToListener), bytesToHex(keys.listenerToClient))

        val swapped = Bt1KeySchedule.derive(secret, nonceListener, nonceClient)
        assertNotEquals(bytesToHex(keys.clientToListener), bytesToHex(swapped.clientToListener))
    }
}

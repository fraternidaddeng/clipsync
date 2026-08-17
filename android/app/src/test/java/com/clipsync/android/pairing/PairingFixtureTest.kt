package com.clipsync.android.pairing

import java.io.File
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Consumes the shared pairing fixtures exactly like the Windows PairingFixtureTests: every
 * valid document parses under the frozen contract, every invalid one is rejected. Both
 * clients must keep failing identically on the same files.
 */
class PairingFixtureTest {
    private fun fixtureFiles(subset: String): List<File> {
        val root = File(requireNotNull(System.getProperty("protocol.fixtures.dir")))
            .resolve("pairing")
            .resolve(subset)
        assertTrue("Pairing fixture directory is missing: $root", root.isDirectory)
        val files = root.listFiles { file -> file.isFile && file.extension == "json" }?.toList().orEmpty()
        assertTrue("Pairing $subset fixture set must not be empty.", files.isNotEmpty())
        return files
    }

    /** Dispatches on the kind discriminator, then parses strictly — mirroring the C# test. */
    private fun parseByKind(text: String): Any = when (PairingJson.peekKind(text)) {
        PairingDocumentKinds.QR -> PairingJson.parseQrPayload(text)
        PairingDocumentKinds.CONFIRM_REQUEST -> PairingJson.parseConfirmRequest(text)
        PairingDocumentKinds.CONFIRM_RESPONSE -> PairingJson.parseConfirmResponse(text)
        PairingDocumentKinds.ERROR -> PairingJson.parseError(text)
        else -> throw SerializationException("unknown kind")
    }

    @Test
    fun `all shared valid pairing fixtures parse strictly`() {
        fixtureFiles("valid").forEach { fixture ->
            assertNotNull("Valid fixture was rejected: ${fixture.name}", parseByKind(fixture.readText()))
        }
    }

    @Test
    fun `all shared invalid pairing fixtures are rejected`() {
        fixtureFiles("invalid").forEach { fixture ->
            val rejected = runCatching { parseByKind(fixture.readText()) }
                .exceptionOrNull() is SerializationException
            assertTrue("Invalid fixture was accepted: ${fixture.name}", rejected)
        }
    }

    @Test
    fun `qr parser enforces limits beyond the fixtures`() {
        val valid = fixtureFiles("valid").first { it.name == "pairing-qr.json" }.readText()
        // Sanity: the pristine document parses before each mutation is tried.
        assertNotNull(PairingJson.parseQrPayload(valid))

        val mutations = listOf(
            // Nine hosts exceed the 1..8 bound.
            valid.replace(
                "\"192.168.1.23\",",
                (1..8).joinToString(separator = "") { "\"10.0.0.$it\"," },
            ),
            // Duplicate host entries are rejected.
            valid.replace("\"10.0.11.7\"", "\"192.168.1.23\""),
            // Port zero is out of range.
            valid.replace("\"port\": 47654", "\"port\": 0"),
            // Uppercase fingerprint violates the lowercase SHA-256 contract.
            valid.replace("0f9a54e310", "0F9A54E310"),
            // A null optional is not allowed anywhere in a pairing document.
            valid.replace("\"display_name\": \"DESKTOP-WIN\"", "\"display_name\": null"),
        )
        mutations.forEachIndexed { index, mutated ->
            check(mutated != valid) { "mutation $index did not change the document" }
            val rejected = runCatching { PairingJson.parseQrPayload(mutated) }
                .exceptionOrNull() is SerializationException
            assertTrue("Mutated QR payload #$index was accepted", rejected)
        }
    }

    @Test
    fun `documents above the size cap are rejected before parsing`() {
        val padding = "x".repeat(PairingJson.MAX_DOCUMENT_BYTES)
        val oversized = "{\"kind\":\"pairing_error\",\"version\":1,\"error\":\"$padding\"}"
        val rejected = runCatching { PairingJson.parseError(oversized) }
            .exceptionOrNull() is SerializationException
        assertTrue(rejected)
    }
}

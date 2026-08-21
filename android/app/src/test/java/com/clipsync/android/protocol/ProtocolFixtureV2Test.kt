package com.clipsync.android.protocol

import java.io.File
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolFixtureV2Test {
    @Test
    fun `all shared valid v2 envelope fixtures parse strictly`() {
        val fixtureFiles = validFixtures()
        assertTrue("Valid protocol v2 fixture set must not be empty.", fixtureFiles.isNotEmpty())
        fixtureFiles.forEach { fixture ->
            val envelope = ProtocolJson.parseEnvelopeV2(fixture.readText())
            assertEquals(2, envelope.version)
            assertTrue(envelope.type.isNotBlank())
        }
    }

    @Test
    fun `all shared invalid v2 envelope fixtures are rejected`() {
        val fixtureFiles = invalidFixtures()
        assertTrue("Invalid protocol v2 fixture set must not be empty.", fixtureFiles.isNotEmpty())
        val accepted = fixtureFiles.filter { fixture ->
            runCatching { ProtocolJson.parseEnvelopeV2(fixture.readText()) }.isSuccess
        }
        assertTrue(
            "Invalid v2 fixtures were accepted: ${accepted.joinToString { it.name }}",
            accepted.isEmpty(),
        )
    }

    @Test
    fun `v1 parser still rejects version 2`() {
        val hello = validFixtures().first { it.name == "hello.json" }.readText()
        val rejected = runCatching { ProtocolJson.parseEnvelope(hello) }.exceptionOrNull()
        assertTrue(rejected is SerializationException)
    }

    @Test(expected = SerializationException::class)
    fun `v2 parser rejects version 1`() {
        ProtocolJson.parseEnvelopeV2(
            """{"version":1,"type":"ping","request_id":"9b24fc53-b75f-4bd3-9b24-127025de111a","body":{"sent_at_ms":1}}""",
        )
    }

    private fun validFixtures(): List<File> =
        fixtureRoot().resolve("valid")
            .walkTopDown()
            .filter { it.isFile && it.extension == "json" }
            .toList()

    private fun invalidFixtures(): List<File> =
        fixtureRoot().resolve("invalid")
            .walkTopDown()
            .filter { it.isFile && it.extension == "json" }
            .toList()

    private fun fixtureRoot(): File {
        val fromProperty = System.getProperty("protocol.fixtures.v2.dir")
        val candidates = listOfNotNull(
            fromProperty?.let(::File),
            File("protocol/v2/fixtures"),
            File("../protocol/v2/fixtures"),
            File("../../protocol/v2/fixtures"),
        )
        val root = candidates.firstOrNull { it.isDirectory }
            ?: error("Cannot resolve protocol/v2/fixtures")
        assertTrue("Protocol v2 fixture directory is missing: $root", root.isDirectory)
        return root
    }
}

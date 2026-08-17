package com.clipsync.android.protocol

import java.io.File
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolFixtureTest {
    @Test
    fun `all shared valid envelope fixtures parse strictly`() {
        val fixtureRoot = File(requireNotNull(System.getProperty("protocol.fixtures.dir")))
        val fixtureFiles = fixtureRoot.resolve("valid")
            .walkTopDown()
            .filter { it.isFile && it.extension == "json" }
            .toList()

        assertTrue("Protocol fixture directory is missing: $fixtureRoot", fixtureRoot.isDirectory)
        assertTrue("Valid protocol fixture set must not be empty.", fixtureFiles.isNotEmpty())
        fixtureFiles.forEach { fixture ->
            val envelope = ProtocolJson.parseEnvelope(fixture.readText())
            assertEquals(1, envelope.version)
            assertTrue(envelope.type.isNotBlank())
        }
    }

    @Test
    fun `all shared invalid envelope fixtures are rejected`() {
        val fixtureRoot = File(requireNotNull(System.getProperty("protocol.fixtures.dir")))
        val fixtureFiles = fixtureRoot.resolve("invalid")
            .walkTopDown()
            .filter { it.isFile && it.extension == "json" }
            .toList()

        assertTrue("Invalid protocol fixture set must not be empty.", fixtureFiles.isNotEmpty())
        fixtureFiles.forEach { fixture ->
            val rejected = runCatching { ProtocolJson.parseEnvelope(fixture.readText()) }.isFailure
            assertTrue("Invalid fixture was accepted: ${fixture.name}", rejected)
        }
    }

    @Test(expected = SerializationException::class)
    fun `unknown envelope member is rejected`() {
        ProtocolJson.parseEnvelope(
            """{"version":1,"type":"ping","request_id":"9b24fc53-b75f-4bd3-9b24-127025de111a","body":{},"extra":true}""",
        )
    }

    @Test(expected = SerializationException::class)
    fun `wrong member casing is rejected`() {
        ProtocolJson.parseEnvelope(
            """{"Version":1,"type":"ping","request_id":"9b24fc53-b75f-4bd3-9b24-127025de111a","body":{}}""",
        )
    }

    @Test(expected = SerializationException::class)
    fun `unsupported protocol version is rejected`() {
        ProtocolJson.parseEnvelope(
            """{"version":2,"type":"ping","request_id":"9b24fc53-b75f-4bd3-9b24-127025de111a","body":{}}""",
        )
    }

    @Test(expected = SerializationException::class)
    fun `body must be an object`() {
        ProtocolJson.parseEnvelope(
            """{"version":1,"type":"ping","request_id":"9b24fc53-b75f-4bd3-9b24-127025de111a","body":[]}""",
        )
    }
}

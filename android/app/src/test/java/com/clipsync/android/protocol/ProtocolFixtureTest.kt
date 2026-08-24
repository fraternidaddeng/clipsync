package com.clipsync.android.protocol

import java.io.File
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
        val expected = loadExpectedErrors(fixtureRoot)
        fixtureFiles.forEach { fixture ->
            val error = runCatching { ProtocolJson.parseEnvelope(fixture.readText()) }.exceptionOrNull()
            assertTrue("Invalid fixture was accepted: ${fixture.name}", error != null)
            val code = when (error) {
                is ProtocolParseException -> error.errorCode
                is SerializationException -> ProtocolErrorCodes.SCHEMA_VIOLATION
                else -> error("unexpected ${error!!::class.java.name} for ${fixture.name}")
            }
            val wanted = expected[fixture.name]
            assertTrue("Missing expected_error for ${fixture.name}", wanted != null)
            assertEquals("Wrong error for ${fixture.name}", wanted, code)
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

    @Test
    fun `writer output of valid fixtures round-trips through the parser`() {
        val fixtureRoot = File(requireNotNull(System.getProperty("protocol.fixtures.dir")))
        val fixtureFiles = fixtureRoot.resolve("valid")
            .walkTopDown()
            .filter { it.isFile && it.extension == "json" }
            .toList()
        fixtureFiles.forEach { fixture ->
            val first = SyncMessages.parse(fixture.readText())
            val encoded = SyncMessageWriter.encode(first.type, first.requestId, first.body)
            val second = SyncMessages.parse(encoded)
            assertEquals(first.type, second.type)
            assertEquals(first.requestId, second.requestId)
            assertEquals(first.version, second.version)
            assertEquals(first.body, second.body)
        }
    }

    @Test(expected = SerializationException::class)
    fun `body must be an object`() {
        ProtocolJson.parseEnvelope(
            """{"version":1,"type":"ping","request_id":"9b24fc53-b75f-4bd3-9b24-127025de111a","body":[]}""",
        )
    }

    private fun loadExpectedErrors(fixtureRoot: File): Map<String, String> {
        val file = fixtureRoot.resolve("expected_errors.json")
        assertTrue("expected_errors.json is missing: $file", file.isFile)
        val obj = kotlinx.serialization.json.Json.parseToJsonElement(file.readText()).jsonObject
        return obj.mapValues { it.value.jsonPrimitive.content }
    }
}

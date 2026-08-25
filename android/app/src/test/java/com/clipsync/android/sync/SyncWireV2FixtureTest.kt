package com.clipsync.android.sync

import com.clipsync.android.protocol.ProtocolErrorCodes
import com.clipsync.android.protocol.ProtocolJson
import com.clipsync.android.protocol.ProtocolParseException
import java.io.File
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Protocol v2 mirror of [SyncWireFixtureTest]: typed decode/encode must agree with the shared
 * v2 fixtures on every message type, and every shared invalid fixture must be rejected by the
 * strict validator — the same files the Windows ProtocolReaderV2Tests consume.
 */
class SyncWireV2FixtureTest {
    private val fixturesRoot = File(requireNotNull(System.getProperty("protocol.v2.fixtures.dir")))

    @Test
    fun `every valid v2 fixture decodes to its typed body and re-encodes equivalently`() {
        val files = File(fixturesRoot, "valid").listFiles { file -> file.extension == "json" }.orEmpty()
        assertTrue("Valid v2 fixture set must not be empty.", files.isNotEmpty())

        files.forEach { file ->
            val decoded = SyncWire.decode(file.readText(), ProtocolJson.PROTOCOL_V2)
            assertEquals("Type must match the fixture name.", file.nameWithoutExtension, decoded.type)
            val reDecoded = SyncWire.decode(
                SyncWire.encode(decoded.type, decoded.requestId, decoded.body, ProtocolJson.PROTOCOL_V2),
                ProtocolJson.PROTOCOL_V2,
            )
            assertEquals("Round-trip mismatch for ${file.name}", decoded.body, reDecoded.body)
            assertEquals(decoded.requestId, reDecoded.requestId)
        }
    }

    @Test
    fun `valid v2 fixtures cover every message type including the image transfer frames`() {
        val types = File(fixturesRoot, "valid").listFiles { file -> file.extension == "json" }
            .orEmpty()
            .map { it.nameWithoutExtension }
            .toSet()
        val expected = setOf(
            SyncMessageTypes.HELLO, SyncMessageTypes.CHALLENGE, SyncMessageTypes.AUTH,
            SyncMessageTypes.KNOWN_VECTOR, SyncMessageTypes.WANT_RANGES,
            SyncMessageTypes.CLIP_ANNOUNCE, SyncMessageTypes.CLIP_FETCH,
            SyncMessageTypes.CLIP_PAYLOAD, SyncMessageTypes.CLIP_PAYLOAD_BEGIN,
            SyncMessageTypes.CLIP_PAYLOAD_CHUNK, SyncMessageTypes.CLIP_PAYLOAD_END,
            SyncMessageTypes.ACK_RANGES, SyncMessageTypes.ERROR,
            SyncMessageTypes.PING, SyncMessageTypes.PONG,
        )
        assertEquals(expected, types)
    }

    @Test
    fun `every invalid v2 fixture is rejected with the expected error code`() {
        val files = File(fixturesRoot, "invalid").listFiles { file -> file.extension == "json" }.orEmpty()
        assertTrue("Invalid v2 fixture set must not be empty.", files.isNotEmpty())

        val expected = loadExpectedErrors()
        files.forEach { file ->
            val error = runCatching {
                ProtocolJson.parseEnvelope(file.readText(), ProtocolJson.PROTOCOL_V2)
            }.exceptionOrNull()
            assertTrue("Invalid fixture must be rejected: ${file.name}", error != null)
            val code = when (error) {
                is ProtocolParseException -> error.errorCode
                is SerializationException -> ProtocolErrorCodes.SCHEMA_VIOLATION
                else -> error("unexpected ${error!!::class.java.name} for ${file.name}")
            }
            val wanted = expected[file.name]
            assertTrue("Missing expected_error for ${file.name}", wanted != null)
            assertEquals("Wrong error for ${file.name}", wanted, code)
        }
    }

    private fun loadExpectedErrors(): Map<String, String> {
        val file = File(fixturesRoot, "expected_errors.json")
        assertTrue("expected_errors.json is missing: $file", file.isFile)
        val obj = Json.parseToJsonElement(file.readText()).jsonObject
        return obj.mapValues { it.value.jsonPrimitive.content }
    }
}

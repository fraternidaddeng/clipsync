package com.clipsync.android.sync

import com.clipsync.android.protocol.ProtocolJson
import java.io.File
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
    fun `every invalid v2 fixture is rejected by the strict validator`() {
        val files = File(fixturesRoot, "invalid").listFiles { file -> file.extension == "json" }.orEmpty()
        assertTrue("Invalid v2 fixture set must not be empty.", files.isNotEmpty())

        files.forEach { file ->
            val outcome = runCatching { ProtocolJson.parseEnvelope(file.readText(), ProtocolJson.PROTOCOL_V2) }
            assertTrue("Invalid fixture must be rejected: ${file.name}", outcome.isFailure)
        }
    }
}

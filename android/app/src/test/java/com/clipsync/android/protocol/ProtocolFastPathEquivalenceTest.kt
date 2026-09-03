package com.clipsync.android.protocol

import com.clipsync.android.media.toLowerHex
import com.clipsync.android.sync.ClipPayloadChunkBody
import com.clipsync.android.sync.SyncMessageTypes
import com.clipsync.android.sync.SyncWire
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * The frame codec's hot spots were rewritten to stop regex-matching and decoding a 256 KiB
 * chunk just to size-check it, to stop copying every JSON string value into a StringBuilder,
 * and to stop `String.format`-ing digests. Each rewrite is held to the previous behaviour
 * here: the old expression is evaluated alongside as the oracle.
 */
class ProtocolFastPathEquivalenceTest {
    private val base64UrlRegex = Regex("^[A-Za-z0-9_-]+$")

    @Test
    fun `alphabet and decoded length agree with the regex and java decoder`() {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_+/= \n"
        var state = 20260903u
        repeat(20_000) {
            val length = 1 + (next(state).also { state = it } % 41u).toInt()
            val chars =
                CharArray(length) {
                    alphabet[(next(state).also { state = it } % alphabet.length.toUInt()).toInt()]
                }
            val value = String(chars)

            val regexOk = base64UrlRegex.matches(value)
            assertEquals(value, regexOk, Base64Url.isAlphabet(value))
            if (regexOk) {
                val decoded = runCatching { Base64.getUrlDecoder().decode(value) }.getOrNull()
                assertEquals(value, decoded?.size, Base64Url.decodedLength(value))
            }
        }
        assertFalse(Base64Url.isAlphabet(""))
    }

    @Test
    fun `chunk frame acceptance is unchanged`() {
        val bytes = ByteArray(1000) { (it * 31).toByte() }
        val data = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

        fun frame(
            chunkBytes: Int,
            payload: String = data,
        ): String =
            SyncWire.encode(
                SyncMessageTypes.CLIP_PAYLOAD_CHUNK,
                SyncWire.newRequestId(),
                ClipPayloadChunkBody(
                    transferId = "6f1d2c3b-4a5e-4f60-8b9c-0d1e2f3a4b5c",
                    eventId = "7a2e3d4c-5b6f-4a71-9c0d-1e2f3a4b5c6d",
                    chunkIndex = 0,
                    chunkCount = 1,
                    chunkBytes = chunkBytes,
                    data = payload,
                ),
                ProtocolJson.PROTOCOL_V2,
            )

        val accepted = ProtocolJson.parseEnvelope(frame(bytes.size), ProtocolJson.PROTOCOL_V2)
        assertEquals("clip_payload_chunk", accepted.type)

        assertRejected(frame(bytes.size + 1))
        assertRejected(frame(bytes.size - 1))
        assertRejected(frame(bytes.size, payload = data + "A"))
        assertRejected(frame(bytes.size, payload = data.dropLast(1) + "="))
        assertRejected(frame(bytes.size, payload = data.replaceRange(10, 11, "+")))
        assertRejected(frame(bytes.size, payload = ""))
    }

    @Test
    fun `utf8 byte gate matches the encoder`() {
        val samples =
            listOf(
                "",
                "ascii only",
                "字符串 mixed ünïcode",
                "emoji \uD83D\uDE00 pair",
                "lone high \uD83D end",
                "lone low \uDE00 middle x",
                "\uDE00",
                "a".repeat(10_000) + "字".repeat(1_000),
            )
        samples.forEach { text ->
            val bytes = text.toByteArray(Charsets.UTF_8).size
            assertFalse(text, ProtocolStrictJson.utf8ByteCountExceeds(text, bytes))
            assertTrue(text, ProtocolStrictJson.utf8ByteCountExceeds(text, bytes - 1))
        }
    }

    @Test
    fun `strict scan keeps rejecting what it rejected`() {
        val json = Json

        fun envelope(value: String) =
            json.encodeToString(
                kotlinx.serialization.json.JsonObject
                    .serializer(),
                buildJsonObject { put("data", JsonPrimitive(value)) },
            )

        ProtocolStrictJson.scan(envelope("plain"))
        ProtocolStrictJson.scan(envelope("escapes \" \\ / \b \u000C \n \r \t"))
        ProtocolStrictJson.scan(envelope("pair \uD83D\uDE00 ok"))
        ProtocolStrictJson.scan("{\"pair\":\"\\uD83D\\uDE00\"}")
        // An escaped high surrogate followed by a raw low one decodes to a valid pair.
        ProtocolStrictJson.scan("{\"mixed\":\"\\uD83D\uDE00\"}")
        // Duplicate detection still sees decoded names: "a\u0062" and "ab" are the same key.
        assertScanFails("{\"ab\":1,\"a\\u0062\":2}")
        assertEquals(
            "pair \uD83D\uDE00 ok",
            json
                .parseToJsonElement(envelope("pair \uD83D\uDE00 ok"))
                .jsonObject["data"]!!
                .jsonPrimitive.content,
        )

        assertScanFails("{\"lone\":\"\uD83D\"}")
        assertScanFails("{\"lone\":\"\uDE00\"}")
        assertScanFails("{\"lone\":\"\\uD83Dx\"}")
        assertScanFails("{\"lone\":\"\\uDE00\"}")
        assertScanFails("{\"lone\":\"\uD83D\uD83D\uDE00\"}")
        assertScanFails("{\"ctrl\":\"a\u0001b\"}")
        assertScanFails("{\"dup\":1,\"dup\":2}")
        assertScanFails("{\"nul\":null}")
        assertScanFails("{\"open\":\"unterminated")
        assertScanFails("{\"bad\":\"\\x\"}")
    }

    @Test
    fun `lowercase hex matches the format idiom`() {
        val bytes = ByteArray(256) { it.toByte() }
        assertEquals(bytes.joinToString(separator = "") { byte -> "%02x".format(byte) }, bytes.toLowerHex())
        assertEquals("", ByteArray(0).toLowerHex())
    }

    private fun assertRejected(frame: String) {
        val error = runCatching { ProtocolJson.parseEnvelope(frame, ProtocolJson.PROTOCOL_V2) }.exceptionOrNull()
        assertTrue("expected a SerializationException, got $error", error is SerializationException)
    }

    private fun assertScanFails(document: String) {
        val error = runCatching { ProtocolStrictJson.scan(document) }.exceptionOrNull()
        assertTrue("expected $document to be rejected", error is ProtocolParseException)
        val expectedCode =
            if (document.contains("null")) {
                ProtocolErrorCodes.SCHEMA_VIOLATION
            } else {
                ProtocolErrorCodes.MALFORMED_JSON
            }
        assertEquals(expectedCode, (error as ProtocolParseException).errorCode)
    }

    private fun next(state: UInt): UInt = state * 1664525u + 1013904223u
}

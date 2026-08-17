package com.clipsync.android.storage

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipExportTest {
    @Test
    fun `empty list encodes to empty string`() {
        assertEquals("", ClipExport.encodeJsonLines(emptyList()))
    }

    @Test
    fun `one row per line with deterministic key order`() {
        val rows = listOf(
            clip(eventId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", originSeq = 1, content = "first"),
            clip(eventId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", originSeq = 2, content = "second"),
        )

        val encoded = ClipExport.encodeJsonLines(rows)
        val lines = encoded.trimEnd('\n').lines()
        assertEquals(2, lines.size)

        lines.forEach { line ->
            val positions = ClipExport.KEYS.map { key -> line.indexOf("\"$key\"") }
            assertTrue(positions.all { it >= 0 })
            assertTrue(positions.zipWithNext().all { (left, right) -> left < right })
        }
    }

    @Test
    fun `unicode and newlines in content survive a JSON parse`() {
        val body = "你好\nsecond line\t\"quoted\""
        val encoded = ClipExport.encodeJsonLines(listOf(clip(content = body, sourceApp = null, expiresAtMs = null)))
        val parsed = Json.parseToJsonElement(encoded.trimEnd('\n')).jsonObject

        assertEquals(body, parsed["content"]!!.jsonPrimitive.content)
        assertEquals("text", parsed["kind"]!!.jsonPrimitive.content)
        assertEquals("null", parsed["source_app"].toString())
        assertEquals("null", parsed["expires_at"].toString())
        assertEquals("42", parsed["origin_seq"].toString())
        assertEquals("1700000000000", parsed["created_at"].toString())
    }

    @Test
    fun `encoder is pure and returns clipboard bodies only in the result string`() {
        val body = "user-clipboard-body-must-not-be-logged"
        val encoded = ClipExport.encodeJsonLines(listOf(clip(content = body)))

        assertTrue(encoded.contains(body))
        val methodNames = ClipExport::class.java.declaredMethods.map { it.name }
        assertFalse(methodNames.any { it.contains("log", ignoreCase = true) })
        assertEquals(0, ClipExport::class.java.declaredFields.count { it.type.name.contains("Logger", ignoreCase = true) })
    }

    private fun clip(
        eventId: String = "11111111-1111-4111-8111-111111111111",
        originDeviceId: String = "android-local",
        originSeq: Long = 42,
        content: String = "plain",
        contentHash: String = "abc",
        sourceApp: String? = "com.example",
        createdAtMs: Long = 1_700_000_000_000,
        expiresAtMs: Long? = 1_700_000_100_000,
    ): ClipEntry = ClipEntry(
        eventId = eventId,
        originDeviceId = originDeviceId,
        originSeq = originSeq,
        content = content,
        contentHash = contentHash,
        sourceApp = sourceApp,
        createdAtMs = createdAtMs,
        expiresAtMs = expiresAtMs,
    )
}

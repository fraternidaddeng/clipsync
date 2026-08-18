package com.clipsync.android.storage

import com.clipsync.android.platform.clipboard.Sha256ContentHasher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipImportTest {
    private val hasher = Sha256ContentHasher

    @Test
    fun `decodeLine restores the fields ClipExport writes`() {
        val content = "你好\nsecond line"
        val row =
            ClipEntry(
                eventId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                originDeviceId = "android-local",
                originSeq = 7,
                content = content,
                contentHash = hasher.hash(content),
                sourceApp = "com.example",
                createdAtMs = 1_700_000_000_000,
                expiresAtMs = 1_700_000_100_000,
            )
        val encoded = ClipExport.encodeJsonLines(listOf(row))
        val decoded = ClipImport.decodeLine(encoded.trimEnd('\n'))
        requireNotNull(decoded)
        assertEquals(row.eventId, decoded.eventId)
        assertEquals(row.originDeviceId, decoded.originDeviceId)
        assertEquals(row.originSeq, decoded.originSeq)
        assertEquals(row.content, decoded.content)
        assertEquals(row.contentHash, decoded.contentHash)
        assertEquals(row.sourceApp, decoded.sourceApp)
        assertEquals(row.createdAtMs, decoded.createdAtMs)
        assertEquals(row.expiresAtMs, decoded.expiresAtMs)
    }

    @Test
    fun `decodeLine skips malformed json`() {
        assertNull(ClipImport.decodeLine("{not-json"))
        assertNull(ClipImport.decodeLine("[]"))
        assertNull(ClipImport.decodeLine("""{"format":"clipsync.export"}"""))
    }

    @Test
    fun `decodeLine skips an oversized content body`() {
        val oversized = "a".repeat(MAX_CLIP_UTF8_BYTES + 1)
        val row =
            ClipEntry(
                eventId = "11111111-1111-4111-8111-111111111111",
                originDeviceId = "android-local",
                originSeq = 42,
                content = oversized,
                contentHash = hasher.hash(oversized),
                sourceApp = null,
                createdAtMs = 1_700_000_000_000,
                expiresAtMs = null,
            )
        val encoded = ClipExport.encodeJsonLines(listOf(row))
        assertNull(ClipImport.decodeLine(encoded.trimEnd('\n')))
    }

    @Test
    fun `decoder is pure and has no logger`() {
        val methodNames = ClipImport::class.java.declaredMethods.map { it.name }
        assertFalse(methodNames.any { it.contains("log", ignoreCase = true) })
        assertEquals(
            0,
            ClipImport::class.java.declaredFields.count { it.type.name.contains("Logger", ignoreCase = true) },
        )
        assertTrue(methodNames.contains("decodeLine"))
    }
}

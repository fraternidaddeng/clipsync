package com.clipsync.android.platform.clipboard.adblog

import com.clipsync.android.platform.clipboard.adblog.fixtures.ClipboardLogFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardLogParsersTest {
    @Test
    fun `aosp parser matches only its own fixtures`() {
        assertFamilyMatches(ClipboardLogRomFamily.AOSP, ClipboardLogFixtures.AOSP_MATCHED)
        assertFamilyRejects(ClipboardLogRomFamily.AOSP, ClipboardLogFixtures.AOSP_UNMATCHED)
        assertFamilyRejects(
            ClipboardLogRomFamily.AOSP,
            ClipboardLogFixtures.ONE_UI_MATCHED.map { it.line } +
                ClipboardLogFixtures.MIUI_HYPEROS_MATCHED.map { it.line } +
                ClipboardLogFixtures.COLOROS_ORIGINOS_MATCHED.map { it.line },
        )
    }

    @Test
    fun `oneui parser matches only its own fixtures`() {
        assertFamilyMatches(ClipboardLogRomFamily.ONE_UI, ClipboardLogFixtures.ONE_UI_MATCHED)
        assertFamilyRejects(ClipboardLogRomFamily.ONE_UI, ClipboardLogFixtures.ONE_UI_UNMATCHED)
        assertFamilyRejects(
            ClipboardLogRomFamily.ONE_UI,
            ClipboardLogFixtures.AOSP_MATCHED.map { it.line },
        )
    }

    @Test
    fun `miui hyperos parser matches only its own fixtures`() {
        assertFamilyMatches(ClipboardLogRomFamily.MIUI_HYPEROS, ClipboardLogFixtures.MIUI_HYPEROS_MATCHED)
        assertFamilyRejects(ClipboardLogRomFamily.MIUI_HYPEROS, ClipboardLogFixtures.MIUI_HYPEROS_UNMATCHED)
        assertFamilyRejects(
            ClipboardLogRomFamily.MIUI_HYPEROS,
            ClipboardLogFixtures.AOSP_MATCHED.map { it.line },
        )
    }

    @Test
    fun `coloros originos parser matches only its own fixtures`() {
        assertFamilyMatches(ClipboardLogRomFamily.COLOROS_ORIGINOS, ClipboardLogFixtures.COLOROS_ORIGINOS_MATCHED)
        assertFamilyRejects(ClipboardLogRomFamily.COLOROS_ORIGINOS, ClipboardLogFixtures.COLOROS_ORIGINOS_UNMATCHED)
        assertFamilyRejects(
            ClipboardLogRomFamily.COLOROS_ORIGINOS,
            ClipboardLogFixtures.AOSP_MATCHED.map { it.line },
        )
    }

    @Test
    fun `unknown format never matches any parser`() {
        for (line in ClipboardLogFixtures.UNKNOWN_UNMATCHED) {
            assertNull(line, ClipboardLogParsers.matchKnownChange(line))
            for (parser in ClipboardLogParsers.ALL) {
                assertNull("$line via ${parser.family}", parser.match(line))
            }
        }
    }

    @Test
    fun `dispatcher matches every family fixture and rejects unmatched`() {
        for (fixture in ClipboardLogFixtures.ALL_MATCHED) {
            val match = ClipboardLogParsers.matchKnownChange(fixture.line)
            assertNotNull(fixture.line, match)
            assertEquals(fixture.family, match!!.family)
            assertEquals(fixture.parserVersion, match.parserVersion)
        }
        for (line in ClipboardLogFixtures.ALL_UNMATCHED) {
            assertNull(line, ClipboardLogParsers.matchKnownChange(line))
        }
    }

    @Test
    fun `deny and payload-bearing lines never trigger`() {
        val dangerous = listOf(
            "E ClipboardService: Denying clipboard access to pkg.app, application is not in focus nor is it a system service for user 0",
            "I ClipboardService: setPrimaryClip: <redacted>",
            "I ClipboardService: setPrimaryClip clip=<redacted>",
        )
        for (line in dangerous) {
            assertNull(line, ClipboardLogParsers.matchKnownChange(line))
        }
    }

    @Test
    fun `fixtures stay anonymized and carry no clipboard body`() {
        val forbidden = listOf("password", "token", "secret", "nonce", "http://", "https://")
        val allLines = ClipboardLogFixtures.ALL_MATCHED.map { it.line } + ClipboardLogFixtures.ALL_UNMATCHED
        for (line in allLines) {
            val lower = line.lowercase()
            assertTrue(line, forbidden.none { it in lower })
            assertTrue(line, !line.contains("clip=") || line.contains("<redacted>"))
        }
    }

    private fun assertFamilyMatches(
        family: ClipboardLogRomFamily,
        fixtures: List<ClipboardLogFixtures.Fixture>,
    ) {
        val parser = ClipboardLogParsers.parserFor(family)
        for (fixture in fixtures) {
            val match = parser.match(fixture.line)
            assertNotNull(fixture.line, match)
            assertEquals(family, match!!.family)
            assertEquals(fixture.parserVersion, match.parserVersion)
        }
    }

    private fun assertFamilyRejects(family: ClipboardLogRomFamily, lines: List<String>) {
        val parser = ClipboardLogParsers.parserFor(family)
        for (line in lines) {
            assertNull("$family rejected $line", parser.match(line))
        }
    }
}

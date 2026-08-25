package com.clipsync.android.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.pow

/**
 * Charter token invariants (docs/design/tokens.md): the neighbour-hue ladder
 * follows pairing order, and the shared superellipse exponent really traces
 * |x|ⁿ + |y|ⁿ = 1.
 */
class CharterTokensTest {
    @Test
    fun `device ladder follows pairing order and wraps past five`() {
        for (colors in listOf(ClipSyncDayColors, ClipSyncNightColors)) {
            assertEquals(
                listOf(colors.dev1, colors.dev2, colors.dev3, colors.dev4, colors.dev5),
                (1..5).map { colors.device(it) },
            )
            // A sixth device restarts the ladder rather than falling off it.
            assertEquals(colors.dev1, colors.device(6))
            assertEquals(colors.dev3, colors.device(8))
        }
    }

    @Test
    fun `device box tokens keep the low-chroma recipe`() {
        // 着色底 11% day / 12% night, 描边 24% (tokens.md §4); the copied
        // colour quantises alpha to 8 bits, hence the one-step tolerance.
        val step = 1f / 255f
        assertEquals(0.11f, ClipSyncDayColors.deviceBg(2).alpha, step)
        assertEquals(0.12f, ClipSyncNightColors.deviceBg(2).alpha, step)
        assertEquals(0.24f, ClipSyncDayColors.deviceLn(4).alpha, step)
        // The tint itself is the slot colour.
        assertEquals(
            ClipSyncDayColors.dev2.copy(alpha = 0.11f),
            ClipSyncDayColors.deviceBg(2),
        )
    }

    @Test
    fun `theme crossfade blend hits both palettes exactly and mixes between`() {
        // dur-theme (tokens.md §9): the endpoints must be the canonical palettes —
        // a settled theme renders token-exact colours, never a rounding neighbour.
        assertEquals(ClipSyncDayColors, lerp(ClipSyncDayColors, ClipSyncNightColors, 0f))
        assertEquals(ClipSyncNightColors, lerp(ClipSyncDayColors, ClipSyncNightColors, 1f))

        val mid = lerp(ClipSyncDayColors, ClipSyncNightColors, 0.5f)
        // Colours really interpolate: the midpoint background sits strictly between
        // the day and night backgrounds on every channel (day is lighter throughout).
        assertTrue(mid.bg.red < ClipSyncDayColors.bg.red && mid.bg.red > ClipSyncNightColors.bg.red)
        assertTrue(mid.bg.green < ClipSyncDayColors.bg.green && mid.bg.green > ClipSyncNightColors.bg.green)
        assertTrue(mid.bg.blue < ClipSyncDayColors.bg.blue && mid.bg.blue > ClipSyncNightColors.bg.blue)
        // Grain strength glides with the palette (day 0.030 → night 0.042).
        assertEquals(0.036f, mid.grainAlpha, 1e-4f)
        // The semantic flag flips at the midpoint, never a blended non-answer.
        assertTrue(lerp(ClipSyncDayColors, ClipSyncNightColors, 0.49f).isDark.not())
        assertTrue(mid.isDark)
    }

    @Test
    fun `superellipse corner samples satisfy the shared exponent`() {
        val n = 4.4
        val unit = superellipseCornerUnit
        val samples = unit.size / 2
        assertTrue(samples >= 8)
        // Endpoints sit exactly on the edges.
        assertEquals(1f, unit[0], 1e-4f)
        assertEquals(0f, unit[1], 1e-4f)
        assertEquals(0f, unit[unit.size - 2], 1e-4f)
        assertEquals(1f, unit[unit.size - 1], 1e-4f)
        for (i in 0 until samples) {
            val x = unit[i * 2].toDouble()
            val y = unit[i * 2 + 1].toDouble()
            val residual = abs(x.pow(n) + y.pow(n) - 1.0)
            assertTrue("sample $i off the superellipse (residual $residual)", residual < 1e-4)
        }
    }
}

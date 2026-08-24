package com.clipsync.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/*
 * Charter palette: gray-blue chrome (hue 245–255, chroma ≤ .02) so the app never fights the
 * content it frames; 流动蓝 (flow blue, hue 250) is the "all good" signal and 召唤赭 (beckon
 * ochre, hue 65) its opposite — the only pair of opposite hues in use, spent on the one
 * distinction that matters: "一切正常" vs "需要你介入". Green is banned outright; red is
 * reserved for true errors (write failure, certificate change).
 */

private val LightColors = lightColorScheme(
    primary = Color(0xFF4D68B4),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE2F4),
    onPrimaryContainer = Color(0xFF1D2C55),
    secondary = Color(0xFF5A6478),
    background = Color(0xFFECEEF3),
    surface = Color(0xFFF3F4F8),
    surfaceVariant = Color(0xFFE3E6EE),
    onSurface = Color(0xFF191B21),
    onSurfaceVariant = Color(0xFF5C6270),
    outline = Color(0xFF787F8F),
    outlineVariant = Color(0xFFC6CAD6),
    error = Color(0xFFB3261E),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA8B8EE),
    onPrimary = Color(0xFF16224A),
    primaryContainer = Color(0xFF33406B),
    onPrimaryContainer = Color(0xFFDCE2F4),
    secondary = Color(0xFFB8BFCE),
    background = Color(0xFF14161B),
    surface = Color(0xFF1A1C22),
    surfaceVariant = Color(0xFF23262E),
    onSurface = Color(0xFFE3E5EA),
    onSurfaceVariant = Color(0xFFB9BEC9),
    outline = Color(0xFF848B99),
    outlineVariant = Color(0xFF3B3F4A),
    error = Color(0xFFFFB4AB),
)

/** State accents beyond the Material slots; provided by [ClipSyncTheme]. */
@Immutable
data class ConduitAccents(
    /** 召唤赭 — the only color allowed to ask for the user's hand (charter §5.5). */
    val beckon: Color,
    val beckonContainer: Color,
)

private val LightAccents = ConduitAccents(
    beckon = Color(0xFF9A6E1B),
    beckonContainer = Color(0xFFF2E4C8),
)

private val DarkAccents = ConduitAccents(
    beckon = Color(0xFFE2B25C),
    beckonContainer = Color(0xFF463414),
)

val LocalConduitAccents = staticCompositionLocalOf { LightAccents }

@Composable
fun ClipSyncTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalConduitAccents provides if (darkTheme) DarkAccents else LightAccents,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            content = content,
        )
    }
}

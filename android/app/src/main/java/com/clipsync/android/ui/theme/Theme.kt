package com.clipsync.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF146C43),
    onPrimary = Color.White,
    secondary = Color(0xFF53646F),
    background = Color(0xFFF7F8FA),
    surface = Color(0xFFF7F8FA),
    onSurface = Color(0xFF171A1C),
    onSurfaceVariant = Color(0xFF5D6469),
    outline = Color(0xFF7A858C),
    error = Color(0xFFB3261E),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF72D69E),
    onPrimary = Color(0xFF00391F),
    secondary = Color(0xFFB8C8D2),
    background = Color(0xFF111416),
    surface = Color(0xFF111416),
    onSurface = Color(0xFFE2E5E7),
    onSurfaceVariant = Color(0xFFBFC6CA),
    outline = Color(0xFF899399),
    error = Color(0xFFFFB4AB),
)

@Composable
fun ClipSyncTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}

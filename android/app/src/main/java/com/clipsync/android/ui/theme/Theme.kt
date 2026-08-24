package com.clipsync.android.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * Charter tokens beyond what Material3 roles can carry (tokens.md §11.2):
 * the five-step lightness ladder, translucent state variants, and the
 * device-neighbour hues. All values are verbatim from docs/design/tokens.md.
 */
@Immutable
data class ClipSyncColors(
    val isDark: Boolean,
    // z0 base + 178° gradient stops (top / mid / bottom)
    val bg: Color,
    val bgTop: Color,
    val bgMid: Color,
    val bgBottom: Color,
    // ladder
    val sf: Color,
    val sfUp: Color,
    val sfIn: Color,
    val sf3: Color,
    // top-light gradient start (sf-grad); ends transparent
    val sfGradTop: Color,
    // lines
    val ln: Color,
    val ln2: Color,
    // text ladder
    val t1: Color,
    val t2: Color,
    val t3: Color,
    val t4: Color,
    // state: flow blue
    val flow: Color,
    val flowBg: Color,
    val flowLn: Color,
    val onFlow: Color,
    // state: action ochre (the only colour allowed to interrupt)
    val act: Color,
    val actBg: Color,
    val actLn: Color,
    // state: error red (errors only, never "unavailable")
    val err: Color,
    val errBg: Color,
    val errLn: Color,
    // device neighbour hues, pairing order 1..5
    val dev1: Color,
    val dev2: Color,
    val dev3: Color,
    val dev4: Color,
    val dev5: Color,
    // shadow ink for elevation
    val shadow: Color,
    // film grain: tint colour + strength
    val grainTint: Color,
    val grainAlpha: Float,
)

val ClipSyncDayColors = ClipSyncColors(
    isDark = false,
    bg = Color(0xFFE2E9F2),
    bgTop = Color(0xFFE9EFF6),
    bgMid = Color(0xFFE2E9F2),
    bgBottom = Color(0xFFDAE3EE),
    sf = Color(0xFFF6F9FC),
    sfUp = Color(0xFFFDFEFF),
    sfIn = Color(0xFFDDE5EF),
    sf3 = Color(0xFFE7EDF5),
    sfGradTop = Color(0xB8FFFFFF),
    ln = Color(0xFFCFD9E6),
    ln2 = Color(0xFFBCC9D9),
    t1 = Color(0xFF1C2733),
    t2 = Color(0xFF3D4A59),
    t3 = Color(0xFF6B7A8B),
    t4 = Color(0xFF9AA7B6),
    flow = Color(0xFF215F8F),
    flowBg = Color(0x1A215F8F),
    flowLn = Color(0x3D215F8F),
    onFlow = Color(0xFFF6F9FC),
    act = Color(0xFF9B6B24),
    actBg = Color(0x1C9B6B24),
    actLn = Color(0x479B6B24),
    err = Color(0xFFA8342B),
    errBg = Color(0x17A8342B),
    errLn = Color(0x3DA8342B),
    dev1 = Color(0xFF4F8288),
    dev2 = Color(0xFF4A7A96),
    dev3 = Color(0xFF6A6F9E),
    dev4 = Color(0xFF8A6194),
    dev5 = Color(0xFF93607A),
    shadow = Color(0xFF233448),
    grainTint = Color.Black,
    grainAlpha = 0.030f,
)

val ClipSyncNightColors = ClipSyncColors(
    isDark = true,
    bg = Color(0xFF0C1116),
    bgTop = Color(0xFF121922),
    bgMid = Color(0xFF0E141B),
    bgBottom = Color(0xFF090D12),
    sf = Color(0xFF1B232E),
    sfUp = Color(0xFF242E3B),
    sfIn = Color(0xFF0F151C),
    sf3 = Color(0xFF1F2833),
    sfGradTop = Color(0x0EFFFFFF),
    ln = Color(0xFF2C3744),
    ln2 = Color(0xFF3E4A59),
    t1 = Color(0xFFE3E9F0),
    t2 = Color(0xFFC0CAD6),
    t3 = Color(0xFF8B98A8),
    t4 = Color(0xFF5F6C7C),
    flow = Color(0xFF6FA8D4),
    flowBg = Color(0x216FA8D4),
    flowLn = Color(0x4D6FA8D4),
    onFlow = Color(0xFF0C1116),
    act = Color(0xFFD9A15C),
    actBg = Color(0x1FD9A15C),
    actLn = Color(0x4DD9A15C),
    err = Color(0xFFE0776C),
    errBg = Color(0x1FE0776C),
    errLn = Color(0x47E0776C),
    dev1 = Color(0xFF81B5BC),
    dev2 = Color(0xFF81B2D0),
    dev3 = Color(0xFFA0A7D9),
    dev4 = Color(0xFFC499CF),
    dev5 = Color(0xFFCF98B3),
    shadow = Color(0xFF000000),
    grainTint = Color.White,
    grainAlpha = 0.042f,
)

val LocalClipSyncColors = staticCompositionLocalOf { ClipSyncDayColors }

val clipSyncColors: ClipSyncColors
    @Composable
    @ReadOnlyComposable
    get() = LocalClipSyncColors.current

/** Material3 role mapping per tokens.md §11.1. */
private fun m3Scheme(c: ClipSyncColors) = if (c.isDark) {
    darkColorScheme(
        primary = c.flow,
        onPrimary = c.onFlow,
        primaryContainer = c.flowBg,
        onPrimaryContainer = c.flow,
        secondary = c.t3,
        onSecondary = c.sf,
        tertiary = c.act,
        onTertiary = c.onFlow,
        tertiaryContainer = c.actBg,
        onTertiaryContainer = c.act,
        background = c.bg,
        onBackground = c.t1,
        surface = c.sf,
        onSurface = c.t1,
        surfaceVariant = c.sf3,
        onSurfaceVariant = c.t3,
        surfaceContainerLowest = c.sfIn,
        surfaceContainerLow = c.sf,
        surfaceContainer = c.sf,
        surfaceContainerHigh = c.sfUp,
        surfaceContainerHighest = c.sf,
        outline = c.ln2,
        outlineVariant = c.ln,
        error = c.err,
        onError = c.onFlow,
        errorContainer = c.errBg,
        onErrorContainer = c.err,
        surfaceTint = Color.Transparent,
        scrim = Color(0xB3000000),
    )
} else {
    lightColorScheme(
        primary = c.flow,
        onPrimary = c.onFlow,
        primaryContainer = c.flowBg,
        onPrimaryContainer = c.flow,
        secondary = c.t3,
        onSecondary = c.sf,
        tertiary = c.act,
        onTertiary = c.onFlow,
        tertiaryContainer = c.actBg,
        onTertiaryContainer = c.act,
        background = c.bg,
        onBackground = c.t1,
        surface = c.sf,
        onSurface = c.t1,
        surfaceVariant = c.sf3,
        onSurfaceVariant = c.t3,
        surfaceContainerLowest = c.sfIn,
        surfaceContainerLow = c.sf,
        surfaceContainer = c.sf,
        surfaceContainerHigh = c.sfUp,
        surfaceContainerHighest = c.sf,
        outline = c.ln2,
        outlineVariant = c.ln,
        error = c.err,
        onError = c.onFlow,
        errorContainer = c.errBg,
        onErrorContainer = c.err,
        surfaceTint = Color.Transparent,
        scrim = Color(0x66233448),
    )
}

/** Corner radii per tokens.md §7: controls 12dp, cards 16dp, screen container 28dp. */
private val ClipSyncShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * Three voices (tokens.md §6), approximated with system font families until the
 * charter typefaces (Noto Serif SC / Plus Jakarta Sans / JetBrains Mono) ship
 * with the APK. Serif is the app's own voice — at most three places per app.
 */
object ClipSyncType {
    val brand = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        letterSpacing = 0.14.em,
    )
    val pageTitle = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        letterSpacing = 0.09.em,
    )
    val sectionTitle = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
    )
    val body = TextStyle(
        fontSize = 14.sp,
        lineHeight = 21.sp,
    )
    val caption = TextStyle(
        fontSize = 12.sp,
        lineHeight = 18.sp,
    )
    val groupHeader = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 10.sp,
        letterSpacing = 0.14.em,
    )
    val meta = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
    )
    val fingerprint = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
    )
}

/**
 * z1 card face: shadow + clip + face colour + top light + 1px hairline
 * (tokens.md §8 — three nested layers collapsed into one modifier).
 */
fun Modifier.charterCard(corner: Dp = 16.dp): Modifier = composed {
    val c = LocalClipSyncColors.current
    val shape = RoundedCornerShape(corner)
    this
        .shadow(elevation = 3.dp, shape = shape, ambientColor = c.shadow, spotColor = c.shadow)
        .clip(shape)
        .background(c.sf)
        .background(Brush.verticalGradient(0f to c.sfGradTop, 0.62f to Color.Transparent))
        .border(1.dp, c.ln, shape)
}

/** z−1 sunken face for inputs and slots. */
fun Modifier.charterSunken(corner: Dp = 12.dp): Modifier = composed {
    val c = LocalClipSyncColors.current
    val shape = RoundedCornerShape(corner)
    this
        .clip(shape)
        .background(c.sfIn)
        .border(1.dp, c.ln, shape)
}

@Composable
fun ClipSyncTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // Dynamic color is deliberately disabled: the charter fixes the palette to
    // the grey-blue ladder and bans hue 100–180 outright, which Material You
    // wallpaper extraction cannot guarantee.
    val colors = if (darkTheme) ClipSyncNightColors else ClipSyncDayColors
    CompositionLocalProvider(LocalClipSyncColors provides colors) {
        MaterialTheme(
            colorScheme = m3Scheme(colors),
            shapes = ClipSyncShapes,
            content = content,
        )
    }
}

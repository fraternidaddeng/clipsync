package com.clipsync.android.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.clipsync.android.R
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

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
) {
    /**
     * The neighbour-hue ladder in pairing order (charter §3.4): assignment
     * follows the order devices were paired, never a hash, wrapping past five.
     */
    val deviceLadder: List<Color>
        get() = listOf(dev1, dev2, dev3, dev4, dev5)

    /** Device colour for a 1-based pairing slot. */
    fun device(pairingOrder: Int): Color =
        deviceLadder[(pairingOrder - 1).mod(deviceLadder.size)]

    /** 着色底 for the low-chroma source box (tokens.md §4: 11% day / 12% night). */
    fun deviceBg(pairingOrder: Int): Color =
        device(pairingOrder).copy(alpha = if (isDark) 0.12f else 0.11f)

    /** 描边 for the source box (tokens.md §4: 24%). */
    fun deviceLn(pairingOrder: Int): Color =
        device(pairingOrder).copy(alpha = 0.24f)
}

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

// ---------------------------------------------------------------------------
// Geometry (tokens.md §7)
// ---------------------------------------------------------------------------

private const val SUPERELLIPSE_EXPONENT = 4.4
private const val CORNER_STEPS = 12

/**
 * Unit quadrant of |x|ⁿ + |y|ⁿ = 1, sampled once and mirrored to all four
 * corners: index 0 lies on the edge (1, 0), the last index on (0, 1).
 */
internal val superellipseCornerUnit: FloatArray by lazy {
    val points = FloatArray((CORNER_STEPS + 1) * 2)
    val power = 2.0 / SUPERELLIPSE_EXPONENT
    for (i in 0..CORNER_STEPS) {
        val t = i * (PI / 2.0) / CORNER_STEPS
        points[i * 2] = cos(t).pow(power).toFloat()
        points[i * 2 + 1] = sin(t).pow(power).toFloat()
    }
    points
}

/**
 * Rounded rectangle whose corners follow a superellipse (n ≈ 4–5) instead of
 * circular arcs. The exponent is shared bone across both ends (charter §3.6);
 * each end picks its own radii (skin).
 */
class SuperellipseShape(private val radius: Dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val r = with(density) { radius.toPx() }
            .coerceAtMost(minOf(size.width, size.height) / 2f)
        if (r < 1f) return Outline.Rectangle(size.toRect())
        return Outline.Generic(superellipsePath(size, r))
    }
}

private fun superellipsePath(size: Size, r: Float): Path {
    val w = size.width
    val h = size.height

    fun Path.corner(cx: Float, cy: Float, sx: Float, sy: Float, reverse: Boolean) {
        val indices = if (reverse) CORNER_STEPS downTo 0 else 0..CORNER_STEPS
        for (i in indices) {
            lineTo(
                cx + sx * superellipseCornerUnit[i * 2] * r,
                cy + sy * superellipseCornerUnit[i * 2 + 1] * r,
            )
        }
    }

    return Path().apply {
        moveTo(r, 0f)
        lineTo(w - r, 0f)
        corner(cx = w - r, cy = r, sx = 1f, sy = -1f, reverse = true)
        lineTo(w, h - r)
        corner(cx = w - r, cy = h - r, sx = 1f, sy = 1f, reverse = false)
        lineTo(r, h)
        corner(cx = r, cy = h - r, sx = -1f, sy = 1f, reverse = true)
        lineTo(0f, r)
        corner(cx = r, cy = r, sx = -1f, sy = -1f, reverse = false)
        close()
    }
}

/** Charter surfaces share two superellipse radii: card 16dp, control 12dp. */
object CharterShapes {
    val card: Shape = SuperellipseShape(16.dp)
    val control: Shape = SuperellipseShape(12.dp)
}

/**
 * Corner radii per tokens.md §7 for Material components (M3 `Shapes` only
 * accepts corner-based shapes, so these stay circular; charter surfaces use
 * [CharterShapes] / [SuperellipseShape] for the shared superellipse bone).
 */
private val ClipSyncShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

// ---------------------------------------------------------------------------
// Motion (tokens.md §9)
// ---------------------------------------------------------------------------

/** Motion tokens: one easing curve (bone), Android durations 260–320ms (skin). */
object CharterMotion {
    /** cubic-bezier(.16, 1, .3, 1) — bit-identical on both ends. */
    val Ease: Easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)

    /** Android interaction transitions run 260–320ms (Windows runs 180–220ms). */
    const val DUR_QUICK_MS = 260
    const val DUR_STANDARD_MS = 300
    const val DUR_EMPHASIS_MS = 320

    /** The needs-action outline pulse period: 2.6s infinite loop. */
    const val PULSE_MS = 2600

    /** A charter-eased tween for interaction transitions. */
    fun <T> spec(durationMillis: Int = DUR_STANDARD_MS): FiniteAnimationSpec<T> =
        tween(durationMillis = durationMillis, easing = Ease)
}

// ---------------------------------------------------------------------------
// Type — the three voices (tokens.md §6), bundled in res/font
// ---------------------------------------------------------------------------

/**
 * The three voices ship with the APK so both ends render the same glyphs —
 * relying on the system would land on OEM-patched fonts (tokens.md §6 hard
 * requirement). Serif speaks for the app, Sans for content, Mono for machines.
 */
object ClipSyncFonts {
    /** Noto Serif SC 600 — brand, empty states, the pairing ritual. At most three places. */
    val serif: FontFamily = FontFamily(
        Font(R.font.noto_serif_sc_semibold, FontWeight.SemiBold),
    )

    /**
     * Noto Sans SC — 95% of the text. The charter packs Regular + Medium only,
     * so SemiBold resolves to the Medium file rather than a synthetic bold.
     */
    val sans: FontFamily = FontFamily(
        Font(R.font.noto_sans_sc_regular, FontWeight.Normal),
        Font(R.font.noto_sans_sc_medium, FontWeight.Medium),
        Font(R.font.noto_sans_sc_medium, FontWeight.SemiBold),
    )

    /** JetBrains Mono — timestamps, shortcuts, hashes, byte counts. Never for Chinese. */
    val mono: FontFamily = FontFamily(
        Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
        Font(R.font.jetbrains_mono_medium, FontWeight.Medium),
    )
}

/** The type scale of tokens.md §6, voiced by the bundled families. */
object ClipSyncType {
    val brand = TextStyle(
        fontFamily = ClipSyncFonts.serif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        letterSpacing = 0.14.em,
    )
    val pageTitle = TextStyle(
        fontFamily = ClipSyncFonts.serif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        letterSpacing = 0.09.em,
    )
    val sectionTitle = TextStyle(
        fontFamily = ClipSyncFonts.sans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
    )
    val body = TextStyle(
        fontFamily = ClipSyncFonts.sans,
        fontSize = 14.sp,
        lineHeight = 21.sp,
    )
    val caption = TextStyle(
        fontFamily = ClipSyncFonts.sans,
        fontSize = 12.sp,
        lineHeight = 18.sp,
    )
    val groupHeader = TextStyle(
        fontFamily = ClipSyncFonts.mono,
        fontSize = 10.sp,
        letterSpacing = 0.14.em,
    )
    val meta = TextStyle(
        fontFamily = ClipSyncFonts.mono,
        fontSize = 11.sp,
    )
    val fingerprint = TextStyle(
        fontFamily = ClipSyncFonts.mono,
        fontSize = 13.sp,
    )
}

/** Every Material role speaks the content voice, so no system font leaks in. */
private val ClipSyncTypography = Typography().let { base ->
    val sans = ClipSyncFonts.sans
    base.copy(
        displayLarge = base.displayLarge.copy(fontFamily = sans),
        displayMedium = base.displayMedium.copy(fontFamily = sans),
        displaySmall = base.displaySmall.copy(fontFamily = sans),
        headlineLarge = base.headlineLarge.copy(fontFamily = sans),
        headlineMedium = base.headlineMedium.copy(fontFamily = sans),
        headlineSmall = base.headlineSmall.copy(fontFamily = sans),
        titleLarge = base.titleLarge.copy(fontFamily = sans),
        titleMedium = base.titleMedium.copy(fontFamily = sans),
        titleSmall = base.titleSmall.copy(fontFamily = sans),
        bodyLarge = base.bodyLarge.copy(fontFamily = sans),
        bodyMedium = base.bodyMedium.copy(fontFamily = sans),
        bodySmall = base.bodySmall.copy(fontFamily = sans),
        labelLarge = base.labelLarge.copy(fontFamily = sans),
        labelMedium = base.labelMedium.copy(fontFamily = sans),
        labelSmall = base.labelSmall.copy(fontFamily = sans),
    )
}

// ---------------------------------------------------------------------------
// Charter surfaces (tokens.md §8)
// ---------------------------------------------------------------------------

/**
 * z1 card face: sh-1 shadow + clip + face colour + top light + 1px hairline
 * (tokens.md §8 — three nested layers collapsed into one modifier).
 */
fun Modifier.charterCard(corner: Dp = 16.dp): Modifier = composed {
    val c = LocalClipSyncColors.current
    val shape = if (corner == 16.dp) CharterShapes.card else SuperellipseShape(corner)
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
    val shape = if (corner == 12.dp) CharterShapes.control else SuperellipseShape(corner)
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
            typography = ClipSyncTypography,
            content = content,
        )
    }
}

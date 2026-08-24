package com.clipsync.android.ui.theme

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import kotlin.random.Random

private const val GRAIN_SIZE = 256

/**
 * One shared 256×256 pure-alpha noise texture (tokens.md §5). Generated once
 * at runtime instead of shipping a PNG; a fixed seed keeps it stable across
 * launches. Day and night reuse the same texture and only swap the tint.
 */
private val grainTexture: ImageBitmap by lazy {
    val random = Random(0x5EED)
    val pixels = IntArray(GRAIN_SIZE * GRAIN_SIZE) {
        (random.nextInt(256) shl 24) or 0x00FFFFFF
    }
    Bitmap.createBitmap(pixels, GRAIN_SIZE, GRAIN_SIZE, Bitmap.Config.ARGB_8888).asImageBitmap()
}

/**
 * Film grain for the app background only — never on cards, popups, or tiles.
 * Tiled at 1 texel = 1 physical pixel (drawn in raw px, never scaled),
 * tinted black at 3.0% by day and white at 4.2% by night.
 */
@Composable
fun Modifier.filmGrain(): Modifier {
    val colors = LocalClipSyncColors.current
    val brush = remember { ShaderBrush(ImageShader(grainTexture, TileMode.Repeated, TileMode.Repeated)) }
    return drawBehind {
        drawRect(
            brush = brush,
            alpha = colors.grainAlpha,
            colorFilter = ColorFilter.tint(colors.grainTint, BlendMode.SrcIn),
        )
    }
}

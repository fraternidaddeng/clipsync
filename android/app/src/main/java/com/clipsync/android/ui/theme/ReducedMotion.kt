package com.clipsync.android.ui.theme

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

/**
 * 减弱动效跟随系统（settings-roadmap P1#13）：the system's 「减弱动态效果」
 * choice is a fact the app follows, never an in-app switch (缺省即隐藏).
 * True turns the 2.6s needs-action pulse into a static stroke, freezes the
 * flow-line dots and makes the tab crossfade a cut.
 */
val LocalReducedMotion = staticCompositionLocalOf { false }

/**
 * Reads the system 「移除动画/减弱动态效果」 state — animator duration scale 0
 * ([Settings.Global.ANIMATOR_DURATION_SCALE]) — and keeps observing it, so
 * flipping the accessibility setting takes effect without an app restart.
 */
@Composable
fun rememberSystemReducedMotion(): Boolean {
    val context = LocalContext.current
    var reduced by remember { mutableStateOf(readSystemReducedMotion(context)) }
    DisposableEffect(context) {
        val observer =
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    reduced = readSystemReducedMotion(context)
                }
            }
        context.contentResolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            observer,
        )
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    return reduced
}

private fun readSystemReducedMotion(context: Context): Boolean =
    Settings.Global.getFloat(
        context.contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        1f,
    ) == 0f

package com.clipsync.android.i18n

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * A UI string that resolves against the current locale at render time (P1#16).
 *
 * ViewModels and the pure conduit builders stay free of Android resources: they emit
 * [Res] (a string resource plus format arguments) or [Raw] (already-final text such as
 * device names or error codes), and the composable resolves on the locale in effect.
 * Because resolution happens at render time, a language switch — which recreates the
 * activity via AppCompat — re-renders every deferred string without the ViewModels
 * having to recompute their state.
 */
sealed interface UiText {
    /** Text that is already final: device names, error codes, formatted numbers. */
    data class Raw(
        val value: String,
    ) : UiText

    /**
     * A string resource with positional format arguments. Arguments may themselves be
     * [UiText] and resolve recursively (e.g. a detail line embedding a route title).
     */
    data class Res(
        @StringRes val id: Int,
        val args: List<Any>,
    ) : UiText {
        constructor(@StringRes id: Int, vararg args: Any) : this(id, args.toList())
    }
}

/** Resolves against [context]'s locale; nested [UiText] arguments resolve first. */
fun UiText.resolve(context: Context): String =
    when (this) {
        is UiText.Raw -> value
        is UiText.Res -> {
            if (args.isEmpty()) {
                context.getString(id)
            } else {
                val resolved =
                    args
                        .map { argument ->
                            if (argument is UiText) argument.resolve(context) else argument
                        }.toTypedArray()
                context.getString(id, *resolved)
            }
        }
    }

/** Composable resolution on the locale in effect for the current context. */
@Composable
fun UiText.string(): String = resolve(LocalContext.current)

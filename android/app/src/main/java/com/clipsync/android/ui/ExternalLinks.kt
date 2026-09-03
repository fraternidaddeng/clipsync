package com.clipsync.android.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.clipsync.android.R
import com.clipsync.android.docs.privacyDocUrl
import java.util.Locale

/**
 * Hands [url] to the system browser. The app never opens a browser on its own initiative:
 * this runs only from an explicit tap, fetches nothing ahead of time and records nothing.
 * False when no installed app can show a web page.
 */
fun openInBrowser(
    context: Context,
    url: String,
): Boolean =
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }

/**
 * A tap handler that opens the 使用前必读 document in the UI language in effect (the
 * AppCompat per-app locale when one is chosen, else the system's), or says why it could not.
 */
@Composable
fun rememberPrivacyDocOpener(): () -> Unit {
    val context = LocalContext.current
    val languageTag =
        LocalConfiguration.current.locales
            .get(0)
            ?.toLanguageTag()
            ?: Locale.getDefault().toLanguageTag()
    return remember(context, languageTag) {
        {
            if (!openInBrowser(context, privacyDocUrl(languageTag))) {
                Toast.makeText(context, R.string.open_url_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }
}

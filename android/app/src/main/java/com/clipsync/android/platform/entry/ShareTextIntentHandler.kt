package com.clipsync.android.platform.entry

import android.content.Intent

/**
 * Pure classification of an incoming share intent, kept free of Android runtime calls so it is
 * unit-testable on the JVM (Intent.* string constants are inlined at compile time).
 */
object ShareTextIntentHandler {

    sealed interface Outcome {
        /** A usable text share; [text] is passed through unmodified (plan: 不自动修改用户内容). */
        data class ShareText(val text: String) : Outcome

        /** Not an ACTION_SEND intent at all (e.g. launched directly). */
        data object NotAShare : Outcome

        /** ACTION_SEND but not a text MIME type (image/file shares stay out of scope). */
        data object UnsupportedContent : Outcome

        /** Text share without usable EXTRA_TEXT (stream-only shares also end up here). */
        data object MissingText : Outcome
    }

    fun classify(action: String?, mimeType: String?, text: CharSequence?): Outcome {
        if (action != Intent.ACTION_SEND) {
            return Outcome.NotAShare
        }
        if (mimeType == null || !isTextMimeType(mimeType)) {
            return Outcome.UnsupportedContent
        }
        val value = text?.toString()
        if (value.isNullOrEmpty()) {
            return Outcome.MissingText
        }
        return Outcome.ShareText(value)
    }

    // The filter matches text/plain; some apps send other "text/…" subtypes with EXTRA_TEXT set.
    private fun isTextMimeType(mimeType: String): Boolean =
        mimeType == "text/plain" || mimeType.startsWith("text/")
}

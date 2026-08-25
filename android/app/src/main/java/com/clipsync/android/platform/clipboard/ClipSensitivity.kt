package com.clipsync.android.platform.clipboard

import android.content.ClipDescription

/**
 * Reads the "this clip is sensitive" marker that source apps (password managers,
 * one-time-code apps) put on their ClipDescription. The capture policy uses it for
 * 跳过敏感内容 (settings-roadmap P0-4). Honest limit: the marker only exists when the
 * source app sets it — absence proves nothing.
 */
object ClipSensitivity {
    /**
     * ClipDescription.EXTRA_IS_SENSITIVE, inlined because the SDK constant only exists on
     * API 33+ while apps have been setting the same string extra on older versions too.
     */
    const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"

    fun isMarkedSensitive(description: ClipDescription?): Boolean =
        try {
            description?.extras?.getBoolean(EXTRA_IS_SENSITIVE, false) ?: false
        } catch (_: RuntimeException) {
            false
        }
}

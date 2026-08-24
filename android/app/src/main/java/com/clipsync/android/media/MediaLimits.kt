package com.clipsync.android.media

/** Stage 9 static clipboard image limits. Shared by storage, protocol v2, and capture. */
object MediaLimits {
    const val MAX_ENCODED_BYTES = 16 * 1024 * 1024
    const val MAX_PIXELS = 32 * 1024 * 1024
    const val MAX_SIDE = 8_192
    const val MAX_CHUNK_BYTES = 256 * 1024
    const val MAX_CONCURRENT_DOWNLOADS = 2
    const val UNFINISHED_DOWNLOAD_HOURS = 24
    const val BLOB_GC_GRACE_MS = 5L * 60L * 1000L
    const val THUMBNAIL_MAX_SIDE = 512
    const val MAX_CHUNK_COUNT = 64

    const val MIME_PNG = "image/png"
    const val MIME_JPEG = "image/jpeg"
    const val KIND_IMAGE = "image"
    const val KIND_TEXT = "text"

    const val BLOB_STATE_READY = "ready"
    const val BLOB_STATE_PENDING = "pending"
    const val BLOB_STATE_FAILED = "failed"

    const val CLIP_MEDIA_READY = "ready"
    const val CLIP_MEDIA_PENDING = "pending"
    const val CLIP_MEDIA_MISSING = "missing"

    fun isSupportedMime(mime: String?): Boolean =
        mime == MIME_PNG || mime == MIME_JPEG

    fun fitsPixelBudget(width: Int, height: Int): Boolean {
        if (width !in 1..MAX_SIDE || height !in 1..MAX_SIDE) {
            return false
        }
        return width.toLong() * height.toLong() <= MAX_PIXELS
    }
}

package com.clipsync.android.platform.clipboard

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import com.clipsync.android.media.ImageCodec
import com.clipsync.android.media.ImageCodecError
import com.clipsync.android.media.MediaLimits
import java.io.InputStream

/**
 * Materializes a PNG/JPEG clipboard item into app-private bytes. URIs are never
 * stored as event bodies. Image capability is never inferred from text reads.
 */
object ClipboardMediaReader {
    fun readPreferred(context: Context, clipboard: ClipboardManager): ClipboardChange? {
        val clip = try {
            if (!clipboard.hasPrimaryClip()) {
                return null
            }
            clipboard.primaryClip
        } catch (_: SecurityException) {
            return null
        } catch (_: RuntimeException) {
            return null
        } ?: return null
        return readPreferred(context, clip)
    }

    fun readPreferred(context: Context, clip: ClipData): ClipboardChange? {
        val image = readFirstImage(context, clip)
        if (image != null) {
            return image
        }
        val text = clip.getItemAt(0).coerce { it.text?.toString() }
        if (!text.isNullOrEmpty()) {
            return ClipboardChange(
                text = text,
                contentHash = Sha256ContentHasher.hash(text),
                observedAtEpochMillis = System.currentTimeMillis(),
            )
        }
        return null
    }

    fun readFirstImage(context: Context, clip: ClipData): ClipboardChange? {
        val count = clip.itemCount
        if (count <= 0) {
            return null
        }
        for (index in 0 until count) {
            val item = clip.getItemAt(index)
            val uri = item.uri ?: continue
            val mime = clip.description.getMimeType(index)
                ?: context.contentResolver.getType(uri)
                ?: continue
            if (!MediaLimits.isSupportedMime(normalizeMime(mime))) {
                continue
            }
            val bytes = readBounded(context, uri) ?: continue
            val inspect = ImageCodec.tryInspect(bytes)
            if (inspect.first != ImageCodecError.OK || inspect.second == null) {
                continue
            }
            val image = inspect.second!!
            return ClipboardChange(
                text = "",
                contentHash = image.contentHash,
                observedAtEpochMillis = System.currentTimeMillis(),
                imageBytes = bytes,
                imageMimeType = image.mimeType,
            )
        }
        return null
    }

    fun descriptionLooksLikeImage(description: ClipDescription?): Boolean {
        if (description == null) {
            return false
        }
        for (index in 0 until description.mimeTypeCount) {
            if (MediaLimits.isSupportedMime(normalizeMime(description.getMimeType(index)))) {
                return true
            }
        }
        return false
    }

    fun readBounded(context: Context, uri: Uri): ByteArray? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                readBounded(stream)
            }
        } catch (_: SecurityException) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun readBounded(stream: InputStream): ByteArray? {
        val buffer = ByteArray(MediaLimits.MAX_ENCODED_BYTES + 1)
        var offset = 0
        while (offset < buffer.size) {
            val read = stream.read(buffer, offset, buffer.size - offset)
            if (read <= 0) {
                break
            }
            offset += read
        }
        if (offset == 0 || offset > MediaLimits.MAX_ENCODED_BYTES) {
            return null
        }
        return buffer.copyOf(offset)
    }

    private fun normalizeMime(mime: String): String = mime.lowercase().substringBefore(';').trim()

    private inline fun <T> ClipData.Item.coerce(block: (ClipData.Item) -> T?): T? =
        try {
            block(this)
        } catch (_: RuntimeException) {
            null
        }
}

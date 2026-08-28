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
        // ClipDescription mime types are a clip-level list with no per-item correspondence
        // (indexing it by item position crashes when the counts differ), so the image hint is
        // evaluated once for the whole clip and per item only via the resolver's URI type.
        val descriptionSaysImage = descriptionLooksLikeImage(clip.description)
        for (index in 0 until count) {
            val item = clip.getItemAt(index)
            val uri = item.uri ?: continue
            if (!descriptionSaysImage && !looksLikeImageMime(resolveType(context, uri))) {
                continue
            }
            val bytes = readBounded(context, uri) ?: continue
            // The magic bytes are the verdict (fail-closed): declared mimes as loose as
            // image/* or as wrong as image/jpg still materialize, while a non-PNG/JPEG body
            // behind an image-looking mime (webp, gif) is skipped no matter the declaration.
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

    /**
     * True when any declared mime is image-like. Real apps rarely declare the exact
     * `image/png`/`image/jpeg` this app stores: Chrome and gallery apps commonly write
     * `image/*` or a concrete-but-nonstandard subtype (`image/jpg`), so the hint accepts any
     * `image/...` and leaves the real PNG/JPEG check to [ImageCodec.tryInspect] on the bytes.
     */
    fun descriptionLooksLikeImage(description: ClipDescription?): Boolean {
        if (description == null) {
            return false
        }
        for (index in 0 until description.mimeTypeCount) {
            if (looksLikeImageMime(description.getMimeType(index))) {
                return true
            }
        }
        return false
    }

    /**
     * True when the clip is worth a [readFirstImage] pass: an image-like description, or any
     * URI item at all — a copied image whose description only says `text/uri-list` is still
     * found through the resolver's per-URI type (getType is one cheap IPC, no data read).
     */
    fun clipLooksLikeImage(clip: ClipData): Boolean {
        if (descriptionLooksLikeImage(clip.description)) {
            return true
        }
        for (index in 0 until clip.itemCount) {
            if (clip.getItemAt(index).coerce { it.uri } != null) {
                return true
            }
        }
        return false
    }

    private fun looksLikeImageMime(mime: String?): Boolean =
        mime != null && normalizeMime(mime).startsWith("image/")

    private fun resolveType(context: Context, uri: Uri): String? =
        try {
            context.contentResolver.getType(uri)
        } catch (_: SecurityException) {
            null
        } catch (_: RuntimeException) {
            null
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

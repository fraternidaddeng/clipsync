package com.clipsync.android.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream

/** Bounded 512 px PNG thumbs for history. Never logs or returns original blobs. */
object ImageThumbnail {
    fun ensure(store: MediaBlobStore, contentHash: String): File? {
        val dest = store.thumbnailPath(contentHash)
        if (dest.isFile && dest.length() > 0L) {
            return dest
        }
        val blob = runCatching { store.requirePath(contentHash) }.getOrNull() ?: return null
        return writeThumbnail(blob, dest)
    }

    fun decodePreview(store: MediaBlobStore, contentHash: String): Bitmap? {
        val thumb = ensure(store, contentHash) ?: return null
        return BitmapFactory.decodeFile(thumb.absolutePath)
    }

    private fun writeThumbnail(blob: File, dest: File): File? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(blob.absolutePath, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width < 1 || height < 1) {
            return null
        }
        val maxSide = MediaLimits.THUMBNAIL_MAX_SIDE
        val longest = maxOf(width, height)
        var sample = 1
        while (longest / sample > maxSide * 2) {
            sample *= 2
        }
        val decoded = BitmapFactory.decodeFile(
            blob.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return null
        val scaled = scaleToMaxSide(decoded, maxSide)
        if (scaled !== decoded) {
            decoded.recycle()
        }
        dest.parentFile?.mkdirs()
        val tmp = File(dest.path + ".part")
        return try {
            FileOutputStream(tmp).use { out ->
                if (!scaled.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    tmp.delete()
                    return null
                }
            }
            if (dest.exists()) {
                dest.delete()
            }
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
            dest.takeIf { it.isFile && it.length() > 0L }
        } catch (_: Exception) {
            tmp.delete()
            null
        } finally {
            if (!scaled.isRecycled) {
                scaled.recycle()
            }
        }
    }

    private fun scaleToMaxSide(source: Bitmap, maxSide: Int): Bitmap {
        val longest = maxOf(source.width, source.height)
        if (longest <= maxSide) {
            return source
        }
        val scale = maxSide.toFloat() / longest.toFloat()
        val width = (source.width * scale).toInt().coerceAtLeast(1)
        val height = (source.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, width, height, true)
    }
}

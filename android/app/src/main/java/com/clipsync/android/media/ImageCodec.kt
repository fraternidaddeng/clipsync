package com.clipsync.android.media

import java.io.File
import java.security.MessageDigest

enum class ImageCodecError {
    OK,
    UNSUPPORTED_MEDIA,
    TOO_LARGE,
    DECODE_FAILED,
    HASH_MISMATCH,
}

data class ValidatedImage(
    val mimeType: String,
    val contentHash: String,
    val encodedBytes: Int,
    val pixelWidth: Int,
    val pixelHeight: Int,
)

object ImageCodec {
    private val PNG_MAGIC = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )
    private val JPEG_MAGIC = byteArrayOf(0xFF.toByte(), 0xD8.toByte())

    fun hashBytes(bytes: ByteArray): String = sha256(bytes)

    fun hashFile(path: File): String = path.inputStream().use { stream ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = stream.read(buffer)
            if (read <= 0) {
                break
            }
            digest.update(buffer, 0, read)
        }
        digest.digest().toHexLower()
    }

    fun tryInspect(
        encoded: ByteArray,
        expectedHash: String? = null,
    ): Pair<ImageCodecError, ValidatedImage?> {
        if (encoded.size < 24) {
            return ImageCodecError.DECODE_FAILED to null
        }
        if (encoded.size > MediaLimits.MAX_ENCODED_BYTES) {
            return ImageCodecError.TOO_LARGE to null
        }
        val dims = tryReadDimensions(encoded) ?: return ImageCodecError.UNSUPPORTED_MEDIA to null
        if (!MediaLimits.fitsPixelBudget(dims.width, dims.height)) {
            return ImageCodecError.TOO_LARGE to null
        }
        val hash = hashBytes(encoded)
        if (expectedHash != null && expectedHash != hash) {
            return ImageCodecError.HASH_MISMATCH to null
        }
        return ImageCodecError.OK to ValidatedImage(
            mimeType = dims.mime,
            contentHash = hash,
            encodedBytes = encoded.size,
            pixelWidth = dims.width,
            pixelHeight = dims.height,
        )
    }

    fun tryInspectFile(
        path: File,
        expectedHash: String? = null,
        expectedBytes: Long? = null,
    ): Pair<ImageCodecError, ValidatedImage?> {
        if (!path.isFile) {
            return ImageCodecError.DECODE_FAILED to null
        }
        val length = path.length()
        if (length < 24) {
            return ImageCodecError.DECODE_FAILED to null
        }
        if (length > MediaLimits.MAX_ENCODED_BYTES) {
            return ImageCodecError.TOO_LARGE to null
        }
        if (expectedBytes != null && length != expectedBytes) {
            return ImageCodecError.HASH_MISMATCH to null
        }
        val headerSize = minOf(length, 64L * 1024).toInt()
        val header = ByteArray(headerSize)
        path.inputStream().use { stream ->
            var offset = 0
            while (offset < header.size) {
                val read = stream.read(header, offset, header.size - offset)
                if (read <= 0) {
                    break
                }
                offset += read
            }
        }
        var dims = tryReadDimensions(header)
        if (dims == null && length > header.size) {
            val bounded = minOf(length, 1024L * 1024).toInt()
            val buffer = ByteArray(bounded)
            path.inputStream().use { stream ->
                var offset = 0
                while (offset < buffer.size) {
                    val read = stream.read(buffer, offset, buffer.size - offset)
                    if (read <= 0) {
                        break
                    }
                    offset += read
                }
            }
            dims = tryReadDimensions(buffer)
        }
        if (dims == null) {
            return ImageCodecError.UNSUPPORTED_MEDIA to null
        }
        if (!MediaLimits.fitsPixelBudget(dims.width, dims.height)) {
            return ImageCodecError.TOO_LARGE to null
        }
        val hash = hashFile(path)
        if (expectedHash != null && expectedHash != hash) {
            return ImageCodecError.HASH_MISMATCH to null
        }
        return ImageCodecError.OK to ValidatedImage(
            mimeType = dims.mime,
            contentHash = hash,
            encodedBytes = length.toInt(),
            pixelWidth = dims.width,
            pixelHeight = dims.height,
        )
    }

    fun tryReadDimensions(encoded: ByteArray): ImageDimensions? {
        if (encoded.size >= PNG_MAGIC.size && encoded.startsWith(PNG_MAGIC)) {
            val size = tryReadPngSize(encoded) ?: return null
            return ImageDimensions(MediaLimits.MIME_PNG, size.first, size.second)
        }
        if (encoded.size >= 2 && encoded[0] == JPEG_MAGIC[0] && encoded[1] == JPEG_MAGIC[1]) {
            val size = tryReadJpegSize(encoded) ?: return null
            return ImageDimensions(MediaLimits.MIME_JPEG, size.first, size.second)
        }
        return null
    }

    data class ImageDimensions(val mime: String, val width: Int, val height: Int)

    private fun tryReadPngSize(encoded: ByteArray): Pair<Int, Int>? {
        if (encoded.size < 24) {
            return null
        }
        val length = encoded.readInt32Be(8)
        if (length != 13) {
            return null
        }
        if (encoded[12] != 'I'.code.toByte() ||
            encoded[13] != 'H'.code.toByte() ||
            encoded[14] != 'D'.code.toByte() ||
            encoded[15] != 'R'.code.toByte()
        ) {
            return null
        }
        val width = encoded.readInt32Be(16)
        val height = encoded.readInt32Be(20)
        if (width < 1 || height < 1) {
            return null
        }
        return width to height
    }

    private fun tryReadJpegSize(encoded: ByteArray): Pair<Int, Int>? {
        var offset = 2
        while (offset + 9 <= encoded.size) {
            if (encoded[offset] != 0xFF.toByte()) {
                return null
            }
            val marker = encoded[offset + 1].toInt() and 0xFF
            offset += 2
            if (marker == 0xD8 || marker == 0xD9 || marker in 0xD0..0xD7 || marker == 0x01) {
                continue
            }
            if (offset + 2 > encoded.size) {
                return null
            }
            val segmentLength = encoded.readUInt16Be(offset)
            if (segmentLength < 2) {
                return null
            }
            if (marker in SOF_MARKERS) {
                if (segmentLength < 7 || offset + 7 > encoded.size) {
                    return null
                }
                val height = encoded.readUInt16Be(offset + 3)
                val width = encoded.readUInt16Be(offset + 5)
                if (width < 1 || height < 1) {
                    return null
                }
                return width to height
            }
            offset += segmentLength
        }
        return null
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHexLower()

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) {
            return false
        }
        for (index in prefix.indices) {
            if (this[index] != prefix[index]) {
                return false
            }
        }
        return true
    }

    private fun ByteArray.readInt32Be(offset: Int): Int =
        ((this[offset].toInt() and 0xFF) shl 24) or
            ((this[offset + 1].toInt() and 0xFF) shl 16) or
            ((this[offset + 2].toInt() and 0xFF) shl 8) or
            (this[offset + 3].toInt() and 0xFF)

    private fun ByteArray.readUInt16Be(offset: Int): Int =
        ((this[offset].toInt() and 0xFF) shl 8) or (this[offset + 1].toInt() and 0xFF)

    private fun ByteArray.toHexLower(): String =
        joinToString(separator = "") { byte -> "%02x".format(byte) }

    private val SOF_MARKERS = setOf(
        0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7,
        0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF,
    )
}

package com.clipsync.android.media

import java.util.Base64

data class ImageChunk(
    val index: Int,
    val count: Int,
    val byteCount: Int,
    val data: String,
)

object ImageChunks {
    fun split(encoded: ByteArray): List<ImageChunk> {
        require(encoded.isNotEmpty() && encoded.size <= MediaLimits.MAX_ENCODED_BYTES) {
            "Encoded image size is out of bounds."
        }
        val count = (encoded.size + MediaLimits.MAX_CHUNK_BYTES - 1) / MediaLimits.MAX_CHUNK_BYTES
        require(count <= MediaLimits.MAX_CHUNK_COUNT) { "Encoded image needs too many chunks." }
        return (0 until count).map { index ->
            val start = index * MediaLimits.MAX_CHUNK_BYTES
            val length = minOf(MediaLimits.MAX_CHUNK_BYTES, encoded.size - start)
            val slice = encoded.copyOfRange(start, start + length)
            ImageChunk(
                index = index,
                count = count,
                byteCount = length,
                data = encodeBase64Url(slice),
            )
        }
    }

    fun tryDecodeChunk(data: String, expectedBytes: Int): ByteArray? {
        val decoded = try {
            decodeBase64Url(data)
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (decoded.size != expectedBytes || expectedBytes !in 1..MediaLimits.MAX_CHUNK_BYTES) {
            return null
        }
        return decoded
    }

    fun encodeBase64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun decodeBase64Url(value: String): ByteArray =
        Base64.getUrlDecoder().decode(value)
}

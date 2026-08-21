package com.clipsync.android.media

import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

class PendingMediaWrite internal constructor(
    internal val tempPath: File,
    internal val stream: FileOutputStream,
    internal val hasher: MessageDigest,
) {
    var bytesWritten: Long = 0
        internal set

    fun close() {
        runCatching { stream.close() }
    }
}

/**
 * Content-addressed PNG/JPEG blob store. Callers never concatenate blob paths;
 * every file name is a validated SHA-256 hex digest or a UUID temp name.
 */
class MediaBlobStore(rootDirectory: File) {
    private val root = rootDirectory.canonicalFile
    private val blobs = File(root, BLOBS_DIRECTORY)
    private val temps = File(root, TEMP_DIRECTORY)
    private val thumbs = File(root, THUMBS_DIRECTORY)

    init {
        blobs.mkdirs()
        temps.mkdirs()
        thumbs.mkdirs()
    }

    val rootDirectory: File get() = root

    fun beginWrite(): PendingMediaWrite {
        val tempPath = File(temps, UUID.randomUUID().toString().replace("-", "") + ".part")
        val stream = FileOutputStream(tempPath)
        return PendingMediaWrite(tempPath, stream, MessageDigest.getInstance("SHA-256"))
    }

    fun append(pending: PendingMediaWrite, chunk: ByteArray) {
        require(chunk.isNotEmpty()) { "Image chunks cannot be empty." }
        val next = pending.bytesWritten + chunk.size
        if (next > MediaLimits.MAX_ENCODED_BYTES) {
            throw MediaStoreException(MEDIA_TOO_LARGE, "Encoded image exceeds 16 MiB.")
        }
        pending.stream.write(chunk)
        pending.hasher.update(chunk)
        pending.bytesWritten = next
    }

    fun commit(
        pending: PendingMediaWrite,
        expectedHash: String? = null,
        expectedMime: String? = null,
    ): ValidatedImage {
        pending.stream.flush()
        val hash = pending.hasher.digest().joinToString("") { "%02x".format(it) }
        pending.close()
        try {
            if (expectedHash != null && expectedHash != hash) {
                throw MediaStoreException(MEDIA_HASH_MISMATCH, MEDIA_HASH_MISMATCH)
            }
            val (inspect, image) = ImageCodec.tryInspectFile(
                pending.tempPath,
                hash,
                pending.bytesWritten,
            )
            if (inspect != ImageCodecError.OK || image == null) {
                throw MediaStoreException(mapInspect(inspect), mapInspect(inspect))
            }
            if (expectedMime != null && expectedMime != image.mimeType) {
                throw MediaStoreException(UNSUPPORTED_MEDIA, UNSUPPORTED_MEDIA)
            }
            val destination = blobPath(image.contentHash)
            destination.parentFile?.mkdirs()
            if (destination.exists()) {
                pending.tempPath.delete()
                return image
            }
            if (!pending.tempPath.renameTo(destination)) {
                pending.tempPath.copyTo(destination, overwrite = false)
                pending.tempPath.delete()
            }
            return image
        } catch (error: Throwable) {
            pending.tempPath.delete()
            throw error
        }
    }

    fun commitBytes(encoded: ByteArray, expectedHash: String? = null): ValidatedImage {
        val (inspect, image) = ImageCodec.tryInspect(encoded, expectedHash)
        if (inspect != ImageCodecError.OK || image == null) {
            throw MediaStoreException(mapInspect(inspect), mapInspect(inspect))
        }
        val destination = blobPath(image.contentHash)
        if (destination.exists()) {
            return image
        }
        val pending = beginWrite()
        return try {
            append(pending, encoded)
            commit(pending, image.contentHash, image.mimeType)
        } catch (error: Throwable) {
            pending.close()
            pending.tempPath.delete()
            throw error
        }
    }

    fun exists(contentHash: String): Boolean = blobPath(contentHash).isFile

    fun requirePath(contentHash: String): File {
        val path = blobPath(contentHash)
        if (!path.isFile) {
            throw MediaStoreException(MEDIA_STORAGE_FAILED, "Media blob is missing.")
        }
        return path
    }

    fun readAllBytes(contentHash: String): ByteArray = requirePath(contentHash).readBytes()

    fun thumbnailPath(contentHash: String): File =
        File(thumbs, normalizeHash(contentHash) + ".png")

    fun deleteBlob(contentHash: String) {
        blobPath(contentHash).delete()
        thumbnailPath(contentHash).delete()
    }

    fun recoverTemps(nowMs: Long, maximumDeletes: Int = 256): Int {
        val cutoff = nowMs - MediaLimits.UNFINISHED_DOWNLOAD_HOURS * 60L * 60L * 1000L
        if (!temps.isDirectory) {
            return 0
        }
        var removed = 0
        temps.listFiles { file -> file.isFile && file.name.endsWith(".part") }?.forEach { file ->
            if (removed >= maximumDeletes) {
                return removed
            }
            if (file.lastModified() <= cutoff) {
                file.delete()
                removed++
            }
        }
        return removed
    }

    fun deleteUnreferenced(liveHashes: Collection<String>, maximumDeletes: Int = 256): Int {
        val live = liveHashes.toHashSet()
        if (!blobs.isDirectory) {
            return 0
        }
        var removed = 0
        blobs.walkTopDown().filter { it.isFile }.forEach { file ->
            if (removed >= maximumDeletes) {
                return removed
            }
            val name = file.name
            if (name.length != 64 || name in live) {
                return@forEach
            }
            file.delete()
            File(thumbs, "$name.png").delete()
            removed++
        }
        return removed
    }

    fun blobPath(contentHash: String): File = File(blobs, normalizeHash(contentHash))

    companion object {
        const val BLOBS_DIRECTORY = "blobs"
        const val TEMP_DIRECTORY = "tmp"
        const val THUMBS_DIRECTORY = "thumbs"

        const val UNSUPPORTED_MEDIA = "UNSUPPORTED_MEDIA"
        const val MEDIA_TOO_LARGE = "MEDIA_TOO_LARGE"
        const val MEDIA_DECODE_FAILED = "MEDIA_DECODE_FAILED"
        const val MEDIA_HASH_MISMATCH = "MEDIA_HASH_MISMATCH"
        const val MEDIA_STORAGE_FAILED = "MEDIA_STORAGE_FAILED"

        fun defaultRootForDatabase(databaseFile: File): File =
            File(databaseFile.parentFile ?: File("."), "media")

        private fun normalizeHash(contentHash: String): String {
            require(contentHash.length == 64 && contentHash.all { it in '0'..'9' || it in 'a'..'f' }) {
                "content_hash must be 64 lowercase hex characters."
            }
            return contentHash
        }

        private fun mapInspect(error: ImageCodecError): String = when (error) {
            ImageCodecError.TOO_LARGE -> MEDIA_TOO_LARGE
            ImageCodecError.HASH_MISMATCH -> MEDIA_HASH_MISMATCH
            ImageCodecError.UNSUPPORTED_MEDIA -> UNSUPPORTED_MEDIA
            else -> MEDIA_DECODE_FAILED
        }
    }
}

class MediaStoreException(val code: String, message: String) : Exception(message)

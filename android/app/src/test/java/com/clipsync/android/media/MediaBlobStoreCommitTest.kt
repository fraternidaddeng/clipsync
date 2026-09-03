package com.clipsync.android.media

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

/**
 * `commit` trusts the digest it accumulated while streaming instead of re-reading the file,
 * so the reported hash must still be the file's real digest and the peer-announced hash,
 * the byte count, and the container header must still be enforced.
 */
class MediaBlobStoreCommitTest {
    private lateinit var root: File
    private lateinit var store: MediaBlobStore

    @Before
    fun setUp() {
        root = createTempDirectory("clipsync-media-commit").toFile()
        store = MediaBlobStore(root)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `streamed commit reports the streamed hash and it equals the file hash`() {
        val png = fakePng(640, 360, 200_000)
        val expectedHash = ImageCodec.hashBytes(png)

        val pending = store.beginWrite()
        png.toList().chunked(7_000).forEach { chunk -> store.append(pending, chunk.toByteArray()) }
        val image = store.commit(pending, expectedHash, MediaLimits.MIME_PNG)

        assertEquals(expectedHash, image.contentHash)
        assertEquals(expectedHash, ImageCodec.hashFile(store.requirePath(expectedHash)))
        assertEquals(png.size, image.encodedBytes)
        assertEquals(640, image.pixelWidth)
        assertEquals(360, image.pixelHeight)
        assertEquals(MediaLimits.MIME_PNG, image.mimeType)
        assertTrue(File(root, MediaBlobStore.TEMP_DIRECTORY).listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `commit still rejects a hash the peer lied about`() {
        val pending = store.beginWrite()
        store.append(pending, fakePng(8, 8, 512))
        val error = runCatching { store.commit(pending, "0".repeat(64), MediaLimits.MIME_PNG) }.exceptionOrNull()
        assertTrue(error is MediaStoreException)
        assertEquals(MediaBlobStore.MEDIA_HASH_MISMATCH, (error as MediaStoreException).code)
        assertFalse(pending.tempPath.exists())
    }

    @Test
    fun `commit still rejects bytes that are not an image`() {
        val pending = store.beginWrite()
        store.append(pending, "definitely not a png or jpeg header, forty-eight bytes".toByteArray())
        val error = runCatching { store.commit(pending) }.exceptionOrNull()
        assertTrue(error is MediaStoreException)
        assertEquals(MediaBlobStore.UNSUPPORTED_MEDIA, (error as MediaStoreException).code)
        assertFalse(pending.tempPath.exists())
    }

    @Test
    fun `header inspect agrees with the full inspect without hashing`() {
        val png = fakePng(1024, 768, 50_000)
        val committed = store.commitBytes(png)
        val path = store.requirePath(committed.contentHash)

        val (headerError, header) = ImageCodec.tryInspectFileHeader(path)
        val (fullError, full) = ImageCodec.tryInspectFile(path)
        assertEquals(ImageCodecError.OK, headerError)
        assertEquals(ImageCodecError.OK, fullError)
        assertNotNull(header)
        assertNotNull(full)
        assertEquals(full!!.mimeType, header!!.mimeType)
        assertEquals(full.encodedBytes.toLong(), header.encodedBytes)
        assertEquals(full.pixelWidth, header.pixelWidth)
        assertEquals(full.pixelHeight, header.pixelHeight)

        val (lengthMismatch, none) = ImageCodec.tryInspectFileHeader(path, expectedBytes = png.size + 1L)
        assertEquals(ImageCodecError.HASH_MISMATCH, lengthMismatch)
        assertNull(none)
        assertEquals(ImageCodecError.DECODE_FAILED, ImageCodec.tryInspectFileHeader(File(root, "missing")).first)
    }

    /** PNG magic + IHDR over deterministic filler: enough for header/dimension/hash paths. */
    private fun fakePng(
        width: Int,
        height: Int,
        size: Int,
    ): ByteArray {
        val bytes = ByteArray(size) { (it * 131 + 7).toByte() }
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A).copyInto(bytes)
        writeInt32Be(bytes, 8, 13)
        "IHDR".toByteArray(Charsets.US_ASCII).copyInto(bytes, 12)
        writeInt32Be(bytes, 16, width)
        writeInt32Be(bytes, 20, height)
        return bytes
    }

    private fun writeInt32Be(
        bytes: ByteArray,
        offset: Int,
        value: Int,
    ) {
        bytes[offset] = (value ushr 24).toByte()
        bytes[offset + 1] = (value ushr 16).toByte()
        bytes[offset + 2] = (value ushr 8).toByte()
        bytes[offset + 3] = value.toByte()
    }
}

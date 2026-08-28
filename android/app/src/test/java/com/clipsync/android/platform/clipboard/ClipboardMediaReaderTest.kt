package com.clipsync.android.platform.clipboard

import android.content.ClipData
import android.content.ClipDescription
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.clipsync.android.media.MediaLimits
import java.io.ByteArrayInputStream
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

/**
 * The MIME shapes real apps actually put on the clipboard (2026-08-28 debug round): Chrome
 * and gallery apps declare the `image/` wildcard or nonstandard subtypes rather than the
 * exact `image/png`/`image/jpeg`, file managers declare only `text/uri-list`, and the
 * ClipDescription mime list has no per-item correspondence at all — indexing it by item
 * position crashed on real clips. The reader's verdict must come from the bytes
 * (ImageCodec magic sniffing, fail-closed), with mimes only as a cheap read hint.
 */
@RunWith(RobolectricTestRunner::class)
class ClipboardMediaReaderTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val fixturesRoot = File(requireNotNull(System.getProperty("protocol.v2.fixtures.dir")))
    private val png: ByteArray by lazy { File(fixturesRoot, "media/png-8x8.png").readBytes() }
    private val jpeg: ByteArray by lazy { File(fixturesRoot, "media/jpeg-1x1.jpg").readBytes() }

    @Before
    fun registerTypeProvider() {
        StaticTypeProvider.types.clear()
        Robolectric.setupContentProvider(StaticTypeProvider::class.java, AUTHORITY)
    }

    private fun serveBytes(uri: Uri, bytes: ByteArray, resolverType: String? = null) {
        Shadows.shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream(bytes))
        if (resolverType != null) {
            StaticTypeProvider.types[uri.toString()] = resolverType
        }
    }

    @Test
    fun `image-star description still materializes the png`() {
        val uri = Uri.parse("content://$AUTHORITY/star.png")
        serveBytes(uri, png)
        val clip = ClipData(ClipDescription("img", arrayOf("image/*")), ClipData.Item(uri))

        val change = ClipboardMediaReader.readFirstImage(context, clip)

        assertNotNull(change)
        assertEquals(MediaLimits.MIME_PNG, change!!.imageMimeType)
        assertArrayEquals(png, change.imageBytes)
    }

    @Test
    fun `nonstandard image-jpg description is corrected by the bytes`() {
        val uri = Uri.parse("content://$AUTHORITY/photo")
        serveBytes(uri, jpeg)
        val clip = ClipData(ClipDescription("img", arrayOf("image/jpg")), ClipData.Item(uri))

        val change = ClipboardMediaReader.readFirstImage(context, clip)

        assertNotNull(change)
        assertEquals(MediaLimits.MIME_JPEG, change!!.imageMimeType)
    }

    @Test
    fun `uri-list description falls back to the resolver's per-uri type`() {
        val uri = Uri.parse("content://$AUTHORITY/copied-file")
        serveBytes(uri, jpeg, resolverType = MediaLimits.MIME_JPEG)
        val clip = ClipData(ClipDescription("files", arrayOf("text/uri-list")), ClipData.Item(uri))

        val change = ClipboardMediaReader.readFirstImage(context, clip)

        assertNotNull(change)
        assertEquals(MediaLimits.MIME_JPEG, change!!.imageMimeType)
        assertArrayEquals(jpeg, change.imageBytes)
    }

    @Test
    fun `more items than description mimes does not crash and still finds the image`() {
        // Real clips carry one description mime list for N items; the old per-item
        // description index threw IndexOutOfBoundsException on exactly this shape.
        val uri = Uri.parse("content://$AUTHORITY/second-item.png")
        serveBytes(uri, png)
        val clip = ClipData(ClipDescription("mixed", arrayOf("image/png")), ClipData.Item("caption text"))
        clip.addItem(ClipData.Item(uri))

        val change = ClipboardMediaReader.readFirstImage(context, clip)

        assertNotNull(change)
        assertEquals(MediaLimits.MIME_PNG, change!!.imageMimeType)
    }

    @Test
    fun `image-declared bytes that are not png or jpeg are skipped fail-closed`() {
        val uri = Uri.parse("content://$AUTHORITY/fake.webp")
        serveBytes(uri, ByteArray(64) { 0x41 })
        val clip = ClipData(ClipDescription("img", arrayOf("image/*")), ClipData.Item(uri))

        assertNull(ClipboardMediaReader.readFirstImage(context, clip))
    }

    @Test
    fun `non-image uri without an image-like resolver type is never read`() {
        val uri = Uri.parse("content://$AUTHORITY/document.pdf")
        serveBytes(uri, png, resolverType = "application/pdf")
        val clip = ClipData(ClipDescription("files", arrayOf("text/uri-list")), ClipData.Item(uri))

        assertNull(ClipboardMediaReader.readFirstImage(context, clip))
    }

    @Test
    fun `description hint accepts any image subtype and normalizes parameters`() {
        assertTrue(ClipboardMediaReader.descriptionLooksLikeImage(ClipDescription("x", arrayOf("image/*"))))
        assertTrue(ClipboardMediaReader.descriptionLooksLikeImage(ClipDescription("x", arrayOf("IMAGE/PNG; charset=binary"))))
        assertTrue(ClipboardMediaReader.descriptionLooksLikeImage(ClipDescription("x", arrayOf("text/plain", "image/jpeg"))))
        assertFalse(ClipboardMediaReader.descriptionLooksLikeImage(ClipDescription("x", arrayOf("text/plain"))))
        assertFalse(ClipboardMediaReader.descriptionLooksLikeImage(null))
    }

    @Test
    fun `clip hint sees uri items even when the description says nothing image-like`() {
        val uri = Uri.parse("content://$AUTHORITY/anything")
        val withUri = ClipData(ClipDescription("files", arrayOf("text/uri-list")), ClipData.Item(uri))
        val textOnly = ClipData.newPlainText("t", "just text")

        assertTrue(ClipboardMediaReader.clipLooksLikeImage(withUri))
        assertFalse(ClipboardMediaReader.clipLooksLikeImage(textOnly))
    }

    /** Serves [types] for getType; bytes come from the shadow resolver's registered streams. */
    class StaticTypeProvider : ContentProvider() {
        override fun onCreate(): Boolean = true

        override fun getType(uri: Uri): String? = types[uri.toString()]

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): android.database.Cursor? = null

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = 0

        companion object {
            val types = mutableMapOf<String, String>()
        }
    }

    private companion object {
        const val AUTHORITY = "clipsync.test.media"
    }
}

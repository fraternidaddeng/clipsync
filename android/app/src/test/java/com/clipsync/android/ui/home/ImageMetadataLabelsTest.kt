package com.clipsync.android.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The machine-voice pill labels (user verdict 2026-08-26, mirror of the Windows
 * ImageMetadataTests): quiet annotations under the thumbnail hero, and unknown
 * facts yield an empty label so the pill hides instead of showing "?".
 */
class ImageMetadataLabelsTest {
    @Test
    fun `format label is the uppercased mime subtype`() {
        assertEquals("PNG", ImageMetadataLabels.format("image/png"))
        assertEquals("JPEG", ImageMetadataLabels.format("image/jpeg"))
        assertEquals("PNG", ImageMetadataLabels.format("png"))
        assertEquals("", ImageMetadataLabels.format(null))
        assertEquals("", ImageMetadataLabels.format(""))
        assertEquals("", ImageMetadataLabels.format("   "))
    }

    @Test
    fun `dimensions require both sides`() {
        assertEquals("320×200", ImageMetadataLabels.dimensions(320, 200))
        assertEquals("", ImageMetadataLabels.dimensions(null, 200))
        assertEquals("", ImageMetadataLabels.dimensions(320, null))
    }

    @Test
    fun `byte size uses binary units with at most one decimal`() {
        assertEquals("96 B", ImageMetadataLabels.byteSize(96))
        assertEquals("1023 B", ImageMetadataLabels.byteSize(1023))
        assertEquals("1 KiB", ImageMetadataLabels.byteSize(1024))
        assertEquals("2 KiB", ImageMetadataLabels.byteSize(2048))
        assertEquals("2.3 KiB", ImageMetadataLabels.byteSize(2400))
        assertEquals("1 MiB", ImageMetadataLabels.byteSize(1024 * 1024))
        assertEquals("16 MiB", ImageMetadataLabels.byteSize(16 * 1024 * 1024))
        assertEquals("", ImageMetadataLabels.byteSize(null))
    }
}

package com.clipsync.android.platform.clipboard

import org.junit.Assert.assertFalse
import org.junit.Test

class CapabilityReportTest {
    @Test
    fun `capability report exposes status metadata but no clipboard content field`() {
        val propertyNames = CapabilityReport::class.java.declaredFields.map { it.name.lowercase() }

        assertFalse(propertyNames.any { "text" in it || "content" in it || "payload" in it })
    }
}

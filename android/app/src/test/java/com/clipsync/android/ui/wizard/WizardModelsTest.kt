package com.clipsync.android.ui.wizard

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId

class WizardModelsTest {
    @Test
    fun `formatLastCheckClock is HH colon mm in the given zone`() {
        val epoch = 1_700_000_000_000L
        assertEquals("22:13", formatLastCheckClock(epoch, ZoneId.of("UTC")))
        assertEquals("17:13", formatLastCheckClock(epoch, ZoneId.of("America/New_York")))
    }
}

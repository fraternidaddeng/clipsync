package com.clipsync.android.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ConduitBandCopyTest {
    @Test
    fun `unpaired needs-action still says not paired`() {
        val copy = ConduitBandCopy.of(needsAction = true, allReady = false, paired = false)
        assertEquals(ConduitBandTone.BLOCKED, copy.tone)
        assertEquals(ConduitBandSubtitle.BLOCKED_UNPAIRED, copy.subtitle)
    }

    @Test
    fun `paired needs-action does not claim the device is unpaired`() {
        val copy = ConduitBandCopy.of(needsAction = true, allReady = false, paired = true)
        assertEquals(ConduitBandTone.BLOCKED, copy.tone)
        assertEquals(ConduitBandSubtitle.BLOCKED_PAIRED, copy.subtitle)
    }

    @Test
    fun `all ready is the clear-path sentence`() {
        val copy = ConduitBandCopy.of(needsAction = false, allReady = true, paired = true)
        assertEquals(ConduitBandTone.READY, copy.tone)
        assertEquals(ConduitBandSubtitle.READY, copy.subtitle)
    }

    @Test
    fun `degraded without a beckon is the partial sentence`() {
        val copy = ConduitBandCopy.of(needsAction = false, allReady = false, paired = true)
        assertEquals(ConduitBandTone.PARTIAL, copy.tone)
        assertEquals(ConduitBandSubtitle.PARTIAL, copy.subtitle)
    }
}

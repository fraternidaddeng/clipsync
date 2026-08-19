package com.clipsync.android.ui.wizard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class WizardModelsTest {
    @Test
    fun `formatLastCheckClock is HH colon mm in the given zone`() {
        val epoch = 1_700_000_000_000L
        assertEquals("22:13", formatLastCheckClock(epoch, ZoneId.of("UTC")))
        assertEquals("17:13", formatLastCheckClock(epoch, ZoneId.of("America/New_York")))
    }

    @Test
    fun `privileged host cards do not open the official manager package`() {
        assertEquals(
            WizardActionKind.START_PRIVILEGED_HOST,
            actionKindFor(WizardStepId.SHIZUKU_BINDER),
        )
        assertEquals(
            WizardActionKind.AUTHORIZE_PRIVILEGED_HOST,
            actionKindFor(WizardStepId.SHIZUKU_AUTH),
        )
        assertTrue(offersInAppGrant(WizardStepId.SHIZUKU_AUTH))
        assertFalse(offersInAppGrant(WizardStepId.SHIZUKU_BINDER))
    }
}

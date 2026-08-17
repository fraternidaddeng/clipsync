package com.clipsync.android.ui.wizard

import com.clipsync.android.pairing.FakeKeyValueStore
import com.clipsync.android.platform.clipboard.ClipboardReadMode
import com.clipsync.android.platform.clipboard.ClipboardWriteMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyValueWizardSettingsTest {
    @Test
    fun `empty store loads wizard defaults without enabling overlay`() {
        val settings = KeyValueWizardSettings(FakeKeyValueStore())
        val choices = settings.load()
        assertEquals(ClipboardReadMode.SHIZUKU_EVENT, choices.preferredReadMode)
        assertTrue(choices.autoFallbackAllowed)
        assertEquals(WizardChoices.DEFAULT_POLLING_INTERVAL_MS, choices.pollingIntervalMs)
        assertFalse(choices.backgroundAutoUpload)
        assertTrue(choices.backgroundAutoApply)
        assertFalse(choices.overlayConsented)
        assertEquals(ClipboardWriteMode.PUBLIC_API, choices.writeMode)
        assertFalse(choices.wizardCompleted)
        assertTrue(settings.loadSkippedSteps().isEmpty())
    }

    @Test
    fun `choices and skipped steps round trip through an in-memory KeyValueStore`() {
        val keys = FakeKeyValueStore()
        val first = KeyValueWizardSettings(keys)
        first.save(
            WizardChoices(
                preferredReadMode = ClipboardReadMode.OVERLAY_POLLING,
                autoFallbackAllowed = false,
                pollingIntervalMs = 1_500,
                backgroundAutoUpload = true,
                backgroundAutoApply = false,
                overlayConsented = true,
                writeMode = ClipboardWriteMode.PUBLIC_API,
                wizardCompleted = true,
            ),
        )
        first.saveSkippedSteps(setOf(WizardStepId.READ_LOGS, WizardStepId.SHIZUKU_AUTH))

        val second = KeyValueWizardSettings(keys)
        val choices = second.load()
        assertEquals(ClipboardReadMode.OVERLAY_POLLING, choices.preferredReadMode)
        assertFalse(choices.autoFallbackAllowed)
        assertEquals(1_500, choices.pollingIntervalMs)
        assertTrue(choices.backgroundAutoUpload)
        assertFalse(choices.backgroundAutoApply)
        assertTrue(choices.overlayConsented)
        assertEquals(ClipboardWriteMode.PUBLIC_API, choices.writeMode)
        assertTrue(choices.wizardCompleted)
        assertEquals(
            setOf(WizardStepId.READ_LOGS, WizardStepId.SHIZUKU_AUTH),
            second.loadSkippedSteps(),
        )
    }

    @Test
    fun `polling interval is clamped on save and load`() {
        val keys = FakeKeyValueStore()
        val settings = KeyValueWizardSettings(keys)
        settings.save(WizardChoices(pollingIntervalMs = 9_999))
        assertEquals(2_000, settings.load().pollingIntervalMs)
        keys.write(mapOf(KeyValueWizardSettings.KEY_POLLING_INTERVAL to "12"))
        assertEquals(500, KeyValueWizardSettings(keys).load().pollingIntervalMs)
    }

    @Test
    fun `ViewModel persists preferred mode through the settings-backed store`() {
        val store = KeyValueWizardSettings(FakeKeyValueStore())
        val first = WizardViewModel(store, WizardProbes.unknown())
        first.setPreferredReadMode(ClipboardReadMode.FOREGROUND_ONLY)
        first.setAutoFallbackAllowed(false)
        first.setPollingIntervalMs(1_200)
        first.setBackgroundAutoUpload(true)
        first.setBackgroundAutoApply(false)
        val second = WizardViewModel(store, WizardProbes.unknown())
        val choices = second.state.value.choices
        assertEquals(ClipboardReadMode.FOREGROUND_ONLY, choices.preferredReadMode)
        assertFalse(choices.autoFallbackAllowed)
        assertEquals(1_200, choices.pollingIntervalMs)
        assertTrue(choices.backgroundAutoUpload)
        assertFalse(choices.backgroundAutoApply)
    }
}

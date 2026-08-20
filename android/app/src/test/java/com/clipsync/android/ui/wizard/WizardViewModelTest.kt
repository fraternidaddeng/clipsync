package com.clipsync.android.ui.wizard

import com.clipsync.android.platform.clipboard.CapabilityState
import com.clipsync.android.platform.clipboard.ClipboardReadMode
import com.clipsync.android.platform.clipboard.ClipboardReadResult
import com.clipsync.android.platform.clipboard.ClipboardSelfTest
import com.clipsync.android.platform.clipboard.ClipboardWriteCoordinator
import com.clipsync.android.platform.clipboard.ClipboardWriteMode
import com.clipsync.android.platform.clipboard.FakeBackgroundClipboardBackend
import com.clipsync.android.platform.clipboard.FakeClipboardWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WizardViewModelTest {
    @Test
    fun `defaults prefer shizuku event, overlay off, and public API write`() {
        val model = wizard()
        val choices = model.state.value.choices
        assertEquals(ClipboardReadMode.SHIZUKU_EVENT, choices.preferredReadMode)
        assertFalse(choices.overlayConsented)
        assertFalse(model.state.value.overlayEnabled)
        assertEquals(ClipboardWriteMode.PUBLIC_API, choices.writeMode)
        assertEquals(WizardChoices.DEFAULT_POLLING_INTERVAL_MS, choices.pollingIntervalMs)
        assertTrue(choices.autoFallbackAllowed)
        assertFalse(choices.backgroundAutoUpload)
        assertTrue(choices.backgroundAutoApply)
        assertTrue(model.state.value.manualFallbackAvailable)
    }

    @Test
    fun `overlay stays disabled when permission is ready until the user consents`() {
        val probes = MutableProbes(overlay = CapabilityState.READY)
        val model = wizard(probes)
        assertFalse(model.state.value.choices.overlayConsented)
        assertFalse(model.state.value.overlayEnabled)
        model.setPreferredReadMode(ClipboardReadMode.OVERLAY_POLLING)
        assertFalse(model.state.value.overlayEnabled)
        assertEquals(ClipboardWriteMode.PUBLIC_API, model.state.value.choices.writeMode)
        model.setOverlayConsented(true)
        assertTrue(model.state.value.choices.overlayConsented)
        assertTrue(model.state.value.overlayEnabled)
        assertEquals(ClipboardWriteMode.PUBLIC_API, model.state.value.choices.writeMode)
    }

    @Test
    fun `write mode stays public API when read mode or overlay consent changes`() {
        val model = wizard()
        model.setPreferredReadMode(ClipboardReadMode.ADB_LOG_OVERLAY)
        model.setOverlayConsented(true)
        model.setAutoFallbackAllowed(false)
        assertEquals(ClipboardWriteMode.PUBLIC_API, model.state.value.choices.writeMode)
        model.setWriteMode(ClipboardWriteMode.MANUAL_ONLY)
        assertEquals(ClipboardWriteMode.MANUAL_ONLY, model.state.value.choices.writeMode)
        model.setWriteMode(ClipboardWriteMode.PUBLIC_API)
        assertEquals(ClipboardWriteMode.PUBLIC_API, model.state.value.choices.writeMode)
    }

    @Test
    fun `polling interval clamps to 500 through 2000 ms`() {
        val model = wizard()
        model.setPollingIntervalMs(499)
        assertEquals(500, model.state.value.choices.pollingIntervalMs)
        model.setPollingIntervalMs(2001)
        assertEquals(2000, model.state.value.choices.pollingIntervalMs)
        model.setPollingIntervalMs(800)
        assertEquals(800, model.state.value.choices.pollingIntervalMs)
        model.setPollingIntervalMs(500)
        assertEquals(500, model.state.value.choices.pollingIntervalMs)
        model.setPollingIntervalMs(2000)
        assertEquals(2000, model.state.value.choices.pollingIntervalMs)
        assertEquals(500, WizardChoices.clampPollingIntervalMs(1))
        assertEquals(2000, WizardChoices.clampPollingIntervalMs(9_999))
    }

    @Test
    fun `step completes when its probe is READY without being skipped`() {
        val probes = MutableProbes(notifications = CapabilityState.NEEDS_USER_ACTION)
        val model = wizard(probes)
        val pending = model.step(WizardStepId.NOTIFICATIONS)
        assertFalse(pending.completed)
        assertFalse(pending.skipped)
        probes.notifications = CapabilityState.READY
        model.refresh()
        val done = model.step(WizardStepId.NOTIFICATIONS)
        assertTrue(done.completed)
        assertFalse(done.skipped)
        assertEquals(CapabilityState.READY, done.state)
    }

    @Test
    fun `skip marks the step complete and records consequences`() {
        val model = wizard()
        assertFalse(model.state.value.canFinish)
        WizardStepId.entries.forEach { id ->
            assertFalse(model.step(id).completed)
            model.skip(id)
            assertTrue(model.step(id).skipped)
            assertTrue(model.step(id).completed)
        }
        val effects = model.state.value.skipEffects
        assertTrue(effects.notificationActionsHidden)
        assertTrue(effects.foregroundServiceLimited)
        assertTrue(effects.batteryMayKillProcess)
        assertTrue(effects.unavailableReadModes.contains(ClipboardReadMode.SHIZUKU_EVENT))
        assertTrue(effects.unavailableReadModes.contains(ClipboardReadMode.OVERLAY_POLLING))
        assertTrue(effects.unavailableReadModes.contains(ClipboardReadMode.ADB_LOG_OVERLAY))
        assertFalse(effects.unavailableReadModes.contains(ClipboardReadMode.FOREGROUND_ONLY))
        assertTrue(effects.manualFallbackAvailable)
        assertTrue(model.state.value.manualFallbackAvailable)
        assertTrue(model.state.value.canFinish)
        model.finish()
        assertTrue(model.state.value.choices.wizardCompleted)
    }

    @Test
    fun `skipping overlay does not enable overlay and keeps manual fallback`() {
        val probes = MutableProbes(overlay = CapabilityState.READY)
        val model = wizard(probes)
        model.setOverlayConsented(true)
        assertTrue(model.state.value.overlayEnabled)
        model.skip(WizardStepId.OVERLAY)
        assertFalse(model.state.value.choices.overlayConsented)
        assertFalse(model.state.value.overlayEnabled)
        assertTrue(
            model.state.value.skipEffects.unavailableReadModes.contains(ClipboardReadMode.OVERLAY_POLLING),
        )
        assertTrue(
            model.state.value.skipEffects.unavailableReadModes.contains(ClipboardReadMode.ADB_LOG_OVERLAY),
        )
        assertTrue(model.state.value.manualFallbackAvailable)
    }

    @Test
    fun `skipping shizuku or read logs only disables those modes`() {
        val model = wizard()
        model.skip(WizardStepId.SHIZUKU_BINDER)
        assertEquals(
            setOf(ClipboardReadMode.SHIZUKU_EVENT),
            model.state.value.skipEffects.unavailableReadModes,
        )
        model.skip(WizardStepId.READ_LOGS)
        assertEquals(
            setOf(ClipboardReadMode.SHIZUKU_EVENT, ClipboardReadMode.ADB_LOG_OVERLAY),
            model.state.value.skipEffects.unavailableReadModes,
        )
        assertTrue(model.state.value.manualFallbackAvailable)
        assertFalse(
            model.state.value.skipEffects.unavailableReadModes.contains(ClipboardReadMode.FOREGROUND_ONLY),
        )
    }

    @Test
    fun `four live indicators stay independent and never collapse into one green`() {
        val probes = MutableProbes(
            network = CapabilityState.READY,
            service = CapabilityState.DEGRADED,
            backgroundRead = CapabilityState.NEEDS_USER_ACTION,
            backgroundWrite = CapabilityState.UNAVAILABLE,
        )
        val model = wizard(probes)
        val indicators = model.state.value.indicators
        assertEquals(CapabilityState.READY, indicators.network)
        assertEquals(CapabilityState.DEGRADED, indicators.service)
        assertEquals(CapabilityState.NEEDS_USER_ACTION, indicators.backgroundRead)
        assertEquals(CapabilityState.UNAVAILABLE, indicators.backgroundWrite)
        assertNotEquals(indicators.network, indicators.service)
        assertNotEquals(indicators.network, indicators.backgroundRead)
        assertNotEquals(indicators.network, indicators.backgroundWrite)
        assertFalse(indicators.allReady())
        assertFalse(model.indicatorsCollapsedToSingleGreen())

        probes.service = CapabilityState.READY
        probes.backgroundRead = CapabilityState.READY
        probes.backgroundWrite = CapabilityState.READY
        model.refresh()
        assertTrue(model.state.value.indicators.allReady())
        assertFalse(model.indicatorsCollapsedToSingleGreen())
    }

    @Test
    fun `network ready does not paint service read or write ready`() {
        val probes = MutableProbes(
            network = CapabilityState.READY,
            service = CapabilityState.UNKNOWN,
            backgroundRead = CapabilityState.UNKNOWN,
            backgroundWrite = CapabilityState.UNKNOWN,
        )
        val model = wizard(probes)
        val indicators = model.state.value.indicators
        assertEquals(CapabilityState.READY, indicators.network)
        assertEquals(CapabilityState.UNKNOWN, indicators.service)
        assertEquals(CapabilityState.UNKNOWN, indicators.backgroundRead)
        assertEquals(CapabilityState.UNKNOWN, indicators.backgroundWrite)
        assertFalse(model.indicatorsCollapsedToSingleGreen())
    }

    @Test
    fun `READ_LOGS is adb-only via bootstrap script and never an in-app dialog`() {
        val model = wizard()
        val card = model.step(WizardStepId.READ_LOGS)
        val guidance = card.readLogsGuidance
        assertNotNull(guidance)
        requireNotNull(guidance)
        assertTrue(guidance.adbOnly)
        assertEquals("android-bootstrap.ps1", guidance.bootstrapScript)
        assertFalse(guidance.inAppDialogAllowed)
        assertTrue(guidance.recheckAfterInstallUpgradeReboot)
        assertEquals(WizardActionKind.RECHECK_ADB, card.actionKind)
        assertFalse(card.offersInAppGrant)
        model.onStepAction(WizardStepId.READ_LOGS)
        assertEquals(WizardActionKind.RECHECK_ADB, model.lastActionKind)
        assertFalse(model.lastActionOfferedInAppGrant)
    }

    @Test
    fun `privileged host auth is an in-app grant and binder is a recheck`() {
        var authCalls = 0
        val model = WizardViewModel(
            InMemoryWizardSettings(),
            MutableProbes().toProbes(),
            requestPrivilegedAuthorization = { onResult ->
                authCalls += 1
                onResult(true)
            },
        )
        model.onStepAction(WizardStepId.SHIZUKU_BINDER)
        assertEquals(WizardActionKind.START_PRIVILEGED_HOST, model.lastActionKind)
        assertFalse(model.lastActionOfferedInAppGrant)
        assertEquals(0, authCalls)
        model.onStepAction(WizardStepId.SHIZUKU_AUTH)
        assertEquals(WizardActionKind.AUTHORIZE_PRIVILEGED_HOST, model.lastActionKind)
        assertTrue(model.lastActionOfferedInAppGrant)
        assertEquals(1, authCalls)
    }

    @Test
    fun `privileged host auth card becomes ready when the grant callback fires`() {
        var complete: ((Boolean) -> Unit)? = null
        val probes = MutableProbes(shizukuAuth = CapabilityState.NEEDS_USER_ACTION)
        val model = WizardViewModel(
            InMemoryWizardSettings(),
            probes.toProbes(),
            requestPrivilegedAuthorization = { onResult ->
                complete = onResult
            },
        )
        assertEquals(CapabilityState.NEEDS_USER_ACTION, model.step(WizardStepId.SHIZUKU_AUTH).state)
        model.onStepAction(WizardStepId.SHIZUKU_AUTH)
        assertEquals(CapabilityState.NEEDS_USER_ACTION, model.step(WizardStepId.SHIZUKU_AUTH).state)
        probes.shizukuAuth = CapabilityState.READY
        val finish = complete
        assertNotNull(finish)
        finish!!.invoke(true)
        assertEquals(CapabilityState.READY, model.step(WizardStepId.SHIZUKU_AUTH).state)
    }

    @Test
    fun `choices persist through the injected WizardSettings seam`() {
        val store = InMemoryWizardSettings()
        val first = wizard(settings = store)
        first.setPreferredReadMode(ClipboardReadMode.OVERLAY_POLLING)
        first.setAutoFallbackAllowed(false)
        first.setPollingIntervalMs(1_500)
        first.setBackgroundAutoUpload(true)
        first.setBackgroundAutoApply(false)
        first.setOverlayConsented(true)
        val second = wizard(settings = store)
        val choices = second.state.value.choices
        assertEquals(ClipboardReadMode.OVERLAY_POLLING, choices.preferredReadMode)
        assertFalse(choices.autoFallbackAllowed)
        assertEquals(1_500, choices.pollingIntervalMs)
        assertTrue(choices.backgroundAutoUpload)
        assertFalse(choices.backgroundAutoApply)
        assertTrue(choices.overlayConsented)
        assertEquals(ClipboardWriteMode.PUBLIC_API, choices.writeMode)
    }

    @Test
    fun `each capability is a distinct independently checkable step`() {
        val model = wizard()
        val ids = model.state.value.steps.map { it.id }
        assertEquals(WizardStepId.entries.toList(), ids)
        assertEquals(7, ids.size)
        assertTrue(ids.contains(WizardStepId.NOTIFICATIONS))
        assertTrue(ids.contains(WizardStepId.FOREGROUND_SERVICE))
        assertTrue(ids.contains(WizardStepId.IGNORE_BATTERY))
        assertTrue(ids.contains(WizardStepId.OVERLAY))
        assertTrue(ids.contains(WizardStepId.READ_LOGS))
        assertTrue(ids.contains(WizardStepId.SHIZUKU_BINDER))
        assertTrue(ids.contains(WizardStepId.SHIZUKU_AUTH))
    }

    @Test
    fun `read and write indicator timestamps come from probe seams`() {
        val probes =
            MutableProbes(
                backgroundRead = CapabilityState.READY,
                backgroundWrite = CapabilityState.READY,
            )
        probes.backgroundReadCheckedAt = 1_700_000_000_000L
        probes.backgroundWriteCheckedAt = 1_700_000_060_000L
        val model = wizard(probes)
        val indicators = model.state.value.indicators
        assertEquals(1_700_000_000_000L, indicators.backgroundReadCheckedAtEpochMillis)
        assertEquals(1_700_000_060_000L, indicators.backgroundWriteCheckedAtEpochMillis)
    }

    @Test
    fun `self test without a tester is a no-op`() {
        val model = wizard()
        model.runBackgroundReadTest()
        model.runBackgroundWriteTest()
        assertFalse(model.selfTestState.value.running)
        assertNull(model.selfTestState.value.read)
        assertNull(model.selfTestState.value.write)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `self test results land in wizard state without carrying the token`() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            val token = "clipsync-selftest-wizard-token"
            val backend = FakeBackgroundClipboardBackend(
                mode = ClipboardReadMode.SHIZUKU_EVENT,
                readResult = ClipboardReadResult.Success(token),
            )
            val selfTest = ClipboardSelfTest(
                writeCoordinator = ClipboardWriteCoordinator(FakeClipboardWriter()),
                readBackend = { backend },
                clearClipboard = { true },
                tokenGenerator = { token },
            )
            val model = WizardViewModel(
                InMemoryWizardSettings(),
                MutableProbes().toProbes(),
                selfTest = selfTest,
                selfTestContext = UnconfinedTestDispatcher(),
            )
            model.runBackgroundReadTest()
            val read = model.selfTestState.value.read
            assertNotNull(read)
            assertTrue(read!!.passed)
            assertEquals(ClipboardReadMode.SHIZUKU_EVENT, read.readMode)
            assertFalse(read.toString().contains(token))
            model.runBackgroundWriteTest()
            val write = model.selfTestState.value.write
            assertNotNull(write)
            assertTrue(write!!.passed)
            assertFalse(model.selfTestState.value.running)
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun wizard(
        probes: MutableProbes = MutableProbes(),
        settings: WizardSettings = InMemoryWizardSettings(),
    ) = WizardViewModel(settings, probes.toProbes())
}

private class MutableProbes(
    var notifications: CapabilityState = CapabilityState.UNKNOWN,
    var foregroundService: CapabilityState = CapabilityState.UNKNOWN,
    var ignoreBattery: CapabilityState = CapabilityState.UNKNOWN,
    var overlay: CapabilityState = CapabilityState.UNKNOWN,
    var readLogs: CapabilityState = CapabilityState.UNKNOWN,
    var shizukuBinder: CapabilityState = CapabilityState.UNKNOWN,
    var shizukuAuth: CapabilityState = CapabilityState.UNKNOWN,
    var network: CapabilityState = CapabilityState.UNKNOWN,
    var service: CapabilityState = CapabilityState.UNKNOWN,
    var backgroundRead: CapabilityState = CapabilityState.UNKNOWN,
    var backgroundWrite: CapabilityState = CapabilityState.UNKNOWN,
) {
    var backgroundReadCheckedAt: Long? = null
    var backgroundWriteCheckedAt: Long? = null

    fun toProbes(): WizardProbes {
        return WizardProbes(
            notifications = { notifications },
            foregroundService = { foregroundService },
            ignoreBattery = { ignoreBattery },
            overlay = { overlay },
            readLogs = { readLogs },
            shizukuBinder = { shizukuBinder },
            shizukuAuth = { shizukuAuth },
            network = { network },
            service = { service },
            backgroundRead = { backgroundRead },
            backgroundWrite = { backgroundWrite },
            backgroundReadCheckedAt = { backgroundReadCheckedAt },
            backgroundWriteCheckedAt = { backgroundWriteCheckedAt },
        )
    }
}

package com.clipsync.android.ui.wizard

import com.clipsync.android.platform.clipboard.BackgroundClipboardBackends
import com.clipsync.android.platform.clipboard.CapabilityReport
import com.clipsync.android.platform.clipboard.CapabilityState
import com.clipsync.android.platform.clipboard.ClipboardAuthorization
import com.clipsync.android.platform.clipboard.ClipboardCapabilityStore
import com.clipsync.android.platform.clipboard.ClipboardReadMode
import com.clipsync.android.platform.clipboard.ClipboardWriteMode
import com.clipsync.android.platform.clipboard.FakeBackgroundClipboardBackend
import com.clipsync.android.platform.clipboard.InMemoryCapabilityKeyValueStore
import com.clipsync.android.platform.clipboard.KeyValueClipboardCapabilityStore
import com.clipsync.android.platform.clipboard.ReadCapabilitySnapshot
import com.clipsync.android.platform.clipboard.WriteCapabilitySnapshot
import com.clipsync.android.platform.clipboard.overlay.FakeOverlayPlatform
import com.clipsync.android.platform.clipboard.overlay.OverlayFocusController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WizardFrameworkProbesTest {
    @Test
    fun `four live indicators stay independent when backends and snapshots differ`() {
        val shizuku = fake(
            ClipboardReadMode.SHIZUKU_EVENT,
            CapabilityState.NEEDS_USER_ACTION,
            authorizations = shizukuAuths(running = false, authorized = false),
        )
        val adb = fake(ClipboardReadMode.ADB_LOG_OVERLAY, CapabilityState.NEEDS_USER_ACTION)
        val overlay = fake(ClipboardReadMode.OVERLAY_POLLING, CapabilityState.NEEDS_USER_ACTION)
        val foreground = fake(ClipboardReadMode.FOREGROUND_ONLY, CapabilityState.READY)
        val settings = InMemoryWizardSettings(
            WizardChoices(preferredReadMode = ClipboardReadMode.SHIZUKU_EVENT),
        )
        val probes = WizardFrameworkProbes.bind(
            backends = assembly(shizuku, adb, overlay, foreground),
            settings = settings,
            network = { CapabilityState.READY },
            service = { CapabilityState.DEGRADED },
            backgroundWrite = { CapabilityState.UNAVAILABLE },
            notifications = { CapabilityState.UNKNOWN },
            foregroundService = { CapabilityState.UNKNOWN },
            ignoreBattery = { CapabilityState.UNKNOWN },
        )
        val model = WizardViewModel(settings, probes)
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
    }

    @Test
    fun `changing one indicator does not paint the other three`() {
        var network = CapabilityState.READY
        var service = CapabilityState.UNKNOWN
        var write = CapabilityState.UNKNOWN
        val shizuku = fake(
            ClipboardReadMode.SHIZUKU_EVENT,
            CapabilityState.NEEDS_USER_ACTION,
            authorizations = shizukuAuths(running = false, authorized = false),
        )
        val settings = InMemoryWizardSettings()
        val probes = WizardFrameworkProbes.bind(
            backends = assembly(
                shizuku,
                fake(ClipboardReadMode.ADB_LOG_OVERLAY, CapabilityState.DEGRADED),
                fake(ClipboardReadMode.OVERLAY_POLLING, CapabilityState.UNAVAILABLE),
                fake(ClipboardReadMode.FOREGROUND_ONLY, CapabilityState.READY),
            ),
            settings = settings,
            network = { network },
            service = { service },
            backgroundWrite = { write },
            notifications = { CapabilityState.UNKNOWN },
            foregroundService = { CapabilityState.UNKNOWN },
            ignoreBattery = { CapabilityState.UNKNOWN },
        )
        val model = WizardViewModel(settings, probes)
        assertEquals(CapabilityState.READY, model.state.value.indicators.network)
        assertEquals(CapabilityState.UNKNOWN, model.state.value.indicators.service)
        assertEquals(CapabilityState.NEEDS_USER_ACTION, model.state.value.indicators.backgroundRead)
        assertEquals(CapabilityState.UNKNOWN, model.state.value.indicators.backgroundWrite)

        service = CapabilityState.READY
        write = CapabilityState.READY
        model.refresh()
        assertEquals(CapabilityState.READY, model.state.value.indicators.network)
        assertEquals(CapabilityState.READY, model.state.value.indicators.service)
        assertEquals(CapabilityState.NEEDS_USER_ACTION, model.state.value.indicators.backgroundRead)
        assertEquals(CapabilityState.READY, model.state.value.indicators.backgroundWrite)
        assertFalse(model.indicatorsCollapsedToSingleGreen())
    }

    @Test
    fun `READ_LOGS Shizuku and overlay cards reflect injected backend probe states`() {
        val shizuku = fake(
            ClipboardReadMode.SHIZUKU_EVENT,
            CapabilityState.NEEDS_USER_ACTION,
            authorizations = shizukuAuths(running = false, authorized = false),
        )
        val adb = fake(ClipboardReadMode.ADB_LOG_OVERLAY, CapabilityState.NEEDS_USER_ACTION)
        val overlay = fake(ClipboardReadMode.OVERLAY_POLLING, CapabilityState.NEEDS_USER_ACTION)
        val probes = WizardFrameworkProbes.bind(
            backends = assembly(
                shizuku,
                adb,
                overlay,
                fake(ClipboardReadMode.FOREGROUND_ONLY, CapabilityState.READY),
            ),
            settings = InMemoryWizardSettings(),
            network = { CapabilityState.UNKNOWN },
            service = { CapabilityState.UNKNOWN },
            backgroundWrite = { CapabilityState.UNKNOWN },
            notifications = { CapabilityState.UNKNOWN },
            foregroundService = { CapabilityState.UNKNOWN },
            ignoreBattery = { CapabilityState.UNKNOWN },
        )
        val model = WizardViewModel(InMemoryWizardSettings(), probes)
        assertEquals(CapabilityState.NEEDS_USER_ACTION, model.step(WizardStepId.OVERLAY).state)
        assertEquals(CapabilityState.NEEDS_USER_ACTION, model.step(WizardStepId.READ_LOGS).state)
        assertEquals(CapabilityState.NEEDS_USER_ACTION, model.step(WizardStepId.SHIZUKU_BINDER).state)
        assertEquals(CapabilityState.NEEDS_USER_ACTION, model.step(WizardStepId.SHIZUKU_AUTH).state)

        shizuku.report = FakeBackgroundClipboardBackend.capabilityReport(
            mode = ClipboardReadMode.SHIZUKU_EVENT,
            state = CapabilityState.READY,
        ).copy(authorizations = shizukuAuths(running = true, authorized = true))
        adb.report = FakeBackgroundClipboardBackend.capabilityReport(
            mode = ClipboardReadMode.ADB_LOG_OVERLAY,
            state = CapabilityState.DEGRADED,
        )
        overlay.report = FakeBackgroundClipboardBackend.capabilityReport(
            mode = ClipboardReadMode.OVERLAY_POLLING,
            state = CapabilityState.READY,
        )
        model.refresh()
        assertEquals(CapabilityState.READY, model.step(WizardStepId.OVERLAY).state)
        assertEquals(CapabilityState.DEGRADED, model.step(WizardStepId.READ_LOGS).state)
        assertEquals(CapabilityState.READY, model.step(WizardStepId.SHIZUKU_BINDER).state)
        assertEquals(CapabilityState.READY, model.step(WizardStepId.SHIZUKU_AUTH).state)
        assertTrue(model.step(WizardStepId.READ_LOGS).state != model.step(WizardStepId.OVERLAY).state)
    }

    @Test
    fun `shizuku binder and auth cards stay independent`() {
        val shizuku = fake(
            ClipboardReadMode.SHIZUKU_EVENT,
            CapabilityState.NEEDS_USER_ACTION,
            authorizations = shizukuAuths(running = true, authorized = false),
        )
        val probes = WizardFrameworkProbes.bind(
            backends = assembly(
                shizuku,
                fake(ClipboardReadMode.ADB_LOG_OVERLAY, CapabilityState.NEEDS_USER_ACTION),
                fake(ClipboardReadMode.OVERLAY_POLLING, CapabilityState.NEEDS_USER_ACTION),
                fake(ClipboardReadMode.FOREGROUND_ONLY, CapabilityState.UNAVAILABLE),
            ),
            settings = InMemoryWizardSettings(),
            network = { CapabilityState.UNKNOWN },
            service = { CapabilityState.UNKNOWN },
            backgroundWrite = { CapabilityState.UNKNOWN },
            notifications = { CapabilityState.UNKNOWN },
            foregroundService = { CapabilityState.UNKNOWN },
            ignoreBattery = { CapabilityState.UNKNOWN },
        )
        val model = WizardViewModel(InMemoryWizardSettings(), probes)
        assertEquals(CapabilityState.READY, model.step(WizardStepId.SHIZUKU_BINDER).state)
        assertEquals(CapabilityState.NEEDS_USER_ACTION, model.step(WizardStepId.SHIZUKU_AUTH).state)
    }

    @Test
    fun `live read and write cards surface stored last-check timestamps`() {
        val store = KeyValueClipboardCapabilityStore(InMemoryCapabilityKeyValueStore())
        store.saveRead(
            ReadCapabilitySnapshot(
                requestedReadMode = ClipboardReadMode.SHIZUKU_EVENT,
                activeReadMode = ClipboardReadMode.SHIZUKU_EVENT,
                autoFallbackAllowed = true,
                lastErrorCode = null,
                lastHealthAtEpochMillis = 1_700_000_000_000L,
            ),
        )
        store.saveWrite(
            WriteCapabilitySnapshot(
                writeMode = ClipboardWriteMode.PUBLIC_API,
                publicLastSuccessAtEpochMillis = 1_700_000_060_000L,
            ),
        )
        val settings =
            InMemoryWizardSettings(
                WizardChoices(preferredReadMode = ClipboardReadMode.SHIZUKU_EVENT),
            )
        val probes =
            WizardFrameworkProbes.bind(
                backends = assembly(
                    fake(ClipboardReadMode.SHIZUKU_EVENT, CapabilityState.READY),
                    fake(ClipboardReadMode.ADB_LOG_OVERLAY, CapabilityState.UNKNOWN),
                    fake(ClipboardReadMode.OVERLAY_POLLING, CapabilityState.UNKNOWN),
                    fake(ClipboardReadMode.FOREGROUND_ONLY, CapabilityState.READY),
                    capabilityStore = store,
                ),
                settings = settings,
                network = { CapabilityState.UNKNOWN },
                service = { CapabilityState.UNKNOWN },
                backgroundWrite = { CapabilityState.READY },
                notifications = { CapabilityState.UNKNOWN },
                foregroundService = { CapabilityState.UNKNOWN },
                ignoreBattery = { CapabilityState.UNKNOWN },
            )
        val model = WizardViewModel(settings, probes)
        val indicators = model.state.value.indicators
        assertEquals(1_700_000_000_000L, indicators.backgroundReadCheckedAtEpochMillis)
        assertEquals(1_700_000_060_000L, indicators.backgroundWriteCheckedAtEpochMillis)
    }

    @Test
    fun `background read follows the selected preferred backend not the others`() {
        val settings = InMemoryWizardSettings(
            WizardChoices(preferredReadMode = ClipboardReadMode.ADB_LOG_OVERLAY),
        )
        val probes = WizardFrameworkProbes.bind(
            backends = assembly(
                fake(ClipboardReadMode.SHIZUKU_EVENT, CapabilityState.READY),
                fake(ClipboardReadMode.ADB_LOG_OVERLAY, CapabilityState.DEGRADED),
                fake(ClipboardReadMode.OVERLAY_POLLING, CapabilityState.READY),
                fake(ClipboardReadMode.FOREGROUND_ONLY, CapabilityState.READY),
            ),
            settings = settings,
            network = { CapabilityState.UNKNOWN },
            service = { CapabilityState.UNKNOWN },
            backgroundWrite = { CapabilityState.READY },
            notifications = { CapabilityState.UNKNOWN },
            foregroundService = { CapabilityState.UNKNOWN },
            ignoreBattery = { CapabilityState.UNKNOWN },
        )
        val model = WizardViewModel(settings, probes)
        assertEquals(CapabilityState.DEGRADED, model.state.value.indicators.backgroundRead)
        assertEquals(CapabilityState.READY, model.state.value.indicators.backgroundWrite)
        assertEquals(CapabilityState.UNKNOWN, model.state.value.indicators.network)
        assertEquals(CapabilityState.UNKNOWN, model.state.value.indicators.service)
    }

    private fun assembly(
        shizuku: FakeBackgroundClipboardBackend,
        adb: FakeBackgroundClipboardBackend,
        overlay: FakeBackgroundClipboardBackend,
        foreground: FakeBackgroundClipboardBackend,
        capabilityStore: ClipboardCapabilityStore? = null,
    ): BackgroundClipboardBackends = BackgroundClipboardBackends.build(
        overlayController = OverlayFocusController(FakeOverlayPlatform()),
        shizuku = shizuku,
        adbLog = adb,
        overlayPolling = overlay,
        foreground = foreground,
        capabilityStore = capabilityStore,
    )

    private fun fake(
        mode: ClipboardReadMode,
        state: CapabilityState,
        authorizations: List<ClipboardAuthorization> = emptyList(),
    ): FakeBackgroundClipboardBackend = FakeBackgroundClipboardBackend(
        mode = mode,
        report = CapabilityReport(
            readMode = mode,
            readState = state,
            writeState = CapabilityState.UNKNOWN,
            systemVersion = "test",
            authorizations = authorizations,
        ),
    )

    private fun shizukuAuths(
        running: Boolean,
        authorized: Boolean,
        installed: Boolean = running || authorized,
    ): List<ClipboardAuthorization> = listOf(
        ClipboardAuthorization(WizardFrameworkProbes.AUTH_SHIZUKU_INSTALLED, installed),
        ClipboardAuthorization(WizardFrameworkProbes.AUTH_SHIZUKU_RUNNING, running),
        ClipboardAuthorization(WizardFrameworkProbes.AUTH_SHIZUKU_AUTHORIZED, authorized),
    )
}

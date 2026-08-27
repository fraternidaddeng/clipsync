package com.clipsync.android.ui.prefs

import com.clipsync.android.pairing.FakeKeyValueStore
import com.clipsync.android.storage.SyncSettingsStore
import com.clipsync.android.sync.SyncServiceNotification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreferencesViewModelTest {
    private val keyValues = FakeKeyValueStore()
    private val settings = SyncSettingsStore(keyValues)

    private fun viewModel() = PreferencesViewModel(settings)

    @Test
    fun `empty store yields the product-scope defaults`() {
        val state = viewModel().state.value
        assertFalse(state.pauseSync)
        assertFalse(state.pauseCapture)
        assertFalse(state.privateMode)
        assertTrue(state.autoApplyRemote)
        assertTrue(state.autoExpire)
        assertEquals(SyncSettingsStore.DEFAULT_MAX_AGE_DAYS, state.retentionDays)
        // settings-roadmap basic settings: their defaults surface without any stored key.
        assertEquals(SyncSettingsStore.DEFAULT_MAX_ENTRIES, state.maxEntries)
        assertEquals(SyncSettingsStore.HISTORY_FONT_SCALE_STANDARD, state.historyFontScale, 0f)
        assertEquals(SyncSettingsStore.DEFAULT_PREVIEW_LINES, state.previewLines)
        assertEquals(SyncSettingsStore.THEME_SYSTEM, state.themeOverride)
        assertTrue(state.skipSensitive)
        assertTrue(state.inboxNotify)
    }

    @Test
    fun `toggles persist under the shared sync settings keys immediately`() {
        val model = viewModel()
        model.setPauseSync(true)
        model.setPrivateMode(true)
        model.setAutoApplyRemote(false)

        // The keys are the sync engine's vocabulary, not a UI-private copy.
        assertEquals("true", keyValues.map["sync.paused"])
        assertEquals("true", keyValues.map["sync.private_mode"])
        assertEquals("false", keyValues.map["sync.auto_apply_remote"])

        val state = model.state.value
        assertTrue(state.pauseSync)
        assertTrue(state.privateMode)
        assertFalse(state.autoApplyRemote)
    }

    @Test
    fun `a new view model over the same store reads the persisted values`() {
        viewModel().apply {
            setPauseSync(true)
            setAutoApplyRemote(false)
            setRetentionDays(7)
        }

        val reloaded = viewModel().state.value
        assertTrue(reloaded.pauseSync)
        assertFalse(reloaded.autoApplyRemote)
        assertEquals(7, reloaded.retentionDays)
    }

    @Test
    fun `auto expire off keeps the stored duration so re-enabling restores it`() {
        val model = viewModel()
        model.setRetentionDays(14)

        model.setAutoExpire(false)
        assertEquals("false", keyValues.map["sync.retention.auto_expire"])
        assertFalse(model.state.value.autoExpire)
        assertEquals(14, model.state.value.retentionDays)

        model.setAutoExpire(true)
        assertTrue(model.state.value.autoExpire)
        assertEquals(14, model.state.value.retentionDays)
    }

    @Test
    fun `the sync engine reads exactly what the user toggled`() {
        val model = viewModel()
        model.setPauseSync(true)
        model.setPrivateMode(true)
        model.setAutoExpire(false)

        // Another component (sync loop, retention cleanup) over the same backing store.
        val engineView = SyncSettingsStore(keyValues)
        assertTrue(engineView.syncPaused)
        assertTrue(engineView.privateMode)
        assertFalse(engineView.autoExpireEnabled)
    }

    @Test
    fun `corrupt stored values fall back to the defaults`() {
        keyValues.map["sync.paused"] = "definitely"
        keyValues.map["sync.auto_apply_remote"] = ""
        keyValues.map["sync.retention.max_age_days"] = "soon"

        val state = viewModel().state.value
        assertFalse(state.pauseSync)
        assertTrue(state.autoApplyRemote)
        assertEquals(SyncSettingsStore.DEFAULT_MAX_AGE_DAYS, state.retentionDays)
    }

    @Test
    fun `boot restore defaults off, persists, and notifies the host to flip the receiver`() {
        val receiverStates = mutableListOf<Boolean>()
        val model =
            PreferencesViewModel(
                settings,
                PreferencesViewModel.SideEffects(onBootRestoreChanged = receiverStates::add),
            )
        assertFalse(model.state.value.bootRestore)

        model.setBootRestore(true)

        // The preference lands first, so the receiver's boot-time re-check agrees with it.
        assertEquals("true", keyValues.map["sync.boot_restore"])
        assertTrue(model.state.value.bootRestore)
        assertEquals(listOf(true), receiverStates)

        model.setBootRestore(false)
        assertEquals("false", keyValues.map["sync.boot_restore"])
        assertEquals(listOf(true, false), receiverStates)
    }

    @Test
    fun `retention changes trigger one immediate cleanup pass`() {
        var cleanups = 0
        val model =
            PreferencesViewModel(
                settings,
                PreferencesViewModel.SideEffects(onRetentionChanged = { cleanups++ }),
            )

        model.setRetentionDays(7)
        model.setAutoExpire(false)

        assertEquals(2, cleanups)
        // Unrelated toggles never trigger cleanup.
        model.setPauseSync(true)
        assertEquals(2, cleanups)
    }

    @Test
    fun `display, capture and notify settings persist under the roadmap keys`() {
        val model = viewModel()
        model.setHistoryFontScale(SyncSettingsStore.HISTORY_FONT_SCALE_LARGE)
        model.setPreviewLines(2)
        model.setSkipSensitive(false)
        model.setInboxNotify(false)

        assertEquals("1.15", keyValues.map["ui.history_font_scale"])
        assertEquals("2", keyValues.map["ui.preview_lines"])
        assertEquals("false", keyValues.map["capture.skip_sensitive"])
        assertEquals("false", keyValues.map["notify.inbox"])

        val state = model.state.value
        assertEquals(SyncSettingsStore.HISTORY_FONT_SCALE_LARGE, state.historyFontScale, 0f)
        assertEquals(2, state.previewLines)
        assertFalse(state.skipSensitive)
        assertFalse(state.inboxNotify)

        // Off-step values are ignored instead of crashing or persisting garbage.
        model.setHistoryFontScale(3f)
        model.setPreviewLines(5)
        assertEquals(SyncSettingsStore.HISTORY_FONT_SCALE_LARGE, model.state.value.historyFontScale, 0f)
        assertEquals(2, model.state.value.previewLines)
    }

    @Test
    fun `theme override persists under the roadmap key and ignores off-list values`() {
        val model = viewModel()
        model.setThemeOverride(SyncSettingsStore.THEME_NIGHT)

        assertEquals("night", keyValues.map["ui.theme"])
        assertEquals(SyncSettingsStore.THEME_NIGHT, model.state.value.themeOverride)

        // A new view model over the same store reads the persisted override.
        assertEquals(SyncSettingsStore.THEME_NIGHT, viewModel().state.value.themeOverride)

        // 跟随系统 is a real choice, not merely the absence of a key.
        model.setThemeOverride(SyncSettingsStore.THEME_SYSTEM)
        assertEquals("system", keyValues.map["ui.theme"])

        // A colour is never a mode (charter: the palette is not a user variable).
        model.setThemeOverride("#ff00ff")
        assertEquals(SyncSettingsStore.THEME_SYSTEM, model.state.value.themeOverride)
        assertEquals("system", keyValues.map["ui.theme"])
    }

    @Test
    fun `max entries persists, clamps to the stepper bounds and cleans up immediately`() {
        var cleanups = 0
        val model =
            PreferencesViewModel(
                settings,
                PreferencesViewModel.SideEffects(onRetentionChanged = { cleanups++ }),
            )
        assertEquals(SyncSettingsStore.DEFAULT_MAX_ENTRIES, model.state.value.maxEntries)

        model.setMaxEntries(500)
        assertEquals("500", keyValues.map["sync.retention.max_entries"])
        assertEquals(500, model.state.value.maxEntries)
        assertEquals(1, cleanups)

        // The stepper bounds hold even against programmatic extremes.
        model.setMaxEntries(1)
        assertEquals(SyncSettingsStore.MIN_MAX_ENTRIES, model.state.value.maxEntries)
        model.setMaxEntries(1_000_000)
        assertEquals(SyncSettingsStore.MAX_MAX_ENTRIES, model.state.value.maxEntries)
    }

    @Test
    fun `retention days clamp to the windows-aligned stepper bounds`() {
        val model = viewModel()
        model.setRetentionDays(0)
        assertEquals(SyncSettingsStore.MIN_RETENTION_DAYS, model.state.value.retentionDays)

        model.setRetentionDays(9_999)
        assertEquals(SyncSettingsStore.MAX_RETENTION_DAYS, model.state.value.retentionDays)
    }

    @Test
    fun `service master switch defaults on, persists, and notifies the host after persisting`() {
        val hostCalls = mutableListOf<Boolean>()
        val model =
            PreferencesViewModel(
                settings,
                PreferencesViewModel.SideEffects(
                    onServiceEnabledChanged = { enabled ->
                        // Persisted first: the host's stop/start — and every service start
                        // path re-checking sync.service_enabled — must read the new value.
                        assertEquals(enabled, settings.serviceEnabled)
                        hostCalls += enabled
                    },
                ),
            )
        assertTrue(model.state.value.serviceEnabled)

        model.setServiceEnabled(false)
        assertEquals("false", keyValues.map["sync.service_enabled"])
        assertFalse(model.state.value.serviceEnabled)
        assertEquals(listOf(false), hostCalls)

        model.setServiceEnabled(true)
        assertEquals("true", keyValues.map["sync.service_enabled"])
        assertTrue(model.state.value.serviceEnabled)
        assertEquals(listOf(false, true), hostCalls)
    }

    @Test
    fun `turning the service off flips no pause gate — stop and pause stay distinct`() {
        val model = viewModel()
        model.setServiceEnabled(false)

        // 彻底关闭 is the service's own switch; the pause semantics (and their keys)
        // stay untouched, so turning the service back on restores the same behaviour.
        assertFalse(model.state.value.pauseSync)
        assertFalse(model.state.value.pauseCapture)
        assertFalse(SyncSettingsStore(keyValues).syncPaused)
        assertFalse(SyncSettingsStore(keyValues).autoCapturePaused)
    }

    @Test
    fun `pause capture persists under the notification action's key and re-checks the gates`() {
        var gateChecks = 0
        val model =
            PreferencesViewModel(
                settings,
                PreferencesViewModel.SideEffects(onCaptureGatesChanged = { gateChecks++ }),
            )
        assertFalse(model.state.value.pauseCapture)

        model.setPauseCapture(true)

        // Same key the resident notification's 暂停捕获 action flips (plan 5.2) — the
        // setting is not a UI-private copy, so the two surfaces can never disagree.
        assertEquals("true", keyValues.map["sync.capture_paused"])
        assertTrue(model.state.value.pauseCapture)
        // The background read backends gate on this key; persisting must re-check them.
        assertEquals(1, gateChecks)
        assertTrue(SyncSettingsStore(keyValues).autoCapturePaused)

        model.setPauseCapture(false)
        assertFalse(model.state.value.pauseCapture)
        assertEquals(2, gateChecks)
    }

    @Test
    fun `the notification's pause-capture action surfaces as the settings toggle state`() {
        SyncServiceNotification.applyAction(SyncServiceNotification.ACTION_PAUSE_CAPTURE, settings)
        assertTrue(viewModel().state.value.pauseCapture)

        SyncServiceNotification.applyAction(SyncServiceNotification.ACTION_RESUME_CAPTURE, settings)
        assertFalse(viewModel().state.value.pauseCapture)
    }

    @Test
    fun `pause and private toggles re-evaluate the capture session gates after persisting`() {
        val gateChecks = mutableListOf<Pair<Boolean, Boolean>>()
        val model =
            PreferencesViewModel(
                settings,
                // Record what the session's gate lambda would read at refresh time: the
                // setting must already be persisted when the re-check runs.
                PreferencesViewModel.SideEffects(
                    onCaptureGatesChanged = { gateChecks += settings.syncPaused to settings.privateMode },
                ),
            )

        model.setPauseSync(true)
        model.setPrivateMode(true)
        model.setPauseSync(false)

        assertEquals(listOf(true to false, true to true, false to true), gateChecks)
        // Unrelated toggles never touch the capture gates.
        model.setAutoApplyRemote(false)
        model.setImageSync(true)
        assertEquals(3, gateChecks.size)
    }
}

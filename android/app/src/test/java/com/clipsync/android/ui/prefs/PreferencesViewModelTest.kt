package com.clipsync.android.ui.prefs

import com.clipsync.android.pairing.FakeKeyValueStore
import com.clipsync.android.storage.SyncSettingsStore
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
        assertFalse(state.privateMode)
        assertTrue(state.autoApplyRemote)
        assertTrue(state.autoExpire)
        assertEquals(SyncSettingsStore.DEFAULT_MAX_AGE_DAYS, state.retentionDays)
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
        val model = PreferencesViewModel(settings, onBootRestoreChanged = receiverStates::add)
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
        val model = PreferencesViewModel(settings, onRetentionChanged = { cleanups++ })

        model.setRetentionDays(7)
        model.setAutoExpire(false)

        assertEquals(2, cleanups)
        // Unrelated toggles never trigger cleanup.
        model.setPauseSync(true)
        assertEquals(2, cleanups)
    }
}

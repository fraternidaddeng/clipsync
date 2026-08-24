package com.clipsync.android.ui.prefs

import com.clipsync.android.pairing.FakeKeyValueStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreferencesViewModelTest {
    private val keyValues = FakeKeyValueStore()

    private fun viewModel() = PreferencesViewModel(keyValues)

    @Test
    fun `empty store yields the product-scope defaults`() {
        val state = viewModel().state.value
        assertFalse(state.pauseSync)
        assertFalse(state.privateMode)
        assertTrue(state.autoApplyRemote)
        assertEquals(PreferenceKeys.DEFAULT_RETENTION_DAYS, state.retentionDays)
        assertTrue(state.autoExpire)
    }

    @Test
    fun `toggles persist under the shared settings keys immediately`() {
        val model = viewModel()
        model.setPauseSync(true)
        model.setPrivateMode(true)
        model.setAutoApplyRemote(false)

        assertEquals("true", keyValues.map[PreferenceKeys.PAUSE_SYNC])
        assertEquals("true", keyValues.map[PreferenceKeys.PRIVATE_MODE])
        assertEquals("false", keyValues.map[PreferenceKeys.AUTO_APPLY_REMOTE])

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
    fun `auto expire off means keep forever and on restores the default`() {
        val model = viewModel()

        model.setAutoExpire(false)
        assertEquals("0", keyValues.map[PreferenceKeys.RETENTION_DAYS])
        assertFalse(model.state.value.autoExpire)

        model.setAutoExpire(true)
        assertEquals(
            PreferenceKeys.DEFAULT_RETENTION_DAYS.toString(),
            keyValues.map[PreferenceKeys.RETENTION_DAYS],
        )
        assertTrue(model.state.value.autoExpire)
    }

    @Test
    fun `negative retention is coerced to keep forever`() {
        val model = viewModel()
        model.setRetentionDays(-5)
        assertEquals(0, model.state.value.retentionDays)
        assertEquals("0", keyValues.map[PreferenceKeys.RETENTION_DAYS])
    }

    @Test
    fun `corrupt stored values fall back to the defaults`() {
        keyValues.map[PreferenceKeys.PAUSE_SYNC] = "definitely"
        keyValues.map[PreferenceKeys.AUTO_APPLY_REMOTE] = ""
        keyValues.map[PreferenceKeys.RETENTION_DAYS] = "soon"

        val state = viewModel().state.value
        assertFalse(state.pauseSync)
        assertTrue(state.autoApplyRemote)
        assertEquals(PreferenceKeys.DEFAULT_RETENTION_DAYS, state.retentionDays)
    }

    @Test
    fun `flag parsing is case-insensitive`() {
        keyValues.map[PreferenceKeys.PRIVATE_MODE] = "TRUE"
        keyValues.map[PreferenceKeys.AUTO_APPLY_REMOTE] = "False"

        val state = viewModel().state.value
        assertTrue(state.privateMode)
        assertFalse(state.autoApplyRemote)
    }
}

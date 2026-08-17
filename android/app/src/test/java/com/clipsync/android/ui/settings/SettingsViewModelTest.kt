package com.clipsync.android.ui.settings

import com.clipsync.android.storage.createTestClipRepository
import com.clipsync.android.ui.HealthTone
import com.clipsync.android.ui.HealthValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    @Before
    fun installMain() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun resetMain() {
        Dispatchers.resetMain()
    }

    @Test
    fun `pause is persisted in clip repository settings`() {
        val repo = createTestClipRepository()
        val model = settingsModel(repo)
        assertFalse(model.state.value.paused)
        model.setPaused(true)
        assertTrue(model.state.value.paused)
        assertTrue(parseSettingFlag(repo.getSettingBlocking(SETTING_IS_PAUSED)))
        model.close()
    }

    @Test
    fun `private mode is persisted in clip repository settings`() {
        val repo = createTestClipRepository()
        val model = settingsModel(repo)
        model.setPrivateMode(true)
        assertTrue(model.state.value.privateMode)
        assertTrue(parseSettingFlag(repo.getSettingBlocking(SETTING_IS_PRIVATE_MODE)))
        model.close()
    }

    @Test
    fun `auto_apply_remote defaults on and persists off`() {
        val repo = createTestClipRepository()
        val model = settingsModel(repo)
        assertTrue(model.state.value.autoApplyRemote)
        model.setAutoApplyRemote(false)
        assertFalse(model.state.value.autoApplyRemote)
        assertFalse(parseSettingFlag(repo.getSettingBlocking(SETTING_AUTO_APPLY_REMOTE), default = true))
        model.close()
    }

    @Test
    fun `saved settings are reloaded`() {
        val repo = createTestClipRepository()
        kotlinx.coroutines.runBlocking {
            repo.setSetting(SETTING_IS_PAUSED, "true")
            repo.setSetting(SETTING_IS_PRIVATE_MODE, "True")
            repo.setSetting(SETTING_AUTO_APPLY_REMOTE, "false")
        }
        val model = settingsModel(repo)
        assertTrue(model.state.value.paused)
        assertTrue(model.state.value.privateMode)
        assertFalse(model.state.value.autoApplyRemote)
        model.close()
    }

    @Test
    fun `network service read and write cards stay independent`() {
        val repo = createTestClipRepository()
        val model = SettingsViewModel(
            repository = repo,
            syncStatus = FixedSyncStatusProvider(
                SyncConnectionStatus(paired = true, windowsReachable = true, serviceRunning = false),
            ),
            capabilities = FixedCapabilityStatus(
                read = HealthValue("Foreground only", HealthTone.NEUTRAL),
                write = HealthValue("Ready", HealthTone.GOOD),
            ),
        )
        val cards = model.state.value
        assertEquals(HealthTone.GOOD, cards.network.tone)
        assertNotEquals(HealthTone.GOOD, cards.service.tone)
        assertEquals(HealthTone.NEUTRAL, cards.read.tone)
        assertEquals(HealthTone.GOOD, cards.write.tone)
        assertNotEquals(cards.network.tone, cards.service.tone)
        assertNotEquals(cards.network.tone, cards.read.tone)
        model.close()
    }

    @Test
    fun `windows unreachable does not paint read or write green`() {
        val repo = createTestClipRepository()
        val model = SettingsViewModel(
            repository = repo,
            syncStatus = FixedSyncStatusProvider(
                SyncConnectionStatus(paired = true, windowsReachable = false, serviceRunning = false),
            ),
            capabilities = FixedCapabilityStatus(
                read = HealthValue("Foreground only", HealthTone.NEUTRAL),
                write = HealthValue("Not probed", HealthTone.NEUTRAL),
            ),
        )
        assertEquals(HealthTone.WARNING, model.state.value.network.tone)
        assertEquals(HealthTone.NEUTRAL, model.state.value.read.tone)
        assertEquals(HealthTone.NEUTRAL, model.state.value.write.tone)
        model.close()
    }

    private fun settingsModel(
        repo: com.clipsync.android.storage.ClipRepository,
    ) = SettingsViewModel(
        repository = repo,
        syncStatus = FixedSyncStatusProvider(
            SyncConnectionStatus(paired = false, windowsReachable = false, serviceRunning = false),
        ),
        capabilities = FixedCapabilityStatus(
            read = HealthValue("Foreground only", HealthTone.NEUTRAL),
            write = HealthValue("Not probed", HealthTone.NEUTRAL),
        ),
    )
}

private fun com.clipsync.android.storage.ClipRepository.getSettingBlocking(key: String): String? =
    kotlinx.coroutines.runBlocking { getSetting(key) }

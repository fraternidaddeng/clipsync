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
    fun `blacklist toggle and extra list persist and reload`() {
        val repo = createTestClipRepository()
        val model = settingsModel(repo)
        assertTrue(model.state.value.blacklistEnabled)
        assertEquals("", model.state.value.blacklistExtra)
        model.setBlacklistEnabled(false)
        model.setBlacklistExtra("com.example.vault, com.ok.app")
        assertFalse(model.state.value.blacklistEnabled)
        assertEquals("com.example.vault, com.ok.app", model.state.value.blacklistExtra)
        assertFalse(parseSettingFlag(repo.getSettingBlocking(SETTING_CAPTURE_BLACKLIST_ENABLED), default = true))
        assertEquals("com.example.vault, com.ok.app", repo.getSettingBlocking(SETTING_CAPTURE_BLACKLIST_EXTRA))
        model.close()

        val reloaded = settingsModel(repo)
        assertFalse(reloaded.state.value.blacklistEnabled)
        assertEquals("com.example.vault, com.ok.app", reloaded.state.value.blacklistExtra)
        reloaded.close()
    }

    @Test
    fun `background sync and boot recovery persist and boot recovery defaults off`() {
        val repo = createTestClipRepository()
        val model = settingsModel(repo)
        assertFalse(model.state.value.backgroundSync)
        assertFalse(model.state.value.bootRecoveryEnabled)
        model.setBackgroundSync(true)
        model.setBootRecoveryEnabled(true)
        assertTrue(model.state.value.backgroundSync)
        assertTrue(model.state.value.bootRecoveryEnabled)
        assertTrue(parseSettingFlag(repo.getSettingBlocking(SETTING_BACKGROUND_SYNC)))
        assertTrue(parseSettingFlag(repo.getSettingBlocking(SETTING_BOOT_RECOVERY_ENABLED)))
        model.close()
    }

    @Test
    fun `service card shows needs recovery without painting network green`() {
        val repo = createTestClipRepository()
        val model = SettingsViewModel(
            repository = repo,
            syncStatus = FixedSyncStatusProvider(
                SyncConnectionStatus(
                    paired = true,
                    windowsReachable = false,
                    serviceRunning = false,
                    serviceNeedsRecovery = true,
                ),
            ),
            capabilities = FixedCapabilityStatus(
                read = HealthValue("Foreground only", HealthTone.NEUTRAL),
                write = HealthValue("Not probed", HealthTone.NEUTRAL),
            ),
        )
        assertEquals("Needs recovery", model.state.value.service.label)
        assertEquals(HealthTone.WARNING, model.state.value.service.tone)
        assertNotEquals(HealthTone.GOOD, model.state.value.network.tone)
        model.close()
    }

    @Test
    fun `network card turns connected when status becomes ready`() {
        val repo = createTestClipRepository()
        val sync = MutableSyncStatusProvider(
            SyncConnectionStatus(paired = true, windowsReachable = false, serviceRunning = true),
        )
        val model = SettingsViewModel(
            repository = repo,
            syncStatus = sync,
            capabilities = FixedCapabilityStatus(
                read = HealthValue("Foreground only", HealthTone.NEUTRAL),
                write = HealthValue("Not probed", HealthTone.NEUTRAL),
            ),
        )
        assertEquals(HealthTone.WARNING, model.state.value.network.tone)
        assertEquals("Windows unreachable", model.state.value.network.label)
        sync.set(SyncConnectionStatus(paired = true, windowsReachable = true, serviceRunning = true))
        assertEquals(HealthTone.GOOD, model.state.value.network.tone)
        assertEquals("Connected", model.state.value.network.label)
        assertEquals(HealthTone.NEUTRAL, model.state.value.read.tone)
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

    @Test
    fun `retention days default to 30 and clamp to 1 through 3650`() {
        val repo = createTestClipRepository()
        val model = settingsModel(repo)
        assertEquals(30, model.state.value.retentionDays)
        assertEquals(null, repo.getSettingBlocking(SETTING_RETENTION_DAYS))

        model.setRetentionDays("0")
        assertEquals(1, model.state.value.retentionDays)
        assertEquals("1", repo.getSettingBlocking(SETTING_RETENTION_DAYS))

        model.setRetentionDays("3651")
        assertEquals(3650, model.state.value.retentionDays)
        assertEquals("3650", repo.getSettingBlocking(SETTING_RETENTION_DAYS))

        model.setRetentionDays("45")
        assertEquals(45, model.state.value.retentionDays)
        assertEquals("45", repo.getSettingBlocking(SETTING_RETENTION_DAYS))
        model.close()
    }

    @Test
    fun `export of two clips writes two jsonl lines through the seam`() {
        val repo = createTestClipRepository()
        kotlinx.coroutines.runBlocking {
            repo.captureLocalText("first export", nowMs = 1L)
            repo.captureLocalText("second export", nowMs = 2L)
        }
        val model = settingsModel(repo)
        var written = "unset"
        kotlinx.coroutines.runBlocking { model.exportTo { written = it } }
        val lines = written.trimEnd('\n').lines().filter { it.isNotEmpty() }
        assertEquals(2, lines.size)
        assertEquals(SettingsExportNotice.DONE, model.state.value.exportNotice)
        model.close()
    }

    @Test
    fun `export of an empty repository writes an empty string and succeeds`() {
        val repo = createTestClipRepository()
        val model = settingsModel(repo)
        var written = "unset"
        kotlinx.coroutines.runBlocking { model.exportTo { written = it } }
        assertEquals("", written)
        assertEquals(SettingsExportNotice.DONE, model.state.value.exportNotice)
        model.close()
    }

    @Test
    fun `importFrom restores exported clips through the seam and reports counts`() {
        val source = createTestClipRepository()
        kotlinx.coroutines.runBlocking {
            source.captureLocalText("first import", nowMs = 1L)
            source.captureLocalText("second import", nowMs = 2L)
        }
        val exporter = settingsModel(source)
        var encoded = ""
        kotlinx.coroutines.runBlocking { exporter.exportTo { encoded = it } }
        exporter.close()

        val dest = createTestClipRepository()
        val importer = settingsModel(dest)
        kotlinx.coroutines.runBlocking { importer.importFrom { encoded } }
        assertEquals(SettingsImportNotice.DONE, importer.state.value.importNotice)
        assertEquals(2, importer.state.value.importImported)
        assertEquals(0, importer.state.value.importSkipped)
        val restored =
            kotlinx.coroutines.runBlocking { dest.search("") }
                .map { it.content }
                .toSet()
        assertEquals(setOf("first import", "second import"), restored)
        importer.close()
    }

    @Test
    fun `importFrom reports failed when the source throws`() {
        val dest = createTestClipRepository()
        val importer = settingsModel(dest)
        kotlinx.coroutines.runBlocking {
            importer.importFrom { error("read failed") }
        }
        assertEquals(SettingsImportNotice.FAILED, importer.state.value.importNotice)
        importer.close()
    }

    @Test
    fun `re-import through the seam skips existing event ids`() {
        val source = createTestClipRepository()
        kotlinx.coroutines.runBlocking { source.captureLocalText("once", nowMs = 1L) }
        val exporter = settingsModel(source)
        var encoded = ""
        kotlinx.coroutines.runBlocking { exporter.exportTo { encoded = it } }
        exporter.close()

        val dest = createTestClipRepository()
        val importer = settingsModel(dest)
        kotlinx.coroutines.runBlocking { importer.importFrom { encoded } }
        kotlinx.coroutines.runBlocking { importer.importFrom { encoded } }
        assertEquals(SettingsImportNotice.DONE, importer.state.value.importNotice)
        assertEquals(0, importer.state.value.importImported)
        assertEquals(1, importer.state.value.importSkipped)
        assertEquals(1, kotlinx.coroutines.runBlocking { dest.search("") }.size)
        importer.close()
    }
}

private fun com.clipsync.android.storage.ClipRepository.getSettingBlocking(key: String): String? =
    kotlinx.coroutines.runBlocking { getSetting(key) }

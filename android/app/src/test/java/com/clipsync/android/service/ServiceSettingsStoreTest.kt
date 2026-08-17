package com.clipsync.android.service

import com.clipsync.android.pairing.FakeKeyValueStore
import com.clipsync.android.ui.settings.SETTING_BOOT_RECOVERY_ENABLED
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceSettingsStoreTest {
    @Test
    fun `boot recovery defaults off and is the only gate for the receiver setting`() {
        val store = ServiceSettingsStore(FakeKeyValueStore())
        assertFalse(store.bootRecoveryEnabled())
        assertFalse(store.backgroundSyncEnabled())
        store.setBootRecoveryEnabled(true)
        assertTrue(store.bootRecoveryEnabled())
        store.setBootRecoveryEnabled(false)
        assertFalse(store.bootRecoveryEnabled())
        assertFalse(FakeKeyValueStore().read(SETTING_BOOT_RECOVERY_ENABLED) == "true")
    }
}

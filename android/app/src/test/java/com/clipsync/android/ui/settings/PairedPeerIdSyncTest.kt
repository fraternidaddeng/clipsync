package com.clipsync.android.ui.settings

import com.clipsync.android.pairing.PairedPeer
import com.clipsync.android.storage.SETTING_PAIRED_PEER_ID
import com.clipsync.android.storage.TEST_PEER_DEVICE_ID
import com.clipsync.android.storage.createTestClipRepository
import com.clipsync.android.ui.pairing.PairingUiState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PairedPeerIdSyncTest {
    @Test
    fun `Paired writes paired_peer_id from PairingStore peer device id`() = runTest {
        val repo = createTestClipRepository()
        var storeReads = 0
        PairedPeerIdSync.onPairingState(
            repository = repo,
            state = PairingUiState.Paired(samplePeer()),
            peerDeviceId = {
                storeReads += 1
                TEST_PEER_DEVICE_ID
            },
        )
        assertEquals(1, storeReads)
        assertEquals(TEST_PEER_DEVICE_ID, repo.getSetting(SETTING_PAIRED_PEER_ID))
    }

    @Test
    fun `Paired does nothing when PairingStore has no peer`() = runTest {
        val repo = createTestClipRepository()
        PairedPeerIdSync.onPairingState(
            repository = repo,
            state = PairingUiState.Paired(samplePeer()),
            peerDeviceId = { null },
        )
        assertTrue(repo.getSetting(SETTING_PAIRED_PEER_ID).isNullOrEmpty())
    }

    @Test
    fun `Idle without a peer clears paired_peer_id`() = runTest {
        val repo = createTestClipRepository()
        repo.setSetting(SETTING_PAIRED_PEER_ID, TEST_PEER_DEVICE_ID)
        PairedPeerIdSync.onPairingState(
            repository = repo,
            state = PairingUiState.Idle(pairedPeer = null),
            peerDeviceId = { null },
        )
        assertEquals("", repo.getSetting(SETTING_PAIRED_PEER_ID))
    }

    private fun samplePeer() = PairedPeer(
        deviceId = TEST_PEER_DEVICE_ID,
        displayName = "DESKTOP-WIN",
        platform = "windows",
        certSha256 = "0f9a54e310154f2f4d6c2a01377549272117572a83a4d64d99a1d501bcda9c25",
        trustEpoch = 1,
        hosts = listOf("192.168.1.23"),
        port = 47654,
        pairedAtMs = 1_700_000_000_000L,
    )
}

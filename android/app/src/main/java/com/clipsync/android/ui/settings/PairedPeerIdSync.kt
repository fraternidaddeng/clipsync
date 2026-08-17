package com.clipsync.android.ui.settings

import com.clipsync.android.storage.ClipRepository
import com.clipsync.android.storage.SETTING_PAIRED_PEER_ID
import com.clipsync.android.ui.pairing.PairingUiState

/**
 * Writes [SETTING_PAIRED_PEER_ID] from [com.clipsync.android.pairing.PairingStore.peer]
 * when pairing succeeds. Does not touch pairing crypto or JSON.
 */
object PairedPeerIdSync {
    suspend fun onPairingState(
        repository: ClipRepository,
        state: PairingUiState,
        peerDeviceId: () -> String?,
    ) {
        when (state) {
            is PairingUiState.Paired -> {
                val id = peerDeviceId() ?: return
                repository.setSetting(SETTING_PAIRED_PEER_ID, id)
            }
            is PairingUiState.Idle -> {
                repository.setSetting(SETTING_PAIRED_PEER_ID, peerDeviceId().orEmpty())
            }
            else -> Unit
        }
    }
}

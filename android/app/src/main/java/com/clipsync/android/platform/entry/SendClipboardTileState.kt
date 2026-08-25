package com.clipsync.android.platform.entry

import android.service.quicksettings.Tile
import com.clipsync.android.sync.SyncConnectionState

/**
 * Pure mapping from sync/preference facts to the Quick Settings tile state, extracted so the
 * honesty contract is unit-testable (ui-gap-audit P2: the tile must not sit in the active
 * look while a tap could not deliver right now).
 *
 * ACTIVE only while a session to the peer is up: a tap sends immediately. Everything else —
 * paused, private mode, connecting, waiting for retry, not paired — is INACTIVE, never
 * UNAVAILABLE: the tap still works (it queues the clip or answers with the honest toast),
 * so the tile stays tappable and only its look tells the truth about immediacy.
 */
object SendClipboardTileState {
    fun of(
        connectionState: SyncConnectionState,
        syncPaused: Boolean,
        privateMode: Boolean,
    ): Int =
        when {
            syncPaused || privateMode -> Tile.STATE_INACTIVE
            connectionState is SyncConnectionState.Connected -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
}

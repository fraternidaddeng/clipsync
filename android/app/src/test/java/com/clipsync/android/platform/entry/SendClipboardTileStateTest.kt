package com.clipsync.android.platform.entry

import android.service.quicksettings.Tile
import com.clipsync.android.sync.SyncConnectionState
import com.clipsync.android.sync.SyncTransportKind
import org.junit.Assert.assertEquals
import org.junit.Test

class SendClipboardTileStateTest {

    private val connected = SyncConnectionState.Connected("桌面")

    @Test
    fun `connected and unpaused is the only active state`() {
        assertEquals(Tile.STATE_ACTIVE, SendClipboardTileState.of(connected, syncPaused = false, privateMode = false))
    }

    @Test
    fun `bluetooth fallback session still counts as connected`() {
        val bluetooth = SyncConnectionState.Connected("桌面", SyncTransportKind.BLUETOOTH)
        assertEquals(Tile.STATE_ACTIVE, SendClipboardTileState.of(bluetooth, syncPaused = false, privateMode = false))
    }

    @Test
    fun `paused sync makes the tile inactive even while connected`() {
        assertEquals(Tile.STATE_INACTIVE, SendClipboardTileState.of(connected, syncPaused = true, privateMode = false))
    }

    @Test
    fun `private mode makes the tile inactive even while connected`() {
        assertEquals(Tile.STATE_INACTIVE, SendClipboardTileState.of(connected, syncPaused = false, privateMode = true))
    }

    @Test
    fun `not paired is inactive not unavailable so the tap can still queue`() {
        val state = SendClipboardTileState.of(SyncConnectionState.NotPaired, syncPaused = false, privateMode = false)
        assertEquals(Tile.STATE_INACTIVE, state)
    }

    @Test
    fun `connecting is inactive because the send would queue, not deliver`() {
        val state = SendClipboardTileState.of(SyncConnectionState.Connecting, syncPaused = false, privateMode = false)
        assertEquals(Tile.STATE_INACTIVE, state)
    }

    @Test
    fun `waiting for retry is inactive`() {
        val waiting = SyncConnectionState.WaitingRetry(attempt = 2, delayMs = 4_000)
        assertEquals(Tile.STATE_INACTIVE, SendClipboardTileState.of(waiting, syncPaused = false, privateMode = false))
    }
}

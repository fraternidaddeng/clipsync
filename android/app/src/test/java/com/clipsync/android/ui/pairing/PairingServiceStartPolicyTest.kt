package com.clipsync.android.ui.pairing

import com.clipsync.android.pairing.PairedPeer
import com.clipsync.android.pairing.PairingQrPayload
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The enable-on-pairing side effect must fire exactly once per pairing ritual
 * (后台同步服务 master switch: 配对完成拨回开). The Paired state is retained by the
 * ViewModel until the user taps 完成, so an activity recreation (rotation, language
 * or theme change) replays it into SyncServiceController — and a replay that
 * re-enabled the service would resurrect one the user switched off in between,
 * breaking "off means truly off".
 */
class PairingServiceStartPolicyTest {
    private val paired = PairingUiState.Paired(peer())
    private val idleWithPeer = PairingUiState.Idle(peer())
    private val idleUnpaired = PairingUiState.Idle(pairedPeer = null)

    @Test
    fun `a fresh pairing completion enables the service once`() {
        assertTrue(PairingServiceStartPolicy.shouldEnableService(paired, alreadyHandled = false))
        assertTrue(PairingServiceStartPolicy.handledAfter(paired, alreadyHandled = false))
    }

    @Test
    fun `a recreation replay of the same Paired state never re-enables`() {
        // The flag lives in saved instance state, so the replayed composition sees true.
        assertFalse(PairingServiceStartPolicy.shouldEnableService(paired, alreadyHandled = true))
        assertTrue(PairingServiceStartPolicy.handledAfter(paired, alreadyHandled = true))
    }

    @Test
    fun `returning to idle re-arms the ritual so a re-pair fires again`() {
        // 完成 on the success pane: Paired -> Idle(peer) re-arms without stopping anything.
        assertFalse(PairingServiceStartPolicy.handledAfter(idleWithPeer, alreadyHandled = true))
        assertFalse(PairingServiceStartPolicy.shouldStopService(idleWithPeer))
        // The next ritual's Paired then fires again.
        assertTrue(PairingServiceStartPolicy.shouldEnableService(paired, alreadyHandled = false))
    }

    @Test
    fun `only a truly forgotten peer stops the service`() {
        assertTrue(PairingServiceStartPolicy.shouldStopService(idleUnpaired))
        assertFalse(PairingServiceStartPolicy.shouldStopService(paired))
        assertFalse(PairingServiceStartPolicy.shouldStopService(PairingUiState.Submitting("DESKTOP-WIN")))
    }

    @Test
    fun `in-flight steps neither fire nor change the handled flag`() {
        val inFlight =
            listOf(
                PairingUiState.Review(qr(), certificateChanged = false),
                PairingUiState.Submitting("DESKTOP-WIN"),
                PairingUiState.Failed(PairingFailure.TIMEOUT),
            )
        inFlight.forEach { state ->
            assertFalse(PairingServiceStartPolicy.shouldEnableService(state, alreadyHandled = false))
            assertFalse(PairingServiceStartPolicy.shouldStopService(state))
            assertTrue(PairingServiceStartPolicy.handledAfter(state, alreadyHandled = true))
            assertFalse(PairingServiceStartPolicy.handledAfter(state, alreadyHandled = false))
        }
    }

    private fun peer() =
        PairedPeer(
            deviceId = "11111111-1111-4111-8111-111111111111",
            displayName = "DESKTOP-WIN",
            platform = "windows",
            certSha256 = "0f9a54e310154f2f4d6c2a01377549272117572a83a4d64d99a1d501bcda9c25",
            trustEpoch = 1,
            hosts = listOf("192.168.1.23"),
            port = 47654,
            pairedAtMs = 1_755_000_000_000,
        )

    private fun qr() =
        PairingQrPayload(
            kind = "pair_qr",
            version = 1,
            hosts = listOf("192.168.1.23"),
            port = 47654,
            deviceId = "11111111-1111-4111-8111-111111111111",
            displayName = "DESKTOP-WIN",
            certSha256 = "0f9a54e310154f2f4d6c2a01377549272117572a83a4d64d99a1d501bcda9c25",
            token = "tok",
            expiresAtMs = 1_755_000_600_000,
        )
}

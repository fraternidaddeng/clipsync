package com.clipsync.android.ui.pairing

import com.clipsync.android.pairing.FakeKeyValueStore
import com.clipsync.android.pairing.FakeSecretProtector
import com.clipsync.android.pairing.HostAttempt
import com.clipsync.android.pairing.LocalIpv4
import com.clipsync.android.pairing.LocalNetworkSource
import com.clipsync.android.pairing.PairingConfirmApi
import com.clipsync.android.pairing.PairingConfirmOutcome
import com.clipsync.android.pairing.PairingConfirmRequest
import com.clipsync.android.pairing.PairingConfirmResponse
import com.clipsync.android.pairing.PairingDocumentKinds
import com.clipsync.android.pairing.PairingErrorCodes
import com.clipsync.android.pairing.PairingJson
import com.clipsync.android.pairing.PairingPhase
import com.clipsync.android.pairing.PairingQrPayload
import com.clipsync.android.pairing.PairingStore
import com.clipsync.android.pairing.UnreachableReason
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PairingViewModelTest {
    private val keyValues = FakeKeyValueStore()
    private val store = PairingStore(keyValues, FakeSecretProtector())
    private val dispatcher = UnconfinedTestDispatcher()

    /**
     * Scripted confirm API; records the request the ViewModel actually built. When [gate] is
     * set the call suspends until it completes, so tests can watch the Submitting state live
     * and drive [onPhase] themselves.
     */
    private class FakeConfirmApi(
        var outcome: (PairingConfirmRequest) -> PairingConfirmOutcome,
    ) : PairingConfirmApi {
        var lastRequest: PairingConfirmRequest? = null
        var gate: CompletableDeferred<Unit>? = null
        var onPhase: ((PairingPhase) -> Unit)? = null

        override suspend fun confirm(
            qr: PairingQrPayload,
            request: PairingConfirmRequest,
            onPhase: (PairingPhase) -> Unit,
        ): PairingConfirmOutcome {
            lastRequest = request
            this.onPhase = onPhase
            gate?.await()
            return outcome(request)
        }
    }

    private val api = FakeConfirmApi { approvedOutcome() }

    private var localIpv4: LocalIpv4? = null

    @Before
    fun installMainDispatcher() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    private fun viewModel() =
        PairingViewModel(
            store,
            api,
            localNameFallback = "Pixel 8",
            nowMs = { 1_755_000_000_000 },
            localNetwork = LocalNetworkSource { localIpv4 },
        )

    private fun qrJson(
        deviceId: String = WINDOWS_ID,
        cert: String = CERT_A,
        hosts: List<String> = listOf("192.168.1.23"),
    ): String =
        PairingJson.serialize(
            PairingQrPayload(
                kind = PairingDocumentKinds.QR,
                version = 1,
                hosts = hosts,
                port = 47654,
                deviceId = deviceId,
                displayName = "DESKTOP-WIN",
                certSha256 = cert,
                token = TOKEN,
                expiresAtMs = 1_755_064_500_000,
            ),
        )

    private fun approvedOutcome(
        deviceId: String = WINDOWS_ID,
        secret: String = SECRET,
        epoch: Long = 1,
    ) = PairingConfirmOutcome.Approved(
        PairingConfirmResponse(
            kind = PairingDocumentKinds.CONFIRM_RESPONSE,
            version = 1,
            deviceId = deviceId,
            displayName = "DESKTOP-WIN",
            platform = "windows",
            pairSecret = secret,
            trustEpoch = epoch,
        ),
        viaHost = "192.168.1.23",
    )

    private fun advanceSeconds(seconds: Int) {
        dispatcher.scheduler.advanceTimeBy(seconds * 1_000L)
        dispatcher.scheduler.runCurrent()
    }

    @Test
    fun `garbage payload fails without leaving idle permanently`() {
        val model = viewModel()
        model.onPayload("not json at all")
        assertEquals(PairingUiState.Failed(PairingFailure.INVALID_PAYLOAD), model.state.value)
        model.reset()
        assertTrue(model.state.value is PairingUiState.Idle)
    }

    @Test
    fun `own device id is refused`() {
        val model = viewModel()
        model.onPayload(qrJson(deviceId = store.localDeviceId()))
        assertEquals(PairingUiState.Failed(PairingFailure.OWN_DEVICE), model.state.value)
    }

    @Test
    fun `valid payload lands in review and confirm stores the pairing`() {
        val model = viewModel()
        model.onPayload(qrJson())
        val review = model.state.value as PairingUiState.Review
        assertFalse(review.certificateChanged)

        model.confirm()
        val paired = model.state.value as PairingUiState.Paired
        assertEquals(WINDOWS_ID, paired.peer.deviceId)
        assertEquals(CERT_A, paired.peer.certSha256)

        // The confirm request carried this phone's identity, not the computer's.
        val request = requireNotNull(api.lastRequest)
        assertEquals(store.localDeviceId(), request.deviceId)
        assertEquals("android", request.platform)
        assertEquals(TOKEN, request.token)

        // The stored secret round-trips to the exact announced bytes.
        assertEquals(SECRET, PairingJson.encodeBase64Url(requireNotNull(store.pairSecret())))
    }

    @Test
    fun `submitting starts in the connecting phase and counts the seconds waited`() {
        api.gate = CompletableDeferred()
        val model = viewModel()
        model.onPayload(qrJson(hosts = listOf("192.168.1.23", "10.0.0.5", "100.64.0.9")))
        model.confirm()

        val connecting = PairingUiState.Submitting("DESKTOP-WIN", hostCount = 3)
        assertEquals(connecting, model.state.value)

        advanceSeconds(2)
        assertEquals(connecting.copy(elapsedSeconds = 2), model.state.value)

        // The client reports that a host has the request; the counter keeps running across it.
        requireNotNull(api.onPhase)(PairingPhase.AWAITING_APPROVAL)
        assertEquals(
            connecting.copy(phase = PairingPhase.AWAITING_APPROVAL, elapsedSeconds = 2),
            model.state.value,
        )
        advanceSeconds(1)
        assertEquals(3, (model.state.value as PairingUiState.Submitting).elapsedSeconds)

        requireNotNull(api.gate).complete(Unit)
        assertTrue(model.state.value is PairingUiState.Paired)

        // The ticker died with the submission: more time changes nothing.
        advanceSeconds(5)
        assertTrue(model.state.value is PairingUiState.Paired)
    }

    @Test
    fun `a failed submission also stops the counter`() {
        api.gate = CompletableDeferred()
        api.outcome = { PairingConfirmOutcome.Denied(PairingErrorCodes.REJECTED) }
        val model = viewModel()
        model.onPayload(qrJson())
        model.confirm()
        advanceSeconds(4)
        assertEquals(4, (model.state.value as PairingUiState.Submitting).elapsedSeconds)

        requireNotNull(api.gate).complete(Unit)
        assertEquals(PairingUiState.Failed(PairingFailure.REJECTED), model.state.value)
        advanceSeconds(5)
        assertEquals(PairingUiState.Failed(PairingFailure.REJECTED), model.state.value)
    }

    @Test
    fun `reset while submitting abandons the attempt instead of letting its outcome land later`() {
        api.gate = CompletableDeferred()
        val model = viewModel()
        model.onPayload(qrJson())
        model.confirm()
        advanceSeconds(1)

        model.reset()
        assertEquals(PairingUiState.Idle(pairedPeer = null), model.state.value)

        requireNotNull(api.gate).complete(Unit)
        advanceSeconds(2)
        assertEquals(PairingUiState.Idle(pairedPeer = null), model.state.value)
        assertNull(store.peer())
    }

    @Test
    fun `a late phase report after the outcome landed is ignored`() {
        val model = viewModel()
        model.onPayload(qrJson())
        model.confirm()
        assertTrue(model.state.value is PairingUiState.Paired)

        requireNotNull(api.onPhase)(PairingPhase.AWAITING_APPROVAL)
        assertTrue(model.state.value is PairingUiState.Paired)
    }

    @Test
    fun `while scanning only the first payload is accepted`() {
        val model = viewModel()
        model.onPayload(qrJson())
        val review = model.state.value
        model.onPayload(qrJson(deviceId = OTHER_ID))
        assertEquals(review, model.state.value)
    }

    @Test
    fun `same device with a different certificate is a loud review warning`() {
        val model = viewModel()
        model.onPayload(qrJson(cert = CERT_A))
        model.confirm()
        assertTrue(model.state.value is PairingUiState.Paired)
        model.reset()

        model.onPayload(qrJson(cert = CERT_B))
        val review = model.state.value as PairingUiState.Review
        assertTrue(review.certificateChanged)
    }

    @Test
    fun `a different windows device is not flagged as certificate change`() {
        val model = viewModel()
        model.onPayload(qrJson())
        model.confirm()
        model.reset()

        model.onPayload(qrJson(deviceId = OTHER_ID, cert = CERT_B))
        val review = model.state.value as PairingUiState.Review
        assertFalse(review.certificateChanged)
    }

    @Test
    fun `responder claiming another identity than the QR is a protocol failure`() {
        api.outcome = { approvedOutcome(deviceId = OTHER_ID) }
        val model = viewModel()
        model.onPayload(qrJson())
        model.confirm()
        assertEquals(PairingUiState.Failed(PairingFailure.PROTOCOL), model.state.value)
        assertNull(store.peer())
    }

    @Test
    fun `malformed secret in an approved response is a protocol failure`() {
        api.outcome = { approvedOutcome(secret = "tooShort") }
        val model = viewModel()
        model.onPayload(qrJson())
        model.confirm()
        assertEquals(PairingUiState.Failed(PairingFailure.PROTOCOL), model.state.value)
        assertNull(store.peer())
    }

    @Test
    fun `denied outcomes map to stable failure buckets`() {
        val expectations =
            mapOf(
                PairingErrorCodes.REJECTED to PairingFailure.REJECTED,
                PairingErrorCodes.TIMEOUT to PairingFailure.TIMEOUT,
                PairingErrorCodes.TOKEN_INVALID to PairingFailure.TOKEN_INVALID,
                PairingErrorCodes.TOKEN_EXPIRED to PairingFailure.TOKEN_EXPIRED,
                // 429 from the listener: a throttle, not a protocol mismatch.
                PairingErrorCodes.RATE_LIMITED to PairingFailure.RATE_LIMITED,
                PairingErrorCodes.SCHEMA_VIOLATION to PairingFailure.PROTOCOL,
            )
        for ((code, expected) in expectations) {
            api.outcome = { PairingConfirmOutcome.Denied(code) }
            val model = viewModel()
            model.onPayload(qrJson())
            model.confirm()
            assertEquals(PairingUiState.Failed(expected), model.state.value)
        }
    }

    @Test
    fun `certificate mismatch maps to its own failure`() {
        api.outcome = { PairingConfirmOutcome.CertificateMismatch("192.168.1.23") }
        val model = viewModel()
        model.onPayload(qrJson())
        model.confirm()
        assertEquals(PairingUiState.Failed(PairingFailure.CERTIFICATE_MISMATCH), model.state.value)
    }

    @Test
    fun `unreachable carries the primary reason, the subnet verdict and the port`() {
        localIpv4 = LocalIpv4("192.168.1.5", prefixLength = 24)
        api.outcome = {
            PairingConfirmOutcome.Unreachable(
                listOf(
                    HostAttempt("10.0.0.5", UnreachableReason.NO_ROUTE),
                    HostAttempt("192.168.1.23", UnreachableReason.TIMEOUT),
                ),
            )
        }
        val model = viewModel()
        model.onPayload(qrJson(hosts = listOf("10.0.0.5", "192.168.1.23")))
        model.confirm()
        assertEquals(
            PairingUiState.Failed(
                PairingFailure.UNREACHABLE,
                UnreachableDetail(reason = UnreachableReason.TIMEOUT, sameSubnet = true, port = 47654),
            ),
            model.state.value,
        )
    }

    @Test
    fun `a refusal on any host outranks timeouts elsewhere`() {
        localIpv4 = LocalIpv4("10.20.30.40", prefixLength = 16)
        api.outcome = {
            PairingConfirmOutcome.Unreachable(
                listOf(
                    HostAttempt("192.168.1.23", UnreachableReason.TIMEOUT),
                    HostAttempt("100.64.0.9", UnreachableReason.REFUSED),
                ),
            )
        }
        val model = viewModel()
        model.onPayload(qrJson(hosts = listOf("192.168.1.23", "100.64.0.9")))
        model.confirm()
        val failed = model.state.value as PairingUiState.Failed
        assertEquals(PairingFailure.UNREACHABLE, failed.reason)
        assertEquals(UnreachableReason.REFUSED, requireNotNull(failed.unreachable).reason)
        assertEquals(false, failed.unreachable?.sameSubnet)
    }

    @Test
    fun `an unreadable local address leaves the subnet verdict unknown`() {
        localIpv4 = null
        api.outcome = {
            PairingConfirmOutcome.Unreachable(listOf(HostAttempt("192.168.1.23", UnreachableReason.TIMEOUT)))
        }
        val model = viewModel()
        model.onPayload(qrJson())
        model.confirm()
        assertEquals(
            UnreachableDetail(reason = UnreachableReason.TIMEOUT, sameSubnet = null, port = 47654),
            (model.state.value as PairingUiState.Failed).unreachable,
        )
    }

    @Test
    fun `cancel review returns to idle with the saved peer intact`() {
        val model = viewModel()
        model.onPayload(qrJson())
        model.confirm()
        model.reset()
        model.onPayload(qrJson(cert = CERT_B))
        model.cancelReview()
        val idle = model.state.value as PairingUiState.Idle
        assertEquals(CERT_A, requireNotNull(idle.pairedPeer).certSha256)
    }

    @Test
    fun `forget peer clears the stored pairing`() {
        val model = viewModel()
        model.onPayload(qrJson())
        model.confirm()
        model.forgetPeer()
        assertEquals(PairingUiState.Idle(pairedPeer = null), model.state.value)
        assertNull(store.peer())
    }

    private companion object {
        const val WINDOWS_ID = "11111111-1111-4111-8111-111111111111"
        const val OTHER_ID = "33333333-3333-4333-8333-333333333333"
        const val CERT_A = "0f9a54e310154f2f4d6c2a01377549272117572a83a4d64d99a1d501bcda9c25"
        const val CERT_B = "aa9a54e310154f2f4d6c2a01377549272117572a83a4d64d99a1d501bcda9c25"
        const val TOKEN = "vJ8kAqhFRWDdiWvUuJ9lPCS0jBSJ73dP9-b1JzW5Qk4"
        const val SECRET = "gY7L0N6a-C0GZWx1Vb3f9YIhE2n4q8s_x5TdKJMuwao"
    }
}

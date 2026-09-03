package com.clipsync.android.ui.pairing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.clipsync.android.pairing.BeaconExpectation
import com.clipsync.android.pairing.BeaconListener
import com.clipsync.android.pairing.BeaconStatus
import com.clipsync.android.pairing.LocalNetworkSource
import com.clipsync.android.pairing.NoResponseKind
import com.clipsync.android.pairing.PairedPeer
import com.clipsync.android.pairing.PairingConfirmApi
import com.clipsync.android.pairing.PairingConfirmOutcome
import com.clipsync.android.pairing.PairingConfirmRequest
import com.clipsync.android.pairing.PairingDocumentKinds
import com.clipsync.android.pairing.PairingErrorCodes
import com.clipsync.android.pairing.PairingJson
import com.clipsync.android.pairing.PairingPhase
import com.clipsync.android.pairing.PairingQrPayload
import com.clipsync.android.pairing.PairingStore
import com.clipsync.android.pairing.UnreachableReason
import com.clipsync.android.pairing.UnreachableReasons
import com.clipsync.android.pairing.anyHostOnSameSubnet
import com.clipsync.android.pairing.hostsPreferringBeacon
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException

/** Stable failure buckets the UI can explain without echoing payload contents. */
enum class PairingFailure {
    INVALID_PAYLOAD,
    OWN_DEVICE,
    CERTIFICATE_MISMATCH,
    UNREACHABLE,
    REJECTED,
    TIMEOUT,
    TOKEN_INVALID,
    TOKEN_EXPIRED,
    RATE_LIMITED,

    /** Connected, request sent, no answer within the read timeout. */
    NO_RESPONSE,

    /** TLS failed for a reason other than the certificate pin. */
    TLS_FAILURE,
    PROTOCOL,
}

/**
 * What the failure screen can add to [PairingFailure.UNREACHABLE]: the dominant reason across
 * the QR hosts, whether any of them shares the phone's IPv4 network (null when the phone's own
 * address could not be read — stated as unknown, never guessed), the port that was dialed, and
 * whether this PC's LAN beacon was heard — the one fact that turns "maybe a firewall" into
 * "the PC is right here and still not answering on TCP".
 */
data class UnreachableDetail(
    val reason: UnreachableReason,
    val sameSubnet: Boolean?,
    val port: Int,
    val beaconSeen: Boolean = false,
)

sealed interface PairingUiState {
    /**
     * No pairing in progress; shows the saved peer when one exists. [cancelledNotice] is the
     * one-shot line after a cancelled submission: the confirm may already have spent the token.
     */
    data class Idle(
        val pairedPeer: PairedPeer?,
        val cancelledNotice: Boolean = false,
    ) : PairingUiState

    /**
     * A scanned/pasted payload awaiting the user's explicit confirmation of name and
     * fingerprint. [certificateChanged] means the same Windows device is presenting a
     * different certificate than the pinned one — shown as a loud warning, never silent.
     */
    data class Review(
        val qr: PairingQrPayload,
        val certificateChanged: Boolean,
        val beacon: BeaconStatus = BeaconStatus.Off,
    ) : PairingUiState

    /** The confirm call is in flight; [elapsedSeconds] ticks once a second while it lasts. */
    data class Submitting(
        val peerName: String,
        val hostCount: Int,
        val phase: PairingPhase = PairingPhase.CONNECTING,
        val elapsedSeconds: Int = 0,
        val beacon: BeaconStatus = BeaconStatus.Off,
    ) : PairingUiState

    data class Paired(
        val peer: PairedPeer,
    ) : PairingUiState

    data class Failed(
        val reason: PairingFailure,
        val unreachable: UnreachableDetail? = null,
        val beacon: BeaconStatus = BeaconStatus.Off,
    ) : PairingUiState
}

class PairingViewModel(
    private val store: PairingStore,
    private val client: PairingConfirmApi,
    private val localNameFallback: String,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val localNetwork: LocalNetworkSource = LocalNetworkSource { null },
    private val beacon: BeaconListener = NoBeaconListener,
) : ViewModel() {
    private val mutableState = MutableStateFlow<PairingUiState>(PairingUiState.Idle(store.peer()))

    val state: StateFlow<PairingUiState> = mutableState.asStateFlow()

    private var submission: Job? = null

    /** The QR in hand from Review until the ritual ends; the beacon listens only while it is set. */
    private var scannedQr: PairingQrPayload? = null

    private var pageVisible = false

    private var listening = false

    /** Feeds one scanned or pasted QR payload; only the first hit in a scan session lands. */
    fun onPayload(text: String) {
        if (mutableState.value !is PairingUiState.Idle) {
            return
        }
        val qr =
            try {
                PairingJson.parseQrPayload(text)
            } catch (_: SerializationException) {
                mutableState.value = PairingUiState.Failed(PairingFailure.INVALID_PAYLOAD)
                return
            }
        if (qr.deviceId == store.localDeviceId()) {
            mutableState.value = PairingUiState.Failed(PairingFailure.OWN_DEVICE)
            return
        }
        val existing = store.peer()
        val certificateChanged =
            existing != null &&
                existing.deviceId == qr.deviceId &&
                !existing.certSha256.equals(qr.certSha256, ignoreCase = true)
        scannedQr = qr
        mutableState.value = PairingUiState.Review(qr, certificateChanged)
        syncBeacon()
    }

    fun confirm() {
        val review = mutableState.value as? PairingUiState.Review ?: return
        // The address the PC actually broadcast from is the one most likely to answer.
        val qr = review.qr.copy(hosts = hostsPreferringBeacon(review.qr.hosts, review.beacon))
        val request =
            PairingConfirmRequest(
                kind = PairingDocumentKinds.CONFIRM_REQUEST,
                version = 1,
                token = qr.token,
                deviceId = store.localDeviceId(),
                displayName = store.localDisplayName(localNameFallback),
                platform = "android",
            )
        mutableState.value =
            PairingUiState.Submitting(qr.displayName, hostCount = qr.hosts.size, beacon = review.beacon)
        submission =
            viewModelScope.launch {
                // The ticker is a child of this job: whatever ends the submission ends it too.
                val ticker =
                    launch {
                        var seconds = 0
                        while (true) {
                            delay(TICK_MS)
                            seconds++
                            updateSubmitting { it.copy(elapsedSeconds = seconds) }
                        }
                    }
                try {
                    val outcome =
                        client.confirm(qr, request) { phase ->
                            updateSubmitting { it.copy(phase = phase) }
                        }
                    val beaconNow = (mutableState.value as? PairingUiState.Submitting)?.beacon ?: BeaconStatus.Off
                    mutableState.value =
                        when (outcome) {
                            is PairingConfirmOutcome.Approved -> saveApproved(qr, outcome)
                            is PairingConfirmOutcome.CertificateMismatch ->
                                PairingUiState.Failed(PairingFailure.CERTIFICATE_MISMATCH, beacon = beaconNow)
                            is PairingConfirmOutcome.Unreachable ->
                                PairingUiState.Failed(
                                    PairingFailure.UNREACHABLE,
                                    unreachableDetail(qr, outcome, localNetwork, beaconNow),
                                    beacon = beaconNow,
                                )
                            is PairingConfirmOutcome.Denied ->
                                PairingUiState.Failed(mapDenied(outcome.errorCode), beacon = beaconNow)
                            is PairingConfirmOutcome.NoResponse ->
                                PairingUiState.Failed(mapNoResponse(outcome.kind), beacon = beaconNow)
                            is PairingConfirmOutcome.ProtocolViolation ->
                                PairingUiState.Failed(PairingFailure.PROTOCOL, beacon = beaconNow)
                        }
                    syncBeacon()
                } finally {
                    ticker.cancel()
                }
            }
    }

    /**
     * Abandons an in-flight confirm. The request may already have reached the PC and spent the
     * one-time token, so the idle page says to show a fresh QR code rather than rescan this one.
     */
    fun cancelSubmission() {
        if (mutableState.value !is PairingUiState.Submitting) {
            return
        }
        submission?.cancel()
        submission = null
        scannedQr = null
        mutableState.value = PairingUiState.Idle(store.peer(), cancelledNotice = true)
        syncBeacon()
    }

    /** Back to idle from any step: review declined, failure acknowledged, or the paired card closed. */
    fun reset() {
        submission?.cancel()
        submission = null
        scannedQr = null
        mutableState.value = PairingUiState.Idle(store.peer())
        syncBeacon()
    }

    fun forgetPeer() {
        store.forgetPeer()
        scannedQr = null
        mutableState.value = PairingUiState.Idle(pairedPeer = null)
        syncBeacon()
    }

    /** The pairing page reports when it is on screen; the beacon listener only runs while it is. */
    fun setPageVisible(visible: Boolean) {
        pageVisible = visible
        syncBeacon()
    }

    override fun onCleared() {
        pageVisible = false
        syncBeacon()
    }

    /**
     * Listen exactly while a QR code is in hand (Review / Submitting / Failed) and the page is
     * on screen; anything else — idle, paired, page left, ViewModel gone — releases socket and lock.
     */
    private fun syncBeacon() {
        val qr = scannedQr
        val wanted = pageVisible && qr != null && mutableState.value.holdsQr()
        if (wanted == listening) {
            return
        }
        listening = wanted
        if (wanted && qr != null) {
            beacon.start(BeaconExpectation(qr.deviceId, qr.certSha256)) { status ->
                mutableState.update { it.withBeacon(status) }
            }
        } else {
            beacon.stop()
            mutableState.update { it.withBeacon(BeaconStatus.Off) }
        }
    }

    private fun updateSubmitting(transform: (PairingUiState.Submitting) -> PairingUiState.Submitting) {
        mutableState.update { current ->
            if (current is PairingUiState.Submitting) transform(current) else current
        }
    }

    private fun saveApproved(
        qr: PairingQrPayload,
        outcome: PairingConfirmOutcome.Approved,
    ): PairingUiState {
        // The responder must be the same identity the QR claimed, with a well-formed secret;
        // anything else is a protocol violation, not a pairing.
        val secret = PairingJson.decodeBase64Url256(outcome.response.pairSecret)
        if (outcome.response.deviceId != qr.deviceId || secret == null) {
            return PairingUiState.Failed(PairingFailure.PROTOCOL)
        }
        store.savePeer(qr, outcome.response, secret, nowMs())
        val saved = store.peer()
        return if (saved == null) PairingUiState.Failed(PairingFailure.PROTOCOL) else PairingUiState.Paired(saved)
    }

    /** A stand-in for hosts (tests, previews) that never hears anything; the status stays Off. */
    private object NoBeaconListener : BeaconListener {
        override fun start(
            expectation: BeaconExpectation,
            onStatus: (BeaconStatus) -> Unit,
        ) = Unit

        override fun stop() = Unit
    }

    companion object {
        private const val TICK_MS = 1_000L

        fun factory(
            store: PairingStore,
            client: PairingConfirmApi,
            localNameFallback: String,
            localNetwork: LocalNetworkSource = LocalNetworkSource { null },
            beacon: BeaconListener = NoBeaconListener,
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    PairingViewModel(
                        store,
                        client,
                        localNameFallback,
                        localNetwork = localNetwork,
                        beacon = beacon,
                    ) as T
            }
    }
}

/** Review, Submitting and Failed all follow a parsed QR code; only they can carry a beacon verdict. */
private fun PairingUiState.holdsQr(): Boolean =
    this is PairingUiState.Review || this is PairingUiState.Submitting || this is PairingUiState.Failed

private fun PairingUiState.withBeacon(status: BeaconStatus): PairingUiState =
    when (this) {
        is PairingUiState.Review -> copy(beacon = status)
        is PairingUiState.Submitting -> copy(beacon = status)
        is PairingUiState.Failed ->
            copy(
                beacon = status,
                // A sighting after the failure still proves the PC is here; it never un-proves it.
                unreachable = unreachable?.let { it.copy(beaconSeen = it.beaconSeen || status is BeaconStatus.Seen) },
            )
        is PairingUiState.Idle, is PairingUiState.Paired -> this
    }

private fun unreachableDetail(
    qr: PairingQrPayload,
    outcome: PairingConfirmOutcome.Unreachable,
    localNetwork: LocalNetworkSource,
    beacon: BeaconStatus,
): UnreachableDetail {
    val local = runCatching { localNetwork.currentIpv4() }.getOrNull()
    return UnreachableDetail(
        reason = UnreachableReasons.primary(outcome.attempts.map { it.reason }),
        sameSubnet = local?.let { anyHostOnSameSubnet(qr.hosts, it.address, it.prefixLength) },
        port = qr.port,
        beaconSeen = beacon is BeaconStatus.Seen,
    )
}

private fun mapDenied(code: String): PairingFailure =
    when (code) {
        PairingErrorCodes.REJECTED -> PairingFailure.REJECTED
        PairingErrorCodes.TIMEOUT -> PairingFailure.TIMEOUT
        PairingErrorCodes.TOKEN_INVALID -> PairingFailure.TOKEN_INVALID
        PairingErrorCodes.TOKEN_EXPIRED -> PairingFailure.TOKEN_EXPIRED
        PairingErrorCodes.RATE_LIMITED -> PairingFailure.RATE_LIMITED
        else -> PairingFailure.PROTOCOL
    }

private fun mapNoResponse(kind: NoResponseKind): PairingFailure =
    when (kind) {
        NoResponseKind.READ_TIMEOUT -> PairingFailure.NO_RESPONSE
        NoResponseKind.TLS_FAILURE -> PairingFailure.TLS_FAILURE
    }

package com.clipsync.android.ui.pairing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.clipsync.android.pairing.LocalNetworkSource
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
    PROTOCOL,
}

/**
 * What the failure screen can add to [PairingFailure.UNREACHABLE]: the dominant reason across
 * the QR hosts, whether any of them shares the phone's IPv4 network (null when the phone's own
 * address could not be read — stated as unknown, never guessed), and the port that was dialed.
 */
data class UnreachableDetail(
    val reason: UnreachableReason,
    val sameSubnet: Boolean?,
    val port: Int,
)

sealed interface PairingUiState {
    /** No pairing in progress; shows the saved peer when one exists. */
    data class Idle(
        val pairedPeer: PairedPeer?,
    ) : PairingUiState

    /**
     * A scanned/pasted payload awaiting the user's explicit confirmation of name and
     * fingerprint. [certificateChanged] means the same Windows device is presenting a
     * different certificate than the pinned one — shown as a loud warning, never silent.
     */
    data class Review(
        val qr: PairingQrPayload,
        val certificateChanged: Boolean,
    ) : PairingUiState

    /** The confirm call is in flight; [elapsedSeconds] ticks once a second while it lasts. */
    data class Submitting(
        val peerName: String,
        val hostCount: Int,
        val phase: PairingPhase = PairingPhase.CONNECTING,
        val elapsedSeconds: Int = 0,
    ) : PairingUiState

    data class Paired(
        val peer: PairedPeer,
    ) : PairingUiState

    data class Failed(
        val reason: PairingFailure,
        val unreachable: UnreachableDetail? = null,
    ) : PairingUiState
}

class PairingViewModel(
    private val store: PairingStore,
    private val client: PairingConfirmApi,
    private val localNameFallback: String,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val localNetwork: LocalNetworkSource = LocalNetworkSource { null },
) : ViewModel() {
    private val mutableState = MutableStateFlow<PairingUiState>(PairingUiState.Idle(store.peer()))

    val state: StateFlow<PairingUiState> = mutableState.asStateFlow()

    private var submission: Job? = null

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
        mutableState.value = PairingUiState.Review(qr, certificateChanged)
    }

    fun confirm() {
        val review = mutableState.value as? PairingUiState.Review ?: return
        val request =
            PairingConfirmRequest(
                kind = PairingDocumentKinds.CONFIRM_REQUEST,
                version = 1,
                token = review.qr.token,
                deviceId = store.localDeviceId(),
                displayName = store.localDisplayName(localNameFallback),
                platform = "android",
            )
        mutableState.value = PairingUiState.Submitting(review.qr.displayName, hostCount = review.qr.hosts.size)
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
                        client.confirm(review.qr, request) { phase ->
                            updateSubmitting { it.copy(phase = phase) }
                        }
                    mutableState.value =
                        when (outcome) {
                            is PairingConfirmOutcome.Approved -> saveApproved(review.qr, outcome)
                            is PairingConfirmOutcome.CertificateMismatch ->
                                PairingUiState.Failed(PairingFailure.CERTIFICATE_MISMATCH)
                            is PairingConfirmOutcome.Unreachable ->
                                PairingUiState.Failed(PairingFailure.UNREACHABLE, unreachableDetail(review.qr, outcome))
                            is PairingConfirmOutcome.Denied -> PairingUiState.Failed(mapDenied(outcome.errorCode))
                            is PairingConfirmOutcome.ProtocolViolation ->
                                PairingUiState.Failed(PairingFailure.PROTOCOL)
                        }
                } finally {
                    ticker.cancel()
                }
            }
    }

    fun cancelReview() {
        if (mutableState.value is PairingUiState.Review) {
            mutableState.value = PairingUiState.Idle(store.peer())
        }
    }

    fun reset() {
        submission?.cancel()
        submission = null
        mutableState.value = PairingUiState.Idle(store.peer())
    }

    fun forgetPeer() {
        store.forgetPeer()
        mutableState.value = PairingUiState.Idle(pairedPeer = null)
    }

    private fun updateSubmitting(transform: (PairingUiState.Submitting) -> PairingUiState.Submitting) {
        mutableState.update { current ->
            if (current is PairingUiState.Submitting) transform(current) else current
        }
    }

    private fun unreachableDetail(
        qr: PairingQrPayload,
        outcome: PairingConfirmOutcome.Unreachable,
    ): UnreachableDetail {
        val local = runCatching { localNetwork.currentIpv4() }.getOrNull()
        return UnreachableDetail(
            reason = UnreachableReasons.primary(outcome.attempts.map { it.reason }),
            sameSubnet = local?.let { anyHostOnSameSubnet(qr.hosts, it.address, it.prefixLength) },
            port = qr.port,
        )
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

    private fun mapDenied(code: String): PairingFailure =
        when (code) {
            PairingErrorCodes.REJECTED -> PairingFailure.REJECTED
            PairingErrorCodes.TIMEOUT -> PairingFailure.TIMEOUT
            PairingErrorCodes.TOKEN_INVALID -> PairingFailure.TOKEN_INVALID
            PairingErrorCodes.TOKEN_EXPIRED -> PairingFailure.TOKEN_EXPIRED
            PairingErrorCodes.RATE_LIMITED -> PairingFailure.RATE_LIMITED
            else -> PairingFailure.PROTOCOL
        }

    companion object {
        private const val TICK_MS = 1_000L

        fun factory(
            store: PairingStore,
            client: PairingConfirmApi,
            localNameFallback: String,
            localNetwork: LocalNetworkSource = LocalNetworkSource { null },
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    PairingViewModel(store, client, localNameFallback, localNetwork = localNetwork) as T
            }
    }
}

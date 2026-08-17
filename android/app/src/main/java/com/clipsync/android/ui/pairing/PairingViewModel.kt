package com.clipsync.android.ui.pairing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.clipsync.android.pairing.PairedPeer
import com.clipsync.android.pairing.PairingConfirmApi
import com.clipsync.android.pairing.PairingConfirmOutcome
import com.clipsync.android.pairing.PairingConfirmRequest
import com.clipsync.android.pairing.PairingDocumentKinds
import com.clipsync.android.pairing.PairingErrorCodes
import com.clipsync.android.pairing.PairingJson
import com.clipsync.android.pairing.PairingQrPayload
import com.clipsync.android.pairing.PairingStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

sealed interface PairingUiState {
    /** No pairing in progress; shows the saved peer when one exists. */
    data class Idle(val pairedPeer: PairedPeer?) : PairingUiState

    /**
     * A scanned/pasted payload awaiting the user's explicit confirmation of name and
     * fingerprint. [certificateChanged] means the same Windows device is presenting a
     * different certificate than the pinned one — shown as a loud warning, never silent.
     */
    data class Review(val qr: PairingQrPayload, val certificateChanged: Boolean) : PairingUiState

    data class Submitting(val peerName: String) : PairingUiState

    data class Paired(val peer: PairedPeer) : PairingUiState

    data class Failed(val reason: PairingFailure) : PairingUiState
}

class PairingViewModel(
    private val store: PairingStore,
    private val client: PairingConfirmApi,
    private val localNameFallback: String,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val mutableState = MutableStateFlow<PairingUiState>(PairingUiState.Idle(store.peer()))

    val state: StateFlow<PairingUiState> = mutableState.asStateFlow()

    /** Feeds one scanned or pasted QR payload; only the first hit in a scan session lands. */
    fun onPayload(text: String) {
        if (mutableState.value !is PairingUiState.Idle) {
            return
        }
        val qr = try {
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
        val certificateChanged = existing != null &&
            existing.deviceId == qr.deviceId &&
            !existing.certSha256.equals(qr.certSha256, ignoreCase = true)
        mutableState.value = PairingUiState.Review(qr, certificateChanged)
    }

    fun confirm() {
        val review = mutableState.value as? PairingUiState.Review ?: return
        val request = PairingConfirmRequest(
            kind = PairingDocumentKinds.CONFIRM_REQUEST,
            version = 1,
            token = review.qr.token,
            deviceId = store.localDeviceId(),
            displayName = store.localDisplayName(localNameFallback),
            platform = "android",
        )
        mutableState.value = PairingUiState.Submitting(review.qr.displayName)
        viewModelScope.launch {
            mutableState.value = when (val outcome = client.confirm(review.qr, request)) {
                is PairingConfirmOutcome.Approved -> saveApproved(review.qr, outcome)
                is PairingConfirmOutcome.CertificateMismatch ->
                    PairingUiState.Failed(PairingFailure.CERTIFICATE_MISMATCH)
                is PairingConfirmOutcome.Unreachable ->
                    PairingUiState.Failed(PairingFailure.UNREACHABLE)
                is PairingConfirmOutcome.Denied -> PairingUiState.Failed(mapDenied(outcome.errorCode))
                is PairingConfirmOutcome.ProtocolViolation ->
                    PairingUiState.Failed(PairingFailure.PROTOCOL)
            }
        }
    }

    fun cancelReview() {
        if (mutableState.value is PairingUiState.Review) {
            mutableState.value = PairingUiState.Idle(store.peer())
        }
    }

    fun reset() {
        mutableState.value = PairingUiState.Idle(store.peer())
    }

    fun forgetPeer() {
        store.forgetPeer()
        mutableState.value = PairingUiState.Idle(pairedPeer = null)
    }

    private fun saveApproved(qr: PairingQrPayload, outcome: PairingConfirmOutcome.Approved): PairingUiState {
        // The responder must be the same identity the QR claimed; anything else is a
        // protocol violation, not a pairing.
        if (outcome.response.deviceId != qr.deviceId) {
            return PairingUiState.Failed(PairingFailure.PROTOCOL)
        }
        val secret = PairingJson.decodeBase64Url256(outcome.response.pairSecret)
            ?: return PairingUiState.Failed(PairingFailure.PROTOCOL)
        store.savePeer(qr, outcome.response, secret, nowMs())
        val saved = store.peer() ?: return PairingUiState.Failed(PairingFailure.PROTOCOL)
        return PairingUiState.Paired(saved)
    }

    private fun mapDenied(code: String): PairingFailure = when (code) {
        PairingErrorCodes.REJECTED -> PairingFailure.REJECTED
        PairingErrorCodes.TIMEOUT -> PairingFailure.TIMEOUT
        PairingErrorCodes.TOKEN_INVALID -> PairingFailure.TOKEN_INVALID
        PairingErrorCodes.TOKEN_EXPIRED -> PairingFailure.TOKEN_EXPIRED
        PairingErrorCodes.RATE_LIMITED -> PairingFailure.RATE_LIMITED
        else -> PairingFailure.PROTOCOL
    }

    companion object {
        fun factory(
            store: PairingStore,
            client: PairingConfirmApi,
            localNameFallback: String,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                PairingViewModel(store, client, localNameFallback) as T
        }
    }
}

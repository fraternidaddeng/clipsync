package com.clipsync.android.ui.pairing

/**
 * JVM-testable policy behind MainActivity's SyncServiceController: when the pairing
 * state may flip the 后台同步服务 master switch back on, and when it may stop the
 * service. Completing the pairing ritual is the one explicit "enable sync" moment,
 * and it must fire exactly once per ritual: the controller's effect re-runs with the
 * same retained [PairingUiState.Paired] after every activity recreation (rotation,
 * language or theme change) until the user taps 完成, and re-enabling on such a
 * replay would resurrect a service the user has since switched off — breaking the
 * master switch's "off means truly off" invariant. The caller keeps the handled
 * flag in saved instance state so it survives the recreations it exists to absorb.
 */
object PairingServiceStartPolicy {
    /** Enable + start exactly once per ritual: on a [PairingUiState.Paired] not yet handled. */
    fun shouldEnableService(
        state: PairingUiState,
        alreadyHandled: Boolean,
    ): Boolean = state is PairingUiState.Paired && !alreadyHandled

    /** Stop only when the peer is truly gone (forgotten); in-flight steps never stop anything. */
    fun shouldStopService(state: PairingUiState): Boolean = state is PairingUiState.Idle && state.pairedPeer == null

    /**
     * The handled flag after observing [state]: Paired marks the ritual handled (whether
     * this pass fired or a recreation replayed it), returning to Idle re-arms for the
     * next ritual, and the in-flight steps (Review/Submitting/Failed) change nothing.
     */
    fun handledAfter(
        state: PairingUiState,
        alreadyHandled: Boolean,
    ): Boolean =
        when (state) {
            is PairingUiState.Paired -> true
            is PairingUiState.Idle -> false
            else -> alreadyHandled
        }
}

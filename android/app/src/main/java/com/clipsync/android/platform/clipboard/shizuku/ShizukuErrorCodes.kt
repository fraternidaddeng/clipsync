package com.clipsync.android.platform.clipboard.shizuku

import com.clipsync.android.platform.clipboard.CapabilityState

/**
 * Seven stable 特权直读 (privileged read) error codes (plan 5.3). Never include clip text.
 * The values surface on route cards and in read-test failures, so they carry the charter
 * name (privileged host), not the client library's brand. Codes persisted or sent by an
 * older build fall through [probeReadState]'s else branch to UNAVAILABLE and self-heal on
 * the next probe.
 */
object ShizukuErrorCodes {
    const val NOT_INSTALLED = "PRIV_HOST_NOT_INSTALLED"
    const val NOT_RUNNING = "PRIV_HOST_NOT_RUNNING"
    const val NOT_AUTHORIZED = "PRIV_HOST_NOT_AUTHORIZED"
    const val BINDER_DEAD = "PRIV_HOST_BINDER_DEAD"
    const val USERSERVICE_DEAD = "PRIV_HOST_USERSERVICE_DEAD"
    const val CLIPBOARD_BINDER_DEAD = "CLIPBOARD_BINDER_DEAD"
    const val API_MISMATCH = "PRIV_HOST_API_MISMATCH"

    val ALL: Set<String> = setOf(
        NOT_INSTALLED,
        NOT_RUNNING,
        NOT_AUTHORIZED,
        BINDER_DEAD,
        USERSERVICE_DEAD,
        CLIPBOARD_BINDER_DEAD,
        API_MISMATCH,
    )

    fun probeReadState(errorCode: String): CapabilityState = when (errorCode) {
        NOT_INSTALLED, NOT_RUNNING, NOT_AUTHORIZED -> CapabilityState.NEEDS_USER_ACTION
        USERSERVICE_DEAD, CLIPBOARD_BINDER_DEAD -> CapabilityState.UNAVAILABLE
        BINDER_DEAD, API_MISMATCH -> CapabilityState.UNAVAILABLE
        else -> CapabilityState.UNAVAILABLE
    }
}

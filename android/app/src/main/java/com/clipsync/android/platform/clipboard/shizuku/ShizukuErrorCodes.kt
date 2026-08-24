package com.clipsync.android.platform.clipboard.shizuku

import com.clipsync.android.platform.clipboard.CapabilityState

/** Seven stable Shizuku clipboard error codes (plan 5.3). Never include clip text. */
object ShizukuErrorCodes {
    const val NOT_INSTALLED = "SHIZUKU_NOT_INSTALLED"
    const val NOT_RUNNING = "SHIZUKU_NOT_RUNNING"
    const val NOT_AUTHORIZED = "SHIZUKU_NOT_AUTHORIZED"
    const val BINDER_DEAD = "SHIZUKU_BINDER_DEAD"
    const val USERSERVICE_DEAD = "SHIZUKU_USERSERVICE_DEAD"
    const val CLIPBOARD_BINDER_DEAD = "CLIPBOARD_BINDER_DEAD"
    const val API_MISMATCH = "SHIZUKU_API_MISMATCH"

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

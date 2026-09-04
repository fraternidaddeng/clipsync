package com.clipsync.android.platform.clipboard.shizuku.host

/**
 * Cadence of the host's binder hand-off to the app's provider. While no app process is attached
 * the host pushes fast (the app may still be starting — the push itself starts it) and then backs
 * off; once a client is attached the push is only a keepalive, so the same binder does not hit
 * the provider every second. A client death resets the tick count so the replacement process
 * gets its binder within a second.
 */
internal object BinderResendPolicy {
    fun nextDelayMillis(
        ticksSent: Int,
        clientAttached: Boolean,
    ): Long =
        when {
            clientAttached -> PrivilegedHostConstants.BINDER_RESEND_KEEPALIVE_MS
            ticksSent < PrivilegedHostConstants.BINDER_RESEND_FAST_TICKS ->
                PrivilegedHostConstants.BINDER_RESEND_FAST_MS
            else -> PrivilegedHostConstants.BINDER_RESEND_SLOW_MS
        }
}

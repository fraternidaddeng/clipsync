package com.clipsync.android.ui.onboarding

import com.clipsync.android.ui.HealthScreenState
import com.clipsync.android.ui.health.ReadRouteId

/**
 * Facts the tutorial can safely detect on-device, so already-done steps state
 * their completion live instead of asking again — the same pattern as the
 * Windows onboarding pair step, which retires its QR and states the fact when
 * pairing completes mid-walk. Everything here is a local probe (pairing store,
 * notification surface, 特权直读 prerequisites); nothing claims remote state.
 */
data class OnboardingProgress(
    /** A Windows peer is saved in the pairing store. */
    val paired: Boolean = false,
    /** POST_NOTIFICATIONS granted and the surface not switched off in Settings. */
    val notificationsEnabled: Boolean = false,
    /** 特权直读 prerequisites all met: channel running and this app authorized. */
    val privilegedChannelReady: Boolean = false,
)

/**
 * Derives the tutorial's live facts from the conduit's own probe results, so
 * the marks can never disagree with what the 通路 page states: pairing from the
 * saved peer, notifications from the same surface probe, and the privileged
 * channel from the wizard's route steps (an unknown fact stays unmarked —
 * missing information is not completion).
 */
fun onboardingProgress(state: HealthScreenState): OnboardingProgress {
    val privileged = state.routes.firstOrNull { it.id == ReadRouteId.PRIVILEGED }
    return OnboardingProgress(
        paired = state.pairedDeviceCount > 0,
        notificationsEnabled = state.notificationsEnabled == true,
        privilegedChannelReady = privileged != null && privileged.stepsRemaining == 0,
    )
}

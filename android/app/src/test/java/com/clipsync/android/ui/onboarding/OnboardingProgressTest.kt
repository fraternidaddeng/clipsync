package com.clipsync.android.ui.onboarding

import com.clipsync.android.i18n.UiText
import com.clipsync.android.platform.clipboard.CapabilityState
import com.clipsync.android.platform.clipboard.ClipboardReadMode
import com.clipsync.android.ui.ConduitSegmentState
import com.clipsync.android.ui.ConduitStatus
import com.clipsync.android.ui.HealthScreenState
import com.clipsync.android.ui.health.ReadRouteId
import com.clipsync.android.ui.health.ReadRouteUi
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The tutorial's live completion marks derive from the conduit's own probe facts
 * (the Windows pair-step pattern): a saved peer marks pairing done, the same
 * notification probe marks the permission granted, and the 特权直读 route marks
 * ready only when the wizard itself counts zero steps remaining. Unknown facts
 * stay unmarked — missing information is not completion.
 */
class OnboardingProgressTest {
    private fun segment() =
        ConduitSegmentState(
            statusLabel = UiText.Raw("状态"),
            detail = UiText.Raw("说明"),
            status = ConduitStatus.READY,
        )

    private fun state(
        pairedDeviceCount: Int = 0,
        notificationsEnabled: Boolean? = null,
        routes: List<ReadRouteUi> = emptyList(),
    ) = HealthScreenState(
        localRead = segment(),
        localService = segment(),
        network = segment(),
        peerWrite = segment(),
        pairedDeviceCount = pairedDeviceCount,
        notificationsEnabled = notificationsEnabled,
        routes = routes,
    )

    private fun privilegedRoute(stepsRemaining: Int) =
        ReadRouteUi(
            id = ReadRouteId.PRIVILEGED,
            mode = ClipboardReadMode.SHIZUKU_EVENT,
            title = UiText.Raw("特权直读"),
            quality = 3,
            cost = UiText.Raw("成本"),
            steps = emptyList(),
            stepsRemaining = stepsRemaining,
            readState = CapabilityState.DEGRADED,
            errorCode = null,
            nextAction = null,
            preferred = true,
        )

    @Test
    fun `a fresh install marks nothing done`() {
        assertEquals(OnboardingProgress(), onboardingProgress(state()))
    }

    @Test
    fun `a saved peer marks the pairing step done`() {
        assertEquals(
            OnboardingProgress(paired = true),
            onboardingProgress(state(pairedDeviceCount = 1)),
        )
    }

    @Test
    fun `the notification mark follows the conduit's own probe`() {
        assertEquals(
            OnboardingProgress(notificationsEnabled = true),
            onboardingProgress(state(notificationsEnabled = true)),
        )
        assertEquals(
            OnboardingProgress(),
            onboardingProgress(state(notificationsEnabled = false)),
        )
        // Probe not wired: unknown is not completion.
        assertEquals(
            OnboardingProgress(),
            onboardingProgress(state(notificationsEnabled = null)),
        )
    }

    @Test
    fun `特权直读 marks ready only when the wizard counts zero steps remaining`() {
        assertEquals(
            OnboardingProgress(privilegedChannelReady = true),
            onboardingProgress(state(routes = listOf(privilegedRoute(stepsRemaining = 0)))),
        )
        assertEquals(
            OnboardingProgress(),
            onboardingProgress(state(routes = listOf(privilegedRoute(stepsRemaining = 1)))),
        )
        // No capability wiring on this build: the route is absent, nothing is claimed.
        assertEquals(OnboardingProgress(), onboardingProgress(state(routes = emptyList())))
    }
}

package com.clipsync.android.ui.onboarding

import androidx.annotation.StringRes
import com.clipsync.android.R

/** One of the three places explained on the welcome step. */
data class OnboardingTabEntry(
    @StringRes val title: Int,
    @StringRes val description: Int,
)

/**
 * A background-read route summarised on the read-path step: the same three
 * routes the conduit wizard opens, ordered by quality (charter §4.1).
 */
data class OnboardingRouteEntry(
    @StringRes val title: Int,
    @StringRes val cost: Int,
    /** Quality dots 1..3, mirroring the conduit wizard's ●●● scale. */
    val quality: Int,
    val recommended: Boolean = false,
)

/** A permission explained before the system ever asks for it. */
data class OnboardingPermissionEntry(
    @StringRes val title: Int,
    @StringRes val description: Int,
)

/** The tutorial's steps, in the order walked. */
enum class OnboardingStep {
    WELCOME,
    PAIR,
    READ_ROUTES,
    PERMISSIONS,
    FINISH,
}

/**
 * The first-run tutorial as pure data, separate from the composables: what the
 * app promises here is a commitment (honesty about capabilities, pairing lives
 * under 通路, the recommended route is 特权直读), so tests can hold the
 * structure to it. The words themselves live in strings.xml (P1#16 — the
 * default resources are the zh-Hans copy); steps reuse the exact keys the
 * destination screens speak, so the tutorial never drifts from the app.
 */
object OnboardingContent {
    /** The steps in walking order; welcome first, the send-off last. */
    val steps: List<OnboardingStep> = OnboardingStep.entries.toList()

    /** 继续 from [index]; the last step has nowhere further to go. */
    fun next(index: Int): Int = (index + 1).coerceAtMost(steps.lastIndex)

    /** 上一步 from [index]; the first step has nowhere back to go. */
    fun previous(index: Int): Int = (index - 1).coerceAtLeast(0)

    // -- step chrome -------------------------------------------------------

    @StringRes val ACTION_NEXT = R.string.onboarding_next

    @StringRes val ACTION_BACK = R.string.onboarding_back

    /** Escape hatch on every step but the last: nothing here blocks forever. */
    @StringRes val ACTION_SKIP_FOR_NOW = R.string.onboarding_skip_for_now

    @StringRes val STEP_PROGRESS = R.string.onboarding_step_progress

    // -- 1 · welcome (the original one-page introduction, kept verbatim) ----

    /** Serif greeting — the app's own voice, reusing the brand moment. */
    @StringRes val TITLE = R.string.brand_name

    @StringRes val SUBTITLE = R.string.onboarding_subtitle

    /** In dock order; the icons are matched positionally by the composable. */
    val tabs =
        listOf(
            OnboardingTabEntry(
                title = R.string.tab_history,
                description = R.string.onboarding_tab_history_desc,
            ),
            OnboardingTabEntry(
                title = R.string.tab_conduit,
                description = R.string.onboarding_tab_conduit_desc,
            ),
            OnboardingTabEntry(
                title = R.string.tab_prefs,
                description = R.string.onboarding_tab_prefs_desc,
            ),
        )

    @StringRes val HONESTY_HEADER = R.string.onboarding_honesty_header

    @StringRes val HONESTY_BODY = R.string.onboarding_honesty_body

    // -- 2 · pair with the PC ----------------------------------------------

    /** Same words as the pairing ritual itself, so nothing drifts. */
    @StringRes val PAIR_TITLE = R.string.pairing_title

    @StringRes val PAIR_BODY = R.string.pairing_intro

    val pairFacts =
        listOf(
            R.string.onboarding_pair_fact_entry,
            R.string.onboarding_pair_fact_verify,
            R.string.onboarding_pair_fact_service,
        )

    // -- 3 · the read path (特权直读 recommended) ---------------------------

    /** The conduit wizard's own title — the tutorial points at the same door. */
    @StringRes val READ_TITLE = R.string.wizard_title

    @StringRes val READ_BODY = R.string.onboarding_read_body

    /** Quality order, the recommended 特权直读 first — same keys as the wizard. */
    val routes =
        listOf(
            OnboardingRouteEntry(
                title = R.string.route_privileged,
                cost = R.string.route_privileged_cost,
                quality = 3,
                recommended = true,
            ),
            OnboardingRouteEntry(
                title = R.string.route_log_overlay,
                cost = R.string.route_log_overlay_cost,
                quality = 2,
            ),
            OnboardingRouteEntry(
                title = R.string.route_overlay_polling,
                cost = R.string.route_overlay_polling_cost,
                quality = 1,
            ),
        )

    @StringRes val ROUTE_RECOMMENDED = R.string.onboarding_route_recommended

    /** USB-free 特权直读: Developer options → Wireless debugging. */
    @StringRes val WIRELESS_DEBUG_HINT = R.string.onboarding_wireless_debug_hint

    // -- 4 · permissions overview -------------------------------------------

    @StringRes val PERMS_TITLE = R.string.onboarding_perms_title

    @StringRes val PERMS_BODY = R.string.onboarding_perms_body

    val permissions =
        listOf(
            OnboardingPermissionEntry(
                title = R.string.onboarding_perm_notifications,
                description = R.string.onboarding_perm_notifications_desc,
            ),
            OnboardingPermissionEntry(
                title = R.string.onboarding_perm_overlay,
                description = R.string.onboarding_perm_overlay_desc,
            ),
            OnboardingPermissionEntry(
                title = R.string.onboarding_perm_battery,
                description = R.string.onboarding_perm_battery_desc,
            ),
        )

    // -- 5 · send-off ---------------------------------------------------------

    @StringRes val FINISH_TITLE = R.string.onboarding_finish_title

    @StringRes val FINISH_BODY = R.string.onboarding_finish_body

    @StringRes val ACTION_PAIR = R.string.action_go_pair

    /** Optional deep-dive: the conduit page hosts the full capability wizard. */
    @StringRes val ACTION_WIZARD = R.string.onboarding_open_wizard

    @StringRes val ACTION_SKIP = R.string.onboarding_skip
}

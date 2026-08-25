package com.clipsync.android.ui.onboarding

import androidx.annotation.StringRes
import com.clipsync.android.R

/** One of the three places explained on the first-run screen. */
data class OnboardingTabEntry(
    @StringRes val title: Int,
    @StringRes val description: Int,
)

/**
 * The first-run copy as pure data, separate from the composable: what the app
 * promises here is a commitment (honesty about capabilities, pairing lives
 * under 通路), so tests can hold the structure to it. The words themselves
 * live in strings.xml (P1#16 — the default resources are the zh-Hans copy).
 */
object OnboardingContent {
    /** Serif greeting — the app's own voice, reusing the brand moment. */
    @StringRes val TITLE = R.string.brand_name

    @StringRes val SUBTITLE = R.string.onboarding_subtitle

    /** In dock order; the icons are matched positionally by the composable. */
    val tabs = listOf(
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

    @StringRes val ACTION_PAIR = R.string.action_go_pair

    @StringRes val ACTION_SKIP = R.string.onboarding_skip
}

package com.clipsync.android.ui.onboarding

import com.clipsync.android.R
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The first-run copy is a commitment: it must name the three places in dock
 * order, point pairing at the conduit tab, and state the capability limits
 * honestly. Since P1#16 the words live in strings.xml (default = zh-Hans);
 * these tests hold the structure — the resource wiring — to the commitment.
 */
class OnboardingContentTest {
    @Test
    fun `explains exactly the three dock places in dock order`() {
        assertEquals(
            listOf(R.string.tab_history, R.string.tab_conduit, R.string.tab_prefs),
            OnboardingContent.tabs.map { it.title },
        )
    }

    @Test
    fun `the pairing entrance is pointed at the conduit tab`() {
        val conduit = OnboardingContent.tabs.first { it.title == R.string.tab_conduit }
        assertEquals(R.string.onboarding_tab_conduit_desc, conduit.description)
        assertEquals(R.string.action_go_pair, OnboardingContent.ACTION_PAIR)
    }

    @Test
    fun `capability honesty is stated before the user meets the limits`() {
        assertEquals(R.string.onboarding_honesty_header, OnboardingContent.HONESTY_HEADER)
        assertEquals(R.string.onboarding_honesty_body, OnboardingContent.HONESTY_BODY)
    }

    @Test
    fun `skipping is offered as a quiet alternative`() {
        assertEquals(R.string.onboarding_skip, OnboardingContent.ACTION_SKIP)
    }
}

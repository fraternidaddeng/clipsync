package com.clipsync.android.ui.onboarding

import com.clipsync.android.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The first-run tutorial is a commitment: it must walk welcome → pairing →
 * read path → permissions → send-off, name the three places in dock order,
 * recommend 特权直读 with honest costs, explain permissions before the system
 * asks, and stay skippable at every step. Since P1#16 the words live in
 * strings.xml (default = zh-Hans); these tests hold the structure — the
 * resource wiring — to the commitment.
 */
class OnboardingContentTest {
    // -- step order -----------------------------------------------------------

    @Test
    fun `the tutorial walks welcome, pairing, read path, permissions, send-off`() {
        assertEquals(
            listOf(
                OnboardingStep.WELCOME,
                OnboardingStep.PAIR,
                OnboardingStep.READ_ROUTES,
                OnboardingStep.PERMISSIONS,
                OnboardingStep.FINISH,
            ),
            OnboardingContent.steps,
        )
    }

    @Test
    fun `next walks forward and stops at the last step`() {
        val walked =
            generateSequence(0) { index ->
                OnboardingContent.next(index).takeIf { it != index }
            }.toList()
        assertEquals(OnboardingContent.steps.indices.toList(), walked)
        // The last step has nowhere further to go — no wrap-around.
        assertEquals(
            OnboardingContent.steps.lastIndex,
            OnboardingContent.next(OnboardingContent.steps.lastIndex),
        )
    }

    @Test
    fun `previous walks backward and stops at the first step`() {
        assertEquals(1, OnboardingContent.previous(2))
        assertEquals(0, OnboardingContent.previous(1))
        // The first step has nowhere back to go — no wrap-around.
        assertEquals(0, OnboardingContent.previous(0))
    }

    @Test
    fun `every step but the last is explicitly skippable`() {
        // 稍后设置 lives in the chrome of steps 1..4; the send-off exits itself.
        assertEquals(R.string.onboarding_skip_for_now, OnboardingContent.ACTION_SKIP_FOR_NOW)
        assertEquals(R.string.onboarding_next, OnboardingContent.ACTION_NEXT)
        assertEquals(R.string.onboarding_back, OnboardingContent.ACTION_BACK)
        assertEquals(R.string.onboarding_step_progress, OnboardingContent.STEP_PROGRESS)
    }

    // -- 1 · welcome ----------------------------------------------------------

    @Test
    fun `explains exactly the three dock places in dock order`() {
        assertEquals(
            listOf(R.string.tab_history, R.string.tab_conduit, R.string.tab_prefs),
            OnboardingContent.tabs.map { it.title },
        )
    }

    @Test
    fun `capability honesty is stated before the user meets the limits`() {
        assertEquals(R.string.onboarding_honesty_header, OnboardingContent.HONESTY_HEADER)
        assertEquals(R.string.onboarding_honesty_body, OnboardingContent.HONESTY_BODY)
    }

    // -- 2 · pairing ----------------------------------------------------------

    @Test
    fun `the pairing step speaks the pairing ritual's own words`() {
        // Same keys as the pairing screen, so the tutorial can never drift.
        assertEquals(R.string.pairing_title, OnboardingContent.PAIR_TITLE)
        assertEquals(R.string.pairing_intro, OnboardingContent.PAIR_BODY)
        assertEquals(
            listOf(
                R.string.onboarding_pair_fact_entry,
                R.string.onboarding_pair_fact_verify,
                R.string.onboarding_pair_fact_service,
            ),
            OnboardingContent.pairFacts,
        )
    }

    @Test
    fun `the pairing entrance is pointed at the conduit tab`() {
        val conduit = OnboardingContent.tabs.first { it.title == R.string.tab_conduit }
        assertEquals(R.string.onboarding_tab_conduit_desc, conduit.description)
        assertEquals(R.string.action_go_pair, OnboardingContent.ACTION_PAIR)
    }

    // -- 3 · the read path ----------------------------------------------------

    @Test
    fun `the read step shows the wizard's three routes with their honest costs`() {
        assertEquals(R.string.wizard_title, OnboardingContent.READ_TITLE)
        assertEquals(
            listOf(
                R.string.route_privileged to R.string.route_privileged_cost,
                R.string.route_log_overlay to R.string.route_log_overlay_cost,
                R.string.route_overlay_polling to R.string.route_overlay_polling_cost,
            ),
            OnboardingContent.routes.map { it.title to it.cost },
        )
    }

    @Test
    fun `特权直读 is the one recommended route, at the top, at full quality`() {
        val recommended = OnboardingContent.routes.filter { it.recommended }
        assertEquals(listOf(R.string.route_privileged), recommended.map { it.title })
        assertEquals(R.string.route_privileged, OnboardingContent.routes.first().title)
        assertEquals(3, OnboardingContent.routes.first().quality)
    }

    @Test
    fun `routes are ordered by descending quality, mirroring the wizard`() {
        assertEquals(
            OnboardingContent.routes.map { it.quality }.sortedDescending(),
            OnboardingContent.routes.map { it.quality },
        )
        assertTrue(OnboardingContent.routes.all { it.quality in 1..3 })
    }

    @Test
    fun `the wireless-debugging hint offers the USB-free path`() {
        assertEquals(
            R.string.onboarding_wireless_debug_hint,
            OnboardingContent.WIRELESS_DEBUG_HINT,
        )
    }

    // -- 4 · permissions ------------------------------------------------------

    @Test
    fun `permissions cover notifications, overlay and battery, in that order`() {
        assertEquals(
            listOf(
                R.string.onboarding_perm_notifications to R.string.onboarding_perm_notifications_desc,
                R.string.onboarding_perm_overlay to R.string.onboarding_perm_overlay_desc,
                R.string.onboarding_perm_battery to R.string.onboarding_perm_battery_desc,
            ),
            OnboardingContent.permissions.map { it.title to it.description },
        )
        assertEquals(R.string.onboarding_perms_title, OnboardingContent.PERMS_TITLE)
        assertEquals(R.string.onboarding_perms_body, OnboardingContent.PERMS_BODY)
    }

    // -- live completion marks --------------------------------------------------

    @Test
    fun `live completion marks reuse the destination screens' own words`() {
        // Pair done, 前提已就绪 and 已开启 must never drift from what the pairing
        // ritual, the wizard and the conduit page themselves would say.
        assertEquals(R.string.onboarding_pair_done, OnboardingContent.PAIR_DONE)
        assertEquals(R.string.wizard_ready, OnboardingContent.ROUTE_READY)
        assertEquals(R.string.status_enabled, OnboardingContent.PERM_GRANTED)
    }

    @Test
    fun `only 特权直读 is marked locally detectable among the routes`() {
        assertEquals(
            listOf(R.string.route_privileged),
            OnboardingContent.routes.filter { it.privileged }.map { it.title },
        )
    }

    @Test
    fun `each permission row is identifiable so a live grant can find it`() {
        assertEquals(
            listOf(
                OnboardingPermissionId.NOTIFICATIONS,
                OnboardingPermissionId.OVERLAY,
                OnboardingPermissionId.BATTERY,
            ),
            OnboardingContent.permissions.map { it.id },
        )
    }

    // -- 5 · send-off ---------------------------------------------------------

    @Test
    fun `the send-off offers pairing, the conduit wizard, and a quiet exit`() {
        assertEquals(R.string.action_go_pair, OnboardingContent.ACTION_PAIR)
        assertEquals(R.string.onboarding_open_wizard, OnboardingContent.ACTION_WIZARD)
        assertEquals(R.string.onboarding_skip, OnboardingContent.ACTION_SKIP)
        assertFalse(OnboardingContent.ACTION_PAIR == OnboardingContent.ACTION_WIZARD)
    }
}

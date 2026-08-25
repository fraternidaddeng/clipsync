package com.clipsync.android.ui.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The first-run copy is a commitment: it must name the three places in dock
 * order, point pairing at the conduit tab, and state the capability limits
 * honestly. These tests hold the words to it.
 */
class OnboardingContentTest {
    @Test
    fun `explains exactly the three dock places in dock order`() {
        assertEquals(listOf("历史", "通路", "偏好"), OnboardingContent.tabs.map { it.title })
    }

    @Test
    fun `the pairing entrance is pointed at the conduit tab`() {
        val conduit = OnboardingContent.tabs.first { it.title == "通路" }
        assertTrue(conduit.description.contains("配对"))
        assertEquals("去配对", OnboardingContent.ACTION_PAIR)
    }

    @Test
    fun `capability honesty is stated before the user meets the limits`() {
        assertTrue(OnboardingContent.HONESTY_BODY.contains("后台读取剪贴板"))
        assertTrue(OnboardingContent.HONESTY_BODY.contains("实测"))
    }

    @Test
    fun `skipping is offered as a quiet alternative`() {
        assertTrue(OnboardingContent.ACTION_SKIP.isNotBlank())
    }
}

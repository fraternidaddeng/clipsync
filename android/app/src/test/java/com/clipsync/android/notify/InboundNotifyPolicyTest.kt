package com.clipsync.android.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InboundNotifyPolicyTest {
    @Test
    fun `copy action is offered when auto-apply is off`() {
        assertEquals(
            InboundNotifyDecision.COPY_ACTION,
            InboundNotifyPolicy.decide(autoApplyRemote = false, writeSucceeded = true),
        )
    }

    @Test
    fun `copy action is offered when public write failed`() {
        assertEquals(
            InboundNotifyDecision.COPY_ACTION,
            InboundNotifyPolicy.decide(autoApplyRemote = true, writeSucceeded = false),
        )
    }

    @Test
    fun `no copy action when auto-apply succeeded`() {
        assertEquals(
            InboundNotifyDecision.NONE,
            InboundNotifyPolicy.decide(autoApplyRemote = true, writeSucceeded = true),
        )
    }

    @Test
    fun `permission denial does not throw`() {
        val poster = SafeNotificationPoster(
            notificationsAllowed = { false },
            post = { error("must not post") },
        )
        assertFalse(poster.tryPost())
    }

    @Test
    fun `security exception from the system is swallowed`() {
        val poster = SafeNotificationPoster(
            notificationsAllowed = { true },
            post = { throw SecurityException("POST_NOTIFICATIONS") },
        )
        assertFalse(poster.tryPost())
    }

    @Test
    fun `granted permission posts once`() {
        var posted = 0
        val poster = SafeNotificationPoster(
            notificationsAllowed = { true },
            post = { posted += 1 },
        )
        assertTrue(poster.tryPost())
        assertEquals(1, posted)
    }
}

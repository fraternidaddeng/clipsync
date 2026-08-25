package com.clipsync.android.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class InboxNotificationGateTest {
    @Test
    fun `posts up to the window budget then coalesces with a running count`() {
        val gate = InboxNotificationGate(maxPerWindow = 3, windowMillis = 30_000L)

        repeat(3) {
            assertEquals(InboxNotificationGate.Verdict.Post, gate.admit(nowMs = 1_000L + it))
        }
        assertEquals(InboxNotificationGate.Verdict.Coalesce(1), gate.admit(nowMs = 1_010L))
        assertEquals(InboxNotificationGate.Verdict.Coalesce(2), gate.admit(nowMs = 1_020L))
        assertEquals(InboxNotificationGate.Verdict.Coalesce(3), gate.admit(nowMs = 1_030L))
    }

    @Test
    fun `a new window restores per-event posts and resets the suppressed count`() {
        val gate = InboxNotificationGate(maxPerWindow = 1, windowMillis = 30_000L)

        assertEquals(InboxNotificationGate.Verdict.Post, gate.admit(nowMs = 0L))
        assertEquals(InboxNotificationGate.Verdict.Coalesce(1), gate.admit(nowMs = 10L))
        assertEquals(InboxNotificationGate.Verdict.Coalesce(2), gate.admit(nowMs = 20L))

        // Window rollover: the burst is over, the surface goes back to per-event cards.
        assertEquals(InboxNotificationGate.Verdict.Post, gate.admit(nowMs = 30_000L))
        // A fresh flood counts from one again instead of resuming the stale total.
        assertEquals(InboxNotificationGate.Verdict.Coalesce(1), gate.admit(nowMs = 30_010L))
    }

    @Test
    fun `slow traffic never coalesces`() {
        val gate = InboxNotificationGate(maxPerWindow = 2, windowMillis = 1_000L)

        var now = 0L
        repeat(10) {
            assertEquals(InboxNotificationGate.Verdict.Post, gate.admit(now))
            now += 600L
        }
    }

    @Test
    fun `suppressed admissions do not extend the window`() {
        val gate = InboxNotificationGate(maxPerWindow = 1, windowMillis = 1_000L)

        assertEquals(InboxNotificationGate.Verdict.Post, gate.admit(nowMs = 0L))
        // Continuous suppressed traffic inside the window must not push the rollover out.
        assertEquals(InboxNotificationGate.Verdict.Coalesce(1), gate.admit(nowMs = 400L))
        assertEquals(InboxNotificationGate.Verdict.Coalesce(2), gate.admit(nowMs = 800L))
        assertEquals(InboxNotificationGate.Verdict.Post, gate.admit(nowMs = 1_000L))
    }

    @Test
    fun `defaults keep a modest per-event budget`() {
        val gate = InboxNotificationGate()

        repeat(InboxNotificationGate.DEFAULT_MAX_PER_WINDOW) {
            assertEquals(InboxNotificationGate.Verdict.Post, gate.admit(nowMs = 0L))
        }
        assertEquals(InboxNotificationGate.Verdict.Coalesce(1), gate.admit(nowMs = 0L))
    }
}

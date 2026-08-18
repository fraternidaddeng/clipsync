package com.clipsync.android.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RetentionPolicyTest {
    @Test
    fun `retention days default to 30 and clamp to 1 through 3650`() {
        assertEquals(DEFAULT_RETENTION_DAYS, parseRetentionDays(null))
        assertEquals(DEFAULT_RETENTION_DAYS, parseRetentionDays(""))
        assertEquals(DEFAULT_RETENTION_DAYS, parseRetentionDays("not-a-number"))
        assertEquals(MIN_RETENTION_DAYS, parseRetentionDays("0"))
        assertEquals(MIN_RETENTION_DAYS, parseRetentionDays("-4"))
        assertEquals(MAX_RETENTION_DAYS, parseRetentionDays("3651"))
        assertEquals(45, parseRetentionDays("45"))
    }

    @Test
    fun `retention purge is due when it has never run`() {
        assertTrue(isRetentionPurgeDue(lastRunMs = null, nowMs = 10_000L))
    }

    @Test
    fun `retention purge is not due before six hours`() {
        val last = 1_000L
        assertFalse(isRetentionPurgeDue(last, last + RETENTION_PURGE_INTERVAL_MS - 1))
    }

    @Test
    fun `retention purge is due at or after six hours`() {
        val last = 1_000L
        assertTrue(isRetentionPurgeDue(last, last + RETENTION_PURGE_INTERVAL_MS))
        assertTrue(isRetentionPurgeDue(last, last + RETENTION_PURGE_INTERVAL_MS + 1))
    }
}

package com.clipsync.android.share

import com.clipsync.android.storage.CapturePolicy
import com.clipsync.android.storage.CaptureRejectReason
import com.clipsync.android.storage.MAX_CLIP_UTF8_BYTES
import com.clipsync.android.storage.SETTING_PAIRED_PEER_ID
import com.clipsync.android.storage.TEST_PEER_DEVICE_ID
import com.clipsync.android.storage.createTestClipRepository
import com.clipsync.android.ui.settings.SETTING_IS_PAUSED
import com.clipsync.android.ui.settings.SETTING_IS_PRIVATE_MODE
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareCaptureHelperTest {
    @Test
    fun `shared text is captured with paired_peer_id so outbox fans out`() = runTest {
        val repo = createTestClipRepository()
        repo.setSetting(SETTING_PAIRED_PEER_ID, TEST_PEER_DEVICE_ID)
        val helper = ShareCaptureHelper(repo, nowMs = { NOW })
        val outcome = helper.capture("hello from share", sourceApp = "share")
        assertTrue(outcome is ShareCaptureOutcome.Stored)
        assertEquals(1, repo.outboxPending(TEST_PEER_DEVICE_ID).size)
        assertEquals("hello from share", repo.search("").single().content)
        assertEquals("share", repo.search("").single().sourceApp)
    }

    @Test
    fun `oversized share is rejected without storing`() = runTest {
        val repo = createTestClipRepository()
        repo.setSetting(SETTING_PAIRED_PEER_ID, TEST_PEER_DEVICE_ID)
        val helper = ShareCaptureHelper(repo, nowMs = { NOW })
        val outcome = helper.capture("a".repeat(MAX_CLIP_UTF8_BYTES + 1))
        assertEquals(ShareCaptureOutcome.Rejected(CaptureRejectReason.TOO_LARGE), outcome)
        assertTrue(repo.search("").isEmpty())
        assertTrue(repo.outboxPending(TEST_PEER_DEVICE_ID).isEmpty())
    }

    @Test
    fun `empty share is rejected`() = runTest {
        val repo = createTestClipRepository()
        val helper = ShareCaptureHelper(repo, nowMs = { NOW })
        assertEquals(
            ShareCaptureOutcome.Rejected(CaptureRejectReason.EMPTY_TEXT),
            helper.capture(""),
        )
    }

    @Test
    fun `paused mode skips capture`() = runTest {
        val repo = createTestClipRepository()
        repo.setSetting(SETTING_PAIRED_PEER_ID, TEST_PEER_DEVICE_ID)
        repo.setSetting(SETTING_IS_PAUSED, "true")
        val helper = ShareCaptureHelper(repo, nowMs = { NOW })
        assertEquals(ShareCaptureOutcome.SkippedPolicy, helper.capture("secret"))
        assertTrue(repo.search("").isEmpty())
    }

    @Test
    fun `private mode skips capture`() = runTest {
        val repo = createTestClipRepository()
        repo.setSetting(SETTING_PAIRED_PEER_ID, TEST_PEER_DEVICE_ID)
        repo.setSetting(SETTING_IS_PRIVATE_MODE, "true")
        val helper = ShareCaptureHelper(repo, nowMs = { NOW })
        assertEquals(ShareCaptureOutcome.SkippedPolicy, helper.capture("secret"))
        assertTrue(repo.search("").isEmpty())
    }

    @Test
    fun `share from a blocked package is a distinct rejected outcome`() = runTest {
        val repo = createTestClipRepository()
        repo.setSetting(SETTING_PAIRED_PEER_ID, TEST_PEER_DEVICE_ID)
        val helper = ShareCaptureHelper(repo, nowMs = { NOW })
        val blocked = CapturePolicy.BUILTIN_BLOCKED_PACKAGES.first()
        val outcome = helper.capture("from vault", sourceApp = blocked)
        assertEquals(ShareCaptureOutcome.Rejected(CaptureRejectReason.BLOCKED_SOURCE), outcome)
        assertTrue(repo.search("").isEmpty())
        assertTrue(repo.outboxPending(TEST_PEER_DEVICE_ID).isEmpty())
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
    }
}

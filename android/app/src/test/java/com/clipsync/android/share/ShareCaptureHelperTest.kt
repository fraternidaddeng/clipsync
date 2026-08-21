package com.clipsync.android.share

import com.clipsync.android.storage.CapturePolicy
import com.clipsync.android.storage.CaptureRejectReason
import com.clipsync.android.storage.MAX_CLIP_UTF8_BYTES
import com.clipsync.android.storage.SETTING_IMAGE_SYNC_ENABLED
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
        val outcome = helper.capture("hello from share", sourceApp = "share", peerId = TEST_PEER_DEVICE_ID)
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

    @Test
    fun `shared png is captured as image and fans out when image sync is on`() = runTest {
        val repo = createTestClipRepository()
        repo.setSetting(SETTING_PAIRED_PEER_ID, TEST_PEER_DEVICE_ID)
        repo.setSetting(SETTING_IMAGE_SYNC_ENABLED, "true")
        val helper = ShareCaptureHelper(repo, nowMs = { NOW })
        val outcome = helper.captureImage(TINY_PNG, sourceApp = "share", peerId = TEST_PEER_DEVICE_ID)
        assertTrue(outcome is ShareCaptureOutcome.Stored)
        val stored = repo.search("").single()
        assertEquals("image", stored.kind)
        assertEquals("share", stored.sourceApp)
        assertEquals("image/png", stored.mimeType)
        assertEquals(8, stored.pixelWidth)
        assertEquals(8, stored.pixelHeight)
        assertTrue(repo.media.exists(stored.contentHash))
        assertEquals(1, repo.outboxPending(TEST_PEER_DEVICE_ID).size)
    }

    @Test
    fun `shared png is rejected when image sync is off`() = runTest {
        val repo = createTestClipRepository()
        repo.setSetting(SETTING_PAIRED_PEER_ID, TEST_PEER_DEVICE_ID)
        val helper = ShareCaptureHelper(repo, nowMs = { NOW })
        assertEquals(
            ShareCaptureOutcome.Rejected(CaptureRejectReason.UNSUPPORTED_MEDIA),
            helper.captureImage(TINY_PNG, peerId = TEST_PEER_DEVICE_ID),
        )
        assertTrue(repo.search("").isEmpty())
        assertTrue(repo.outboxPending(TEST_PEER_DEVICE_ID).isEmpty())
    }

    @Test
    fun `shared gif bytes are decode-failed and never stored as text`() = runTest {
        val repo = createTestClipRepository()
        repo.setSetting(SETTING_PAIRED_PEER_ID, TEST_PEER_DEVICE_ID)
        repo.setSetting(SETTING_IMAGE_SYNC_ENABLED, "true")
        val helper = ShareCaptureHelper(repo, nowMs = { NOW })
        val gif = "GIF89a".toByteArray() + ByteArray(32)
        assertEquals(
            ShareCaptureOutcome.Rejected(CaptureRejectReason.DECODE_FAILED),
            helper.captureImage(gif, peerId = TEST_PEER_DEVICE_ID),
        )
        assertTrue(repo.search("").isEmpty())
    }

    @Test
    fun `image share with unreadable bytes does not fall back to leftover text`() {
        assertEquals(
            SharePayload.Empty,
            SharePayloadResolver.resolve("content://media/1", imageBytes = null, imageShare = true),
        )
        assertTrue(
            SharePayloadResolver.resolve("caption", TINY_PNG, imageShare = true) is SharePayload.Image,
        )
        assertEquals(
            SharePayload.Text("hello"),
            SharePayloadResolver.resolve("hello", imageBytes = null, imageShare = false),
        )
    }

    private companion object {
        const val NOW = 1_700_000_000_000L

        val TINY_PNG: ByteArray =
            hex(
                "89504e470d0a1a0a0000000d49484452000000080000000808020000004b6d29dc" +
                    "000000114944415478da63f8cfc08015310c2d090028ff3fc1ce77c84f0000000049454e44ae426082",
            )

        fun hex(value: String): ByteArray {
            val out = ByteArray(value.length / 2)
            var index = 0
            while (index < value.length) {
                out[index / 2] = value.substring(index, index + 2).toInt(16).toByte()
                index += 2
            }
            return out
        }
    }
}

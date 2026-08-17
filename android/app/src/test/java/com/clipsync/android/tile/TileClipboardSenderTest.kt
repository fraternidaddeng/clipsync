package com.clipsync.android.tile

import com.clipsync.android.platform.clipboard.ClipboardReadResult
import com.clipsync.android.storage.CaptureRejectReason
import com.clipsync.android.storage.MAX_CLIP_UTF8_BYTES
import com.clipsync.android.storage.SETTING_PAIRED_PEER_ID
import com.clipsync.android.storage.TEST_PEER_DEVICE_ID
import com.clipsync.android.storage.createTestClipRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TileClipboardSenderTest {
    @Test
    fun `tile reads current clipboard then captures with paired_peer_id`() = runTest {
        val repo = createTestClipRepository()
        repo.setSetting(SETTING_PAIRED_PEER_ID, TEST_PEER_DEVICE_ID)
        val sender = TileClipboardSender(
            repository = repo,
            readText = { ClipboardReadResult.Success("from tile") },
            nowMs = { NOW },
        )
        val outcome = sender.send()
        assertTrue(outcome is TileSendOutcome.Stored)
        assertEquals("from tile", repo.search("").single().content)
        assertEquals(1, repo.outboxPending(TEST_PEER_DEVICE_ID).size)
        assertEquals("qs_tile", repo.search("").single().sourceApp)
    }

    @Test
    fun `empty clipboard is not captured`() = runTest {
        val repo = createTestClipRepository()
        val sender = TileClipboardSender(
            repository = repo,
            readText = { ClipboardReadResult.Empty },
            nowMs = { NOW },
        )
        assertEquals(TileSendOutcome.EmptyClipboard, sender.send())
        assertTrue(repo.search("").isEmpty())
    }

    @Test
    fun `read failure is returned without capturing`() = runTest {
        val repo = createTestClipRepository()
        val sender = TileClipboardSender(
            repository = repo,
            readText = { ClipboardReadResult.Failure("FOREGROUND_READ_NOT_VISIBLE") },
            nowMs = { NOW },
        )
        assertEquals(
            TileSendOutcome.ReadFailed("FOREGROUND_READ_NOT_VISIBLE"),
            sender.send(),
        )
        assertTrue(repo.search("").isEmpty())
    }

    @Test
    fun `oversized clipboard text is rejected`() = runTest {
        val repo = createTestClipRepository()
        repo.setSetting(SETTING_PAIRED_PEER_ID, TEST_PEER_DEVICE_ID)
        val sender = TileClipboardSender(
            repository = repo,
            readText = { ClipboardReadResult.Success("a".repeat(MAX_CLIP_UTF8_BYTES + 1)) },
            nowMs = { NOW },
        )
        assertEquals(
            TileSendOutcome.Rejected(CaptureRejectReason.TOO_LARGE),
            sender.send(),
        )
        assertTrue(repo.search("").isEmpty())
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
    }
}

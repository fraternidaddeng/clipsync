package com.clipsync.android.ui.history

import com.clipsync.android.platform.clipboard.ClipboardWriteCoordinator
import com.clipsync.android.platform.clipboard.ClipboardWriteResult
import com.clipsync.android.platform.clipboard.FakeClipboardWriter
import com.clipsync.android.platform.clipboard.Sha256ContentHasher
import com.clipsync.android.storage.CaptureRejectReason
import com.clipsync.android.storage.CaptureResult
import com.clipsync.android.storage.RemoteClipEvent
import com.clipsync.android.storage.SETTING_IMAGE_SYNC_ENABLED
import com.clipsync.android.storage.SETTING_PAIRED_PEER_ID
import com.clipsync.android.storage.TEST_PEER_DEVICE_ID
import com.clipsync.android.storage.createTestClipRepository
import com.clipsync.android.ui.settings.FixedSyncStatusProvider
import com.clipsync.android.ui.settings.MutableSyncStatusProvider
import com.clipsync.android.ui.settings.SyncConnectionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun installMain() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun resetMain() {
        Dispatchers.resetMain()
    }

    @Test
    fun `search returns matching clips without requiring a raw clipboard`() = runTestModel { model, repo, _ ->
        repo.captureLocalText("alpha note", nowMs = NOW, peerId = TEST_PEER_DEVICE_ID)
        repo.captureLocalText("beta memo", nowMs = NOW + 10, peerId = TEST_PEER_DEVICE_ID)
        model.search("beta")
        assertEquals(listOf("beta memo"), model.state.value.items.map { it.preview })
        assertFalse(model.state.value.empty)
    }

    @Test
    fun `copy writes through the coordinator and never a raw clipboard call`() = runTestModel { model, repo, writer ->
        val stored = repo.captureLocalText("copy me", nowMs = NOW, peerId = TEST_PEER_DEVICE_ID)
            as CaptureResult.Stored
        model.refresh()
        model.copy(stored.eventId)
        assertEquals(1, writer.writes.size)
        assertEquals("copy me", writer.writes.single().text)
        assertEquals(stored.eventId, writer.writes.single().originEventId)
        assertFalse(model.state.value.copyFailed)
    }

    @Test
    fun `copy failure is surfaced without crashing`() = runTestModel(
        writer = FakeClipboardWriter().apply {
            enqueue(ClipboardWriteResult.Failure("PUBLIC_WRITE_REJECTED"))
        },
    ) { model, repo, writer ->
        val stored = repo.captureLocalText("blocked", nowMs = NOW, peerId = TEST_PEER_DEVICE_ID)
            as CaptureResult.Stored
        model.refresh()
        model.copy(stored.eventId)
        assertEquals(1, writer.writes.size)
        assertTrue(model.state.value.copyFailed)
    }

    @Test
    fun `copy failure notice clears after the timeout`() {
        runTestModel(
            writer = FakeClipboardWriter().apply {
                enqueue(ClipboardWriteResult.Failure("PUBLIC_WRITE_REJECTED"))
            },
        ) { model, repo, _ ->
            val stored = repo.captureLocalText(
                "blocked",
                nowMs = NOW,
                peerId = TEST_PEER_DEVICE_ID,
            ) as CaptureResult.Stored
            model.refresh()
            model.copy(stored.eventId)
            assertTrue(model.state.value.copyFailed)
            dispatcher.scheduler.advanceTimeBy(HistoryViewModel.COPY_FAILURE_NOTICE_MS - 1)
            assertTrue(model.state.value.copyFailed)
            dispatcher.scheduler.advanceTimeBy(1)
            dispatcher.scheduler.runCurrent()
            assertFalse(model.state.value.copyFailed)
        }
    }

    @Test
    fun `successful copy clears a pending failure notice`() {
        runTestModel(
            writer = FakeClipboardWriter().apply {
                enqueue(ClipboardWriteResult.Failure("PUBLIC_WRITE_REJECTED"))
            },
        ) { model, repo, writer ->
            val stored = repo.captureLocalText(
                "retry me",
                nowMs = NOW,
                peerId = TEST_PEER_DEVICE_ID,
            ) as CaptureResult.Stored
            model.refresh()
            model.copy(stored.eventId)
            assertTrue(model.state.value.copyFailed)
            model.copy(stored.eventId)
            assertEquals(2, writer.writes.size)
            assertFalse(model.state.value.copyFailed)
            dispatcher.scheduler.advanceTimeBy(HistoryViewModel.COPY_FAILURE_NOTICE_MS)
            dispatcher.scheduler.runCurrent()
            assertFalse(model.state.value.copyFailed)
        }
    }

    @Test
    fun `delete removes a clip from the list`() = runTestModel { model, repo, _ ->
        val stored = repo.captureLocalText("gone soon", nowMs = NOW, peerId = TEST_PEER_DEVICE_ID)
            as CaptureResult.Stored
        model.refresh()
        assertEquals(1, model.state.value.items.size)
        model.delete(stored.eventId)
        assertTrue(model.state.value.empty)
        assertTrue(repo.search("").isEmpty())
    }

    @Test
    fun `clear tombstones every visible clip`() = runTestModel { model, repo, _ ->
        repo.captureLocalText("one", nowMs = NOW, peerId = TEST_PEER_DEVICE_ID)
        repo.captureLocalText("two", nowMs = NOW + 10, peerId = TEST_PEER_DEVICE_ID)
        model.refresh()
        model.clear()
        assertTrue(model.state.value.empty)
        assertTrue(repo.search("").isEmpty())
    }

    @Test
    fun `empty history is an explicit state`() = runTestModel { model, _, _ ->
        model.refresh()
        assertTrue(model.state.value.empty)
        assertTrue(model.state.value.items.isEmpty())
        assertTrue(HistoryNotice.EMPTY in model.state.value.notices)
    }

    @Test
    fun `unpaired is an explicit state when paired_peer_id is missing`() = runTestModel { model, _, _ ->
        model.refresh()
        assertTrue(model.state.value.unpaired)
        assertTrue(HistoryNotice.UNPAIRED in model.state.value.notices)
    }

    @Test
    fun `paired_peer_id clears the unpaired notice`() = runTestModel { model, repo, _ ->
        repo.setSetting(SETTING_PAIRED_PEER_ID, TEST_PEER_DEVICE_ID)
        model.refresh()
        assertFalse(model.state.value.unpaired)
        assertFalse(HistoryNotice.UNPAIRED in model.state.value.notices)
    }

    @Test
    fun `remote ingest appears without refresh`() = runTestModel { model, repo, _ ->
        assertTrue(model.state.value.empty)
        repo.ingestRemoteClip(
            RemoteClipEvent(
                eventId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                originDeviceId = TEST_PEER_DEVICE_ID,
                originSeq = 1,
                content = "from windows",
                contentHash = Sha256ContentHasher.hash("from windows"),
                sourceApp = null,
                createdAtMs = NOW,
            ),
            sourcePeerId = TEST_PEER_DEVICE_ID,
        )
        assertEquals(listOf("from windows"), model.state.value.items.map { it.preview })
    }

    @Test
    fun `new local clip appears without refresh`() = runTestModel { model, repo, _ ->
        assertTrue(model.state.value.empty)
        repo.captureLocalText("live row", nowMs = NOW, peerId = TEST_PEER_DEVICE_ID)
        assertEquals(listOf("live row"), model.state.value.items.map { it.preview })
        assertFalse(model.state.value.empty)
    }

    @Test
    fun `paired_peer_id appears without refresh`() = runTestModel { model, repo, _ ->
        assertTrue(model.state.value.unpaired)
        repo.setSetting(SETTING_PAIRED_PEER_ID, TEST_PEER_DEVICE_ID)
        assertFalse(model.state.value.unpaired)
        assertFalse(HistoryNotice.UNPAIRED in model.state.value.notices)
    }

    @Test
    fun `ready status clears unreachable without refresh`() {
        val repo = createTestClipRepository()
        val writer = FakeClipboardWriter()
        val sync = MutableSyncStatusProvider(
            SyncConnectionStatus(paired = true, windowsReachable = false, serviceRunning = true),
        )
        val model = HistoryViewModel(
            repository = repo,
            writeCoordinator = ClipboardWriteCoordinator(writer),
            syncStatus = sync,
            nowMs = { NOW },
        )
        assertTrue(model.state.value.windowsUnreachable)
        assertTrue(HistoryNotice.WINDOWS_UNREACHABLE in model.state.value.notices)
        sync.set(SyncConnectionStatus(paired = true, windowsReachable = true, serviceRunning = true))
        assertFalse(model.state.value.windowsUnreachable)
        assertFalse(HistoryNotice.WINDOWS_UNREACHABLE in model.state.value.notices)
        model.onClearedForTest()
    }

    @Test
    fun `windows unreachable is an explicit state`() {
        val repo = createTestClipRepository()
        val writer = FakeClipboardWriter()
        val model = HistoryViewModel(
            repository = repo,
            writeCoordinator = ClipboardWriteCoordinator(writer),
            syncStatus = FixedSyncStatusProvider(
                SyncConnectionStatus(paired = true, windowsReachable = false, serviceRunning = false),
            ),
            nowMs = { NOW },
        )
        model.refresh()
        assertTrue(model.state.value.windowsUnreachable)
        assertTrue(HistoryNotice.WINDOWS_UNREACHABLE in model.state.value.notices)
        model.onClearedForTest()
    }

    @Test
    fun `oversized reject is an explicit state`() = runTestModel { model, _, _ ->
        model.noteCaptureResult(CaptureResult.Rejected(CaptureRejectReason.TOO_LARGE))
        assertEquals(CaptureRejectReason.TOO_LARGE, model.state.value.lastReject)
        assertTrue(HistoryNotice.OVERSIZED in model.state.value.notices)
    }

    @Test
    fun `image history rows keep content hash and image preview instead of a text body`() = runTestModel { model, repo, _ ->
        repo.setSetting(SETTING_IMAGE_SYNC_ENABLED, "true")
        val stored = repo.captureLocalImage(TINY_PNG, nowMs = NOW, peerId = TEST_PEER_DEVICE_ID)
            as CaptureResult.Stored
        model.refresh()
        val item = model.state.value.items.single()
        assertEquals(stored.eventId, item.eventId)
        assertTrue(item.isImage)
        assertEquals(stored.contentHash, item.contentHash)
        assertEquals("image/png", item.mimeType)
        assertEquals(8, item.pixelWidth)
        assertEquals(8, item.pixelHeight)
        assertEquals("Image image/png 8×8", item.preview)
        assertEquals("Image image/png 8×8", item.content)
    }

    @Test
    fun `copy of an image writes bytes through the coordinator`() = runTestModel { model, repo, writer ->
        repo.setSetting(SETTING_IMAGE_SYNC_ENABLED, "true")
        val stored = repo.captureLocalImage(TINY_PNG, nowMs = NOW, peerId = TEST_PEER_DEVICE_ID)
            as CaptureResult.Stored
        model.refresh()
        model.copy(stored.eventId)
        assertEquals(1, writer.writes.size)
        assertEquals(stored.eventId, writer.writes.single().originEventId)
        assertTrue(TINY_PNG.contentEquals(writer.writes.single().imageBytes))
        assertEquals("image/png", writer.writes.single().mimeType)
        assertFalse(model.state.value.copyFailed)
    }

    private fun runTestModel(
        writer: FakeClipboardWriter = FakeClipboardWriter(),
        sync: FixedSyncStatusProvider = FixedSyncStatusProvider(
            SyncConnectionStatus(paired = false, windowsReachable = false, serviceRunning = false),
        ),
        block: suspend (HistoryViewModel, com.clipsync.android.storage.ClipRepository, FakeClipboardWriter) -> Unit,
    ) {
        val repo = createTestClipRepository()
        val model = HistoryViewModel(
            repository = repo,
            writeCoordinator = ClipboardWriteCoordinator(writer),
            syncStatus = sync,
            nowMs = { NOW },
        )
        try {
            runBlocking { block(model, repo, writer) }
        } finally {
            model.onClearedForTest()
        }
    }

    @Test
    fun `selecting a row exposes it as the selected item`() {
        runTestModel { model, repo, _ ->
            val stored =
                repo.captureLocalText("full detail body", nowMs = NOW, peerId = TEST_PEER_DEVICE_ID)
                    as CaptureResult.Stored
            model.openDetail(stored.eventId)
            val selected = model.state.value.selectedItem
            assertEquals(stored.eventId, model.state.value.selectedEventId)
            assertEquals(stored.eventId, selected?.eventId)
            assertEquals("full detail body", selected?.content)
        }
    }

    @Test
    fun `close detail clears the selected item`() {
        runTestModel { model, repo, _ ->
            val stored =
                repo.captureLocalText("closable", nowMs = NOW, peerId = TEST_PEER_DEVICE_ID)
                    as CaptureResult.Stored
            model.openDetail(stored.eventId)
            assertEquals(stored.eventId, model.state.value.selectedEventId)
            model.closeDetail()
            assertEquals(null, model.state.value.selectedEventId)
            assertEquals(null, model.state.value.selectedItem)
        }
    }

    @Test
    fun `selected item clears when the row disappears from the live list`() {
        runTestModel { model, repo, _ ->
            val stored =
                repo.captureLocalText("will vanish", nowMs = NOW, peerId = TEST_PEER_DEVICE_ID)
                    as CaptureResult.Stored
            model.openDetail(stored.eventId)
            assertEquals(stored.eventId, model.state.value.selectedEventId)
            repo.delete(stored.eventId, NOW + 1)
            assertEquals(null, model.state.value.selectedItem)
            assertTrue(model.state.value.empty)
        }
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

private fun HistoryViewModel.onClearedForTest() {
    // ViewModel.onCleared is protected; cancel via a package-visible hook.
    close()
}

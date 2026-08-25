package com.clipsync.android.ui.prefs

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.clipsync.android.pairing.FakeKeyValueStore
import com.clipsync.android.platform.clipboard.Sha256ContentHasher
import com.clipsync.android.storage.ClipSyncDatabase
import com.clipsync.android.storage.ClipSyncRepository
import com.clipsync.android.storage.LocalClipDraft
import com.clipsync.android.storage.SyncSettingsStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The 偏好 导出历史/导入历史 wiring: streams in, honest status line out. The
 * merge semantics themselves are covered by storage.HistoryTransferTest; here we
 * assert the ViewModel reports counts and failures truthfully.
 */
@RunWith(RobolectricTestRunner::class)
class PreferencesHistoryTransferTest {
    private lateinit var database: ClipSyncDatabase
    private lateinit var repository: ClipSyncRepository
    private lateinit var viewModel: PreferencesViewModel

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, ClipSyncDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = ClipSyncRepository(database, LOCAL_DEVICE)
        viewModel = PreferencesViewModel(
            settings = SyncSettingsStore(FakeKeyValueStore()),
            historyRepository = { repository },
            ioDispatcher = Dispatchers.IO,
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun exportReportsTheCountAndWritesTheDocument() = runBlocking {
        repository.storeLocalEvent(draft("clip one"), emptyList())
        repository.storeLocalEvent(draft("clip two"), emptyList())
        val output = ByteArrayOutputStream()

        viewModel.exportHistory { output }
        val status = awaitTransferStatus()

        assertTrue(status.contains("已导出 2 条"))
        val lines = output.toString(Charsets.UTF_8).trimEnd('\n').split('\n')
        assertEquals(3, lines.size)
    }

    @Test
    fun importReportsMergeCountsAndStaysIdempotent() = runBlocking {
        repository.storeLocalEvent(draft("exported"), emptyList())
        val output = ByteArrayOutputStream()
        repository.exportHistory(output, NOW)
        val document = output.toByteArray()

        // Into a second repository under the ViewModel, as a restore would run.
        val context = ApplicationProvider.getApplicationContext<Context>()
        val freshDb = Room.inMemoryDatabaseBuilder(context, ClipSyncDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val fresh = ClipSyncRepository(freshDb, OTHER_DEVICE)
            val model = PreferencesViewModel(
                settings = SyncSettingsStore(FakeKeyValueStore()),
                historyRepository = { fresh },
                ioDispatcher = Dispatchers.IO,
            )

            model.importHistory { ByteArrayInputStream(document) }
            val firstStatus = awaitTransferStatus(model)
            assertTrue("actual status: $firstStatus", firstStatus.contains("新增 1"))

            model.importHistory { ByteArrayInputStream(document) }
            val second = withTimeout(5_000) {
                model.state.first { it.transferStatus != null && it.transferStatus != firstStatus }
            }
            assertTrue(second.transferStatus!!.contains("新增 0"))
            assertTrue(second.transferStatus!!.contains("已存在 1"))
            assertEquals(1, fresh.searchHistory().size)
        } finally {
            freshDb.close()
        }
    }

    @Test
    fun clearHistoryRemovesEveryVisibleEntryAndReportsTheCount() = runBlocking {
        repository.storeLocalEvent(draft("clip one"), emptyList())
        repository.storeLocalEvent(draft("clip two"), emptyList())
        repository.storeLocalEvent(draft("clip three"), emptyList())

        viewModel.clearHistory()
        val status = awaitTransferStatus()

        // settings-roadmap P0-5: honest count, local-only semantics stated.
        assertTrue("actual status: $status", status.contains("已清空 3 条"))
        assertTrue(status.contains("仅本机"))
        assertTrue(repository.searchHistory().isEmpty())
    }

    @Test
    fun foreignFilesFailWithAStatusLineAndNoChanges() = runBlocking {
        viewModel.importHistory { ByteArrayInputStream("{\"hello\":\"world\"}\n".toByteArray()) }
        val status = awaitTransferStatus()

        assertTrue(status.contains("导入失败"))
        assertTrue(status.contains("未做任何改动"))
        assertTrue(repository.searchHistory().isEmpty())
    }

    private suspend fun awaitTransferStatus(model: PreferencesViewModel = viewModel): String =
        withTimeout(5_000) { model.state.first { it.transferStatus != null } }.transferStatus!!

    private fun draft(text: String) = LocalClipDraft(
        content = text,
        contentHash = Sha256ContentHasher.hash(text),
        sourceApp = "com.example.app",
        capturedAtMs = NOW,
    )

    private companion object {
        const val LOCAL_DEVICE = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        const val OTHER_DEVICE = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
        const val NOW = 1_700_000_000_000L
    }
}

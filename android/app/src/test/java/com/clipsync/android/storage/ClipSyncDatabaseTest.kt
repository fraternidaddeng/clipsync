package com.clipsync.android.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ClipSyncDatabaseTest {
    private lateinit var context: Context
    private lateinit var database: ClipSyncDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = ClipSyncDatabase.build(context, name = TEST_DB)
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(TEST_DB)
    }

    @Test
    fun onDiskDatabaseRunsInWalMode() {
        val journalMode = database.openHelper.writableDatabase
            .query("PRAGMA journal_mode").use { cursor ->
                cursor.moveToFirst()
                cursor.getString(0)
            }
        assertEquals("wal", journalMode.lowercase())
    }

    @Test
    fun schemaVersionIsTwo() {
        assertEquals(ClipSyncDatabase.SCHEMA_VERSION, database.openHelper.writableDatabase.version)
        assertEquals(2, ClipSyncDatabase.SCHEMA_VERSION)
    }

    @Test
    fun dataSurvivesReopeningTheSameFile() {
        val repository = ClipSyncRepository(database, "android-device-1")
        runBlocking {
            repository.storeLocalEvent(
                LocalClipDraft("persisted", "hash", null, capturedAtMs = 1L),
                fanOutPeerIds = listOf("windows-device-1"),
            )
        }
        database.close()

        database = ClipSyncDatabase.build(context, name = TEST_DB)
        val reopened = ClipSyncRepository(database, "android-device-1")
        runBlocking {
            assertEquals(1, reopened.searchHistory().size)
            assertEquals(1, reopened.pendingOutboxCount("windows-device-1"))
            // The sequence allocator must continue after 1, never reuse it.
            val next = reopened.storeLocalEvent(
                LocalClipDraft("after reopen", "hash2", null, capturedAtMs = 2L),
                fanOutPeerIds = emptyList(),
            )
            assertEquals(2, next.originSeq)
        }
    }

    private companion object {
        const val TEST_DB = "clipsync-test.db"
    }
}

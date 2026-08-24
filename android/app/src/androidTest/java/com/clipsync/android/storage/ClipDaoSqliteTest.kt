package com.clipsync.android.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ClipDaoSqliteTest {
    @Test
    fun searchVisibleHardDeleteAndOutboxBatchRunOnSqlite() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = ClipDatabase.inMemory(context)
        try {
            val dao = db.clipDao()
            val now = System.currentTimeMillis()
            dao.insert(
                ClipEntity(
                    eventId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                    originDeviceId = "11111111-1111-4111-8111-111111111111",
                    originSeq = 1,
                    kind = CLIP_KIND_TEXT,
                    content = "visible",
                    contentHash = "aa".repeat(32),
                    sourceApp = null,
                    createdAt = now,
                    expiresAt = null,
                    deletedAt = null,
                    terminalReason = null,
                ),
            )
            val visible = dao.searchVisible(matchAll = 1, pattern = "", limit = 10, offset = 0)
            assertEquals(1, visible.size)
            assertEquals(0, dao.hardDeleteExpiredLive(now - 1))
            val ids = dao.visibleEventIds(SQLITE_SAFE_IN_CLAUSE)
            assertEquals(1, ids.size)
            db.outboxDao().deleteByEventIds(ids)
            assertTrue(dao.findLiveContentByHash("aa".repeat(32)) == "visible")
        } finally {
            db.close()
        }
    }
}

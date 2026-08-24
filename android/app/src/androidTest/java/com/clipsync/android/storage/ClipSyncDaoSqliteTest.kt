package com.clipsync.android.storage

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device port of the stage-4 `ClipDaoSqliteTest`, adapted to this branch's DAOs. The point
 * is not to re-prove DAO logic (the Robolectric suite covers that on the JVM) but to run the
 * SQLite-dialect-sensitive queries — `LIKE ... ESCAPE`, the `WITH` CTE in [ClipEventDao.cleanup],
 * `LIMIT -1 OFFSET`, and the GC subselects — against the framework SQLite an actual device ships.
 */
@RunWith(AndroidJUnit4::class)
class ClipSyncDaoSqliteTest {
    @Test
    fun searchSoftDeleteAndCleanupRunOnFrameworkSqlite() =
        runBlocking {
            val db = inMemoryDatabase()
            try {
                val dao = db.clipEvents()
                dao.insert(clip("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1", seq = 1, content = "hello world", createdAt = 1))
                dao.insert(clip("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa2", seq = 2, content = "100% done", createdAt = 2))
                dao.insert(clip("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa3", seq = 3, content = "newest", createdAt = 3))

                // LIKE with the DAO's ESCAPE '\' clause: a literal '%' must be findable.
                assertEquals(1, dao.search(pattern = "%100\\%%", limit = 10, offset = 0).size)
                assertEquals(3, dao.search(pattern = null, limit = 10, offset = 0).size)

                assertEquals(1, dao.softDelete("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa3", deletedAtMs = 50))
                assertEquals(2, dao.countVisible())

                // The CTE-based cleanup: age out everything created before 2, keep the rest.
                assertEquals(1, dao.cleanup(oldestCreatedAtMs = 2, maximumEntries = 10, deletedAtMs = 60))
                assertEquals(1, dao.countVisible())

                // Cap to zero entries via the LIMIT -1 OFFSET branch.
                assertEquals(1, dao.cleanup(oldestCreatedAtMs = 0, maximumEntries = 0, deletedAtMs = 70))
                assertEquals(0, dao.countVisible())

                // Terminal rows keep identity but lose content, and stay findable for export.
                val exported = dao.exportAll()
                assertEquals(3, exported.size)
                assertTrue(exported.all { it.content.isEmpty() && it.deletedAtMs != null })
                assertNull(dao.findLiveContentByHash("aa".repeat(32)))
            } finally {
                db.close()
            }
        }

    @Test
    fun outboxBatchLifecycleRunsOnFrameworkSqlite() =
        runBlocking {
            val db = inMemoryDatabase()
            try {
                val clips = db.clipEvents()
                val outbox = db.outbox()
                (1L..3L).forEach { seq ->
                    clips.insert(clip("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb$seq", seq = seq, content = "clip $seq", createdAt = seq))
                    outbox.insertAll(
                        listOf(
                            OutboxEntryEntity(
                                peerId = PEER_ID,
                                eventId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb$seq",
                                originDeviceId = ORIGIN_ID,
                                originSeq = seq,
                            ),
                        ),
                    )
                }

                val batch = outbox.pendingBatch(PEER_ID, limit = 10)
                assertEquals(listOf(1L, 2L, 3L), batch.map { it.clip.originSeq })
                assertEquals("clip 2", batch[1].clip.content)

                outbox.markAnnounced(batch.take(2).map { it.outboxId })
                assertEquals(1, outbox.pendingCount(PEER_ID))

                // A dropped session re-arms announced entries.
                outbox.resetToPending(PEER_ID)
                assertEquals(3, outbox.pendingCount(PEER_ID))

                // The peer acked seq 1..2: those obligations disappear, seq 3 survives.
                outbox.deleteAckedRange(PEER_ID, ORIGIN_ID, startSeq = 1, endSeq = 2)
                val remaining = outbox.pendingBatch(PEER_ID, limit = 10)
                assertEquals(listOf(3L), remaining.map { it.clip.originSeq })
            } finally {
                db.close()
            }
        }

    @Test
    fun mediaGcQueriesRunOnFrameworkSqlite() =
        runBlocking {
            val db = inMemoryDatabase()
            try {
                val hash = "dd".repeat(32)
                db.clipEvents().insert(
                    clip("cccccccc-cccc-4ccc-8ccc-ccccccccccc1", seq = 1, content = "", createdAt = 1, kind = ClipKinds.IMAGE),
                )
                db.mediaBlobs().upsert(
                    MediaBlobEntity(
                        contentHash = hash,
                        mimeType = "image/png",
                        encodedBytes = 68,
                        pixelWidth = 1,
                        pixelHeight = 1,
                        state = "complete",
                        createdAtMs = 1,
                    ),
                )
                db.clipMedia().upsert(
                    ClipMediaEntity(eventId = "cccccccc-cccc-4ccc-8ccc-ccccccccccc1", contentHash = hash, state = "complete"),
                )

                // A live image clip keeps its link and blob.
                assertEquals(0, db.clipMedia().deleteOrphaned())
                assertEquals(0, db.mediaBlobs().deleteUnreferenced())
                assertEquals(1, db.clipEvents().countLiveImagesByHash(hash))

                // Deleting the clip orphans the link, which unblocks blob GC.
                db.clipEvents().softDelete("cccccccc-cccc-4ccc-8ccc-ccccccccccc1", deletedAtMs = 9)
                assertEquals(1, db.clipMedia().deleteOrphaned())
                assertEquals(1, db.mediaBlobs().deleteUnreferenced())
                assertNull(db.mediaBlobs().find(hash))
            } finally {
                db.close()
            }
        }

    private fun inMemoryDatabase(): ClipSyncDatabase {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return Room.inMemoryDatabaseBuilder(context, ClipSyncDatabase::class.java).build()
    }

    private fun clip(
        eventId: String,
        seq: Long,
        content: String,
        createdAt: Long,
        kind: String = ClipKinds.TEXT,
    ): ClipEventEntity =
        ClipEventEntity(
            eventId = eventId,
            originDeviceId = ORIGIN_ID,
            originSeq = seq,
            kind = kind,
            content = content,
            contentHash = if (kind == ClipKinds.IMAGE) "dd".repeat(32) else "aa".repeat(32),
            sourceApp = null,
            createdAtMs = createdAt,
            expiresAtMs = null,
            deletedAtMs = null,
            terminalReason = null,
            appliedAtMs = null,
        )

    private companion object {
        const val PEER_ID = "22222222-2222-4222-8222-222222222222"
        const val ORIGIN_ID = "11111111-1111-4111-8111-111111111111"
    }
}

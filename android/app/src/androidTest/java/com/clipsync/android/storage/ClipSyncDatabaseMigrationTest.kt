package com.clipsync.android.storage

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device port of the stage-4 `ClipDatabaseMigrationTest`, adapted to this branch's
 * [ClipSyncDatabase]. The v1 database is created from the committed `schemas/1.json`, the
 * real [ClipSyncDatabase.MIGRATION_1_2] runs on framework SQLite, and Room validates the
 * migrated schema against `schemas/2.json` — including the tables, columns, and index the
 * Robolectric suite cannot check against a real device SQLite build.
 */
@RunWith(AndroidJUnit4::class)
class ClipSyncDatabaseMigrationTest {
    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            ClipSyncDatabase::class.java,
            emptyList(),
            FrameworkSQLiteOpenHelperFactory(),
        )

    @After
    fun tearDown() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(HELPER_DB)
        context.deleteDatabase(PRODUCTION_OPEN_DB)
    }

    @Test
    fun migrate1To2AddsMediaTablesAndKeepsExistingRows() {
        seedVersion1Database(HELPER_DB)

        helper.runMigrationsAndValidate(HELPER_DB, 2, true, ClipSyncDatabase.MIGRATION_1_2).use { migrated ->
            migrated
                .query(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name IN ('media_blobs','clip_media') ORDER BY name",
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("clip_media", cursor.getString(0))
                    assertTrue(cursor.moveToNext())
                    assertEquals("media_blobs", cursor.getString(0))
                }
            migrated
                .query(
                    "SELECT name FROM sqlite_master WHERE type='index' AND name='index_clip_media_content_hash'",
                ).use { cursor ->
                    assertTrue("clip_media hash index missing after migration", cursor.moveToFirst())
                }

            // Pre-migration history survives untouched.
            migrated.query("SELECT content, kind FROM clips WHERE event_id='$EVENT_ID'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("hello", cursor.getString(0))
                assertEquals("text", cursor.getString(1))
            }
            migrated.query("SELECT next_seq FROM local_sequences WHERE device_id='$ORIGIN_ID'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(2L, cursor.getLong(0))
            }

            // The new image tables accept rows immediately after the migration.
            migrated.execSQL(
                """
                INSERT INTO media_blobs (content_hash, mime_type, encoded_bytes, pixel_width, pixel_height, state, created_at)
                VALUES ('${"bb".repeat(32)}', 'image/png', 68, 1, 1, 'complete', 5)
                """.trimIndent(),
            )
            migrated.query("SELECT mime_type FROM media_blobs").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("image/png", cursor.getString(0))
            }
        }
    }

    @Test
    fun productionBuilderOpensAV1FileAndRunsTheMigration() {
        seedVersion1Database(PRODUCTION_OPEN_DB)

        // The real open path: WAL mode plus the full MIGRATIONS array, exactly as the app does.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = ClipSyncDatabase.build(context, name = PRODUCTION_OPEN_DB)
        try {
            runBlocking {
                assertEquals(1, database.clipEvents().countVisible())
                val survivor = database.clipEvents().getByEventId(EVENT_ID, includeDeleted = false)
                assertNotNull(survivor)
                assertEquals("hello", survivor!!.content)

                database.mediaBlobs().upsert(
                    MediaBlobEntity(
                        contentHash = "cc".repeat(32),
                        mimeType = "image/jpeg",
                        encodedBytes = 128,
                        pixelWidth = 2,
                        pixelHeight = 2,
                        state = "complete",
                        createdAtMs = 7,
                    ),
                )
                assertEquals("image/jpeg", database.mediaBlobs().find("cc".repeat(32))?.mimeType)
            }
        } finally {
            database.close()
        }
    }

    /** Creates an on-disk database at schema v1 (from `schemas/1.json`) with one clip row. */
    private fun seedVersion1Database(name: String) {
        helper.createDatabase(name, 1).apply {
            execSQL(
                """
                INSERT INTO clips (
                    event_id, origin_device_id, origin_seq, kind, content, content_hash,
                    source_app, created_at, expires_at, deleted_at, terminal_reason, applied_at
                ) VALUES (
                    '$EVENT_ID', '$ORIGIN_ID', 1, 'text', 'hello', '${"aa".repeat(32)}',
                    NULL, 1, NULL, NULL, NULL, NULL
                )
                """.trimIndent(),
            )
            execSQL("INSERT INTO local_sequences (device_id, next_seq) VALUES ('$ORIGIN_ID', 2)")
            close()
        }
    }

    private companion object {
        const val HELPER_DB = "migration-1-2.db"
        const val PRODUCTION_OPEN_DB = "migration-production-open.db"
        const val EVENT_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        const val ORIGIN_ID = "11111111-1111-4111-8111-111111111111"
    }
}

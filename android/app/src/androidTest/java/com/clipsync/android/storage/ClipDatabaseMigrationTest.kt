package com.clipsync.android.storage

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ClipDatabaseMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        ClipDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate1to2AddsMediaTables() {
        val dbName = "migration-1-2.db"
        helper.createDatabase(dbName, 1).apply {
            execSQL(
                """
                INSERT INTO clips (
                    event_id, origin_device_id, origin_seq, kind, content, content_hash,
                    source_app, created_at, expires_at, deleted_at, terminal_reason
                ) VALUES (
                    'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
                    '11111111-1111-4111-8111-111111111111',
                    1, 'text', 'hello', '00', NULL, 1, NULL, NULL, NULL
                )
                """.trimIndent(),
            )
            close()
        }

        helper.runMigrationsAndValidate(dbName, 2, true, ClipDatabase.MIGRATION_1_2).use { migrated ->
            migrated.query("SELECT name FROM sqlite_master WHERE type='table' AND name='media_blobs'").use { cursor ->
                assertTrue(cursor.moveToFirst())
            }
            migrated.query("SELECT content FROM clips WHERE event_id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("hello", cursor.getString(0))
            }
        }
    }
}

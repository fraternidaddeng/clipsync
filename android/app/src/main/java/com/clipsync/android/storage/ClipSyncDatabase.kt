package com.clipsync.android.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The Android sync store: inbox/history clips, per-peer outbox, peer cursors, this device's
 * receive vector, and the local sequence allocator. Settings deliberately live in
 * SharedPreferences ([SyncSettingsStore]), not in Room.
 *
 * Version 1 is the baseline schema; version 2 adds the image-sync tables (`media_blobs`,
 * `clip_media`); version 3 adds `clips.local_only_at` (the 仅本机保留 badge, ADR 0005 §5).
 * Every later change must ship an explicit [Migration] in [MIGRATIONS]; destructive fallbacks
 * are never enabled because history must survive upgrades.
 */
@Database(
    entities = [
        ClipEventEntity::class,
        OutboxEntryEntity::class,
        PeerCursorEntity::class,
        OriginReceiveStateEntity::class,
        LocalSequenceEntity::class,
        MediaBlobEntity::class,
        ClipMediaEntity::class,
    ],
    version = ClipSyncDatabase.SCHEMA_VERSION,
    exportSchema = true,
)
abstract class ClipSyncDatabase : RoomDatabase() {
    abstract fun clipEvents(): ClipEventDao

    abstract fun outbox(): OutboxDao

    abstract fun peerCursors(): PeerCursorDao

    abstract fun originReceiveState(): OriginReceiveStateDao

    abstract fun localSequences(): LocalSequenceDao

    abstract fun mediaBlobs(): MediaBlobDao

    abstract fun clipMedia(): ClipMediaDao

    companion object {
        const val SCHEMA_VERSION = 3
        const val DEFAULT_NAME = "clipsync.db"

        /** v1 -> v2: the two image-sync tables; existing clips rows are untouched. */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `media_blobs` (
                        `content_hash` TEXT NOT NULL,
                        `mime_type` TEXT NOT NULL,
                        `encoded_bytes` INTEGER NOT NULL,
                        `pixel_width` INTEGER NOT NULL,
                        `pixel_height` INTEGER NOT NULL,
                        `state` TEXT NOT NULL,
                        `created_at` INTEGER NOT NULL,
                        PRIMARY KEY(`content_hash`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `clip_media` (
                        `event_id` TEXT NOT NULL,
                        `content_hash` TEXT NOT NULL,
                        `state` TEXT NOT NULL,
                        PRIMARY KEY(`event_id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_clip_media_content_hash` ON `clip_media` (`content_hash`)",
                )
            }
        }

        /** v2 -> v3: the nullable `local_only_at` mark on clips; existing rows stay null. */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `clips` ADD COLUMN `local_only_at` INTEGER")
            }
        }

        /** Chronological migrations, one per version step. */
        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)

        /** Opens the on-disk database in WAL mode, matching the Windows store's journal setup. */
        fun build(context: Context, name: String = DEFAULT_NAME): ClipSyncDatabase =
            Room.databaseBuilder(context.applicationContext, ClipSyncDatabase::class.java, name)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(*MIGRATIONS)
                .build()
    }
}

package com.clipsync.android.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ClipEntity::class,
        OutboxEntity::class,
        OriginReceiveStateEntity::class,
        PeerCursorEntity::class,
        LocalSequenceEntity::class,
        SettingEntity::class,
        MediaBlobEntity::class,
        ClipMediaEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class ClipDatabase : RoomDatabase() {
    abstract fun clipDao(): ClipDao
    abstract fun outboxDao(): OutboxDao
    abstract fun originReceiveStateDao(): OriginReceiveStateDao
    abstract fun peerCursorDao(): PeerCursorDao
    abstract fun localSequenceDao(): LocalSequenceDao
    abstract fun settingDao(): SettingDao
    abstract fun mediaBlobDao(): MediaBlobDao
    abstract fun clipMediaDao(): ClipMediaDao

    companion object {
        const val NAME = "clipsync.db"
        const val VERSION = 2

        /**
         * Adjacent Room [Migration] objects, one per version integer.
         * Do not enable destructive fallback on the persistent builder —
         * bumping [VERSION] without a registered Migration must fail closed
         * so upgrades cannot silently drop clip history.
         */
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

        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2)

        init {
            require(MIGRATIONS.size >= VERSION - 1) {
                "ClipDatabase.VERSION=$VERSION requires at least ${VERSION - 1} Migration(s) before opening a persistent database."
            }
        }

        fun persistent(context: Context): ClipDatabase =
            Room.databaseBuilder(context.applicationContext, ClipDatabase::class.java, NAME)
                .addMigrations(*MIGRATIONS)
                .build()

        fun inMemory(context: Context): ClipDatabase =
            Room.inMemoryDatabaseBuilder(context.applicationContext, ClipDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }
}

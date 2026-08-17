package com.clipsync.android.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

@Database(
    entities = [
        ClipEntity::class,
        OutboxEntity::class,
        OriginReceiveStateEntity::class,
        PeerCursorEntity::class,
        LocalSequenceEntity::class,
        SettingEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class ClipDatabase : RoomDatabase() {
    abstract fun clipDao(): ClipDao
    abstract fun outboxDao(): OutboxDao
    abstract fun originReceiveStateDao(): OriginReceiveStateDao
    abstract fun peerCursorDao(): PeerCursorDao
    abstract fun localSequenceDao(): LocalSequenceDao
    abstract fun settingDao(): SettingDao

    companion object {
        const val NAME = "clipsync.db"
        const val VERSION = 1

        /**
         * Adjacent Room [Migration] objects, one per version integer.
         * Version stays at 1 until a real column/table change exists.
         * Do not enable destructive fallback on the persistent builder —
         * bumping [VERSION] without a registered Migration must fail closed
         * so upgrades cannot silently drop clip history.
         */
        val MIGRATIONS: Array<Migration> = emptyArray()

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

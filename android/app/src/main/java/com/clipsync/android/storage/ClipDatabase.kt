package com.clipsync.android.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Minimal read-path database for the 一屏 history list. The Stage 4 sync
 * engine writes into the same `clips` table; further tables (outbox, cursors,
 * settings) arrive with that branch.
 */
@Database(
    entities = [ClipEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class ClipDatabase : RoomDatabase() {
    abstract fun clipDao(): ClipDao

    companion object {
        const val NAME = "clipsync.db"

        @Volatile
        private var instance: ClipDatabase? = null

        fun open(context: Context): ClipDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ClipDatabase::class.java,
                    NAME,
                ).build().also { instance = it }
            }
    }
}

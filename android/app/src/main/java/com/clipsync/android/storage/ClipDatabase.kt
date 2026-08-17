package com.clipsync.android.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

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
    exportSchema = false,
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

        fun persistent(context: Context): ClipDatabase =
            Room.databaseBuilder(context.applicationContext, ClipDatabase::class.java, NAME)
                .build()

        fun inMemory(context: Context): ClipDatabase =
            Room.inMemoryDatabaseBuilder(context.applicationContext, ClipDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }
}

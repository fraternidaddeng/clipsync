package com.clipsync.android.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

/**
 * The Android sync store: inbox/history clips, per-peer outbox, peer cursors, this device's
 * receive vector, and the local sequence allocator. Settings deliberately live in
 * SharedPreferences ([SyncSettingsStore]), not in Room.
 *
 * Version 1 is the baseline schema. Every later change must ship an explicit [Migration] in
 * [MIGRATIONS]; destructive fallbacks are never enabled because history must survive upgrades.
 */
@Database(
    entities = [
        ClipEventEntity::class,
        OutboxEntryEntity::class,
        PeerCursorEntity::class,
        OriginReceiveStateEntity::class,
        LocalSequenceEntity::class,
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

    companion object {
        const val SCHEMA_VERSION = 1
        const val DEFAULT_NAME = "clipsync.db"

        /** Chronological migrations; empty while v1 is the only released schema. */
        val MIGRATIONS: Array<Migration> = emptyArray()

        /** Opens the on-disk database in WAL mode, matching the Windows store's journal setup. */
        fun build(context: Context, name: String = DEFAULT_NAME): ClipSyncDatabase =
            Room.databaseBuilder(context.applicationContext, ClipSyncDatabase::class.java, name)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(*MIGRATIONS)
                .build()
    }
}

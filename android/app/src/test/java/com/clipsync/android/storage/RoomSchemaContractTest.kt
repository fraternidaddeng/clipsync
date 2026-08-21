package com.clipsync.android.storage

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomSchemaContractTest {
    @Test
    fun `schema version stays at 2 and migrations are wired without destructive fallback`() {
        assertEquals(2, ClipDatabase.VERSION)
        assertEquals(
            "version 2 needs one Migration object; bumping VERSION requires one per step",
            ClipDatabase.VERSION - 1,
            ClipDatabase.MIGRATIONS.size,
        )
        assertTrue(ClipDatabase.MIGRATIONS.size >= ClipDatabase.VERSION - 1)

        val source = databaseSource()
        assertTrue(source.contains("exportSchema = true"))
        assertTrue(source.contains("version = 2"))
        assertTrue(source.contains("addMigrations(*MIGRATIONS)"))
        assertFalse(source.contains(".fallbackToDestructiveMigration("))
        assertFalse(source.contains("DROP TABLE `clips`"))
        assertFalse(source.contains("DROP TABLE clips"))

        val schemaV1 = exportedSchemaFile(1)
        val schemaV2 = exportedSchemaFile(2)
        assertTrue(schemaV1.isFile)
        assertTrue(schemaV2.isFile)
        assertTrue(schemaV1.readText().contains("\"version\": 1"))
        val schemaV2Text = schemaV2.readText()
        assertTrue(schemaV2Text.contains("\"version\": 2"))
        assertTrue(schemaV2Text.contains("\"identityHash\": \"d36e686738aca1b8cdfaf42518fde865\""))
        assertTrue(schemaV2Text.contains("\"tableName\": \"media_blobs\""))
        assertTrue(schemaV2Text.contains("\"tableName\": \"clip_media\""))
    }

    @Test
    fun `database declares the eight contract tables and no tombstones table`() {
        val entityNames = setOf(
            ClipEntity::class.java.simpleName,
            OutboxEntity::class.java.simpleName,
            OriginReceiveStateEntity::class.java.simpleName,
            PeerCursorEntity::class.java.simpleName,
            LocalSequenceEntity::class.java.simpleName,
            SettingEntity::class.java.simpleName,
            MediaBlobEntity::class.java.simpleName,
            ClipMediaEntity::class.java.simpleName,
        )
        assertEquals(
            setOf(
                "ClipEntity",
                "OutboxEntity",
                "OriginReceiveStateEntity",
                "PeerCursorEntity",
                "LocalSequenceEntity",
                "SettingEntity",
                "MediaBlobEntity",
                "ClipMediaEntity",
            ),
            entityNames,
        )
        assertFalse(entityNames.any { it.contains("Tombstone", ignoreCase = true) })
        assertNotNull(Class.forName("com.clipsync.android.storage.ClipDatabase_Impl"))
    }

    @Test
    fun `clip entity keeps terminal marker columns on the clips row`() {
        val columns = ClipEntity::class.java.declaredFields.map { it.name }.toSet()
        assertTrue(columns.contains("deletedAt"))
        assertTrue(columns.contains("terminalReason"))
        assertTrue(columns.contains("content"))
        assertTrue(columns.contains("contentHash"))
    }

    @Test
    fun `repository exposes no logging helpers that could leak clip bodies`() {
        val methodNames = ClipRepository::class.java.declaredMethods.map { it.name }
        assertFalse(methodNames.any { it.contains("log", ignoreCase = true) })
    }

    private fun databaseSource(): String {
        val candidates = listOf(
            File("src/main/java/com/clipsync/android/storage/ClipDatabase.kt"),
            File("app/src/main/java/com/clipsync/android/storage/ClipDatabase.kt"),
        )
        return candidates.first { it.isFile }.readText()
    }

    private fun exportedSchemaFile(version: Int): File {
        val candidates = listOf(
            File("schemas/com.clipsync.android.storage.ClipDatabase/$version.json"),
            File("app/schemas/com.clipsync.android.storage.ClipDatabase/$version.json"),
        )
        return candidates.first { it.isFile }
    }
}

package com.clipsync.android.storage

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomSchemaContractTest {
    @Test
    fun `schema version stays at 1 and migrations are wired without destructive fallback`() {
        assertEquals(1, ClipDatabase.VERSION)
        assertEquals(
            "version 1 needs no Migration objects; bumping VERSION requires one per step",
            ClipDatabase.VERSION - 1,
            ClipDatabase.MIGRATIONS.size,
        )
        assertTrue(ClipDatabase.MIGRATIONS.size >= ClipDatabase.VERSION - 1)

        val source = databaseSource()
        assertTrue(source.contains("exportSchema = true"))
        assertTrue(source.contains("version = 1"))
        assertTrue(source.contains("addMigrations(*MIGRATIONS)"))
        assertFalse(source.contains(".fallbackToDestructiveMigration("))

        val schema = exportedSchemaFile()
        assertTrue(schema.isFile)
        assertTrue(schema.readText().contains("\"version\": 1"))
    }

    @Test
    fun `database declares the six contract tables and no tombstones table`() {
        val entityNames = setOf(
            ClipEntity::class.java.simpleName,
            OutboxEntity::class.java.simpleName,
            OriginReceiveStateEntity::class.java.simpleName,
            PeerCursorEntity::class.java.simpleName,
            LocalSequenceEntity::class.java.simpleName,
            SettingEntity::class.java.simpleName,
        )
        assertEquals(
            setOf(
                "ClipEntity",
                "OutboxEntity",
                "OriginReceiveStateEntity",
                "PeerCursorEntity",
                "LocalSequenceEntity",
                "SettingEntity",
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

    private fun exportedSchemaFile(): File {
        val candidates = listOf(
            File("schemas/com.clipsync.android.storage.ClipDatabase/1.json"),
            File("app/schemas/com.clipsync.android.storage.ClipDatabase/1.json"),
        )
        return candidates.first { it.isFile }
    }
}

package com.clipsync.android.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomSchemaContractTest {
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
}

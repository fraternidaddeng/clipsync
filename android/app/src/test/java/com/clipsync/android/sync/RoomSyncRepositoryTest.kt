package com.clipsync.android.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.clipsync.android.storage.ClipSyncDatabase
import com.clipsync.android.storage.ClipSyncRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RoomSyncRepositoryTest {
    private lateinit var database: ClipSyncDatabase
    private lateinit var store: ClipSyncRepository
    private var userCap = SyncLimits.MAX_CONTENT_UTF8_BYTES

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, ClipSyncDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = ClipSyncRepository(database, LOCAL_DEVICE)
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun repository() = RoomSyncRepository(
        store = store,
        fanOutPeerIds = { listOf(PEER) },
        maxContentUtf8Bytes = { userCap },
    )

    @Test
    fun userCapGatesLocalCaptureAndIsReReadPerClip() = runBlocking {
        val repository = repository()
        userCap = 4

        // 5 UTF-8 bytes over a 4-byte cap: rejected, nothing stored, nothing queued.
        assertNull(repository.recordLocalClip("12345", sourceApp = null, nowMs = 1))
        assertEquals(0, store.pendingOutboxCount(PEER))

        // The cap is a provider: raising it applies to the very next clip.
        userCap = 8
        val stored = repository.recordLocalClip("12345", sourceApp = null, nowMs = 2)
        assertNotNull(stored)
        assertEquals(1, store.pendingOutboxCount(PEER))
    }

    @Test
    fun userCapNeverRaisesTheProtocolLimit() = runBlocking {
        val repository = repository()
        userCap = Int.MAX_VALUE
        val oversized = "a".repeat(SyncLimits.MAX_CONTENT_UTF8_BYTES + 1)

        assertNull(repository.recordLocalClip(oversized, sourceApp = null, nowMs = 1))
    }

    private companion object {
        const val LOCAL_DEVICE = "android-device-1"
        const val PEER = "windows-device-1"
    }
}

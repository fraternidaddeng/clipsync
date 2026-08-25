package com.clipsync.android.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.clipsync.android.platform.SharedPrefsKeyValueStore
import com.clipsync.android.storage.ClipSyncDatabase
import com.clipsync.android.storage.ClipSyncRepository
import com.clipsync.android.storage.RemoteClipEvent
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RoomClipInboxTest {
    private lateinit var database: ClipSyncDatabase
    private lateinit var store: ClipSyncRepository
    private lateinit var inbox: RoomClipInbox

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database =
            Room
                .inMemoryDatabaseBuilder(context, ClipSyncDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        store = ClipSyncRepository(database, LOCAL_DEVICE)
        inbox = RoomClipInbox { store }
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `committed remote text resolves by event id`() =
        runBlocking {
            store.storeRemoteEvent(remoteText(EVENT_ID, "来自电脑的文本"), sourcePeerId = PEER)

            assertEquals("来自电脑的文本", inbox.textFor(EVENT_ID))
        }

    @Test
    fun `unknown event id resolves to null`() {
        assertNull(inbox.textFor("99999999-9999-4999-8999-999999999999"))
    }

    @Test
    fun `deleting the history entry invalidates the notification copy action`() =
        runBlocking {
            store.storeRemoteEvent(remoteText(EVENT_ID, "will be deleted"), sourcePeerId = PEER)
            store.deleteEvent(EVENT_ID, deletedAtMs = 100L)

            // Deleted is gone — the receiver shows the honest 内容已不存在 toast instead of
            // resurrecting content from a second store the user cannot clear.
            assertNull(inbox.textFor(EVENT_ID))
        }

    @Test
    fun `record is a no-op because the Room commit preceding delivery is the record`() {
        inbox.record(EVENT_ID, "never persisted here", 1L)

        assertNull(inbox.textFor(EVENT_ID))
    }

    @Test
    fun `purging the legacy stub removes its plaintext blob`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = SharedPrefsKeyValueStore(context, name = "clipsync.sync")
        prefs.write(mapOf("inbox.recent" to """[{"eventId":"x","text":"residue","receivedAtEpochMillis":1}]"""))

        RoomClipInbox.purgeLegacyStub(prefs)

        assertNull(prefs.read("inbox.recent"))
    }

    private fun remoteText(
        eventId: String,
        text: String,
    ) = RemoteClipEvent(
        eventId = eventId,
        originDeviceId = PEER,
        originSeq = 1,
        content = text,
        contentHash = "ab".repeat(32),
        sourceApp = null,
        createdAtMs = 42L,
        expiresAtMs = null,
    )

    private companion object {
        const val LOCAL_DEVICE = "22222222-2222-4222-8222-222222222222"
        const val PEER = "11111111-1111-4111-8111-111111111111"
        const val EVENT_ID = "33333333-3333-4333-8333-333333333333"
    }
}

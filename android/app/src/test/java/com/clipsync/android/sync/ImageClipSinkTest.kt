package com.clipsync.android.sync

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.clipsync.android.media.ImageCodec
import com.clipsync.android.media.MediaBlobStore
import com.clipsync.android.media.MediaLimits
import com.clipsync.android.media.ValidatedImage
import com.clipsync.android.platform.SharedPrefsKeyValueStore
import com.clipsync.android.storage.ClipSyncDatabase
import com.clipsync.android.storage.ClipSyncRepository
import com.clipsync.android.storage.SyncSettingsStore
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ImageClipSinkTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val png = File(
        requireNotNull(System.getProperty("protocol.v2.fixtures.dir")),
        "media/png-8x8.png",
    ).readBytes()
    private val hash = ImageCodec.hashBytes(png)
    private val databaseName = "image-share-${UUID.randomUUID()}.db"
    private lateinit var root: File
    private lateinit var media: MediaBlobStore
    private lateinit var settings: SyncSettingsStore
    private lateinit var originalRepositoryProvider: (Context) -> SyncRepository
    private lateinit var originalRequester: SyncRequester
    private var database: ClipSyncDatabase? = null
    private var nudges = 0

    @Before
    fun setUp() {
        root = File(context.cacheDir, "image-share-${UUID.randomUUID()}")
        media = MediaBlobStore(root)
        settings = SyncSettingsStore(
            SharedPrefsKeyValueStore(context, name = SyncSettingsStore.PREFERENCES_NAME),
        )
        settings.imageSyncEnabled = true
        settings.privateMode = false
        settings.syncPaused = false
        originalRepositoryProvider = ClipboardSyncService.repositoryProvider
        originalRequester = SyncServices.syncRequester
        SyncServices.install(SyncServices.outbox, SyncServices.inbox, SyncRequester { nudges++ })
    }

    @After
    fun tearDown() {
        ClipboardSyncService.repositoryProvider = originalRepositoryProvider
        SyncServices.install(SyncServices.outbox, SyncServices.inbox, originalRequester)
        database?.close()
        context.deleteDatabase(databaseName)
        root.deleteRecursively()
    }

    @Test
    fun acceptedWaitsForBlobAndRepositoryCommitOffTheMainThread() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val repository = repository {
            assertFalse(Looper.getMainLooper().thread === Thread.currentThread())
            assertArrayEquals(png, media.readAllBytes(hash))
            entered.complete(Unit)
            release.await()
            event(it)
        }
        ClipboardSyncService.repositoryProvider = { repository }

        val result = async { ImageClipSink.submit(context, png, SOURCE) }
        withTimeout(5_000) { entered.await() }
        assertFalse(result.isCompleted)
        assertEquals(0, nudges)

        release.complete(Unit)
        assertEquals(ImageClipSink.Outcome.Accepted, result.await())
        assertEquals(1, nudges)
    }

    @Test
    fun blobWriteFailureIsNotAcceptedAndNeverCallsTheRepository() = runBlocking {
        val tempDirectory = File(root, MediaBlobStore.TEMP_DIRECTORY)
        assertTrue(tempDirectory.deleteRecursively())
        tempDirectory.writeText("a file cannot hold a pending blob")
        var records = 0
        val repository = repository {
            records++
            event(it)
        }
        ClipboardSyncService.repositoryProvider = { repository }

        assertEquals(ImageClipSink.Outcome.SaveFailed, ImageClipSink.submit(context, png, SOURCE))
        assertFalse(media.exists(hash))
        assertEquals(0, records)
        assertEquals(0, nudges)
    }

    @Test
    fun missingMediaStoreIsNotAccepted() = runBlocking {
        ClipboardSyncService.repositoryProvider = { InMemorySyncRepository(LOCAL_DEVICE) }

        assertEquals(ImageClipSink.Outcome.SaveFailed, ImageClipSink.submit(context, png, SOURCE))
        assertEquals(0, nudges)
    }

    @Test
    fun nullRepositoryResultIsNotAcceptedEvenWhenTheBlobWasWritten() = runBlocking {
        val repository = repository { null }
        ClipboardSyncService.repositoryProvider = { repository }

        assertEquals(ImageClipSink.Outcome.SaveFailed, ImageClipSink.submit(context, png, SOURCE))
        assertTrue(media.exists(hash))
        assertEquals(0, nudges)
    }

    @Test
    fun repositoryInitializationFailureIsNotAccepted() = runBlocking {
        ClipboardSyncService.repositoryProvider = { throw IOException("cannot open storage") }

        assertEquals(ImageClipSink.Outcome.SaveFailed, ImageClipSink.submit(context, png, SOURCE))
        assertEquals(0, nudges)
    }

    @Test
    fun roomTransactionFailureRollsBackTheEventMetadataVectorAndOutbox() = runBlocking {
        val db = openDatabase()
        val store = ClipSyncRepository(db, LOCAL_DEVICE, media)
        val repository = RoomSyncRepository(store, fanOutPeerIds = { listOf(PEER) })
        ClipboardSyncService.repositoryProvider = { repository }
        withContext(Dispatchers.IO) {
            db.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER fail_share BEFORE INSERT ON outbox " +
                    "BEGIN SELECT RAISE(ABORT, 'injected outbox failure'); END",
            )
        }

        assertEquals(ImageClipSink.Outcome.SaveFailed, ImageClipSink.submit(context, png, SOURCE))
        assertTrue(store.searchHistory().isEmpty())
        assertEquals(0, store.pendingOutboxCount(PEER))
        assertTrue(store.knownVector().isEmpty())
        assertNull(db.mediaBlobs().find(hash))
        assertTrue(db.clipMedia().referencedHashes().isEmpty())
        assertNull(db.localSequences().nextSeq(LOCAL_DEVICE))
        assertEquals(0, nudges)
    }

    @Test
    fun acceptedImageAndOutboxSurviveClosingAndReopeningTheDatabaseAndBlobStore() = runBlocking {
        val store = ClipSyncRepository(openDatabase(), LOCAL_DEVICE, media)
        ClipboardSyncService.repositoryProvider = { RoomSyncRepository(store, fanOutPeerIds = { listOf(PEER) }) }

        assertEquals(ImageClipSink.Outcome.Accepted, ImageClipSink.submit(context, png, SOURCE))
        database!!.close()
        val reopenedMedia = MediaBlobStore(root)
        val reopened = ClipSyncRepository(openDatabase(), LOCAL_DEVICE, reopenedMedia)
        val event = reopened.searchHistory().single()

        assertEquals(MediaLimits.KIND_IMAGE, event.kind)
        assertEquals(hash, event.contentHash)
        assertEquals(SOURCE, event.sourceApp)
        assertNotNull(reopened.findLiveImageByHash(hash))
        val pending = reopened.outboxBatch(PEER, 10).single().event
        assertEquals(event.eventId, pending.eventId)
        assertEquals(hash, pending.media!!.contentHash)
        assertEquals(1L, reopened.knownVector().getValue(LOCAL_DEVICE).contiguousSeq)
        assertArrayEquals(png, reopenedMedia.readAllBytes(hash))
    }

    @Test
    fun cancellingAnUncommittedRequestDoesNotReturnAcceptedOrNudgeSync() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val repository = repository {
            entered.complete(Unit)
            release.await()
            event(it)
        }
        ClipboardSyncService.repositoryProvider = { repository }
        val result = async { ImageClipSink.submit(context, png, SOURCE) }
        withTimeout(5_000) { entered.await() }

        result.cancelAndJoin()

        assertTrue(result.isCancelled)
        assertEquals(0, nudges)
    }

    @Test
    fun failedSyncNudgeDoesNotMisreportAnAlreadyPersistedImage() = runBlocking {
        val store = ClipSyncRepository(openDatabase(), LOCAL_DEVICE, media)
        ClipboardSyncService.repositoryProvider = { RoomSyncRepository(store, fanOutPeerIds = { listOf(PEER) }) }
        SyncServices.install(
            SyncServices.outbox,
            SyncServices.inbox,
            SyncRequester { throw IllegalStateException("sync not running") },
        )

        assertEquals(ImageClipSink.Outcome.Accepted, ImageClipSink.submit(context, png, SOURCE))
        assertEquals(1, store.searchHistory().size)
        assertEquals(1, store.pendingOutboxCount(PEER))
    }

    @Test
    fun policyAndValidationRejectionsDoNotTouchPersistence() = runBlocking {
        ClipboardSyncService.repositoryProvider = { error("rejected images must not open storage") }
        settings.imageSyncEnabled = false
        assertEquals(ImageClipSink.Outcome.ImageSyncOff, ImageClipSink.submit(context, png, SOURCE))
        settings.imageSyncEnabled = true
        settings.privateMode = true
        assertEquals(ImageClipSink.Outcome.PrivateMode, ImageClipSink.submit(context, png, SOURCE))
        settings.privateMode = false
        settings.syncPaused = true
        assertEquals(ImageClipSink.Outcome.SyncPaused, ImageClipSink.submit(context, png, SOURCE))
        settings.syncPaused = false
        assertEquals(ImageClipSink.Outcome.Invalid, ImageClipSink.submit(context, byteArrayOf(1), SOURCE))
        assertFalse(media.exists(hash))
        assertEquals(0, nudges)
    }

    private fun openDatabase(): ClipSyncDatabase =
        ClipSyncDatabase.build(context, databaseName).also { database = it }

    private fun repository(record: suspend (ValidatedImage) -> SyncableClipEvent?): SyncRepository =
        object : SyncRepository by InMemorySyncRepository(LOCAL_DEVICE) {
            override val media: MediaBlobStore = this@ImageClipSinkTest.media

            override suspend fun recordLocalImageClip(
                image: ValidatedImage,
                sourceApp: String?,
                nowMs: Long,
            ): SyncableClipEvent? = record(image)
        }

    private fun event(image: ValidatedImage) = SyncableClipEvent(
        eventId = "saved-image",
        originDeviceId = LOCAL_DEVICE,
        originSeq = 1,
        isTerminal = false,
        content = "",
        contentHash = image.contentHash,
        kind = MediaLimits.KIND_IMAGE,
        mimeType = image.mimeType,
        encodedBytes = image.encodedBytes,
        pixelWidth = image.pixelWidth,
        pixelHeight = image.pixelHeight,
    )

    private companion object {
        const val LOCAL_DEVICE = "android-share-test"
        const val PEER = "windows-share-test"
        const val SOURCE = "android.share_sheet"
    }
}

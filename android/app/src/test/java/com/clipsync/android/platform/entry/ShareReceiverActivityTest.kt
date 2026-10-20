package com.clipsync.android.platform.entry

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.clipsync.android.R
import com.clipsync.android.media.MediaBlobStore
import com.clipsync.android.media.ValidatedImage
import com.clipsync.android.platform.SharedPrefsKeyValueStore
import com.clipsync.android.storage.SyncSettingsStore
import com.clipsync.android.sync.ClipboardSyncService
import com.clipsync.android.sync.InMemorySyncRepository
import com.clipsync.android.sync.SyncRepository
import com.clipsync.android.sync.SyncRequester
import com.clipsync.android.sync.SyncServices
import com.clipsync.android.sync.SyncableClipEvent
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowToast

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ShareReceiverActivityTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dispatcher = StandardTestDispatcher()
    private val png = File(
        requireNotNull(System.getProperty("protocol.v2.fixtures.dir")),
        "media/png-8x8.png",
    ).readBytes()
    private lateinit var root: File
    private lateinit var media: MediaBlobStore
    private lateinit var originalRepositoryProvider: (Context) -> SyncRepository
    private lateinit var originalRequester: SyncRequester
    private var controller: ActivityController<ShareReceiverActivity>? = null
    private var nudges = 0

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        ShadowToast.reset()
        root = File(context.cacheDir, "share-activity-${UUID.randomUUID()}")
        media = MediaBlobStore(root)
        val settings = SyncSettingsStore(
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
        controller?.pause()?.stop()?.destroy()
        dispatcher.scheduler.runCurrent()
        Dispatchers.resetMain()
        ClipboardSyncService.repositoryProvider = originalRepositoryProvider
        SyncServices.install(SyncServices.outbox, SyncServices.inbox, originalRequester)
        root.deleteRecursively()
    }

    @Test
    fun imageShareCanResumeAndOnlyToastsSuccessAndFinishesAfterPersistence() {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val repository = repository {
            assertFalse(Looper.getMainLooper().thread === Thread.currentThread())
            assertTrue(media.exists(it.contentHash))
            entered.complete(Unit)
            release.await()
            savedEvent(it)
        }
        ClipboardSyncService.repositoryProvider = { repository }
        var readThread: Thread? = null
        val uri = Uri.parse("content://share-tests/photo.png")
        shadowOf(context.contentResolver).registerInputStream(
            uri,
            object : ByteArrayInputStream(png) {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    readThread = Thread.currentThread()
                    return super.read(buffer, offset, length)
                }
            },
        )

        val activity = startImageShare(uri)
        // Resuming a NoDisplay activity which has not finished throws on Android 6+.
        activity.theme.obtainStyledAttributes(intArrayOf(android.R.attr.windowNoDisplay)).use {
            assertFalse(it.getBoolean(0, false))
        }
        assertFalse(activity.isFinishing)
        assertNull(ShadowToast.getTextOfLatestToast())
        pumpUntil { entered.isCompleted }
        assertFalse(readThread === Looper.getMainLooper().thread)
        assertFalse(activity.isFinishing)
        assertNull(ShadowToast.getTextOfLatestToast())
        assertEquals(0, nudges)

        release.complete(Unit)
        pumpUntil { activity.isFinishing }

        assertEquals(context.getString(R.string.toast_share_image_enqueued), ShadowToast.getTextOfLatestToast())
        assertEquals(1, nudges)
    }

    @Test
    fun destroyingTheActivityCancelsAnUnacceptedShareWithoutAStaleSuccessToast() {
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val repository = repository {
            entered.complete(Unit)
            try {
                release.await()
                savedEvent(it)
            } finally {
                cancelled.complete(Unit)
            }
        }
        ClipboardSyncService.repositoryProvider = { repository }
        startImageShare(serveBytes(png))
        pumpUntil { entered.isCompleted }

        controller!!.pause().stop().destroy()
        controller = null
        pumpUntil { cancelled.isCompleted }
        dispatcher.scheduler.runCurrent()

        assertNull(ShadowToast.getTextOfLatestToast())
        assertEquals(0, nudges)
    }

    @Test
    fun missingMediaStoreFinishesWithAnHonestFailureToast() {
        ClipboardSyncService.repositoryProvider = { InMemorySyncRepository("local") }

        assertSaveFailure()
    }

    @Test
    fun repositoryReturningNullFinishesWithAnHonestFailureToast() {
        val repository = repository { null }
        ClipboardSyncService.repositoryProvider = { repository }

        assertSaveFailure()
    }

    @Test
    fun repositoryFailureFinishesWithAnHonestFailureToast() {
        val repository = repository { throw IOException("event commit failed") }
        ClipboardSyncService.repositoryProvider = { repository }

        assertSaveFailure()
    }

    @Test
    fun blobWriteFailureFinishesWithAnHonestFailureToast() {
        val tempDirectory = File(root, MediaBlobStore.TEMP_DIRECTORY)
        assertTrue(tempDirectory.deleteRecursively())
        tempDirectory.writeText("not a directory")
        val repository = repository { savedEvent(it) }
        ClipboardSyncService.repositoryProvider = { repository }

        assertSaveFailure()
    }

    @Test
    fun unreadableUriDoesNotCrashOrReportSuccess() {
        val uri = Uri.parse("content://share-tests/broken.png")
        shadowOf(context.contentResolver).registerInputStream(
            uri,
            object : ByteArrayInputStream(png) {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                    throw IOException("shared stream closed")
            },
        )

        val activity = startImageShare(uri)
        pumpUntil { activity.isFinishing }

        assertEquals(context.getString(R.string.toast_share_image_failed), ShadowToast.getTextOfLatestToast())
        assertEquals(0, nudges)
    }

    @Test
    fun invalidImageFinishesWithTheValidationToast() {
        ClipboardSyncService.repositoryProvider = { error("invalid images must not open storage") }
        val activity = startImageShare(serveBytes(byteArrayOf(1, 2, 3)))
        pumpUntil { activity.isFinishing }

        assertEquals(context.getString(R.string.toast_share_image_invalid), ShadowToast.getTextOfLatestToast())
        assertEquals(0, nudges)
    }

    private fun assertSaveFailure() {
        val activity = startImageShare(serveBytes(png))
        pumpUntil { activity.isFinishing }
        assertEquals(context.getString(R.string.toast_share_image_failed), ShadowToast.getTextOfLatestToast())
        assertEquals(0, nudges)
    }

    private fun serveBytes(bytes: ByteArray): Uri {
        val uri = Uri.parse("content://share-tests/photo.png")
        shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream(bytes))
        return uri
    }

    private fun startImageShare(uri: Uri): ShareReceiverActivity {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Robolectric.buildActivity(ShareReceiverActivity::class.java, intent)
            .also { controller = it }
            .create().start().resume().visible().get()
    }

    /** IO is real; only advance the main dispatcher while waiting for an observable boundary. */
    private fun pumpUntil(condition: () -> Boolean) = runBlocking {
        withTimeout(10_000) {
            while (!condition()) {
                dispatcher.scheduler.runCurrent()
                delay(10)
            }
        }
        dispatcher.scheduler.runCurrent()
    }

    private fun repository(record: suspend (ValidatedImage) -> SyncableClipEvent?): SyncRepository =
        object : SyncRepository by InMemorySyncRepository("local") {
            override val media: MediaBlobStore = this@ShareReceiverActivityTest.media

            override suspend fun recordLocalImageClip(
                image: ValidatedImage,
                sourceApp: String?,
                nowMs: Long,
            ): SyncableClipEvent? = record(image)
        }

    private fun savedEvent(image: ValidatedImage) = SyncableClipEvent(
        eventId = "saved-image",
        originDeviceId = "local",
        originSeq = 1,
        isTerminal = false,
        contentHash = image.contentHash,
    )
}

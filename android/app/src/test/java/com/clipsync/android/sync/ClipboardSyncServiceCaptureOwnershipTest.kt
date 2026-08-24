package com.clipsync.android.sync

import android.content.Context
import android.content.Intent
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.clipsync.android.pairing.FakeKeyValueStore
import com.clipsync.android.platform.SharedPrefsKeyValueStore
import com.clipsync.android.platform.clipboard.ClipboardAccessCoordinator
import com.clipsync.android.platform.clipboard.ClipboardCapabilityStore
import com.clipsync.android.platform.clipboard.ClipboardCaptureSession
import com.clipsync.android.platform.clipboard.ClipboardReadMode
import com.clipsync.android.platform.clipboard.ClipboardWriteCoordinator
import com.clipsync.android.platform.clipboard.ContentHasher
import com.clipsync.android.platform.clipboard.FakeBackgroundClipboardBackend
import com.clipsync.android.platform.clipboard.FakeClipboardWriter
import com.clipsync.android.platform.clipboard.ForegroundClipboardBackend
import com.clipsync.android.platform.clipboard.RealBackgroundReaders
import com.clipsync.android.platform.clipboard.RoutePrerequisites
import com.clipsync.android.platform.clipboard.RouteProbes
import com.clipsync.android.storage.ClipSyncDatabase
import com.clipsync.android.storage.ClipSyncRepository
import com.clipsync.android.storage.SyncSettingsStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ServiceController

private const val LOCAL_DEVICE_ID = "22222222-2222-4222-8222-222222222222"

/**
 * The plan-5.2 P0 wiring: [ClipboardSyncService] must own the process-wide
 * [ClipboardCaptureSession] while promoted, so the background read backends keep capturing
 * with the main UI gone, and the activity's visibility only matters when no service runs.
 * The stack is swapped for one built on fake backends; the service lifecycle is real.
 */
@RunWith(RobolectricTestRunner::class)
class ClipboardSyncServiceCaptureOwnershipTest {
    private lateinit var context: Context
    private lateinit var database: ClipSyncDatabase
    private lateinit var settings: SyncSettingsStore
    private lateinit var shizuku: FakeBackgroundClipboardBackend
    private lateinit var session: ClipboardCaptureSession
    private lateinit var outbox: KeyValueClipOutbox
    private lateinit var controller: ServiceController<ClipboardSyncService>

    private lateinit var originalStackProvider: (Context) -> CaptureStack
    private lateinit var originalRepositoryProvider: (Context) -> SyncRepository

    private val backendCalls = mutableListOf<String>()
    private var nudges = 0

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        settings =
            SyncSettingsStore(
                SharedPrefsKeyValueStore(context, name = SyncSettingsStore.PREFERENCES_NAME),
            )
        originalStackProvider = SharedClipboardCapture.stackProvider
        SharedClipboardCapture.stackProvider = { fakeBackedStack() }
        originalRepositoryProvider = ClipboardSyncService.repositoryProvider
        ClipboardSyncService.repositoryProvider = { inMemoryRepository() }
        controller = Robolectric.buildService(ClipboardSyncService::class.java)
    }

    /**
     * The capture pipeline the service must drive: a READY fake privileged backend, the
     * production coordinator/manager/session classes, and an observable local outbox.
     */
    private fun fakeBackedStack(): CaptureStack {
        shizuku =
            FakeBackgroundClipboardBackend(
                mode = ClipboardReadMode.SHIZUKU_EVENT,
                callLog = backendCalls,
            )
        val coordinator =
            ClipboardAccessCoordinator(
                backends =
                    listOf(
                        shizuku,
                        FakeBackgroundClipboardBackend(ClipboardReadMode.FOREGROUND_ONLY, callLog = backendCalls),
                    ),
                hasher = ContentHasher { "hash:$it" },
            )
        outbox = KeyValueClipOutbox(FakeKeyValueStore())
        val captureManager =
            ClipboardCaptureManager(
                settings = settings,
                writeCoordinator = ClipboardWriteCoordinator(publicWriter = FakeClipboardWriter()),
                outbox = { outbox },
                syncRequester = { SyncRequester { nudges++ } },
            )
        session =
            ClipboardCaptureSession(
                coordinator = coordinator,
                onChanged = { captureManager.onClipboardChanged(it) },
                captureAllowed = {
                    !settings.syncPaused && !settings.privateMode && !settings.autoCapturePaused
                },
            )
        return CaptureStack(
            capabilityStore = ClipboardCapabilityStore(FakeKeyValueStore()),
            routeProbes =
                object : RouteProbes {
                    override fun probe(): RoutePrerequisites = RoutePrerequisites()
                },
            realReaders = RealBackgroundReaders.build(context),
            foregroundBackend = ForegroundClipboardBackend(context, systemVersion = "test"),
            coordinator = coordinator,
            session = session,
        )
    }

    /** Keeps the service's Room wiring in memory; the capture assertions never touch it. */
    private fun inMemoryRepository(): RoomSyncRepository {
        database =
            Room
                .inMemoryDatabaseBuilder(context, ClipSyncDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        return RoomSyncRepository(
            store = ClipSyncRepository(database, LOCAL_DEVICE_ID),
            fanOutPeerIds = { emptyList() },
            maxContentUtf8Bytes = { settings.effectiveMaxSyncTextBytes },
        )
    }

    @After
    fun tearDown() {
        SharedClipboardCapture.stackProvider = originalStackProvider
        SharedClipboardCapture.reset()
        ClipboardSyncService.repositoryProvider = originalRepositoryProvider
        database.close()
    }

    private fun startService() {
        controller.create().startCommand(0, 0)
    }

    @Test
    fun `the promoted service owns the session and capture survives the activity leaving`() {
        startService()

        assertTrue(session.isRunning)
        assertTrue(session.serviceOwned)
        assertEquals("SHIZUKU_EVENT.start", backendCalls.last())

        // The activity comes to the foreground and leaves again: the audited P0 was exactly
        // this edge stopping the coordinator underneath the running service.
        session.acquire(ClipboardCaptureSession.Owner.ACTIVITY)
        session.release(ClipboardCaptureSession.Owner.ACTIVITY)
        assertTrue(session.isRunning)

        // A copy observed by the background backend lands in the outbox and nudges the engine.
        shizuku.emit("copied while the app is backgrounded", "hash:1")
        assertEquals("copied while the app is backgrounded", outbox.pending().single().text)
        assertEquals(ClipSource.FOREGROUND_APP, outbox.pending().single().source)
        assertEquals(1, nudges)

        controller.destroy()
        assertFalse(session.isRunning)
    }

    @Test
    fun `a visible activity keeps capture alive across a service stop`() {
        startService()
        session.acquire(ClipboardCaptureSession.Owner.ACTIVITY)

        controller.destroy()

        assertTrue(session.isRunning)
        assertFalse(session.serviceOwned)
        session.release(ClipboardCaptureSession.Owner.ACTIVITY)
        assertFalse(session.isRunning)
    }

    @Test
    fun `paused sync keeps every backend stopped even while the service is promoted`() {
        settings.syncPaused = true

        startService()

        assertTrue(session.serviceOwned)
        assertFalse(session.isRunning)
        assertTrue(backendCalls.isEmpty())

        // What PreferencesViewModel triggers via onCaptureGatesChanged on the unpause toggle.
        settings.syncPaused = false
        session.refreshGates()
        assertTrue(session.isRunning)
        controller.destroy()
    }

    @Test
    fun `flipping private mode on stops background reads immediately`() {
        startService()
        assertTrue(session.isRunning)

        settings.privateMode = true
        session.refreshGates()

        assertFalse(session.isRunning)
        assertEquals("SHIZUKU_EVENT.stop", backendCalls.last())
        controller.destroy()
    }

    @Test
    fun `the pause-capture notification action stops background reads via the settings listener`() {
        startService()
        assertTrue(session.isRunning)

        // The user taps 暂停捕获 on the resident notification: the PendingIntent redelivers
        // the service intent with the action, applyAction persists the toggle, and the
        // service's preference listener re-checks the gates — no UI involvement at all.
        controller.get().onStartCommand(actionIntent(SyncServiceNotification.ACTION_PAUSE_CAPTURE), 0, 1)

        assertFalse(session.isRunning)
        assertEquals("SHIZUKU_EVENT.stop", backendCalls.last())

        // 恢复捕获 from the same notification restarts the backend without an app launch.
        controller.get().onStartCommand(actionIntent(SyncServiceNotification.ACTION_RESUME_CAPTURE), 0, 2)
        assertTrue(session.isRunning)
        controller.destroy()
    }

    private fun actionIntent(action: String): Intent = Intent(context, ClipboardSyncService::class.java).setAction(action)
}

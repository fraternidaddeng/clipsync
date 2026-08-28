package com.clipsync.android.e2e

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.clipsync.android.media.MediaBlobStore
import com.clipsync.android.pairing.PairingJson
import com.clipsync.android.protocol.ProtocolJson
import com.clipsync.android.storage.ClipSyncDatabase
import com.clipsync.android.storage.ClipSyncRepository
import com.clipsync.android.sync.OkHttpSyncConnector
import com.clipsync.android.sync.OriginSequenceRanges
import com.clipsync.android.sync.RemoteClipApplied
import com.clipsync.android.sync.RoomSyncRepository
import com.clipsync.android.sync.SyncEngine
import com.clipsync.android.sync.SyncRepository
import com.clipsync.android.sync.SyncSessionConfig
import com.clipsync.android.sync.SyncSessionResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.io.path.createTempDirectory

/**
 * The protocol v2 image leg of the cross-client E2E (scripts/run-e2e-stage4.ps1), beside
 * [CrossClientSyncE2eTest]'s v1 text leg: the production [SyncEngine] on a real pinned TLS
 * WebSocket, backed by real Room + a real content-addressed [MediaBlobStore], against the
 * C# ClipSync.E2eHost. The script seeds png-8x8 on the Windows side and this test pushes
 * jpeg-1x1 back — the shared media fixtures whose bytes and hashes both suites already pin,
 * chosen distinct so hash dedup cannot mask a broken direction. Skipped unless
 * clipsync.e2e.enabled=true so normal unit-test runs stay green.
 *
 * This leg dials as its own paired device (android_image_device_id): each dialer test starts
 * from a fresh repository whose local sequences begin at 1, so sharing the text leg's identity
 * would collide the (origin, seq) keyspace on the Windows side between legs.
 */
@RunWith(RobolectricTestRunner::class)
class CrossClientImageSyncE2eTest {
    @Test
    fun mediaFixturesConvergeBothWaysOverV2() {
        Assume.assumeTrue(
            "set clipsync.e2e.enabled=true to run the cross-client interop test",
            System.getProperty(PROP_ENABLED) == "true",
        )

        val context: Context = ApplicationProvider.getApplicationContext()
        val database =
            Room
                .inMemoryDatabaseBuilder(context, ClipSyncDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        val mediaRoot = createTempDirectory("clipsync-e2e-image").toFile()
        try {
            runBlocking { Harness(database, mediaRoot).runLeg() }
        } finally {
            database.close()
            mediaRoot.deleteRecursively()
        }
    }

    /** One dialed v2 session with real Room + blob storage; each step is a method. */
    private class Harness(
        database: ClipSyncDatabase,
        mediaRoot: File,
    ) {
        private val androidDeviceId = requireProp(PROP_IMAGE_ID)
        private val windowsDeviceId = requireProp(PROP_WINDOWS_ID)
        private val mediaFixtures =
            File(File(requireNotNull(System.getProperty("protocol.v2.fixtures.dir"))), "media")
        private val manifest =
            Json.parseToJsonElement(File(mediaFixtures, "manifest.json").readText()).jsonObject
        private val pngBytes = File(mediaFixtures, "png-8x8.png").readBytes()
        private val pngHash = manifestString("png_8x8_sha256")

        private val mediaStore = MediaBlobStore(mediaRoot)
        private val repository =
            AckRecordingRepository(
                RoomSyncRepository(
                    store = ClipSyncRepository(database, androidDeviceId, mediaStore),
                    fanOutPeerIds = { listOf(windowsDeviceId) },
                ),
            )
        private val committed = mutableListOf<RemoteClipApplied>()
        private val engine =
            SyncEngine(
                repository = repository,
                config =
                    SyncSessionConfig(
                        localDeviceId = androidDeviceId,
                        peerDeviceId = windowsDeviceId,
                        trustEpoch = requireProp(PROP_IMAGE_EPOCH).toLong(),
                        clientVersion = "0.1.0",
                        protocolVersion = ProtocolJson.PROTOCOL_V2,
                        outboxDrainIntervalMs = 100,
                    ),
                pairSecret =
                    PairingJson.decodeBase64Url256(requireProp(PROP_SECRET))
                        ?: throw AssertionError("$PROP_SECRET is not 32-byte unpadded base64url"),
                onRemoteClipsCommitted = { batch -> synchronized(committed) { committed.addAll(batch) } },
            )

        suspend fun runLeg() {
            val transport =
                OkHttpSyncConnector().connect(
                    "127.0.0.1",
                    requireProp(PROP_PORT).toInt(),
                    requireProp(PROP_CERT),
                    protocolVersion = 2,
                )
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val run = scope.async { engine.run(transport) }
            try {
                awaitWindowsPng()
                pushJpegAndAwaitAck()
                closeAndAssertConverged(run)
                println("E2E-KOTLIN-IMAGE-OK")
            } finally {
                engine.requestClose()
                runCatching { withTimeout(5_000) { run.await() } }
                transport.dispose()
                scope.cancel()
            }
        }

        /**
         * The Windows-seeded fixture image arrives over announce -> want_ranges ->
         * clip_fetch -> begin/chunk/end, byte-exact, exactly once.
         */
        private suspend fun awaitWindowsPng() {
            val gotWindowsImage = awaitUntil(TIMEOUT_MS) { pngCommits() == 1 }
            if (!gotWindowsImage) {
                fail(
                    "timed out waiting for the Windows png fixture within ${TIMEOUT_MS}ms; " +
                        "committed=${synchronized(committed) { committed.map { it.originSeq to it.kind } }}",
                )
            }
            assertArrayEquals(
                "blob bytes must survive the chunk stream",
                pngBytes,
                mediaStore.readAllBytes(pngHash),
            )
        }

        /**
         * A local jpeg image travels announce -> peer fetch -> chunk stream to Windows and
         * is acked; the script then verifies its hash in the Windows store exactly once.
         */
        private suspend fun pushJpegAndAwaitAck() {
            val validated =
                mediaStore.commitBytes(
                    File(mediaFixtures, "jpeg-1x1.jpg").readBytes(),
                    manifestString("jpeg_1x1_sha256"),
                )
            val recorded =
                repository.recordLocalImageClip(validated, sourceApp = "e2e", nowMs = System.currentTimeMillis())
            assertNotNull("local image must be recorded", recorded)
            val acked =
                awaitUntil(TIMEOUT_MS) {
                    repository.ackedLocalSequences(androidDeviceId).contains(recorded!!.originSeq)
                }
            if (!acked) {
                fail(
                    "timed out waiting for the android->windows image ack within ${TIMEOUT_MS}ms; " +
                        "acked=${repository.ackedLocalSequences(androidDeviceId)}",
                )
            }
        }

        /** The session ends authenticated and the inbound image committed exactly once. */
        private suspend fun closeAndAssertConverged(run: Deferred<SyncSessionResult>) {
            engine.requestClose()
            val result = withTimeout(TIMEOUT_MS) { run.await() }
            assertTrue("session must end authenticated: $result", result.authenticated)
            assertEquals("windows image must commit exactly once", 1, pngCommits())
        }

        // Hash equality is the whole identity: text clips carry a null contentHash.
        private fun pngCommits(): Int = synchronized(committed) { committed.count { it.contentHash == pngHash } }

        private fun manifestString(key: String): String = requireNotNull(manifest[key]).jsonPrimitive.content
    }

    /** Records the peer's ACK_RANGES so the test can observe them without mutating state. */
    private class AckRecordingRepository(
        private val inner: SyncRepository,
    ) : SyncRepository by inner {
        private val lock = Any()
        private val acked = mutableListOf<OriginSequenceRanges>()

        override suspend fun applyPeerAckRanges(
            peerDeviceId: String,
            ranges: List<OriginSequenceRanges>,
            nowMs: Long,
            dropTerminalOutbox: Boolean,
        ) {
            inner.applyPeerAckRanges(peerDeviceId, ranges, nowMs, dropTerminalOutbox)
            synchronized(lock) { acked.addAll(ranges) }
        }

        fun ackedLocalSequences(originDeviceId: String): Set<Long> =
            synchronized(lock) {
                acked
                    .filter { it.originDeviceId == originDeviceId }
                    .flatMap { origin -> origin.ranges.flatMap { range -> (range.startSeq..range.endSeq).toList() } }
                    .toSet()
            }
    }

    private companion object {
        const val PROP_ENABLED = "clipsync.e2e.enabled"
        const val PROP_PORT = "clipsync.e2e.port"
        const val PROP_CERT = "clipsync.e2e.cert"
        const val PROP_WINDOWS_ID = "clipsync.e2e.windowsDeviceId"
        const val PROP_IMAGE_ID = "clipsync.e2e.androidImageDeviceId"
        const val PROP_SECRET = "clipsync.e2e.pairSecretB64url"
        const val PROP_IMAGE_EPOCH = "clipsync.e2e.imageTrustEpoch"
        const val TIMEOUT_MS = 30_000L

        fun requireProp(name: String): String {
            val value = System.getProperty(name)
            require(!value.isNullOrBlank()) { "missing system property $name" }
            return value
        }

        suspend fun awaitUntil(
            timeoutMs: Long,
            condition: suspend () -> Boolean,
        ): Boolean {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (condition()) {
                    return true
                }
                delay(50)
            }
            return condition()
        }
    }
}

package com.clipsync.android.e2e

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.clipsync.android.media.ImageCodec
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
 * Multi-chunk sibling of [CrossClientImageSyncE2eTest]: the shared media fixtures are tiny
 * (png-8x8 / jpeg-1x1, one chunk each), so this leg pulls and pushes megabyte-scale PNGs —
 * every image travels as a real begin -> chunk-stream -> end of MAX_CHUNK_BYTES frames over
 * the pinned TLS WebSocket against the C# host, the size class real screenshots occupy.
 * Runs only when the driver passes the large fixture paths; regular unit-test runs skip it.
 *
 * Dials as its own paired identity (android_large_image_device_id): each leg starts from a
 * fresh repository whose local sequences begin at 1, so sharing an identity with the small
 * image leg would collide the (origin, seq) keyspace between the two.
 */
@RunWith(RobolectricTestRunner::class)
class LargeImageCrossClientE2eTest {
    @Test
    fun multiChunkImagesConvergeBothWaysOverV2() {
        Assume.assumeTrue(
            "set clipsync.e2e.enabled=true to run the cross-client interop test",
            System.getProperty(PROP_ENABLED) == "true",
        )
        Assume.assumeTrue(
            "set $PROP_SEED_PATH/$PROP_PUSH_PATH to run the large-image leg",
            !System.getProperty(PROP_SEED_PATH).isNullOrBlank() &&
                !System.getProperty(PROP_PUSH_PATH).isNullOrBlank(),
        )

        val context: Context = ApplicationProvider.getApplicationContext()
        val database =
            Room
                .inMemoryDatabaseBuilder(context, ClipSyncDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        val mediaRoot = createTempDirectory("clipsync-e2e-large-image").toFile()
        try {
            runBlocking { Harness(database, mediaRoot).runLeg() }
        } finally {
            database.close()
            mediaRoot.deleteRecursively()
        }
    }

    /** One dialed v2 session moving one large image in each direction. */
    private class Harness(
        database: ClipSyncDatabase,
        mediaRoot: File,
    ) {
        private val androidDeviceId = requireProp(PROP_LARGE_ID)
        private val windowsDeviceId = requireProp(PROP_WINDOWS_ID)
        private val seedBytes = File(requireProp(PROP_SEED_PATH)).readBytes()
        private val seedHash = ImageCodec.hashBytes(seedBytes)
        private val pushBytes = File(requireProp(PROP_PUSH_PATH)).readBytes()

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
                        trustEpoch = requireProp(PROP_LARGE_EPOCH).toLong(),
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
                awaitSeededLargePng()
                pushLargePngAndAwaitAck()
                closeAndAssertConverged(run)
                println("E2E-KOTLIN-LARGE-IMAGE-OK")
            } finally {
                engine.requestClose()
                runCatching { withTimeout(5_000) { run.await() } }
                transport.dispose()
                scope.cancel()
            }
        }

        /** The Windows-seeded multi-chunk PNG arrives byte-exact, exactly once. */
        private suspend fun awaitSeededLargePng() {
            val arrived = awaitUntil(TIMEOUT_MS) { seedCommits() == 1 }
            if (!arrived) {
                fail(
                    "timed out waiting for the ${seedBytes.size}-byte Windows png within ${TIMEOUT_MS}ms; " +
                        "committed=${synchronized(committed) { committed.map { it.originSeq to it.kind } }}",
                )
            }
            assertArrayEquals(
                "blob bytes must survive the multi-chunk stream",
                seedBytes,
                mediaStore.readAllBytes(seedHash),
            )
        }

        /** A local multi-chunk PNG streams out and Windows acks its sequence. */
        private suspend fun pushLargePngAndAwaitAck() {
            val validated = mediaStore.commitBytes(pushBytes, ImageCodec.hashBytes(pushBytes))
            val recorded =
                repository.recordLocalImageClip(validated, sourceApp = "e2e", nowMs = System.currentTimeMillis())
            assertNotNull("local image must be recorded", recorded)
            val acked =
                awaitUntil(TIMEOUT_MS) {
                    repository.ackedLocalSequences(androidDeviceId).contains(recorded!!.originSeq)
                }
            if (!acked) {
                fail(
                    "timed out waiting for the android->windows large-image ack within ${TIMEOUT_MS}ms; " +
                        "acked=${repository.ackedLocalSequences(androidDeviceId)}",
                )
            }
        }

        private suspend fun closeAndAssertConverged(run: Deferred<SyncSessionResult>) {
            engine.requestClose()
            val result = withTimeout(TIMEOUT_MS) { run.await() }
            assertTrue("session must end authenticated: $result", result.authenticated)
            assertEquals("windows image must commit exactly once", 1, seedCommits())
        }

        private fun seedCommits(): Int = synchronized(committed) { committed.count { it.contentHash == seedHash } }
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
        ) {
            inner.applyPeerAckRanges(peerDeviceId, ranges, nowMs)
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
        const val PROP_LARGE_ID = "clipsync.e2e.androidLargeImageDeviceId"
        const val PROP_SECRET = "clipsync.e2e.pairSecretB64url"
        const val PROP_LARGE_EPOCH = "clipsync.e2e.largeImageTrustEpoch"
        const val PROP_SEED_PATH = "clipsync.e2e.largeSeedPath"
        const val PROP_PUSH_PATH = "clipsync.e2e.largePushPath"
        const val TIMEOUT_MS = 60_000L

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

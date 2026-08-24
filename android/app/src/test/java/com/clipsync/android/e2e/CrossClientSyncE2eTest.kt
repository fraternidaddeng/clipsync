package com.clipsync.android.e2e

import com.clipsync.android.pairing.PairingJson
import com.clipsync.android.sync.InMemorySyncRepository
import com.clipsync.android.sync.OkHttpSyncConnector
import com.clipsync.android.sync.OriginSequenceRanges
import com.clipsync.android.sync.RemoteClipApplied
import com.clipsync.android.sync.SyncEngine
import com.clipsync.android.sync.SyncRepository
import com.clipsync.android.sync.SyncSessionConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume
import org.junit.Test

/**
 * Real-stack interop: the production [SyncEngine] dialing over a real pinned TLS WebSocket
 * ([OkHttpSyncConnector]) against the C# ClipSync.E2eHost listener started by
 * scripts/run-e2e-stage4.ps1. Skipped unless clipsync.e2e.enabled=true so normal
 * unit-test runs stay green.
 */
class CrossClientSyncE2eTest {
    @Test
    fun windowsListenerAndAndroidDialerConvergeBothWays() {
        Assume.assumeTrue(
            "set clipsync.e2e.enabled=true to run the cross-client interop test",
            System.getProperty(PROP_ENABLED) == "true",
        )

        runBlocking {
            val port = requireProp(PROP_PORT).toInt()
            val cert = requireProp(PROP_CERT)
            val windowsDeviceId = requireProp(PROP_WINDOWS_ID)
            val androidDeviceId = requireProp(PROP_ANDROID_ID)
            val secret = PairingJson.decodeBase64Url256(requireProp(PROP_SECRET))
                ?: throw AssertionError("$PROP_SECRET is not 32-byte unpadded base64url")
            val trustEpoch = requireProp(PROP_EPOCH).toLong()

            val repository = AckRecordingRepository(InMemorySyncRepository(androidDeviceId))
            val committed = mutableListOf<RemoteClipApplied>()
            val engine = SyncEngine(
                repository = repository,
                config = SyncSessionConfig(
                    localDeviceId = androidDeviceId,
                    peerDeviceId = windowsDeviceId,
                    trustEpoch = trustEpoch,
                    clientVersion = "0.1.0",
                    outboxDrainIntervalMs = 100,
                ),
                pairSecret = secret,
                onRemoteClipsCommitted = { batch -> synchronized(committed) { committed.addAll(batch) } },
            )

            val transport = OkHttpSyncConnector().connect("127.0.0.1", port, cert, protocolVersion = 1)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val run = scope.async { engine.run(transport) }
            try {
                // 1. The Windows backlog clip captured before this dial arrives exactly once.
                val gotWindowsBacklog = awaitUntil(TIMEOUT_MS) {
                    synchronized(committed) { committed.count { it.content == WINDOWS_TEXT } } == 1
                }
                if (!gotWindowsBacklog) {
                    fail(
                        "timed out waiting for the Windows backlog ($WINDOWS_TEXT) within ${TIMEOUT_MS}ms; " +
                            "committed=${synchronized(committed) { committed.map { it.originSeq } }}",
                    )
                }

                // 2. A local capture travels the outbox drain to Windows and is acked.
                val recorded = repository.recordLocalClip(ANDROID_TEXT, null, System.currentTimeMillis())
                assertNotNull("local capture must be recorded", recorded)
                val acked = awaitUntil(TIMEOUT_MS) {
                    repository.ackedLocalSequences(androidDeviceId).contains(recorded!!.originSeq)
                }
                if (!acked) {
                    fail(
                        "timed out waiting for the android->windows ack within ${TIMEOUT_MS}ms; " +
                            "acked=${repository.ackedLocalSequences(androidDeviceId)}",
                    )
                }

                // 3. The session ends authenticated, and nothing arrived twice.
                engine.requestClose()
                val result = withTimeout(TIMEOUT_MS) { run.await() }
                assertTrue("session must end authenticated: $result", result.authenticated)
                assertEquals(
                    "windows clip must commit exactly once",
                    1,
                    synchronized(committed) { committed.count { it.content == WINDOWS_TEXT } },
                )

                // 4. Acked outbox rows are gone for good: a reset resurrects nothing.
                repository.resetOutboxToPending(windowsDeviceId)
                assertTrue(
                    "acked rows must not reappear after reset-to-pending",
                    repository.getOutboxBatch(windowsDeviceId, 16).isEmpty(),
                )
                println("E2E-KOTLIN-OK")
            } finally {
                engine.requestClose()
                runCatching { withTimeout(5_000) { run.await() } }
                transport.dispose()
                scope.cancel()
            }
        }
    }

    private fun requireProp(name: String): String {
        val value = System.getProperty(name)
        require(!value.isNullOrBlank()) { "missing system property $name" }
        return value
    }

    private suspend fun awaitUntil(timeoutMs: Long, condition: suspend () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) {
                return true
            }
            delay(50)
        }
        return condition()
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

        fun ackedLocalSequences(originDeviceId: String): Set<Long> = synchronized(lock) {
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
        const val PROP_ANDROID_ID = "clipsync.e2e.androidDeviceId"
        const val PROP_SECRET = "clipsync.e2e.pairSecretB64url"
        const val PROP_EPOCH = "clipsync.e2e.trustEpoch"
        const val WINDOWS_TEXT = "e2e-from-windows"
        const val ANDROID_TEXT = "e2e-from-android"
        const val TIMEOUT_MS = 30_000L
    }
}

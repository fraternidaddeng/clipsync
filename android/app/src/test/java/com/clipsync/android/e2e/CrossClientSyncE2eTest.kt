package com.clipsync.android.e2e

import com.clipsync.android.pairing.FakeKeyValueStore
import com.clipsync.android.pairing.FakeSecretProtector
import com.clipsync.android.pairing.PairingConfirmResponse
import com.clipsync.android.pairing.PairingDocumentKinds
import com.clipsync.android.pairing.PairingJson
import com.clipsync.android.pairing.PairingQrPayload
import com.clipsync.android.pairing.PairingStore
import com.clipsync.android.storage.CaptureResult
import com.clipsync.android.storage.SETTING_PAIRED_PEER_ID
import com.clipsync.android.storage.createTestClipRepository
import com.clipsync.android.sync.SyncSessionOptions
import com.clipsync.android.sync.createSyncController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume
import org.junit.Test

/**
 * Real-stack interop: Kotlin dialer against the C# ClipSync.E2eHost listener.
 * Skipped unless clipsync.e2e.enabled=true so normal unit-test runs stay green.
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
            val secretB64url = System.getProperty(PROP_SECRET)?.takeIf { it.isNotBlank() }
                ?: requireProp("clipsync.e2e.secretB64url")
            val trustEpoch = requireProp(PROP_EPOCH).toLong()

            val keys = FakeKeyValueStore()
            keys.write(mapOf("local.device_id" to androidDeviceId))
            val pairingStore = PairingStore(keys, FakeSecretProtector())
            val secret = PairingJson.decodeBase64Url256(secretB64url)
                ?: throw AssertionError("$PROP_SECRET is not 32-byte unpadded base64url")
            pairingStore.savePeer(
                PairingQrPayload(
                    kind = PairingDocumentKinds.QR,
                    version = 1,
                    hosts = listOf("127.0.0.1"),
                    port = port,
                    deviceId = windowsDeviceId,
                    displayName = "E2E Windows",
                    certSha256 = cert,
                    token = DUMMY_TOKEN,
                    expiresAtMs = System.currentTimeMillis() + 60_000,
                ),
                PairingConfirmResponse(
                    kind = PairingDocumentKinds.CONFIRM_RESPONSE,
                    version = 1,
                    deviceId = windowsDeviceId,
                    displayName = "E2E Windows",
                    platform = "windows",
                    pairSecret = secretB64url,
                    trustEpoch = trustEpoch,
                ),
                secret,
                nowMs = System.currentTimeMillis(),
            )

            val repository = createTestClipRepository(localDeviceId = androidDeviceId)
            repository.initialize()
            repository.setSetting(SETTING_PAIRED_PEER_ID, windowsDeviceId)

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val controller = createSyncController(
                pairingStore = pairingStore,
                repository = repository,
                scope = scope,
                options = SyncSessionOptions(outboxDrainIntervalMs = 100),
            )
            try {
                controller.start()

                val gotWindowsBacklog = awaitUntil(TIMEOUT_MS) {
                    repository.search(WINDOWS_TEXT).any { it.content == WINDOWS_TEXT }
                }
                if (!gotWindowsBacklog) {
                    val status = controller.status()
                    fail(
                        "timed out waiting for Windows backlog ($WINDOWS_TEXT) within ${TIMEOUT_MS}ms. " +
                            "status=${status.status} lastError=${status.lastErrorCode} " +
                            "lastDetail=${status.lastDetail} authenticated=${status.authenticated} " +
                            "visible=${repository.search("").size}",
                    )
                }

                val captured = repository.captureLocalText(ANDROID_TEXT, nowMs = System.currentTimeMillis())
                assertTrue("android capture rejected: $captured", captured is CaptureResult.Stored)

                val acked = awaitUntil(TIMEOUT_MS) {
                    repository.outboxPending(windowsDeviceId).isEmpty()
                }
                if (!acked) {
                    val status = controller.status()
                    fail(
                        "timed out waiting for android->windows ack within ${TIMEOUT_MS}ms. " +
                            "pending=${repository.outboxPending(windowsDeviceId).size} " +
                            "status=${status.status} lastError=${status.lastErrorCode} " +
                            "lastDetail=${status.lastDetail} authenticated=${status.authenticated}",
                    )
                }

                controller.stop()
                val windowsCopies = repository.search(WINDOWS_TEXT).count { it.content == WINDOWS_TEXT }
                assertEquals("windows clip must arrive exactly once", 1, windowsCopies)
                println("E2E-KOTLIN-OK")
            } finally {
                controller.stop()
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
        const val DUMMY_TOKEN = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
    }
}

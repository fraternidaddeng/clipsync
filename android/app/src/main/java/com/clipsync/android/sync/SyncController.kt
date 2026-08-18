package com.clipsync.android.sync

import com.clipsync.android.pairing.PairingStore
import com.clipsync.android.storage.ClipRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Small start/stop/status surface the later history UI can call. Does not own
 * a ForegroundService (Stage 5). Android is always the dialer.
 */
class SyncController(
    private val pairingStore: PairingStore,
    private val repository: ClipRepository,
    private val connector: SyncConnector,
    private val scope: CoroutineScope,
    private val options: SyncSessionOptions = SyncSessionOptions(),
    private val logger: SyncLogger = SyncLogger.NoOp,
    private val onRemoteClipsCommitted: (List<RemoteClipApplied>) -> Unit = {},
) {
    private val _state = MutableStateFlow(SyncControllerState(SyncStatus.STOPPED))
    val state: StateFlow<SyncControllerState> = _state.asStateFlow()

    private var loopJob: Job? = null

    fun status(): SyncControllerState = _state.value

    // Synchronized: the process-scoped instance is started from the main thread
    // (Activity, service) and the connectivity callback thread concurrently; an
    // unsynchronized check-then-launch could orphan a second run loop.
    @Synchronized
    fun start() {
        if (loopJob?.isActive == true) {
            return
        }
        loopJob = scope.launch { runLoop() }
    }

    @Synchronized
    fun stop() {
        loopJob?.cancel()
        loopJob = null
        _state.value = SyncControllerState(SyncStatus.STOPPED)
    }

    private suspend fun runLoop() {
        var failures = 0
        while (currentCoroutineContext().isActive) {
            val peer = pairingStore.peer()
            val secret = pairingStore.pairSecret()
            if (peer == null || secret == null) {
                secret?.fill(0)
                publish(SyncControllerState(SyncStatus.IDLE_UNPAIRED))
                return
            }
            publish(
                SyncControllerState(
                    status = SyncStatus.CONNECTING,
                    peerDeviceId = peer.deviceId,
                ),
            )
            val connect = try {
                connector.connect(peer.hosts, peer.port, peer.certSha256)
            } catch (_: Exception) {
                secret.fill(0)
                SyncConnectResult.Unreachable(peer.hosts)
            }
            when (connect) {
                is SyncConnectResult.CertificateMismatch -> {
                    secret.fill(0)
                    logger.event("certificate_mismatch", "blocked")
                    publish(
                        SyncControllerState(
                            status = SyncStatus.CERTIFICATE_MISMATCH,
                            peerDeviceId = peer.deviceId,
                            lastDetail = "certificate_mismatch",
                        ),
                    )
                    return
                }
                is SyncConnectResult.Unreachable -> {
                    secret.fill(0)
                    failures += 1
                    val wait = reconnectBackoffMs(failures - 1)
                    publish(
                        SyncControllerState(
                            status = SyncStatus.BACKING_OFF,
                            peerDeviceId = peer.deviceId,
                            lastDetail = "unreachable",
                            nextRetryAtMs = options.nowMs() + wait,
                        ),
                    )
                    options.delayMs(wait)
                }
                is SyncConnectResult.Connected -> {
                    publish(
                        SyncControllerState(
                            status = SyncStatus.AUTHENTICATING,
                            peerDeviceId = peer.deviceId,
                        ),
                    )
                    val engine = SyncSessionEngine(
                        repository = repository,
                        localDeviceId = pairingStore.localDeviceId(),
                        peer = peer,
                        pairSecret = secret,
                        options = options,
                        logger = logger,
                        isPeerTrusted = {
                            val current = pairingStore.peer()
                            current != null &&
                                current.deviceId == peer.deviceId &&
                                current.trustEpoch == peer.trustEpoch
                        },
                        onReady = {
                            publish(
                                SyncControllerState(
                                    status = SyncStatus.READY,
                                    peerDeviceId = peer.deviceId,
                                    authenticated = true,
                                ),
                            )
                        },
                        onRemoteClipsCommitted = onRemoteClipsCommitted,
                    )
                    secret.fill(0)
                    val result = try {
                        engine.run(connect.transport)
                    } catch (cancelled: CancellationException) {
                        if (!currentCoroutineContext().isActive) {
                            return
                        }
                        SyncSessionResult(true, null, "cancelled")
                    } finally {
                        try {
                            connect.transport.close("controller")
                        } catch (_: Exception) {
                        }
                        connect.release()
                    }
                    if (!currentCoroutineContext().isActive) {
                        return
                    }
                    failures += 1
                    val wait = reconnectBackoffMs(failures - 1)
                    publish(
                        SyncControllerState(
                            status = SyncStatus.BACKING_OFF,
                            peerDeviceId = peer.deviceId,
                            lastErrorCode = result.errorCode,
                            lastDetail = result.detail,
                            nextRetryAtMs = options.nowMs() + wait,
                            authenticated = result.authenticated,
                        ),
                    )
                    options.delayMs(wait)
                }
            }
        }
    }

    private fun publish(next: SyncControllerState) {
        _state.value = next
    }

    companion object {
        /**
         * 1, 2, 4, 8, 16, 30s then stay at 30s, never above 5 minutes.
         * [failureIndex] is zero for the first reconnect after a failure.
         */
        fun reconnectBackoffMs(failureIndex: Int): Long {
            val steps = longArrayOf(1_000, 2_000, 4_000, 8_000, 16_000, 30_000)
            val index = failureIndex.coerceAtLeast(0)
            val delay = if (index < steps.size) steps[index] else 30_000L
            return minOf(delay, SyncSessionOptions.MAX_BACKOFF_MS)
        }
    }
}

// Named-args factory mirroring the SyncController constructor; every extra
// parameter is an optional seam with a safe default.
@Suppress("LongParameterList")
fun createSyncController(
    pairingStore: PairingStore,
    repository: ClipRepository,
    scope: CoroutineScope,
    options: SyncSessionOptions = SyncSessionOptions(),
    logger: SyncLogger = SyncLogger.NoOp,
    onRemoteClipsCommitted: (List<RemoteClipApplied>) -> Unit = {},
): SyncController = SyncController(
    pairingStore = pairingStore,
    repository = repository,
    connector = OkHttpSyncConnector(),
    scope = scope,
    options = options,
    logger = logger,
    onRemoteClipsCommitted = onRemoteClipsCommitted,
)

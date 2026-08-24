package com.clipsync.android.ui.conduit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.clipsync.android.pairing.PairingStore
import com.clipsync.android.pairing.PeerHealthApi
import com.clipsync.android.pairing.PeerHealthOutcome
import com.clipsync.android.platform.clipboard.BackgroundClipboardBackend
import com.clipsync.android.platform.clipboard.CapabilityState
import com.clipsync.android.platform.clipboard.ClipboardAccessCoordinator
import com.clipsync.android.platform.clipboard.ClipboardCapabilityStore
import com.clipsync.android.platform.clipboard.ClipboardReadMode
import com.clipsync.android.platform.clipboard.ClipboardReadResult
import com.clipsync.android.platform.clipboard.ClipboardWriteCoordinator
import com.clipsync.android.platform.clipboard.ClipboardWriteResult
import com.clipsync.android.platform.clipboard.RouteProbes
import com.clipsync.android.service.ClipboardSyncService
import java.util.UUID
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Drives the conduit page: probes every capability on resume and on user refresh, persists the
 * chosen read route, and runs the read/write tests. All state derivation lives in
 * [ConduitStateMapper]; this class only gathers facts and never touches clipboard content
 * beyond the generated test token.
 */
class ConduitViewModel(
    private val coordinator: ClipboardAccessCoordinator,
    private val routeProbes: RouteProbes,
    private val capabilityStore: ClipboardCapabilityStore,
    private val pairingStore: PairingStore,
    private val peerHealth: PeerHealthApi,
    private val writeCoordinator: ClipboardWriteCoordinator,
    private val foregroundBackend: BackgroundClipboardBackend,
    private val clearClipboard: () -> Unit,
    private val ioContext: CoroutineContext = Dispatchers.IO,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private var inputs = ConduitInputs.initial(
        preferredReadMode = capabilityStore.preferredReadMode(),
        publicWriteState = capabilityStore.publicWriteState(),
    )

    private val mutableState = MutableStateFlow(
        ConduitStateMapper.derive(inputs).let { derived ->
            ConduitUiState(
                segments = derived.segments,
                routes = derived.routes,
                preferredReadMode = inputs.preferredReadMode,
                paired = false,
                peerName = null,
            )
        },
    )
    val state: StateFlow<ConduitUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                ClipboardSyncService.running,
                ClipboardSyncService.lastErrorCode,
            ) { running, errorCode -> running to errorCode }
                .collect { (running, errorCode) ->
                    updateInputs { it.copy(serviceRunning = running, serviceErrorCode = errorCode) }
                }
        }
    }

    /** Full re-probe: capability ladder, route prerequisites, pairing and peer reachability. */
    fun refresh() {
        viewModelScope.launch {
            mutableState.update { it.copy(refreshing = true) }
            val reports = withContext(ioContext) { coordinator.probe() }.associateBy { it.readMode }
            val prerequisites = withContext(ioContext) { routeProbes.probe() }
            val peer = withContext(ioContext) { pairingStore.peer() }
            val reachability = if (peer == null) {
                PeerReachability.UNKNOWN
            } else {
                when (peerHealth.probe(peer)) {
                    is PeerHealthOutcome.Reachable -> PeerReachability.REACHABLE
                    PeerHealthOutcome.CertificateMismatch -> PeerReachability.CERTIFICATE_MISMATCH
                    PeerHealthOutcome.Unreachable -> PeerReachability.UNREACHABLE
                }
            }
            updateInputs {
                it.copy(
                    reports = reports,
                    prerequisites = prerequisites,
                    preferredReadMode = capabilityStore.preferredReadMode(),
                    paired = peer != null,
                    peerName = peer?.displayName,
                    reachability = reachability,
                    publicWriteState = capabilityStore.publicWriteState(),
                    publicWriteErrorCode = capabilityStore.publicWriteErrorCode(),
                    fallbackWriteState = writeCoordinator.fallbackWriteState,
                )
            }
            mutableState.update { it.copy(refreshing = false, lastProbeAtMs = nowMs()) }
        }
    }

    /** Persists the wizard's route choice; the coordinator will start from it (task §4). */
    fun selectPreferredMode(mode: ClipboardReadMode) {
        capabilityStore.setPreferredReadMode(mode)
        coordinator.requestMode(mode)
        updateInputs { it.copy(preferredReadMode = mode) }
    }

    fun setWizardOpen(open: Boolean) {
        mutableState.update { it.copy(wizardOpen = open) }
    }

    fun dismissTestResult() {
        mutableState.update { it.copy(testResult = null) }
    }

    fun noteAdbCommandCopied() {
        mutableState.update {
            it.copy(
                testResult = TestResult("已复制 adb 命令；在电脑上执行后回来点「刷新」", success = true),
            )
        }
    }

    /** Foreground read check — reports only success/emptiness/error code, never content. */
    fun runReadTest() {
        viewModelScope.launch {
            val result = withContext(ioContext) { foregroundBackend.readText() }
            val test = when (result) {
                is ClipboardReadResult.Success ->
                    TestResult("读取成功：${result.text.length} 个字符（内容不显示）", success = true)
                ClipboardReadResult.Empty -> TestResult("读取成功：剪贴板当前为空", success = true)
                is ClipboardReadResult.Failure -> TestResult("读取失败：${result.errorCode}", success = false)
            }
            mutableState.update { it.copy(testResult = test) }
        }
    }

    /**
     * Writes an app-generated random token through the write coordinator, verifies it by
     * reading back, clears it immediately (plan §5.3) and persists the verified state so the
     * write segment shows real, tested capability.
     */
    fun runWriteTest() {
        viewModelScope.launch {
            val token = "clipsync-test-" + UUID.randomUUID().toString().take(8)
            val (outcome, readBack) = withContext(ioContext) {
                val written = writeCoordinator.writeText(token, originEventId = "capability-write-test-${nowMs()}")
                val back = foregroundBackend.readText()
                clearClipboard()
                written to back
            }
            val verified = outcome.result is ClipboardWriteResult.Success &&
                (readBack as? ClipboardReadResult.Success)?.text == token
            val errorCode = (outcome.result as? ClipboardWriteResult.Failure)?.errorCode
                ?: ERROR_WRITE_UNVERIFIED.takeUnless { verified }
            withContext(ioContext) {
                capabilityStore.recordWriteTest(
                    state = if (verified) CapabilityState.READY else CapabilityState.UNAVAILABLE,
                    errorCode = errorCode,
                    atMs = nowMs(),
                )
            }
            updateInputs {
                it.copy(
                    publicWriteState = capabilityStore.publicWriteState(),
                    publicWriteErrorCode = capabilityStore.publicWriteErrorCode(),
                )
            }
            mutableState.update {
                it.copy(
                    testResult = if (verified) {
                        TestResult("写入测试通过（测试文本已清除）", success = true)
                    } else {
                        TestResult("写入测试失败：$errorCode", success = false)
                    },
                )
            }
        }
    }

    private fun updateInputs(transform: (ConduitInputs) -> ConduitInputs) {
        inputs = transform(inputs)
        val derived = ConduitStateMapper.derive(inputs)
        mutableState.update {
            it.copy(
                segments = derived.segments,
                routes = derived.routes,
                preferredReadMode = inputs.preferredReadMode,
                paired = inputs.paired,
                peerName = inputs.peerName,
            )
        }
    }

    companion object {
        const val ERROR_WRITE_UNVERIFIED = "CLIPBOARD_WRITE_UNVERIFIED"

        fun factory(
            coordinator: ClipboardAccessCoordinator,
            routeProbes: RouteProbes,
            capabilityStore: ClipboardCapabilityStore,
            pairingStore: PairingStore,
            peerHealth: PeerHealthApi,
            writeCoordinator: ClipboardWriteCoordinator,
            foregroundBackend: BackgroundClipboardBackend,
            clearClipboard: () -> Unit,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ConduitViewModel(
                coordinator = coordinator,
                routeProbes = routeProbes,
                capabilityStore = capabilityStore,
                pairingStore = pairingStore,
                peerHealth = peerHealth,
                writeCoordinator = writeCoordinator,
                foregroundBackend = foregroundBackend,
                clearClipboard = clearClipboard,
            ) as T
        }
    }
}

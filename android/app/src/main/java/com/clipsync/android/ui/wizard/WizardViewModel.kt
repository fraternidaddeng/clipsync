package com.clipsync.android.ui.wizard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.clipsync.android.platform.clipboard.CapabilityState
import com.clipsync.android.platform.clipboard.ClipboardReadMode
import com.clipsync.android.platform.clipboard.ClipboardSelfTest
import com.clipsync.android.platform.clipboard.ClipboardWriteMode
import com.clipsync.android.platform.clipboard.SelfTestKind
import com.clipsync.android.platform.clipboard.SelfTestResult
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class WizardViewModel(
    private val settings: WizardSettings,
    private val probes: WizardProbes,
    private val selfTest: ClipboardSelfTest? = null,
    private val selfTestContext: CoroutineContext = Dispatchers.IO,
    private val requestPrivilegedAuthorization: (((Boolean) -> Unit) -> Unit)? = null,
) : ViewModel() {
    private var skipped: Set<WizardStepId> = settings.loadSkippedSteps()
    private val mutableState = MutableStateFlow(buildState(settings.load()))
    private val mutableSelfTest = MutableStateFlow(SelfTestUiState())

    val state: StateFlow<WizardUiState> = mutableState.asStateFlow()
    val selfTestState: StateFlow<SelfTestUiState> = mutableSelfTest.asStateFlow()

    var lastActionKind: WizardActionKind? = null
        private set

    var lastActionOfferedInAppGrant: Boolean = false
        private set

    fun step(id: WizardStepId): WizardStepStatus =
        state.value.steps.first { it.id == id }

    fun refresh() {
        publish(state.value.choices)
    }

    fun skip(id: WizardStepId) {
        skipped = skipped + id
        settings.saveSkippedSteps(skipped)
        val choices = if (id == WizardStepId.OVERLAY) {
            state.value.choices.copy(overlayConsented = false)
        } else {
            state.value.choices
        }
        persist(choices)
    }

    fun unskip(id: WizardStepId) {
        skipped = skipped - id
        settings.saveSkippedSteps(skipped)
        publish(state.value.choices)
    }

    fun onStepAction(id: WizardStepId) {
        lastActionKind = actionKindFor(id)
        lastActionOfferedInAppGrant = offersInAppGrant(id) && id != WizardStepId.READ_LOGS
        if (id == WizardStepId.SHIZUKU_AUTH) {
            requestPrivilegedAuthorization?.invoke { refresh() }
            return
        }
        refresh()
    }

    fun setPreferredReadMode(mode: ClipboardReadMode) {
        persist(state.value.choices.copy(preferredReadMode = mode))
    }

    fun setAutoFallbackAllowed(allowed: Boolean) {
        persist(state.value.choices.copy(autoFallbackAllowed = allowed))
    }

    fun setPollingIntervalMs(ms: Int) {
        persist(
            state.value.choices.copy(
                pollingIntervalMs = WizardChoices.clampPollingIntervalMs(ms),
            ),
        )
    }

    fun setBackgroundAutoUpload(enabled: Boolean) {
        persist(state.value.choices.copy(backgroundAutoUpload = enabled))
    }

    fun setBackgroundAutoApply(enabled: Boolean) {
        persist(state.value.choices.copy(backgroundAutoApply = enabled))
    }

    fun setOverlayConsented(consented: Boolean) {
        val nextSkipped = if (consented) skipped - WizardStepId.OVERLAY else skipped
        skipped = nextSkipped
        settings.saveSkippedSteps(skipped)
        persist(state.value.choices.copy(overlayConsented = consented))
    }

    fun setWriteMode(mode: ClipboardWriteMode) {
        persist(state.value.choices.copy(writeMode = mode))
    }

    fun finish() {
        if (!state.value.canFinish) {
            return
        }
        persist(state.value.choices.copy(wizardCompleted = true))
    }

    fun runBackgroundReadTest() = launchSelfTest(SelfTestKind.BACKGROUND_READ)

    fun runBackgroundWriteTest() = launchSelfTest(SelfTestKind.BACKGROUND_WRITE)

    private fun launchSelfTest(kind: SelfTestKind) {
        val tester = selfTest ?: return
        if (mutableSelfTest.value.running) {
            return
        }
        mutableSelfTest.update { it.copy(running = true) }
        viewModelScope.launch(selfTestContext) {
            val result = try {
                when (kind) {
                    SelfTestKind.BACKGROUND_READ -> tester.runReadTest()
                    SelfTestKind.BACKGROUND_WRITE -> tester.runWriteTest()
                }
            } catch (_: Exception) {
                SelfTestResult(kind = kind, passed = false, errorCode = ClipboardSelfTest.ERROR_CRASHED)
            }
            mutableSelfTest.update { current ->
                when (kind) {
                    SelfTestKind.BACKGROUND_READ -> current.copy(running = false, read = result)
                    SelfTestKind.BACKGROUND_WRITE -> current.copy(running = false, write = result)
                }
            }
        }
    }

    /**
     * True only if the four indicators were incorrectly collapsed into a single
     * derived green. Each probe is stored separately and never OR-ed together.
     */
    fun indicatorsCollapsedToSingleGreen(): Boolean {
        val shown = state.value.indicators
        val probed = LiveIndicators(
            network = probes.network(),
            service = probes.service(),
            backgroundRead = probes.backgroundRead(),
            backgroundWrite = probes.backgroundWrite(),
            backgroundReadCheckedAtEpochMillis = probes.backgroundReadCheckedAt(),
            backgroundWriteCheckedAtEpochMillis = probes.backgroundWriteCheckedAt(),
        )
        if (shown != probed) {
            return true
        }
        val anyReady = listOf(
            probed.network,
            probed.service,
            probed.backgroundRead,
            probed.backgroundWrite,
        ).any { it == CapabilityState.READY }
        return anyReady && shown.allReady() && !probed.allReady()
    }

    private fun persist(choices: WizardChoices) {
        settings.save(choices)
        publish(choices)
    }

    private fun publish(choices: WizardChoices) {
        mutableState.value = buildState(choices)
    }

    private fun buildState(choices: WizardChoices): WizardUiState {
        val steps = WizardStepId.entries.map { id ->
            val probeState = probes.forStep(id).invoke()
            val isSkipped = id in skipped
            WizardStepStatus(
                id = id,
                state = probeState,
                skipped = isSkipped,
                completed = isSkipped || probeState == CapabilityState.READY,
                actionKind = actionKindFor(id),
                offersInAppGrant = offersInAppGrant(id),
                readLogsGuidance = if (id == WizardStepId.READ_LOGS) ReadLogsGuidance.Default else null,
            )
        }
        val overlayReady = probes.overlay() == CapabilityState.READY
        return WizardUiState(
            steps = steps,
            choices = choices,
            indicators = LiveIndicators(
                network = probes.network(),
                service = probes.service(),
                backgroundRead = probes.backgroundRead(),
                backgroundWrite = probes.backgroundWrite(),
                backgroundReadCheckedAtEpochMillis = probes.backgroundReadCheckedAt(),
                backgroundWriteCheckedAtEpochMillis = probes.backgroundWriteCheckedAt(),
            ),
            skipEffects = skipEffectsOf(skipped),
            overlayEnabled = choices.overlayConsented && overlayReady,
            canFinish = steps.all { it.completed },
            manualFallbackAvailable = true,
        )
    }

    companion object {
        fun factory(
            settings: WizardSettings,
            probes: WizardProbes,
            selfTest: ClipboardSelfTest? = null,
            requestPrivilegedAuthorization: (((Boolean) -> Unit) -> Unit)? = null,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                WizardViewModel(
                    settings,
                    probes,
                    selfTest,
                    requestPrivilegedAuthorization = requestPrivilegedAuthorization,
                ) as T
        }
    }
}

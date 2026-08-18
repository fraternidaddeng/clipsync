package com.clipsync.android

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.clipsync.android.capture.ClipboardCaptureManager
import com.clipsync.android.capture.ClipboardCaptureRuntime
import com.clipsync.android.pairing.PairingConfirmClient
import com.clipsync.android.service.ClipboardSyncRuntime
import com.clipsync.android.service.ClipboardSyncService
import com.clipsync.android.service.ServiceNotificationActions
import com.clipsync.android.ui.HealthScreen
import com.clipsync.android.ui.HealthScreenState
import com.clipsync.android.ui.history.HistoryScreen
import com.clipsync.android.ui.history.HistoryViewModel
import com.clipsync.android.ui.pairing.PairingScreen
import com.clipsync.android.ui.pairing.PairingUiState
import com.clipsync.android.ui.pairing.PairingViewModel
import com.clipsync.android.ui.settings.ClipServices
import com.clipsync.android.ui.settings.PairedPeerIdSync
import com.clipsync.android.ui.settings.SettingsScreen
import com.clipsync.android.ui.settings.SettingsViewModel
import com.clipsync.android.ui.settings.SyncControllerStatusAdapter
import com.clipsync.android.ui.theme.ClipSyncTheme
import com.clipsync.android.platform.SharedPrefsKeyValueStore
import com.clipsync.android.platform.clipboard.BackgroundClipboardBackends
import com.clipsync.android.platform.clipboard.CapabilityState
import com.clipsync.android.platform.clipboard.ClipboardSelfTest
import com.clipsync.android.platform.clipboard.ClipboardWriteCoordinator
import com.clipsync.android.service.ServiceProcessState
import com.clipsync.android.ui.wizard.KeyValueWizardSettings
import com.clipsync.android.ui.wizard.WizardChoices
import com.clipsync.android.ui.wizard.WizardFrameworkProbes
import com.clipsync.android.ui.wizard.WizardNavigation
import com.clipsync.android.ui.wizard.WizardProbes
import com.clipsync.android.ui.wizard.WizardScreen
import com.clipsync.android.ui.wizard.WizardSettings
import com.clipsync.android.ui.wizard.WizardViewModel
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
            // Denial is fine: inbound copy notifications stay off; the app must not crash.
        }

    private var captureManager: ClipboardCaptureManager? = null
    private val openTab = MutableStateFlow(0)
    private val pendingPairingPayload = MutableStateFlow<String?>(null)
    private var lastPaired: Boolean = false
    private var pendingAutoConfirm: Boolean = false
    private var pendingEnableBackground: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()
        consumePairingPayload(intent)
        openTab.value = tabFrom(intent)
        val pairingStore = ClipServices.pairingStore(this)
        val repository = ClipServices.repository(this)
        val writeCoordinator = ClipServices.writeCoordinator(this)
        val serviceSettings = ClipServices.serviceSettings(this)
        val backgroundWanted = serviceSettings.backgroundSyncEnabled()
        ClipboardSyncRuntime.noteActivityCreated()
        ClipboardSyncRuntime.orchestrator.wantedRunning = backgroundWanted
        ClipboardSyncRuntime.orchestrator.setBootRecoveryEnabled(serviceSettings.bootRecoveryEnabled())
        // Single process-scoped controller: Activity and Service share it, so
        // there is no ownership handover and no resubscription tick machinery.
        val syncController = ClipboardSyncRuntime.controller(applicationContext)
        if (backgroundWanted) {
            ClipboardSyncRuntime.orchestrator.requestBackgroundStart()
            ClipboardSyncService.start(this)
        }
        val syncStatus = SyncControllerStatusAdapter(
            controller = { syncController },
            isPaired = { pairingStore.peer() != null },
            serviceSnapshot = { ClipboardSyncRuntime.orchestrator.snapshot() },
            serviceSnapshots = ClipboardSyncRuntime.orchestrator.snapshots,
        )
        val capabilities = ClipServices.capabilities(this, isVisible = { hasWindowFocus() })
        // The capture stack (backends, coordinator, health loop) is process-owned
        // so it survives this Activity being destroyed while the FGS keeps the
        // process alive. The Activity only reports visibility and reads state.
        val capture = ClipboardCaptureRuntime.ensureStarted(applicationContext)
        captureManager = capture
        val storedWizardSettings = KeyValueWizardSettings(SharedPrefsKeyValueStore(applicationContext))
        val wizardSettings =
            object : WizardSettings by storedWizardSettings {
                override fun save(choices: WizardChoices) {
                    storedWizardSettings.save(choices)
                    capture.applyChoices(choices)
                }
            }
        val clipboardSelfTest = ClipboardSelfTest(
            writeCoordinator = writeCoordinator,
            readBackend = {
                val backends = capture.backends()
                val activeMode = capture.access()?.state?.activeReadMode
                activeMode?.let { mode -> backends?.backend(mode) }
                    ?: backends?.selectedEligibleBackend(wizardSettings.load().preferredReadMode)
            },
            clearClipboard = {
                val manager = getSystemService(ClipboardManager::class.java)
                manager != null && runCatching { manager.clearPrimaryClip() }.isSuccess
            },
        )
        setContent {
            ClipSyncTheme {
                val requestedTab by openTab.collectAsState()
                var tab by rememberSaveable { mutableIntStateOf(requestedTab) }
                LaunchedEffect(requestedTab) {
                    tab = requestedTab
                }
                val pairingViewModel: PairingViewModel = viewModel(
                    factory = PairingViewModel.factory(
                        pairingStore,
                        PairingConfirmClient(),
                        localNameFallback = deviceLabel(),
                    ),
                )
                val historyViewModel: HistoryViewModel = viewModel(
                    factory = HistoryViewModel.factory(repository, writeCoordinator, syncStatus),
                )
                val settingsViewModel: SettingsViewModel = viewModel(
                    factory = SettingsViewModel.factory(
                        repository,
                        syncStatus,
                        capabilities,
                        serviceSettings = serviceSettings,
                        onBackgroundSyncChanged = { enabled ->
                            onBackgroundSyncToggled(enabled)
                        },
                        onBootRecoveryChanged = { enabled ->
                            ClipboardSyncRuntime.applyBootReceiverEnabled(this, enabled)
                        },
                    ),
                )
                val wizardViewModel: WizardViewModel = viewModel(
                    factory = WizardViewModel.factory(
                        settings = wizardSettings,
                        probes = wizardProbes(
                            syncStatus,
                            checkNotNull(capture.backends()),
                            wizardSettings,
                            writeCoordinator,
                        ),
                        selfTest = clipboardSelfTest,
                    ),
                )
                LaunchedEffect(Unit) {
                    pendingPairingPayload.collect { payload ->
                        if (payload != null) {
                            pairingViewModel.onPayload(payload)
                            if (pendingAutoConfirm) {
                                pairingViewModel.confirm()
                                pendingAutoConfirm = false
                            }
                            pendingPairingPayload.value = null
                        }
                    }
                }
                val pairingState by pairingViewModel.state.collectAsState()
                val settingsState by settingsViewModel.state.collectAsState()
                val serviceSnap by ClipboardSyncRuntime.orchestrator.snapshots.collectAsState()
                LaunchedEffect(pairingState) {
                    val currentPairing = pairingState
                    PairedPeerIdSync.onPairingState(
                        repository = repository,
                        state = currentPairing,
                        peerDeviceId = { pairingStore.peer()?.deviceId },
                    )
                    val paired = currentPairing is PairingUiState.Paired ||
                        (currentPairing is PairingUiState.Idle && currentPairing.pairedPeer != null)
                    lastPaired = paired
                    if (paired) {
                        syncController.start()
                    } else if (currentPairing is PairingUiState.Idle) {
                        syncController.stop()
                    }
                    if (currentPairing is PairingUiState.Paired) {
                        tab = WizardNavigation.TAB_INDEX
                        if (pendingEnableBackground) {
                            pendingEnableBackground = false
                            settingsViewModel.setBackgroundSync(true)
                            wizardViewModel.setBackgroundAutoUpload(true)
                            wizardViewModel.setBackgroundAutoApply(true)
                        }
                    }
                }
                LaunchedEffect(serviceSnap) {
                    wizardViewModel.refresh()
                }
                LaunchedEffect(Unit) {
                    syncStatus.snapshots().collect {
                        wizardViewModel.refresh()
                    }
                }
                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(
                                selected = tab == 0,
                                onClick = { tab = 0 },
                                icon = {},
                                label = { Text(stringResource(R.string.tab_history)) },
                            )
                            NavigationBarItem(
                                selected = tab == 1,
                                onClick = { tab = 1 },
                                icon = {},
                                label = { Text(stringResource(R.string.tab_status)) },
                            )
                            NavigationBarItem(
                                selected = tab == 2,
                                onClick = { tab = 2 },
                                icon = {},
                                label = { Text(stringResource(R.string.tab_settings)) },
                            )
                            NavigationBarItem(
                                selected = tab == 3,
                                onClick = { tab = 3 },
                                icon = {},
                                label = { Text(stringResource(R.string.tab_pairing)) },
                            )
                            NavigationBarItem(
                                selected = tab == WizardNavigation.TAB_INDEX,
                                onClick = { tab = WizardNavigation.TAB_INDEX },
                                icon = {},
                                label = { Text(stringResource(R.string.tab_wizard)) },
                            )
                        }
                    },
                ) { padding ->
                    when (tab) {
                        0 -> HistoryScreen(
                            viewModel = historyViewModel,
                            modifier = Modifier.padding(padding),
                        )
                        1 -> HealthScreen(
                            state = HealthScreenState(
                                network = settingsState.network,
                                service = settingsState.service,
                                read = settingsState.read,
                                write = settingsState.write,
                                pairedDeviceCount = settingsState.pairedDeviceCount,
                            ),
                            modifier = Modifier.padding(padding),
                        )
                        2 -> Column(
                            modifier = Modifier
                                .padding(padding)
                                .fillMaxSize(),
                        ) {
                            TextButton(onClick = { tab = WizardNavigation.TAB_INDEX }) {
                                Text(stringResource(R.string.wizard_open_from_settings))
                            }
                            SettingsScreen(
                                viewModel = settingsViewModel,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        WizardNavigation.TAB_INDEX -> WizardScreen(
                            viewModel = wizardViewModel,
                            modifier = Modifier.padding(padding),
                        )
                        else -> PairingScreen(
                            viewModel = pairingViewModel,
                            modifier = Modifier.padding(padding),
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        captureManager?.setActivityVisible(true)
    }

    override fun onStop() {
        captureManager?.setActivityVisible(false)
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumePairingPayload(intent)
        openTab.value = tabFrom(intent)
    }

    private fun onBackgroundSyncToggled(enabled: Boolean) {
        // The controller is process-scoped and keeps running across the toggle;
        // only the foreground service comes and goes.
        if (enabled) {
            ClipboardSyncRuntime.orchestrator.requestBackgroundStart()
            ClipboardSyncService.start(this)
        } else {
            ClipboardSyncRuntime.orchestrator.requestBackgroundStop()
            ClipboardSyncService.stop(this)
            if (lastPaired) {
                ClipboardSyncRuntime.controller(applicationContext).start()
            }
        }
    }

    private fun wizardProbes(
        syncStatus: SyncControllerStatusAdapter,
        backends: BackgroundClipboardBackends,
        settings: WizardSettings,
        writeCoordinator: ClipboardWriteCoordinator,
    ): WizardProbes = WizardFrameworkProbes.bind(
        backends = backends,
        settings = settings,
        network = {
            val status = syncStatus.current()
            when {
                !status.paired -> CapabilityState.UNKNOWN
                status.windowsReachable -> CapabilityState.READY
                else -> CapabilityState.DEGRADED
            }
        },
        service = {
            val status = syncStatus.current()
            when {
                status.serviceErrorCode != null || status.serviceNeedsRecovery ->
                    CapabilityState.DEGRADED
                status.serviceRunning -> CapabilityState.READY
                else -> CapabilityState.NEEDS_USER_ACTION
            }
        },
        backgroundWrite = { writeCoordinator.publicWriteState },
        notifications = WizardFrameworkProbes.notifications(this),
        foregroundService = {
            when (ClipboardSyncRuntime.orchestrator.snapshot().processState) {
                ServiceProcessState.RUNNING -> CapabilityState.READY
                ServiceProcessState.ERROR,
                ServiceProcessState.NEEDS_RECOVERY,
                -> CapabilityState.DEGRADED
                ServiceProcessState.STARTING -> CapabilityState.UNKNOWN
                ServiceProcessState.STOPPED -> CapabilityState.NEEDS_USER_ACTION
            }
        },
        ignoreBattery = WizardFrameworkProbes.ignoreBattery(this),
    )

    private fun consumePairingPayload(intent: Intent?) {
        if (intent == null) {
            return
        }
        val inline = intent.getStringExtra(EXTRA_PAIRING_PAYLOAD)?.takeIf { it.isNotBlank() }
        val fromFile = intent.getStringExtra(EXTRA_PAIRING_FILE)?.let { name ->
            val root = getExternalFilesDir(null) ?: filesDir
            runCatching { File(root, name).readText() }.getOrNull()
        }?.takeIf { it.isNotBlank() }
        val payload = inline ?: fromFile
        if (payload != null) {
            pendingAutoConfirm = intentFlag(intent, EXTRA_PAIRING_AUTO_CONFIRM)
            pendingEnableBackground = intentFlag(intent, EXTRA_ENABLE_BACKGROUND_SYNC)
            pendingPairingPayload.value = payload
        }
    }

    private fun intentFlag(intent: Intent, key: String): Boolean {
        if (intent.getBooleanExtra(key, false)) {
            return true
        }
        val raw = intent.getStringExtra(key) ?: return false
        return raw == "1" || raw.equals("true", ignoreCase = true)
    }

    private fun tabFrom(intent: Intent?): Int =
        if (intent?.getStringExtra(EXTRA_PAIRING_PAYLOAD) != null ||
            intent?.getStringExtra(EXTRA_PAIRING_FILE) != null
        ) {
            3
        } else if (intent?.getStringExtra(ServiceNotificationActions.EXTRA_OPEN_TAB) ==
            ServiceNotificationActions.TAB_STATUS
        ) {
            1
        } else {
            0
        }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < 33) {
            return
        }
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            runCatching { notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
        }
    }

    private fun deviceLabel(): String {
        val manufacturer = Build.MANUFACTURER.trim()
        val model = Build.MODEL.trim()
        val label = if (model.startsWith(manufacturer, ignoreCase = true)) {
            model
        } else {
            "$manufacturer $model".trim()
        }
        return label.ifBlank { "Android phone" }
    }

    companion object {
        const val EXTRA_PAIRING_PAYLOAD = "clipsync.pairing_payload"
        const val EXTRA_PAIRING_FILE = "clipsync.pairing_file"
        const val EXTRA_PAIRING_AUTO_CONFIRM = "clipsync.pairing_auto_confirm"
        const val EXTRA_ENABLE_BACKGROUND_SYNC = "clipsync.enable_background_sync"
    }

    override fun onDestroy() {
        // Deliberately NOT stopping the capture stack: it is process-owned
        // (ClipboardCaptureRuntime) and must survive Activity destruction so
        // background capture keeps working while the FGS holds the process.
        captureManager = null
        ClipboardSyncRuntime.noteActivityDestroyed()
        // Without background sync the process-scoped controller has no owner
        // left; stopping preserves the old "sync dies with the UI" behavior.
        ClipboardSyncRuntime.stopControllerIfUnneeded()
        super.onDestroy()
    }
}

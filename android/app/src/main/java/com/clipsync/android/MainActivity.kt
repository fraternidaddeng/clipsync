package com.clipsync.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.clipsync.android.notify.InboundClip
import com.clipsync.android.notify.InboundClipApplier
import com.clipsync.android.notify.InboundClipNotifier
import com.clipsync.android.pairing.PairingConfirmClient
import com.clipsync.android.sync.SyncController
import com.clipsync.android.sync.createSyncController
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
            // Denial is fine: inbound copy notifications stay off; the app must not crash.
        }

    private var syncController: SyncController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()
        val pairingStore = ClipServices.pairingStore(this)
        val repository = ClipServices.repository(this)
        val writeCoordinator = ClipServices.writeCoordinator(this)
        val notifier = InboundClipNotifier(this)
        val applier = InboundClipApplier(repository, writeCoordinator) { eventId ->
            notifier.notifyCopyAction(eventId)
        }
        val controller = createSyncController(
            pairingStore = pairingStore,
            repository = repository,
            scope = lifecycleScope,
            onRemoteClipsCommitted = { clips ->
                lifecycleScope.launch(Dispatchers.IO) {
                    applier.onCommitted(
                        clips.map { InboundClip(eventId = it.eventId, content = it.content) },
                    )
                }
            },
        )
        syncController = controller
        val syncStatus = SyncControllerStatusAdapter(
            controller = { syncController },
            isPaired = { pairingStore.peer() != null },
        )
        val capabilities = ClipServices.capabilities(this, isVisible = { hasWindowFocus() })
        setContent {
            ClipSyncTheme {
                var tab by rememberSaveable { mutableIntStateOf(0) }
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
                    factory = SettingsViewModel.factory(repository, syncStatus, capabilities),
                )
                val pairingState by pairingViewModel.state.collectAsState()
                val settingsState by settingsViewModel.state.collectAsState()
                val syncState by controller.state.collectAsState()
                LaunchedEffect(pairingState) {
                    val currentPairing = pairingState
                    PairedPeerIdSync.onPairingState(
                        repository = repository,
                        state = currentPairing,
                        peerDeviceId = { pairingStore.peer()?.deviceId },
                    )
                    val paired = currentPairing is PairingUiState.Paired ||
                        (currentPairing is PairingUiState.Idle && currentPairing.pairedPeer != null)
                    if (paired) {
                        controller.start()
                    } else if (currentPairing is PairingUiState.Idle) {
                        controller.stop()
                    }
                    historyViewModel.refresh()
                    settingsViewModel.refresh()
                }
                LaunchedEffect(syncState) {
                    historyViewModel.refresh()
                    settingsViewModel.refresh()
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
                        2 -> SettingsScreen(
                            viewModel = settingsViewModel,
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

    override fun onDestroy() {
        syncController?.stop()
        syncController = null
        super.onDestroy()
    }
}

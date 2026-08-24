package com.clipsync.android

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clipsync.android.pairing.PairingConfirmClient
import com.clipsync.android.pairing.PairingStore
import com.clipsync.android.platform.KeystoreSecretProtector
import com.clipsync.android.platform.SharedPrefsKeyValueStore
import com.clipsync.android.platform.clipboard.AndroidPublicClipboardWriter
import com.clipsync.android.platform.clipboard.ClipboardAccessCoordinator
import com.clipsync.android.platform.clipboard.ClipboardWriteCoordinator
import com.clipsync.android.storage.SyncSettingsStore
import com.clipsync.android.sync.ClipboardSyncService
import com.clipsync.android.sync.SyncConnectionState
import com.clipsync.android.sync.SyncStore
import com.clipsync.android.ui.HealthScreen
import com.clipsync.android.ui.health.HealthViewModel
import com.clipsync.android.ui.health.SyncHealth
import com.clipsync.android.ui.health.SyncHealthSource
import com.clipsync.android.ui.home.ClipSyncHistoryGateway
import com.clipsync.android.ui.home.HomeScreen
import com.clipsync.android.ui.home.HomeViewModel
import com.clipsync.android.ui.pairing.PairingScreen
import com.clipsync.android.ui.pairing.PairingUiState
import com.clipsync.android.ui.pairing.PairingViewModel
import com.clipsync.android.ui.prefs.PreferencesScreen
import com.clipsync.android.ui.prefs.PreferencesViewModel
import com.clipsync.android.ui.theme.ClipSyncIcons
import com.clipsync.android.ui.theme.ClipSyncTheme
import com.clipsync.android.ui.theme.clipSyncColors
import com.clipsync.android.ui.theme.filmGrain
import kotlinx.coroutines.flow.combine

class MainActivity : ComponentActivity() {
    private val pairingStore by lazy {
        PairingStore(SharedPrefsKeyValueStore(this), KeystoreSecretProtector())
    }

    private val pairingViewModel: PairingViewModel by viewModels {
        PairingViewModel.factory(
            pairingStore,
            PairingConfirmClient(),
            localNameFallback = deviceLabel(),
        )
    }

    private val healthViewModel: HealthViewModel by viewModels {
        HealthViewModel.factory(
            pairingStore = pairingStore,
            // No background read backends ship in this stage; probe() reports that honestly.
            clipboard = ClipboardAccessCoordinator(backends = emptyList()),
            // Live facts from the sync foreground service: alive + authenticated session.
            syncHealthSource = SyncHealthSource {
                combine(
                    ClipboardSyncService.serviceRunning,
                    ClipboardSyncService.connectionStates,
                ) { running, connection ->
                    SyncHealth(
                        serviceRunning = running,
                        connected = connection is SyncConnectionState.Connected,
                    )
                }
            },
        )
    }

    private val homeViewModel: HomeViewModel by viewModels {
        // SyncStore is the process-wide handle, so service and UI share one
        // database instance and history observers see the engine's writes.
        HomeViewModel.factory(
            history = ClipSyncHistoryGateway(SyncStore.repository(applicationContext)),
            writeCoordinator = ClipboardWriteCoordinator(
                publicWriter = AndroidPublicClipboardWriter(applicationContext),
            ),
            pairingStore = pairingStore,
        )
    }

    private val preferencesViewModel: PreferencesViewModel by viewModels {
        PreferencesViewModel.factory(
            SyncSettingsStore(
                SharedPrefsKeyValueStore(this, name = SyncSettingsStore.PREFERENCES_NAME),
            ),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (pairingStore.peer() != null) {
            ClipboardSyncService.start(this)
        }
        setContent {
            ClipSyncTheme {
                SyncServiceController(pairingViewModel)
                ClipSyncApp(
                    pairingViewModel = pairingViewModel,
                    healthViewModel = healthViewModel,
                    homeViewModel = homeViewModel,
                    preferencesViewModel = preferencesViewModel,
                )
            }
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
}

/** Starts the sync foreground service once paired and stops it when the pairing is forgotten. */
@Composable
private fun SyncServiceController(pairingViewModel: PairingViewModel) {
    val context = LocalContext.current
    val pairingState by pairingViewModel.state.collectAsState()
    LaunchedEffect(pairingState) {
        when (val state = pairingState) {
            is PairingUiState.Paired -> ClipboardSyncService.start(context)
            is PairingUiState.Idle ->
                if (state.pairedPeer == null) {
                    ClipboardSyncService.stop(context)
                }
            else -> Unit
        }
    }
}

/**
 * Three positions (charter: the old five screens fold into 一屏 / 通路 / 偏好).
 * Pairing hangs under the conduit's network segment rather than owning a tab.
 */
@Composable
private fun ClipSyncApp(
    pairingViewModel: PairingViewModel,
    healthViewModel: HealthViewModel,
    homeViewModel: HomeViewModel,
    preferencesViewModel: PreferencesViewModel,
) {
    val c = clipSyncColors
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var pairingOpen by rememberSaveable { mutableStateOf(false) }
    val healthState by healthViewModel.state.collectAsState()
    val homeState by homeViewModel.state.collectAsState()
    val preferencesState by preferencesViewModel.state.collectAsState()
    val pairingState by pairingViewModel.state.collectAsState()

    // Pairing completing (or the peer being forgotten) must reflect in the
    // conduit and in the history source tags immediately, not on next start.
    LaunchedEffect(pairingState) {
        healthViewModel.refresh()
        homeViewModel.refreshPeer()
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            // z0: 178° ≈ vertical gradient, light above, dark below…
            .background(
                Brush.verticalGradient(
                    0f to c.bgTop,
                    0.42f to c.bgMid,
                    1f to c.bgBottom,
                ),
            )
            // …with film grain on the app background only.
            .filmGrain(),
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = {
                ClipSyncDock(
                    selected = tab,
                    onSelect = {
                        tab = it
                        pairingOpen = false
                    },
                )
            },
        ) { padding ->
            when (tab) {
                0 -> HomeScreen(
                    conduit = healthState,
                    home = homeState,
                    onQueryChange = homeViewModel::setQuery,
                    onCopy = homeViewModel::copy,
                    onDelete = homeViewModel::delete,
                    onOpenConduit = { tab = 1 },
                    modifier = Modifier.padding(padding),
                )
                1 -> if (pairingOpen) {
                    Column(Modifier.padding(padding)) {
                        BackRow(label = "通路", onBack = { pairingOpen = false })
                        PairingScreen(viewModel = pairingViewModel)
                    }
                } else {
                    HealthScreen(
                        state = healthState,
                        onPairRequest = { pairingOpen = true },
                        modifier = Modifier.padding(padding),
                    )
                }
                else -> PreferencesScreen(
                    state = preferencesState,
                    onPauseSyncChange = preferencesViewModel::setPauseSync,
                    onPrivateModeChange = preferencesViewModel::setPrivateMode,
                    onAutoApplyRemoteChange = preferencesViewModel::setAutoApplyRemote,
                    onAutoExpireChange = preferencesViewModel::setAutoExpire,
                    modifier = Modifier.padding(padding),
                )
            }
        }
    }
}

@Composable
private fun BackRow(label: String, onBack: () -> Unit) {
    val c = clipSyncColors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onBack)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = "‹", fontSize = 18.sp, color = c.flow)
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = c.flow,
        )
    }
}

/** Charter dock: hairline on top, z1 face, flow blue marks the active place. */
@Composable
private fun ClipSyncDock(selected: Int, onSelect: (Int) -> Unit) {
    val c = clipSyncColors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(c.sf)
            .background(Brush.verticalGradient(0f to c.sfGradTop, 1f to Color.Transparent)),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(c.ln),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(top = 8.dp, bottom = 10.dp),
        ) {
            DockItem(
                icon = ClipSyncIcons.History,
                label = "历史",
                active = selected == 0,
                onClick = { onSelect(0) },
                modifier = Modifier.weight(1f),
            )
            DockItem(
                icon = ClipSyncIcons.Conduit,
                label = "通路",
                active = selected == 1,
                onClick = { onSelect(1) },
                modifier = Modifier.weight(1f),
            )
            DockItem(
                icon = ClipSyncIcons.Prefs,
                label = "偏好",
                active = selected == 2,
                onClick = { onSelect(2) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun DockItem(
    icon: ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = clipSyncColors
    val tint = if (active) c.flow else c.t4
    Column(
        modifier = modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick,
        ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            color = tint,
        )
    }
}

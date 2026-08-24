package com.clipsync.android

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.Crossfade
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.clipsync.android.pairing.PairingConfirmClient
import com.clipsync.android.pairing.PairingStore
import com.clipsync.android.pairing.PeerHealthClient
import com.clipsync.android.platform.KeystoreSecretProtector
import com.clipsync.android.platform.SharedPrefsKeyValueStore
import com.clipsync.android.platform.clipboard.AdbLogOverlayBackend
import com.clipsync.android.platform.clipboard.AndroidRouteProbes
import com.clipsync.android.platform.clipboard.ClipboardAccessCoordinator
import com.clipsync.android.platform.clipboard.ClipboardCapabilityStore
import com.clipsync.android.platform.clipboard.ClipboardReadMode
import com.clipsync.android.platform.clipboard.ForegroundClipboardBackend
import com.clipsync.android.platform.clipboard.OverlayPollingBackend
import com.clipsync.android.platform.clipboard.RealBackgroundReaders
import com.clipsync.android.platform.clipboard.SharedClipboardWrites
import com.clipsync.android.platform.clipboard.ShizukuClipboardBackend
import com.clipsync.android.storage.SyncSettingsStore
import com.clipsync.android.sync.BootCompletedReceiver
import com.clipsync.android.sync.ClipboardCaptureManager
import com.clipsync.android.sync.ClipboardSyncService
import com.clipsync.android.sync.SyncConnectionState
import com.clipsync.android.sync.SyncStore
import com.clipsync.android.ui.HealthScreen
import com.clipsync.android.ui.health.CapabilityWiring
import com.clipsync.android.ui.health.HealthViewModel
import com.clipsync.android.ui.health.ReadRouteUi
import com.clipsync.android.ui.health.RouteActionId
import com.clipsync.android.ui.health.SyncHealth
import com.clipsync.android.ui.health.SyncHealthSource
import com.clipsync.android.ui.home.ClipSyncHistoryGateway
import com.clipsync.android.ui.home.HomeScreen
import com.clipsync.android.ui.home.HomeViewModel
import com.clipsync.android.ui.onboarding.FirstRunStore
import com.clipsync.android.ui.onboarding.OnboardingScreen
import com.clipsync.android.ui.pairing.PairingScreen
import com.clipsync.android.ui.pairing.PairingUiState
import com.clipsync.android.ui.pairing.PairingViewModel
import com.clipsync.android.ui.prefs.PreferencesScreen
import com.clipsync.android.ui.prefs.PreferencesViewModel
import com.clipsync.android.ui.theme.CharterMotion
import com.clipsync.android.ui.theme.ClipSyncIcons
import com.clipsync.android.ui.theme.ClipSyncTheme
import com.clipsync.android.ui.theme.clipSyncColors
import com.clipsync.android.ui.theme.filmGrain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {
    private val pairingStore by lazy {
        PairingStore(SharedPrefsKeyValueStore(this), KeystoreSecretProtector())
    }

    private val firstRunStore by lazy {
        FirstRunStore(SharedPrefsKeyValueStore(this, name = FirstRunStore.PREFERENCES_NAME))
    }

    private val pairingViewModel: PairingViewModel by viewModels {
        PairingViewModel.factory(
            pairingStore,
            PairingConfirmClient(),
            localNameFallback = deviceLabel(),
        )
    }

    private val capabilityStore by lazy {
        ClipboardCapabilityStore(SharedPrefsKeyValueStore(this, name = "clipsync.capability"))
    }

    private val routeProbes by lazy { AndroidRouteProbes(this) }

    private val foregroundBackend by lazy {
        ForegroundClipboardBackend(this, systemVersion = systemVersion())
    }

    // The real device read backends (privileged Shizuku channel, logcat+overlay, overlay polling).
    // The flat capability-ladder adapters below wrap these and gate them on the honest probe.
    private val realReaders by lazy { RealBackgroundReaders.build(applicationContext) }

    private val clipboardCoordinator by lazy {
        ClipboardAccessCoordinator(
            backends = listOf(
                ShizukuClipboardBackend(
                    probes = routeProbes,
                    systemVersion = systemVersion(),
                    delegate = realReaders.shizuku,
                    readVerified = { capabilityStore.isReadVerified(ClipboardReadMode.SHIZUKU_EVENT) },
                ),
                AdbLogOverlayBackend(
                    probes = routeProbes,
                    systemVersion = systemVersion(),
                    delegate = realReaders.adbLog,
                    readVerified = { capabilityStore.isReadVerified(ClipboardReadMode.ADB_LOG_OVERLAY) },
                ),
                OverlayPollingBackend(
                    probes = routeProbes,
                    systemVersion = systemVersion(),
                    delegate = realReaders.overlayPolling,
                    readVerified = { capabilityStore.isReadVerified(ClipboardReadMode.OVERLAY_POLLING) },
                ),
                foregroundBackend,
            ),
            requestedReadMode = capabilityStore.preferredReadMode(),
            autoFallbackAllowed = capabilityStore.autoFallbackAllowed(),
        )
    }

    // One process-wide write coordinator: history copy, auto-apply, and the write test all
    // share the suppression table the capture pipeline consults, so self-writes never echo.
    private val writeCoordinator by lazy { SharedClipboardWrites.coordinator(applicationContext) }

    private val captureManager by lazy {
        ClipboardCaptureManager(
            settings = SyncSettingsStore(
                SharedPrefsKeyValueStore(this, name = SyncSettingsStore.PREFERENCES_NAME),
            ),
            writeCoordinator = writeCoordinator,
        )
    }

    private val healthViewModel: HealthViewModel by viewModels {
        HealthViewModel.factory(
            pairingStore = pairingStore,
            clipboard = clipboardCoordinator,
            // Live facts from the sync foreground service: alive + authenticated session.
            syncHealthSource = SyncHealthSource {
                combine(
                    ClipboardSyncService.serviceRunning,
                    ClipboardSyncService.connectionStates,
                    ClipboardSyncService.startErrorCodes,
                ) { running, connection, startError ->
                    SyncHealth(
                        serviceRunning = running,
                        connected = connection is SyncConnectionState.Connected,
                        serviceErrorCode = startError,
                    )
                }
            },
            capability = CapabilityWiring(
                routeProbes = routeProbes,
                capabilityStore = capabilityStore,
                writeCoordinator = writeCoordinator,
                foregroundBackend = foregroundBackend,
                clearClipboard = foregroundBackend::clear,
                peerHealth = PeerHealthClient(),
                // Covers both the API 33+ runtime denial and the surface being
                // switched off in Settings on any API level.
                notificationsEnabled = {
                    NotificationManagerCompat.from(this).areNotificationsEnabled()
                },
            ),
        )
    }

    // Shizuku authorization completes outside the app; re-probe when it answers.
    private val shizukuPermissionListener =
        Shizuku.OnRequestPermissionResultListener { _, _ -> healthViewModel.refresh() }

    private val homeViewModel: HomeViewModel by viewModels {
        // SyncStore is the process-wide handle, so service and UI share one
        // database instance and history observers see the engine's writes.
        HomeViewModel.factory(
            history = ClipSyncHistoryGateway(SyncStore.repository(applicationContext)),
            writeCoordinator = writeCoordinator,
            pairingStore = pairingStore,
        )
    }

    private val syncSettings by lazy {
        SyncSettingsStore(
            SharedPrefsKeyValueStore(this, name = SyncSettingsStore.PREFERENCES_NAME),
        )
    }

    private val preferencesViewModel: PreferencesViewModel by viewModels {
        PreferencesViewModel.factory(
            syncSettings,
            onBootRestoreChanged = { enabled ->
                BootCompletedReceiver.setReceiverEnabled(this, enabled)
                if (enabled) {
                    // The recovery path speaks through a notification; ask honestly up front.
                    requestNotificationsPermissionIfMissing()
                }
            },
            onRetentionChanged = {
                // Mirror Windows: a changed retention applies now, not at the next service start.
                lifecycleScope.launch(Dispatchers.IO) {
                    runCatching {
                        SyncStore.repository(applicationContext)
                            .cleanup(syncSettings.effectiveRetentionPolicy(), System.currentTimeMillis())
                    }
                }
            },
            historyRepository = { SyncStore.repository(applicationContext) },
        )
    }

    // 导出历史/导入历史 write and read only where the user explicitly points (SAF);
    // the streams are opened lazily on the ViewModel's IO dispatcher.
    private val exportHistoryLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        if (uri != null) {
            preferencesViewModel.exportHistory { contentResolver.openOutputStream(uri) }
        }
    }

    private val importHistoryLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            preferencesViewModel.importHistory { contentResolver.openInputStream(uri) }
        }
    }

    /**
     * Denial is respected as-is: the sync service and inbox keep working, only the
     * notification surface goes missing, which the notification helpers already report
     * honestly (areNotificationsEnabled checks before every post).
     */
    private val notificationsPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        runCatching { Shizuku.addRequestPermissionResultListener(shizukuPermissionListener) }
        if (pairingStore.peer() != null) {
            // Already-enabled sync resumes quietly; the permission dialog only appears on the
            // explicit enable moments (pairing completion, 启动服务, 开机恢复), never per app open.
            ClipboardSyncService.start(this)
        }
        // Decided before composition: reading it may mark an already-paired
        // install as seen, which must not happen as a composition side effect.
        val showOnboarding =
            firstRunStore.shouldShowOnboarding(alreadyPaired = pairingStore.peer() != null)
        setContent {
            ClipSyncTheme {
                SyncServiceController(
                    pairingViewModel = pairingViewModel,
                    onStartService = ::startSyncService,
                    onStopService = { ClipboardSyncService.stop(this) },
                )
                ClipSyncApp(
                    pairingViewModel = pairingViewModel,
                    healthViewModel = healthViewModel,
                    homeViewModel = homeViewModel,
                    preferencesViewModel = preferencesViewModel,
                    showOnboarding = showOnboarding,
                    onOnboardingSeen = firstRunStore::markOnboardingSeen,
                    onRouteAction = ::handleRouteAction,
                    onServiceStart = ::startSyncService,
                    onServiceStop = { ClipboardSyncService.stop(this) },
                    onOpenNotificationSettings = ::openNotificationSettings,
                    onExportHistory = {
                        val stamp = java.time.format.DateTimeFormatter
                            .ofPattern("yyyyMMdd-HHmmss")
                            .format(java.time.LocalDateTime.now())
                        exportHistoryLauncher.launch("clipsync-history-$stamp.jsonl")
                    },
                    onImportHistory = { importHistoryLauncher.launch(arrayOf("*/*")) },
                )
            }
        }
    }

    /**
     * The user just enabled sync (paired, or tapped 启动服务): start the service and, on
     * Android 13+, ask for POST_NOTIFICATIONS so the status/inbox surfaces can appear. The
     * service starts either way — the permission is never a precondition (plan 5.2).
     */
    private fun startSyncService() {
        requestNotificationsPermissionIfMissing()
        ClipboardSyncService.start(this)
    }

    private fun requestNotificationsPermissionIfMissing() {
        if (Build.VERSION.SDK_INT < 33) {
            return
        }
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            // After two denials the system returns immediately; we never nag beyond that.
            notificationsPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onStart() {
        super.onStart()
        // Stage-4 acceptance: while the app is visible, the capability ladder captures copies
        // automatically (today that resolves to the FOREGROUND_ONLY backend; a privileged
        // backend upgrades this without any change here). The manager gates on pause/private
        // and on self-writes before enqueueing into the same outbox the share sheet uses.
        clipboardCoordinator.start { change -> captureManager.onClipboardChanged(change) }
    }

    override fun onStop() {
        // Android 10+ denies background reads anyway; stopping keeps the listener honest.
        clipboardCoordinator.stop()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        // Grants change outside the app (Settings, adb, Shizuku); re-probe every return.
        healthViewModel.refresh()
    }

    override fun onDestroy() {
        runCatching { Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener) }
        super.onDestroy()
    }

    /** Resolves a wizard route action to the system surface that can satisfy it. */
    private fun handleRouteAction(route: ReadRouteUi, action: RouteActionId) {
        when (action) {
            RouteActionId.REQUEST_PRIVILEGED_PERMISSION ->
                realReaders.requestShizukuAuthorization { granted ->
                    if (granted) {
                        healthViewModel.refresh()
                    }
                }
            RouteActionId.COPY_ADB_READ_LOGS_COMMAND -> {
                val manager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                manager.setPrimaryClip(
                    ClipData.newPlainText("adb", "adb shell pm grant $packageName android.permission.READ_LOGS"),
                )
                healthViewModel.noteAdbCommandCopied()
            }
            RouteActionId.OPEN_OVERLAY_SETTINGS -> startActivitySafely(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
            )
            RouteActionId.OPEN_BATTERY_SETTINGS -> startActivitySafely(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName"),
                ),
            )
            RouteActionId.SET_PREFERRED -> healthViewModel.setPreferredReadMode(route.mode)
            RouteActionId.RUN_READ_TEST -> healthViewModel.runReadTest(route.mode)
        }
    }

    private fun startActivitySafely(intent: Intent) {
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // Some OEM builds hide standard Settings screens; the conduit's
            // re-probe on resume keeps the shown state truthful either way.
        }
    }

    /** The 通知已关闭 banner's action; the resume re-probe picks up the outcome. */
    private fun openNotificationSettings() {
        startActivitySafely(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
        )
    }

    private fun systemVersion(): String = "android-${Build.VERSION.SDK_INT}"

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
private fun SyncServiceController(
    pairingViewModel: PairingViewModel,
    onStartService: () -> Unit,
    onStopService: () -> Unit,
) {
    val pairingState by pairingViewModel.state.collectAsState()
    LaunchedEffect(pairingState) {
        when (val state = pairingState) {
            is PairingUiState.Paired -> onStartService()
            is PairingUiState.Idle ->
                if (state.pairedPeer == null) {
                    onStopService()
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
    showOnboarding: Boolean = false,
    onOnboardingSeen: () -> Unit = {},
    onRouteAction: (ReadRouteUi, RouteActionId) -> Unit = { _, _ -> },
    onServiceStart: () -> Unit = {},
    onServiceStop: () -> Unit = {},
    onOpenNotificationSettings: (() -> Unit)? = null,
    onExportHistory: () -> Unit = {},
    onImportHistory: () -> Unit = {},
) {
    val c = clipSyncColors
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var pairingOpen by rememberSaveable { mutableStateOf(false) }
    var onboardingOpen by rememberSaveable { mutableStateOf(showOnboarding) }
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
        if (onboardingOpen) {
            // First-run introduction: once dismissed, the flag is persisted and
            // the chosen entrance (通路 for pairing, or 一屏) takes over.
            OnboardingScreen(
                onPair = {
                    onOnboardingSeen()
                    onboardingOpen = false
                    tab = 1
                },
                onSkip = {
                    onOnboardingSeen()
                    onboardingOpen = false
                    tab = 0
                },
            )
            return@Box
        }
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
            // Switching place crossfades on the charter curve (tokens.md §9:
            // 260–320ms, cubic-bezier(.16,1,.3,1)) instead of a hard cut.
            Crossfade(
                targetState = tab to pairingOpen,
                animationSpec = CharterMotion.spec(CharterMotion.DUR_STANDARD_MS),
                label = "place",
            ) { (place, pairing) ->
                when {
                    place == 0 -> HomeScreen(
                        conduit = healthState,
                        home = homeState,
                        onQueryChange = homeViewModel::setQuery,
                        onFormatFilterChange = homeViewModel::setFormatFilter,
                        onCopy = homeViewModel::copy,
                        onDelete = homeViewModel::delete,
                        onOpenConduit = { tab = 1 },
                        modifier = Modifier.padding(padding),
                    )
                    place == 1 && pairing -> Column(Modifier.padding(padding)) {
                        BackRow(label = "通路", onBack = { pairingOpen = false })
                        PairingScreen(viewModel = pairingViewModel)
                    }
                    place == 1 -> HealthScreen(
                        state = healthState,
                        onPairRequest = { pairingOpen = true },
                        onRefresh = healthViewModel::refresh,
                        onRouteAction = onRouteAction,
                        onServiceStart = onServiceStart,
                        onServiceStop = onServiceStop,
                        onTestWrite = healthViewModel::runWriteTest,
                        onDismissTestResult = healthViewModel::dismissTestResult,
                        onOpenNotificationSettings = onOpenNotificationSettings,
                        modifier = Modifier.padding(padding),
                    )
                    else -> PreferencesScreen(
                        state = preferencesState,
                        onPauseSyncChange = preferencesViewModel::setPauseSync,
                        onPrivateModeChange = preferencesViewModel::setPrivateMode,
                        onAutoApplyRemoteChange = preferencesViewModel::setAutoApplyRemote,
                        onAutoExpireChange = preferencesViewModel::setAutoExpire,
                        onBootRestoreChange = preferencesViewModel::setBootRestore,
                        pairedDeviceName = healthState.pairedPeerName,
                        onOpenConduit = {
                            pairingOpen = false
                            tab = 1
                        },
                        onExportHistory = onExportHistory,
                        onImportHistory = onImportHistory,
                        modifier = Modifier.padding(padding),
                    )
                }
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

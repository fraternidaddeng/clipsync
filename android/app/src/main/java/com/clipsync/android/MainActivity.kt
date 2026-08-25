package com.clipsync.android

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.clipsync.android.media.ImageThumbnail
import com.clipsync.android.pairing.PairedPeer
import com.clipsync.android.pairing.PairingConfirmClient
import com.clipsync.android.pairing.PairingStore
import com.clipsync.android.pairing.PeerHealthClient
import com.clipsync.android.platform.KeystoreSecretProtector
import com.clipsync.android.platform.SharedPrefsKeyValueStore
import com.clipsync.android.platform.clipboard.ClipboardCaptureSession
import com.clipsync.android.platform.clipboard.SharedClipboardWrites
import com.clipsync.android.storage.SyncSettingsStore
import com.clipsync.android.sync.BluetoothSyncConnector
import com.clipsync.android.sync.BootCompletedReceiver
import com.clipsync.android.sync.ClipboardSyncService
import com.clipsync.android.sync.SharedClipboardCapture
import com.clipsync.android.sync.SyncConnectionState
import com.clipsync.android.sync.SyncStore
import com.clipsync.android.sync.SyncTransportKind
import com.clipsync.android.ui.HealthScreen
import com.clipsync.android.ui.health.BluetoothFallbackUi
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
import com.clipsync.android.ui.prefs.BondedBluetoothDevice
import com.clipsync.android.ui.prefs.PreferencesScreen
import com.clipsync.android.ui.prefs.PreferencesViewModel
import com.clipsync.android.ui.theme.CharterMotion
import com.clipsync.android.ui.theme.ClipSyncIcons
import com.clipsync.android.ui.theme.ClipSyncTheme
import com.clipsync.android.ui.theme.LocalReducedMotion
import com.clipsync.android.ui.theme.clipSyncColors
import com.clipsync.android.ui.theme.filmGrain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {
    /** One-shot tab request from a notification tap; cleared once the UI applies it. */
    private val tabRequests = MutableStateFlow<Int?>(null)
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

    // The process-wide capture stack (ladder backends, coordinator, policy engine, session).
    // Shared with ClipboardSyncService so the service can own the coordinator while promoted
    // (plan 5.2) and the conduit page probes the very objects that are actually capturing.
    private val captureStack by lazy { SharedClipboardCapture.stack(applicationContext) }

    // One process-wide write coordinator: history copy, auto-apply, and the write test all
    // share the suppression table the capture pipeline consults, so self-writes never echo.
    private val writeCoordinator by lazy { SharedClipboardWrites.coordinator(applicationContext) }

    private val healthViewModel: HealthViewModel by viewModels {
        HealthViewModel.factory(
            pairingStore = pairingStore,
            clipboard = captureStack.coordinator,
            // Live facts from the sync foreground service: alive + authenticated session.
            syncHealthSource =
                SyncHealthSource {
                    combine(
                        ClipboardSyncService.serviceRunning,
                        ClipboardSyncService.connectionStates,
                        ClipboardSyncService.startErrorCodes,
                        ClipboardSyncService.peerThrottled,
                    ) { running, connection, startError, throttled ->
                        SyncHealth(
                            serviceRunning = running,
                            connected = connection is SyncConnectionState.Connected,
                            serviceErrorCode = startError,
                            peerThrottled = throttled,
                            // The conduit must state the degraded bt1 path honestly (ADR 0005).
                            bluetoothFallback =
                                connection is SyncConnectionState.Connected &&
                                    connection.transport == SyncTransportKind.BLUETOOTH,
                        )
                    }
                },
            capability =
                CapabilityWiring(
                    routeProbes = captureStack.routeProbes,
                    capabilityStore = captureStack.capabilityStore,
                    writeCoordinator = writeCoordinator,
                    foregroundBackend = captureStack.foregroundBackend,
                    clearClipboard = captureStack.foregroundBackend::clear,
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
            sideEffects =
                PreferencesViewModel.SideEffects(
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
                                SyncStore
                                    .repository(applicationContext)
                                    .cleanup(syncSettings.effectiveRetentionPolicy(), System.currentTimeMillis())
                            }
                        }
                    },
                    // 暂停同步/私密模式 gate the read backends themselves, not just the per-event
                    // policy: re-evaluate the capture session so a background reader stops (or
                    // resumes) on the very toggle, without waiting for the next lifecycle edge.
                    onCaptureGatesChanged = { captureStack.session.refreshGates() },
                    // The fallback needs BLUETOOTH_CONNECT on API 31+; ask on the explicit
                    // enable moment, never per app open. Denial keeps the toggle honest —
                    // the dialer re-checks the permission per dial and simply stays off.
                    onBluetoothFallbackChanged = { enabled ->
                        if (enabled) {
                            requestBluetoothPermissionIfMissing(thenShowDevices = false)
                        }
                    },
                ),
            historyRepository = { SyncStore.repository(applicationContext) },
        )
    }

    // 导出历史/导入历史 write and read only where the user explicitly points (SAF);
    // the streams are opened lazily on the ViewModel's IO dispatcher.
    private val exportHistoryLauncher =
        registerForActivityResult(
            ActivityResultContracts.CreateDocument("application/octet-stream"),
        ) { uri ->
            if (uri != null) {
                preferencesViewModel.exportHistory { contentResolver.openOutputStream(uri) }
            }
        }

    private val importHistoryLauncher =
        registerForActivityResult(
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

    /** Bonded devices for the 蓝牙目标设备 chooser; null keeps the inline chooser collapsed. */
    private val bluetoothDeviceChoices = MutableStateFlow<List<BondedBluetoothDevice>?>(null)

    /** Set when the permission ask came from the device chooser, so a grant opens it. */
    private var showDevicesAfterBluetoothGrant = false

    /**
     * Denial is respected: the 蓝牙备援 toggle stays on but honest — the dialer re-checks
     * the permission per dial and simply never connects, and the chooser explains itself.
     */
    private val bluetoothPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted && showDevicesAfterBluetoothGrant) {
                bluetoothDeviceChoices.value = queryBondedDevices()
            }
            showDevicesAfterBluetoothGrant = false
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        runCatching { Shizuku.addRequestPermissionResultListener(shizukuPermissionListener) }
        if (pairingStore.peer() != null) {
            // Already-enabled sync resumes quietly; the permission dialog only appears on the
            // explicit enable moments (pairing completion, 启动服务, 开机恢复), never per app open.
            ClipboardSyncService.start(this)
        }
        // The FGS notification's 打开故障状态 tap lands here with the 通路 tab requested.
        handleOpenTabIntent(intent)
        // Decided before composition: reading it may mark an already-paired
        // install as seen, which must not happen as a composition side effect.
        val showOnboarding =
            firstRunStore.shouldShowOnboarding(alreadyPaired = pairingStore.peer() != null)
        setContent {
            // 外观（settings-roadmap P1-6）: the override decides the palette before the
            // theme composes; 跟随系统 keeps deferring to isSystemInDarkTheme live.
            val darkTheme = rememberEffectiveDarkTheme()
            ClipSyncTheme(darkTheme = darkTheme) {
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
                        val stamp =
                            java.time.format.DateTimeFormatter
                                .ofPattern("yyyyMMdd-HHmmss")
                                .format(java.time.LocalDateTime.now())
                        exportHistoryLauncher.launch("clipsync-history-$stamp.jsonl")
                    },
                    onImportHistory = { importHistoryLauncher.launch(arrayOf("*/*")) },
                    bluetoothDevices = bluetoothDeviceChoices,
                    onRequestBluetoothDevices = {
                        requestBluetoothPermissionIfMissing(thenShowDevices = true)
                    },
                    onBluetoothDeviceChosen = { device ->
                        preferencesViewModel.setBluetoothDevice(device)
                        bluetoothDeviceChoices.value = null
                    },
                    onDismissBluetoothDevices = { bluetoothDeviceChoices.value = null },
                    imageThumbnail = { contentHash ->
                        SyncStore.repository(applicationContext).media?.let { store ->
                            ImageThumbnail.decodePreview(store, contentHash)
                        }
                    },
                    tabRequests = tabRequests,
                    onTabRequestConsumed = { tabRequests.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOpenTabIntent(intent)
    }

    /**
     * 外观（settings-roadmap P1-6）: the effective palette — a manual 日间/夜间 override
     * wins, 跟随系统 defers to [isSystemInDarkTheme] live. System-bar icon contrast must
     * track this effective answer, not the OS theme: a forced 日间 needs dark status/nav
     * icons even while the system is dark, and vice versa — so edge-to-edge is re-asserted
     * with an explicit dark answer on every flip.
     */
    @Composable
    private fun rememberEffectiveDarkTheme(): Boolean {
        val preferencesState by preferencesViewModel.state.collectAsState()
        val darkTheme =
            when (preferencesState.themeOverride) {
                SyncSettingsStore.THEME_DAY -> false
                SyncSettingsStore.THEME_NIGHT -> true
                else -> isSystemInDarkTheme()
            }
        DisposableEffect(darkTheme) {
            enableEdgeToEdge(
                statusBarStyle =
                    SystemBarStyle.auto(
                        android.graphics.Color.TRANSPARENT,
                        android.graphics.Color.TRANSPARENT,
                    ) { darkTheme },
                navigationBarStyle = SystemBarStyle.auto(navBarLightScrim, navBarDarkScrim) { darkTheme },
            )
            onDispose {}
        }
        return darkTheme
    }

    private fun handleOpenTabIntent(intent: Intent?) {
        val requested = intent?.getIntExtra(EXTRA_OPEN_TAB, -1) ?: -1
        if (requested in TAB_HOME..TAB_PREFS) {
            tabRequests.value = requested
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
        val granted =
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            // After two denials the system returns immediately; we never nag beyond that.
            notificationsPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /**
     * BLUETOOTH_CONNECT exists only on API 31+; below that the install-time BLUETOOTH
     * permission already covers the fallback, so the devices show right away when asked.
     */
    private fun requestBluetoothPermissionIfMissing(thenShowDevices: Boolean) {
        if (BluetoothSyncConnector.hasConnectPermission(this)) {
            if (thenShowDevices) {
                bluetoothDeviceChoices.value = queryBondedDevices()
            }
            return
        }
        showDevicesAfterBluetoothGrant = thenShowDevices
        bluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
    }

    /**
     * The system-bonded device list for the chooser. Empty (not null) when Bluetooth is off
     * or nothing is bonded, so the chooser states the honest reason instead of hanging.
     */
    private fun queryBondedDevices(): List<BondedBluetoothDevice> {
        val adapter = BluetoothSyncConnector.adapter(this) ?: return emptyList()
        return try {
            adapter.bondedDevices
                .orEmpty()
                .map { device ->
                    BondedBluetoothDevice(
                        name = device.name ?: device.address,
                        address = device.address,
                    )
                }.sortedBy { it.name }
        } catch (_: SecurityException) {
            emptyList()
        }
    }

    override fun onStart() {
        super.onStart()
        // While the app is visible, the capability ladder captures copies automatically
        // (stage-4 acceptance). Ownership, not a raw start: when the foreground service is
        // promoted it already holds the same session, and acquiring is then a no-op.
        captureStack.session.acquire(ClipboardCaptureSession.Owner.ACTIVITY)
    }

    override fun onStop() {
        // Release only this owner's stake. With the foreground service promoted, the verified
        // background routes keep capturing (plan 5.2/5.5); without it, the coordinator stops —
        // Android 10+ denies the foreground-only backend's reads to backgrounded apps, and the
        // privileged routes must not run with no service accountable for them.
        captureStack.session.release(ClipboardCaptureSession.Owner.ACTIVITY)
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
    private fun handleRouteAction(
        route: ReadRouteUi,
        action: RouteActionId,
    ) {
        when (action) {
            RouteActionId.REQUEST_PRIVILEGED_PERMISSION ->
                captureStack.realReaders.requestShizukuAuthorization { granted ->
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
            RouteActionId.OPEN_OVERLAY_SETTINGS ->
                startActivitySafely(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
                )
            RouteActionId.OPEN_BATTERY_SETTINGS ->
                startActivitySafely(
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

    private fun deviceLabel(): String {
        val manufacturer = Build.MANUFACTURER.trim()
        val model = Build.MODEL.trim()
        val label =
            if (model.startsWith(manufacturer, ignoreCase = true)) {
                model
            } else {
                "$manufacturer $model".trim()
            }
        return label.ifBlank { "Android phone" }
    }

    companion object {
        /**
         * The `enableEdgeToEdge` default three-button navigation-bar scrims (androidx.activity
         * keeps them private), restated because passing an explicit dark answer requires
         * passing the scrims too.
         */
        private val navBarLightScrim = android.graphics.Color.argb(0xE6, 0xFF, 0xFF, 0xFF)
        private val navBarDarkScrim = android.graphics.Color.argb(0x80, 0x1B, 0x1B, 0x1B)

        /** Int extra selecting the tab to open: 0 一屏(历史) / 1 通路 / 2 偏好. */
        const val EXTRA_OPEN_TAB = "com.clipsync.android.extra.OPEN_TAB"
        const val TAB_HOME = 0
        const val TAB_CONDUIT = 1
        const val TAB_PREFS = 2

        /**
         * The FGS notification's 打开故障状态 target: the 通路 (conduit/status) tab.
         * SINGLE_TOP keeps an existing task alive so the request arrives via onNewIntent.
         */
        fun conduitIntent(context: Context): Intent =
            Intent(context, MainActivity::class.java)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
                        or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        or Intent.FLAG_ACTIVITY_SINGLE_TOP,
                ).putExtra(EXTRA_OPEN_TAB, TAB_CONDUIT)
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
    bluetoothDevices: StateFlow<List<BondedBluetoothDevice>?> = MutableStateFlow(null),
    onRequestBluetoothDevices: () -> Unit = {},
    onBluetoothDeviceChosen: (BondedBluetoothDevice) -> Unit = {},
    onDismissBluetoothDevices: () -> Unit = {},
    imageThumbnail: suspend (String) -> android.graphics.Bitmap? = { null },
    tabRequests: StateFlow<Int?> = MutableStateFlow(null),
    onTabRequestConsumed: () -> Unit = {},
) {
    val c = clipSyncColors
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var pairingOpen by rememberSaveable { mutableStateOf(false) }
    var onboardingOpen by rememberSaveable { mutableStateOf(showOnboarding) }
    // A notification tap (打开故障状态 → 通路) may arrive while the app is already open;
    // the request is consumed once applied so a later same-tab request fires again.
    val requestedTab by tabRequests.collectAsState()
    LaunchedEffect(requestedTab) {
        val request = requestedTab ?: return@LaunchedEffect
        tab = request
        pairingOpen = false
        onTabRequestConsumed()
    }
    val healthState by healthViewModel.state.collectAsState()
    val homeState by homeViewModel.state.collectAsState()
    val preferencesState by preferencesViewModel.state.collectAsState()
    val pairingState by pairingViewModel.state.collectAsState()
    val bluetoothDeviceChoices by bluetoothDevices.collectAsState()

    // Pairing completing (or the peer being forgotten) must reflect in the
    // conduit and in the history source tags immediately, not on next start.
    // Keyed to the persisted peer, not the whole pairing state: the in-flight
    // steps (review, submitting, a failed attempt) never change the saved peer,
    // so they must not each trigger a full conduit probe pass. The initial
    // emission is dropped too — init{} and onResume already probe on entry.
    LaunchedEffect(pairingViewModel, healthViewModel, homeViewModel) {
        snapshotFlow { pairingState }
            .mapNotNull { state ->
                when (state) {
                    is PairingUiState.Idle -> PersistedPeerKey(state.pairedPeer)
                    is PairingUiState.Paired -> PersistedPeerKey(state.peer)
                    else -> null
                }
            }.distinctUntilChanged()
            .drop(1)
            .collect {
                healthViewModel.refresh()
                homeViewModel.refreshPeer()
            }
    }
    Box(
        modifier =
            Modifier
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
            // 260–320ms, cubic-bezier(.16,1,.3,1)) instead of a hard cut —
            // unless the system asks for reduced motion (P1#13): then it IS a cut.
            Crossfade(
                targetState = tab to pairingOpen,
                animationSpec =
                    if (LocalReducedMotion.current) {
                        snap()
                    } else {
                        CharterMotion.spec(CharterMotion.DUR_STANDARD_MS)
                    },
                label = "place",
            ) { (place, pairing) ->
                when {
                    place == 0 ->
                        HomeScreen(
                            conduit = healthState,
                            home = homeState,
                            onQueryChange = homeViewModel::setQuery,
                            onFormatFilterChange = homeViewModel::setFormatFilter,
                            onCopy = homeViewModel::copy,
                            onDelete = homeViewModel::delete,
                            onOpenConduit = { tab = 1 },
                            modifier = Modifier.padding(padding),
                            thumbnail = imageThumbnail,
                            historyFontScale = preferencesState.historyFontScale,
                            previewLines = preferencesState.previewLines,
                        )
                    place == 1 && pairing ->
                        Column(Modifier.padding(padding)) {
                            BackRow(
                                label = stringResource(R.string.tab_conduit),
                                onBack = { pairingOpen = false },
                            )
                            PairingScreen(viewModel = pairingViewModel)
                        }
                    place == 1 ->
                        HealthScreen(
                            state = healthState,
                            onPairRequest = { pairingOpen = true },
                            onRefresh = healthViewModel::refresh,
                            onRouteAction = onRouteAction,
                            onServiceStart = onServiceStart,
                            onServiceStop = onServiceStop,
                            onTestWrite = healthViewModel::runWriteTest,
                            onDismissTestResult = healthViewModel::dismissTestResult,
                            onOpenNotificationSettings = onOpenNotificationSettings,
                            // 收到内容通知的应用内开关（P1#8）；关闭时通路以灰面事实条陈述后果。
                            inboxNotifyEnabled = preferencesState.inboxNotify,
                            // 设备色是设备行的属性（P1#14）；历史来源盒立即跟色。
                            onDeviceAccentChange = { deviceId, slot ->
                                healthViewModel.setDeviceAccent(deviceId, slot)
                                homeViewModel.refreshPeer()
                            },
                            // 蓝牙备援挂在网络段下（IA 迁移）；状态仍由 PreferencesViewModel 持有。
                            bluetoothFallback =
                                BluetoothFallbackUi(
                                    enabled = preferencesState.bluetoothFallback,
                                    deviceName = preferencesState.bluetoothDeviceName,
                                ),
                            onBluetoothFallbackChange = preferencesViewModel::setBluetoothFallback,
                            bluetoothDevices = bluetoothDeviceChoices,
                            onRequestBluetoothDevices = onRequestBluetoothDevices,
                            onBluetoothDeviceChosen = onBluetoothDeviceChosen,
                            onDismissBluetoothDevices = onDismissBluetoothDevices,
                            modifier = Modifier.padding(padding),
                        )
                    else ->
                        PreferencesScreen(
                            state = preferencesState,
                            onPauseSyncChange = preferencesViewModel::setPauseSync,
                            onPrivateModeChange = preferencesViewModel::setPrivateMode,
                            onAutoApplyRemoteChange = preferencesViewModel::setAutoApplyRemote,
                            onAutoExpireChange = preferencesViewModel::setAutoExpire,
                            onBootRestoreChange = preferencesViewModel::setBootRestore,
                            onImageSyncChange = preferencesViewModel::setImageSync,
                            onAutoApplyImagesChange = preferencesViewModel::setAutoApplyImages,
                            pairedDeviceName = healthState.pairedPeerName,
                            onOpenConduit = {
                                pairingOpen = false
                                tab = 1
                            },
                            onExportHistory = onExportHistory,
                            onImportHistory = onImportHistory,
                            onHistoryFontScaleChange = preferencesViewModel::setHistoryFontScale,
                            onPreviewLinesChange = preferencesViewModel::setPreviewLines,
                            onThemeOverrideChange = preferencesViewModel::setThemeOverride,
                            onSkipSensitiveChange = preferencesViewModel::setSkipSensitive,
                            onInboxNotifyChange = preferencesViewModel::setInboxNotify,
                            onRetentionDaysChange = preferencesViewModel::setRetentionDays,
                            onMaxEntriesChange = preferencesViewModel::setMaxEntries,
                            onClearHistory = preferencesViewModel::clearHistory,
                            onLanguageChange = preferencesViewModel::setLanguage,
                            modifier = Modifier.padding(padding),
                        )
                }
            }
        }
    }
}

/**
 * The saved peer as reflected by the pairing states that can change it. A value
 * class over the full [PairedPeer] (not just the id) so re-pairing the same
 * device — new certificate or trust epoch — still counts as a change.
 */
private data class PersistedPeerKey(
    val peer: PairedPeer?,
)

@Composable
private fun BackRow(
    label: String,
    onBack: () -> Unit,
) {
    val c = clipSyncColors
    Row(
        modifier =
            Modifier
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
private fun ClipSyncDock(
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    val c = clipSyncColors
    Column(
        modifier =
            Modifier
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
            modifier =
                Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(top = 8.dp, bottom = 10.dp),
        ) {
            DockItem(
                icon = ClipSyncIcons.History,
                label = stringResource(R.string.tab_history),
                active = selected == 0,
                onClick = { onSelect(0) },
                modifier = Modifier.weight(1f),
            )
            DockItem(
                icon = ClipSyncIcons.Conduit,
                label = stringResource(R.string.tab_conduit),
                active = selected == 1,
                onClick = { onSelect(1) },
                modifier = Modifier.weight(1f),
            )
            DockItem(
                icon = ClipSyncIcons.Prefs,
                label = stringResource(R.string.tab_prefs),
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
        modifier =
            modifier.clickable(
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

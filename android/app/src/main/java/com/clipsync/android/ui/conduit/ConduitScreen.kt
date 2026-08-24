package com.clipsync.android.ui.conduit

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.clipsync.android.platform.clipboard.AndroidRouteProbes
import com.clipsync.android.service.ClipboardSyncService
import com.clipsync.android.ui.theme.LocalConduitAccents
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import rikka.shizuku.Shizuku

private const val SHIZUKU_PERMISSION_REQUEST_CODE = 4310
private const val SHIZUKU_DOWNLOAD_URL = "https://shizuku.rikka.app/download/"
private const val ADB_READ_LOGS_COMMAND =
    "adb shell pm grant com.clipsync.android android.permission.READ_LOGS"

/**
 * 通路 — the conduit page (charter §4.2/§5.6). One question: 通不通; four pipe segments answer
 * "堵在哪". Every segment expands into detail plus concrete repair actions, and the read
 * segment opens the three-route capability wizard.
 */
@Composable
fun ConduitScreen(
    viewModel: ConduitViewModel,
    onNavigateToPairing: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    // Real status must follow the user back from Settings/Shizuku: re-probe on every resume.
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    // Shizuku authorization changes arrive through listeners, not resume.
    DisposableEffect(Unit) {
        val binderReceived = Shizuku.OnBinderReceivedListener { viewModel.refresh() }
        val binderDead = Shizuku.OnBinderDeadListener { viewModel.refresh() }
        val permissionResult =
            Shizuku.OnRequestPermissionResultListener { _, _ -> viewModel.refresh() }
        runCatching {
            Shizuku.addBinderReceivedListener(binderReceived)
            Shizuku.addBinderDeadListener(binderDead)
            Shizuku.addRequestPermissionResultListener(permissionResult)
        }
        onDispose {
            runCatching {
                Shizuku.removeBinderReceivedListener(binderReceived)
                Shizuku.removeBinderDeadListener(binderDead)
                Shizuku.removeRequestPermissionResultListener(permissionResult)
            }
        }
    }

    // POST_NOTIFICATIONS is not a precondition for the FGS; ask once, then start either way.
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { ClipboardSyncService.start(context) }

    fun onSegmentAction(action: SegmentActionId) {
        when (action) {
            SegmentActionId.OPEN_WIZARD -> viewModel.setWizardOpen(!state.wizardOpen)
            SegmentActionId.TEST_READ -> viewModel.runReadTest()
            SegmentActionId.TEST_WRITE -> viewModel.runWriteTest()
            SegmentActionId.GO_PAIR -> onNavigateToPairing()
            SegmentActionId.STOP_SERVICE -> ClipboardSyncService.stop(context)
            SegmentActionId.START_SERVICE -> {
                val needsAsk = Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
                if (needsAsk) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    ClipboardSyncService.start(context)
                }
            }
        }
    }

    fun onRouteAction(route: RouteUi, action: RouteActionId) {
        when (action) {
            RouteActionId.INSTALL_SHIZUKU ->
                openSafely(context, Intent(Intent.ACTION_VIEW, Uri.parse(SHIZUKU_DOWNLOAD_URL)))
            RouteActionId.LAUNCH_SHIZUKU -> {
                val launch = context.packageManager
                    .getLaunchIntentForPackage(AndroidRouteProbes.SHIZUKU_PACKAGE)
                if (launch != null) {
                    openSafely(context, launch)
                } else {
                    openSafely(context, Intent(Intent.ACTION_VIEW, Uri.parse(SHIZUKU_DOWNLOAD_URL)))
                }
            }
            RouteActionId.REQUEST_SHIZUKU_PERMISSION ->
                runCatching { Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE) }
                    .onFailure { viewModel.refresh() }
            RouteActionId.COPY_ADB_READ_LOGS_COMMAND -> {
                val clipboard =
                    context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("adb", ADB_READ_LOGS_COMMAND))
                viewModel.noteAdbCommandCopied()
            }
            RouteActionId.OPEN_OVERLAY_SETTINGS -> openSafely(
                context,
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}"),
                ),
            )
            RouteActionId.OPEN_BATTERY_SETTINGS -> {
                val direct = Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${context.packageName}"),
                )
                if (!openSafely(context, direct)) {
                    openSafely(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }
            RouteActionId.SET_PREFERRED -> viewModel.selectPreferredMode(route.mode)
        }
    }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Header(refreshing = state.refreshing, lastProbeAtMs = state.lastProbeAtMs) {
                viewModel.refresh()
            }
            ConduitRail(state.segments)
            state.testResult?.let { result ->
                TestResultLine(result) { viewModel.dismissTestResult() }
            }

            var expandedKey by rememberSaveable { mutableStateOf<String?>(null) }
            state.segments.forEach { segment ->
                val expanded = expandedKey?.let { it == segment.id.name }
                    ?: segment.beckoning
                SegmentCard(
                    segment = segment,
                    expanded = expanded,
                    onToggle = {
                        expandedKey = if (expanded) "" else segment.id.name
                    },
                    onAction = ::onSegmentAction,
                )
                if (segment.id == ConduitSegmentId.READ) {
                    AnimatedVisibility(visible = state.wizardOpen) {
                        CapabilityWizard(
                            routes = state.routes,
                            onRouteAction = ::onRouteAction,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun openSafely(context: Context, intent: Intent): Boolean = runCatching {
    context.startActivity(intent)
}.isSuccess

@Composable
private fun Header(
    refreshing: Boolean,
    lastProbeAtMs: Long?,
    onRefresh: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            // One of the at most three serif moments in the whole app (charter §3.5).
            Text(
                text = "剪剪相传",
                style = MaterialTheme.typography.headlineSmall,
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = lastProbeAtMs?.let { "通路 · 上次探测 ${formatTime(it)}" } ?: "通路",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (refreshing) {
                CircularProgressIndicator(
                    modifier = Modifier.width(18.dp).height(18.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            TextButton(onClick = onRefresh, enabled = !refreshing) { Text("刷新") }
        }
    }
}

private fun formatTime(epochMs: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(epochMs))

/** The four-segment rail — the same idiom as the phone status band and the PC title bar. */
@Composable
private fun ConduitRail(segments: List<ConduitSegmentUi>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        segments.forEachIndexed { index, segment ->
            StatusGlyph(status = segment.status, beckoning = segment.beckoning)
            if (index != segments.lastIndex) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(2.dp)
                        .padding(horizontal = 4.dp),
                ) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = if (segment.status == SegmentStatus.READY) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        },
                        content = {},
                    )
                }
            }
        }
    }
}

@Composable
private fun SegmentCard(
    segment: ConduitSegmentUi,
    expanded: Boolean,
    onToggle: () -> Unit,
    onAction: (SegmentActionId) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle),
        colors = CardDefaults.cardColors(
            containerColor = if (segment.beckoning) {
                LocalConduitAccents.current.beckonContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    StatusGlyph(status = segment.status, beckoning = segment.beckoning)
                    Text(
                        text = segment.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Text(
                    text = statusLabel(segment.status),
                    style = MaterialTheme.typography.labelMedium,
                    color = statusColor(segment.status),
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = segment.summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.padding(top = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    segment.detail.forEach { line ->
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    segment.errorDetail?.let { error ->
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    if (segment.actions.isNotEmpty()) {
                        Row(
                            modifier = Modifier.padding(top = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            segment.actions.forEachIndexed { index, action ->
                                // One screen, one protagonist: only the beckoning segment's
                                // first action is a solid button (charter §5.1).
                                if (segment.beckoning && index == 0) {
                                    Button(onClick = { onAction(action.id) }) { Text(action.label) }
                                } else {
                                    OutlinedButton(onClick = { onAction(action.id) }) { Text(action.label) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TestResultLine(result: TestResult, onDismiss: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onDismiss),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
    ) {
        Text(
            text = result.label,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodySmall,
            color = if (result.success) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.error
            },
        )
    }
}

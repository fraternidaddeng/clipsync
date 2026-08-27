package com.clipsync.android.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clipsync.android.R
import com.clipsync.android.i18n.UiText
import com.clipsync.android.i18n.string
import com.clipsync.android.platform.clipboard.ClipboardReadMode
import com.clipsync.android.ui.health.BluetoothFallbackCard
import com.clipsync.android.ui.health.BluetoothFallbackUi
import com.clipsync.android.ui.health.CapabilityWizard
import com.clipsync.android.ui.health.ReadRouteUi
import com.clipsync.android.ui.health.RouteActionId
import com.clipsync.android.ui.health.buildHealthScreenState
import com.clipsync.android.ui.prefs.BondedBluetoothDevice
import com.clipsync.android.ui.theme.CharterMotion
import com.clipsync.android.ui.theme.CharterShapes
import com.clipsync.android.ui.theme.ClipSyncIcons
import com.clipsync.android.ui.theme.ClipSyncTheme
import com.clipsync.android.ui.theme.ClipSyncType
import com.clipsync.android.ui.theme.LocalReducedMotion
import com.clipsync.android.ui.theme.charterCard
import com.clipsync.android.ui.theme.clipSyncColors

/**
 * Five-fill status encoding (tokens.md §10): one shape, five fill degrees,
 * two hues. Red never appears here — "unavailable" is a fact, not an error.
 */
enum class ConduitStatus {
    /** Full flow-blue fill. Good news does not grab attention. */
    READY,

    /** 52% fill — the difference must read in peripheral vision. */
    DEGRADED,

    /** Empty + ochre outline + pulse. The only segment allowed to reach out. */
    NEEDS_ACTION,

    /** Solid grey fill. States a fact; must never look like an error. */
    UNAVAILABLE,

    /** Dashed outline. Missing information is not bad news. */
    UNPROBED,
}

data class ConduitSegmentState(
    val statusLabel: UiText,
    val detail: UiText,
    val status: ConduitStatus,
    /** Extra fact lines shown when the segment card is expanded. */
    val detailLines: List<UiText> = emptyList(),
    /** Rendered in error red; reserved for true errors such as a certificate change. */
    val errorDetail: UiText? = null,
    /**
     * Single-beckon rule (charter §5.6): at most one segment pulses ochre. The
     * builder demotes downstream NEEDS_ACTION segments to a quiet rendering.
     */
    val beckoning: Boolean = status == ConduitStatus.NEEDS_ACTION,
)

/** Transient outcome line of a 测试 button; never contains clipboard content. */
data class ConduitTestResult(
    val label: UiText,
    val success: Boolean,
    /** One-line human hint for a failure's machine code (特权直读 closed set). */
    val hint: UiText? = null,
)

/**
 * One paired device row in the conduit's 已配对设备 area. The neighbour hue is
 * a property of the device row (P1#14): pairing order supplies the default
 * slot, a manual override may replace it, and selecting the default clears
 * the override again.
 */
data class ConduitDeviceUi(
    val deviceId: String,
    val displayName: String,
    val platformLabel: String,
    /** Effective neighbour-hue slot 1..5: the manual override, else the default. */
    val accentSlot: Int,
    /** The slot pairing order assigns — the value 「跟随配对顺位」 returns to. */
    val defaultSlot: Int,
)

/**
 * The conduit: four segments in the order clipboard content actually travels,
 * which is also the troubleshooting order.
 */
data class HealthScreenState(
    val localRead: ConduitSegmentState,
    val localService: ConduitSegmentState,
    val network: ConduitSegmentState,
    val peerWrite: ConduitSegmentState,
    val pairedDeviceCount: Int,
    /** Display name of the paired Windows peer; null while unpaired. */
    val pairedPeerName: String? = null,
    /** Paired device rows with their effective neighbour hues (P1#14). */
    val pairedDevices: List<ConduitDeviceUi> = emptyList(),
    /** 本机写回 — the inbound write axis; null until the capability stack is wired. */
    val localWrite: ConduitSegmentState? = null,
    /** The wizard's three routes; empty until the capability stack is wired. */
    val routes: List<ReadRouteUi> = emptyList(),
    val serviceRunning: Boolean = false,
    val testResult: ConduitTestResult? = null,
    /** Null = notification probe not wired; false = the surface is off right now. */
    val notificationsEnabled: Boolean? = null,
    /** True while a 重新探测 pass runs — the action states busy, re-taps coalesce. */
    val probing: Boolean = false,
    /** True while the write test's round-trip runs — its action states busy. */
    val writeTestRunning: Boolean = false,
    /** The route whose device-verified read test is in flight; null when none. */
    val readTestMode: ClipboardReadMode? = null,
) {
    val statuses: List<ConduitStatus>
        get() = listOf(localRead.status, localService.status, network.status, peerWrite.status)
}

@Composable
fun HealthScreen(
    state: HealthScreenState,
    modifier: Modifier = Modifier,
    onPairRequest: () -> Unit = {},
    onRefresh: (() -> Unit)? = null,
    onRouteAction: ((ReadRouteUi, RouteActionId) -> Unit)? = null,
    onServiceStart: (() -> Unit)? = null,
    onServiceStop: (() -> Unit)? = null,
    onTestWrite: (() -> Unit)? = null,
    onDismissTestResult: () -> Unit = {},
    onOpenNotificationSettings: (() -> Unit)? = null,
    /**
     * 收到内容通知 (settings-roadmap P1-8): the in-app switch state; null = not wired.
     * Off is the user's choice — the conduit states the consequence in grey, never red.
     */
    inboxNotifyEnabled: Boolean? = null,
    // 蓝牙备援住在网络段下（settings-roadmap IA 迁移）；null 表示未接线，卡片不出现。
    bluetoothFallback: BluetoothFallbackUi? = null,
    onBluetoothFallbackChange: (Boolean) -> Unit = {},
    /** Bonded devices to choose from; null keeps the inline chooser collapsed. */
    bluetoothDevices: List<BondedBluetoothDevice>? = null,
    onRequestBluetoothDevices: () -> Unit = {},
    onBluetoothDeviceChosen: (BondedBluetoothDevice) -> Unit = {},
    onDismissBluetoothDevices: () -> Unit = {},
    // 设备色手动改（settings-roadmap P1#14）：slot 1..5 pins a colour, null = 跟随配对顺位.
    onDeviceAccentChange: ((deviceId: String, slot: Int?) -> Unit)? = null,
) {
    val c = clipSyncColors
    // The wizard opens itself when the read segment is the one beckoning.
    var wizardOpen by rememberSaveable(state.localRead.beckoning) {
        mutableStateOf(state.localRead.beckoning)
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 2.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.tab_conduit),
                style = ClipSyncType.pageTitle,
                color = c.t1,
                modifier = Modifier.weight(1f),
            )
            if (onRefresh != null) {
                // While the probe pass runs, the action itself states so and
                // stops inviting taps (they would only coalesce anyway).
                Text(
                    text =
                        stringResource(
                            if (state.probing) R.string.conduit_reprobing else R.string.conduit_reprobe,
                        ),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (state.probing) c.t3 else c.flow,
                    modifier = Modifier
                        .clip(CharterShapes.control)
                        .clickable(enabled = !state.probing, onClick = onRefresh)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
        state.testResult?.let { test ->
            TestResultRow(
                result = test,
                onDismiss = onDismissTestResult,
                modifier = Modifier.padding(bottom = 10.dp),
            )
        }
        if (state.notificationsEnabled == false) {
            NotificationsOffBanner(
                onOpenSettings = onOpenNotificationSettings,
                modifier = Modifier.padding(bottom = 10.dp),
            )
        } else if (inboxNotifyEnabled == false) {
            // The system surface is fine but the in-app switch is off; one banner at a
            // time — the system-level fact already covers this consequence when shown.
            InboxNotifyOffBanner(modifier = Modifier.padding(bottom = 10.dp))
        }
        PipelineSegment(
            title = stringResource(R.string.conduit_segment_local_read),
            icon = ClipSyncIcons.History,
            segment = state.localRead,
            actions = buildList {
                if (state.routes.isNotEmpty()) {
                    add(
                        SegmentActionUi(
                            label =
                                stringResource(
                                    if (wizardOpen) R.string.conduit_wizard_close else R.string.conduit_wizard_open,
                                ),
                            emphasized = !wizardOpen && state.localRead.beckoning,
                            onClick = { wizardOpen = !wizardOpen },
                        ),
                    )
                }
            },
        )
        if (state.routes.isNotEmpty() && onRouteAction != null) {
            AnimatedVisibility(
                visible = wizardOpen,
                enter = fadeIn(CharterMotion.spec(CharterMotion.DUR_QUICK_MS)) +
                    expandVertically(CharterMotion.spec(CharterMotion.DUR_EMPHASIS_MS)),
                exit = fadeOut(CharterMotion.spec(CharterMotion.DUR_QUICK_MS)) +
                    shrinkVertically(CharterMotion.spec(CharterMotion.DUR_EMPHASIS_MS)),
            ) {
                Column {
                    Spacer(Modifier.height(8.dp))
                    CapabilityWizard(
                        routes = state.routes,
                        onRouteAction = onRouteAction,
                        readTestMode = state.readTestMode,
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        PipelineSegment(
            title = stringResource(R.string.conduit_segment_local_service),
            icon = ClipSyncIcons.Service,
            segment = state.localService,
            actions = buildList {
                if (state.serviceRunning && onServiceStop != null) {
                    add(SegmentActionUi(label = stringResource(R.string.conduit_service_stop), onClick = onServiceStop))
                } else if (!state.serviceRunning && state.pairedDeviceCount > 0 && onServiceStart != null) {
                    add(
                        SegmentActionUi(
                            label = stringResource(R.string.conduit_service_start),
                            emphasized = true,
                            onClick = onServiceStart,
                        ),
                    )
                }
            },
        )
        Spacer(Modifier.height(8.dp))
        PipelineSegment(
            title = stringResource(R.string.conduit_segment_network),
            icon = ClipSyncIcons.Network,
            segment = state.network,
            actions = listOf(
                SegmentActionUi(
                    label =
                        stringResource(
                            if (state.pairedDeviceCount > 0) {
                                R.string.conduit_manage_pairing
                            } else {
                                R.string.action_go_pair
                            },
                        ),
                    emphasized = state.network.beckoning,
                    onClick = onPairRequest,
                ),
            ),
        )
        bluetoothFallback?.let { fallback ->
            Spacer(Modifier.height(8.dp))
            BluetoothFallbackCard(
                state = fallback,
                onEnabledChange = onBluetoothFallbackChange,
                devices = bluetoothDevices,
                onRequestDevices = onRequestBluetoothDevices,
                onDeviceChosen = onBluetoothDeviceChosen,
                onDismissDevices = onDismissBluetoothDevices,
            )
        }
        Spacer(Modifier.height(8.dp))
        PipelineSegment(
            title = stringResource(R.string.conduit_segment_peer_write),
            icon = ClipSyncIcons.Monitor,
            segment = state.peerWrite,
        )
        state.localWrite?.let { localWrite ->
            Spacer(Modifier.height(8.dp))
            PipelineSegment(
                title = stringResource(R.string.conduit_segment_local_write),
                icon = ClipSyncIcons.Conduit,
                segment = localWrite,
                actions = buildList {
                    if (onTestWrite != null) {
                        add(
                            SegmentActionUi(
                                label =
                                    stringResource(
                                        if (state.writeTestRunning) {
                                            R.string.conduit_testing
                                        } else {
                                            R.string.conduit_test_write
                                        },
                                    ),
                                busy = state.writeTestRunning,
                                onClick = onTestWrite,
                            ),
                        )
                    }
                },
            )
        }
        FlowLine(modifier = Modifier.padding(vertical = 12.dp))
        if (state.pairedDeviceCount == 0) {
            PairedDevicesEmptyState(
                onPairRequest = onPairRequest,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        } else {
            Text(
                text = stringResource(R.string.conduit_paired_devices_count, state.pairedDeviceCount),
                style = ClipSyncType.groupHeader,
                color = c.t4,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                textAlign = TextAlign.Center,
            )
            state.pairedDevices.forEach { device ->
                ConduitDeviceRow(
                    device = device,
                    onAccentChange = onDeviceAccentChange,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
        }
    }
}

/**
 * A paired device row (P1#14): the device's tinted identity box plus a
 * five-swatch 设备色 picker. Selecting the pairing-order default clears the
 * override, so the stored state stays minimal and the fact line stays honest.
 */
@Composable
private fun ConduitDeviceRow(
    device: ConduitDeviceUi,
    onAccentChange: ((String, Int?) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val c = clipSyncColors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .charterCard()
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val boxShape = RoundedCornerShape(9.dp)
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(boxShape)
                    .background(c.deviceBg(device.accentSlot))
                    .border(1.dp, c.deviceLn(device.accentSlot), boxShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = ClipSyncIcons.Monitor,
                    contentDescription = null,
                    tint = c.device(device.accentSlot),
                    modifier = Modifier.size(15.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = device.displayName,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = c.t1,
                )
                Text(
                    text = device.platformLabel,
                    style = ClipSyncType.meta,
                    fontSize = 10.sp,
                    color = c.t4,
                )
            }
        }
        if (onAccentChange != null) {
            Spacer(Modifier.height(10.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.device_accent_label),
                    style = ClipSyncType.caption,
                    color = c.t3,
                )
                for (slot in 1..DEVICE_ACCENT_SLOTS) {
                    val selected = slot == device.accentSlot
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(c.device(slot))
                            .border(
                                width = if (selected) 2.dp else 1.dp,
                                color = if (selected) c.t1 else c.deviceLn(slot),
                                shape = CircleShape,
                            )
                            .clickable {
                                onAccentChange(
                                    device.deviceId,
                                    slot.takeIf { it != device.defaultSlot },
                                )
                            },
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text =
                        stringResource(
                            if (device.accentSlot == device.defaultSlot) {
                                R.string.device_accent_default
                            } else {
                                R.string.device_accent_manual
                            },
                        ),
                    style = ClipSyncType.meta,
                    fontSize = 10.sp,
                    color = c.t4,
                )
            }
        }
    }
}

private const val DEVICE_ACCENT_SLOTS = 5

/**
 * Honest fact strip (charter: a user's choice is a fact, not an error). With
 * notifications off, sync keeps working — but the inbox-copy and boot-recovery
 * notifications silently never appear, and that consequence must be stated.
 */
@Composable
private fun NotificationsOffBanner(
    onOpenSettings: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val c = clipSyncColors
    val shape = CharterShapes.control
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.sf3)
            .border(1.dp, c.ln, shape)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            text = stringResource(R.string.conduit_notifications_off_title),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = c.t2,
        )
        Text(
            text = stringResource(R.string.conduit_notifications_off_body),
            style = ClipSyncType.caption,
            color = c.t3,
        )
        if (onOpenSettings != null) {
            Text(
                text = stringResource(R.string.conduit_notifications_off_action) + " ›",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = c.flow,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onOpenSettings)
                    .padding(vertical = 2.dp),
            )
        }
    }
}

/**
 * 收到内容通知 turned off in 偏好·运行 (settings-roadmap P1-8). Same honest-fact
 * strip as the system-level banner: the choice is stated, the consequence named,
 * and where to change it — sync and history are explicitly unaffected.
 */
@Composable
private fun InboxNotifyOffBanner(modifier: Modifier = Modifier) {
    val c = clipSyncColors
    val shape = CharterShapes.control
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.sf3)
            .border(1.dp, c.ln, shape)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            text = stringResource(R.string.conduit_inbox_notify_off_title),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = c.t2,
        )
        Text(
            text = stringResource(R.string.conduit_inbox_notify_off_body),
            style = ClipSyncType.caption,
            color = c.t3,
        )
    }
}

/**
 * The paired-devices area with nothing in it: a stated fact plus a quiet ghost
 * entrance (the beckoning lives on the network segment, not here).
 */
@Composable
private fun PairedDevicesEmptyState(
    onPairRequest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = clipSyncColors
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = stringResource(R.string.conduit_no_paired_devices),
            style = ClipSyncType.groupHeader,
            color = c.t4,
        )
        Text(
            text = stringResource(R.string.conduit_pair_hint),
            style = ClipSyncType.caption,
            color = c.t3,
            textAlign = TextAlign.Center,
        )
        val shape = CharterShapes.control
        Text(
            text = stringResource(R.string.action_go_pair) + " ›",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = c.flow,
            modifier = Modifier
                .clip(shape)
                .border(1.dp, c.flowLn, shape)
                .clickable(onClick = onPairRequest)
                .padding(horizontal = 14.dp, vertical = 7.dp),
        )
    }
}

/** One quiet line: test outcome without content, dismissable with a tap. */
@Composable
private fun TestResultRow(
    result: ConduitTestResult,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = clipSyncColors
    val tint = if (result.success) c.flow else c.err
    val shape = CharterShapes.control
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (result.success) c.flowBg else c.errBg)
            .border(1.dp, if (result.success) c.flowLn else c.errLn, shape)
            .clickable(onClick = onDismiss)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = result.label.string(),
                style = ClipSyncType.caption,
                color = tint,
            )
            // A failure from the 特权直读 closed code set explains itself in one
            // line; the machine code above stays as the anchor for reports.
            result.hint?.let { hint ->
                Spacer(Modifier.height(2.dp))
                Text(
                    text = hint.string(),
                    style = ClipSyncType.meta,
                    color = tint,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(text = "×", fontSize = 14.sp, color = c.t4)
    }
}

/**
 * The 44dp status band: the conduit folded into one strip. Four mini rail
 * segments map 1:1 to the four pipeline segments — same vocabulary at a
 * smaller scale.
 */
@Composable
fun ConduitStatusBand(
    state: HealthScreenState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = clipSyncColors
    val needsAction = state.statuses.any { it == ConduitStatus.NEEDS_ACTION }
    val allReady = state.statuses.all { it == ConduitStatus.READY }
    // The band shifts between the flow and beckon vocabularies as segments
    // change; the crossing runs on the charter curve, never a hard cut.
    val tint by animateColorAsState(
        targetValue = if (needsAction) c.act else c.flow,
        animationSpec = CharterMotion.spec(CharterMotion.DUR_STANDARD_MS),
        label = "bandTint",
    )
    val bandBg by animateColorAsState(
        targetValue = if (needsAction) c.actBg else c.flowBg,
        animationSpec = CharterMotion.spec(CharterMotion.DUR_STANDARD_MS),
        label = "bandBg",
    )
    val bandLn by animateColorAsState(
        targetValue = if (needsAction) c.actLn else c.flowLn,
        animationSpec = CharterMotion.spec(CharterMotion.DUR_STANDARD_MS),
        label = "bandLn",
    )
    val title = stringResource(
        when {
            needsAction -> R.string.conduit_band_blocked
            allReady -> R.string.conduit_band_ready
            else -> R.string.conduit_band_partial
        },
    )
    val subtitle = stringResource(
        when {
            needsAction -> R.string.conduit_band_blocked_sub
            allReady -> R.string.conduit_band_ready_sub
            else -> R.string.conduit_band_partial_sub
        },
    )
    val shape = CharterShapes.control
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(shape)
            .background(bandBg)
            .border(1.dp, bandLn, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ConduitRail(statuses = state.statuses)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = tint,
            )
            Text(
                text = subtitle,
                fontSize = 10.sp,
                color = c.t3,
            )
        }
        Text(text = "›", fontSize = 16.sp, color = c.t4)
    }
}

/** Four mini segments, 14×4dp fully rounded — the rail vocabulary. */
@Composable
fun ConduitRail(
    statuses: List<ConduitStatus>,
    modifier: Modifier = Modifier,
) {
    val c = clipSyncColors
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        statuses.forEach { status ->
            val segModifier = Modifier
                .size(width = 14.dp, height = 4.dp)
                .clip(RoundedCornerShape(2.dp))
            when (status) {
                ConduitStatus.READY -> Box(segModifier.background(c.flow))
                ConduitStatus.NEEDS_ACTION -> Box(segModifier.background(c.act))
                ConduitStatus.DEGRADED -> Box(
                    segModifier.background(
                        Brush.horizontalGradient(
                            0f to c.flow,
                            0.52f to c.flow,
                            0.52f to c.ln2,
                            1f to c.ln2,
                        ),
                    ),
                )
                else -> Box(segModifier.background(c.ln2))
            }
        }
    }
}

/** One tappable segment action; emphasized actions render in beckon ochre. */
data class SegmentActionUi(
    val label: String,
    val emphasized: Boolean = false,
    /** True while the action's work is in flight: quiet face, taps absorbed. */
    val busy: Boolean = false,
    val onClick: () -> Unit,
)

@Composable
private fun PipelineSegment(
    title: String,
    icon: ImageVector,
    segment: ConduitSegmentState,
    modifier: Modifier = Modifier,
    actions: List<SegmentActionUi> = emptyList(),
) {
    val c = clipSyncColors
    val beckons = segment.beckoning
    val expandable = segment.detailLines.isNotEmpty() || segment.errorDetail != null
    var expanded by rememberSaveable(title) { mutableStateOf(false) }
    val shape = CharterShapes.card
    val tint = when {
        segment.status == ConduitStatus.NEEDS_ACTION -> c.act
        segment.status == ConduitStatus.READY -> c.flow
        segment.status == ConduitStatus.DEGRADED -> c.flow.copy(alpha = 0.8f)
        else -> c.t4
    }
    val surface = if (beckons) {
        // The beckoning card keeps the same z1 depth (sh-1 + face) with the
        // ochre wash composited on top — a tinted card, not a flat strip.
        Modifier
            .shadow(elevation = 3.dp, shape = shape, ambientColor = c.shadow, spotColor = c.shadow)
            .clip(shape)
            .background(c.sf)
            .background(c.actBg)
            .border(1.dp, c.actLn, shape)
    } else {
        Modifier.charterCard(corner = 16.dp)
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(surface)
            .then(
                if (expandable) Modifier.clickable { expanded = !expanded } else Modifier,
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (beckons) c.act else c.t1,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = segment.statusLabel.string(),
                style = ClipSyncType.meta,
                fontWeight = if (beckons) FontWeight.SemiBold else FontWeight.Normal,
                color = tint,
            )
            if (expandable) {
                Text(
                    text = if (expanded) "⌃" else "⌄",
                    fontSize = 12.sp,
                    color = c.t4,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        FillBar(status = if (beckons) segment.status else segment.status.quietened())
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(if (beckons) c.actLn else c.ln),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = segment.detail.string(),
            style = ClipSyncType.caption,
            color = c.t3,
        )
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(CharterMotion.spec(CharterMotion.DUR_QUICK_MS)) +
                expandVertically(CharterMotion.spec(CharterMotion.DUR_STANDARD_MS)),
            exit = fadeOut(CharterMotion.spec(CharterMotion.DUR_QUICK_MS)) +
                shrinkVertically(CharterMotion.spec(CharterMotion.DUR_STANDARD_MS)),
        ) {
            Column {
                segment.detailLines.forEach { line ->
                    Spacer(Modifier.height(5.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(c.ln2),
                        )
                        Spacer(Modifier.width(7.dp))
                        Text(
                            text = line.string(),
                            style = ClipSyncType.caption,
                            color = c.t3,
                        )
                    }
                }
            }
        }
        segment.errorDetail?.let { error ->
            Spacer(Modifier.height(8.dp))
            Text(
                text = error.string(),
                style = ClipSyncType.caption,
                color = c.err,
            )
        }
        if (actions.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                actions.forEach { action ->
                    SegmentActionChip(action)
                }
            }
        }
    }
}

/**
 * A demoted NEEDS_ACTION (single-beckon rule) still states its status but must
 * not pulse; render it with the unprobed dashed bar instead.
 */
private fun ConduitStatus.quietened(): ConduitStatus =
    if (this == ConduitStatus.NEEDS_ACTION) ConduitStatus.UNPROBED else this

@Composable
private fun SegmentActionChip(action: SegmentActionUi) {
    val c = clipSyncColors
    // A busy action states its progress on a quiet face: no invite chevron, no
    // flow tint, no click — the work is already running (charter: feedback
    // within 100ms, and a button must never pretend a second tap would help).
    val tint = when {
        action.busy -> c.t3
        action.emphasized -> c.act
        else -> c.flow
    }
    val bg = when {
        action.busy -> c.sf3
        action.emphasized -> c.actBg
        else -> c.flowBg
    }
    val line = when {
        action.busy -> c.ln2
        action.emphasized -> c.actLn
        else -> c.flowLn
    }
    val shape = CharterShapes.control
    Text(
        text = if (action.busy) action.label else "${action.label} ›",
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = tint,
        modifier = Modifier
            .clip(shape)
            .background(bg)
            .border(1.dp, line, shape)
            .clickable(enabled = !action.busy, onClick = action.onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

/** One shape, five fills: the capsule bar carrying the status encoding. */
@Composable
private fun FillBar(
    status: ConduitStatus,
    modifier: Modifier = Modifier,
) {
    val c = clipSyncColors
    when (status) {
        ConduitStatus.READY -> FilledTrack(modifier, fraction = 1f, fill = c.flow)
        ConduitStatus.DEGRADED -> FilledTrack(modifier, fraction = 0.52f, fill = c.flow)
        ConduitStatus.UNAVAILABLE -> FilledTrack(modifier, fraction = 1f, fill = c.sf3, border = c.ln2)
        ConduitStatus.NEEDS_ACTION -> PulsingBar(modifier)
        ConduitStatus.UNPROBED -> DashedBar(modifier)
    }
}

@Composable
private fun FilledTrack(
    modifier: Modifier = Modifier,
    fraction: Float,
    fill: Color,
    border: Color? = null,
) {
    val c = clipSyncColors
    val shape = RoundedCornerShape(2.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(shape)
            .background(c.sfIn)
            .then(if (border != null) Modifier.border(1.dp, border, shape) else Modifier),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .fillMaxHeight()
                .clip(shape)
                .background(fill),
        )
    }
}

/**
 * Empty capsule + ochre outline + 2.6s pulse — the reaching hand (tokens.md §9).
 * With the system's 减弱动态效果 on (P1#13) the halo never plays: the act-bg
 * track and 1.5px ochre stroke alone carry the beckoning, statically.
 */
@Composable
private fun PulsingBar(modifier: Modifier = Modifier) {
    val c = clipSyncColors
    if (LocalReducedMotion.current) {
        Canvas(
            modifier = modifier
                .fillMaxWidth()
                .height(4.dp),
        ) {
            val radius = CornerRadius(size.height / 2f)
            drawRoundRect(color = c.actBg, cornerRadius = radius)
            drawRoundRect(
                color = c.act,
                cornerRadius = radius,
                style = Stroke(width = 1.5.dp.toPx()),
            )
        }
        return
    }
    val transition = rememberInfiniteTransition(label = "needsActionPulse")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = CharterMotion.PULSE_MS, easing = CharterMotion.Ease),
            repeatMode = RepeatMode.Restart,
        ),
        label = "pulse",
    )
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(4.dp),
    ) {
        val radius = CornerRadius(size.height / 2f)
        drawRoundRect(color = c.actBg, cornerRadius = radius)
        drawRoundRect(
            color = c.act,
            cornerRadius = radius,
            style = Stroke(width = 1.5.dp.toPx()),
        )
        val expand = 5.dp.toPx() * pulse
        if (expand > 0f) {
            drawRoundRect(
                color = c.act.copy(alpha = (1f - pulse) * 0.30f),
                topLeft = Offset(-expand, -expand),
                size = Size(size.width + expand * 2f, size.height + expand * 2f),
                cornerRadius = CornerRadius(radius.x + expand),
                style = Stroke(width = 1.5.dp.toPx()),
            )
        }
    }
}

/** Dashed outline — unknown, not bad. */
@Composable
private fun DashedBar(modifier: Modifier = Modifier) {
    val c = clipSyncColors
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(4.dp),
    ) {
        drawLine(
            color = c.ln2,
            start = Offset(2.dp.toPx(), size.height / 2f),
            end = Offset(size.width - 2.dp.toPx(), size.height / 2f),
            strokeWidth = 1.5.dp.toPx(),
            cap = StrokeCap.Round,
            pathEffect = PathEffect.dashPathEffect(
                floatArrayOf(6.dp.toPx(), 5.dp.toPx()),
            ),
        )
    }
}

/**
 * Three phase-shifted dots: content will flow through here (tokens.md §9).
 * Under reduced motion (P1#13) the same three dots hold still — the metaphor
 * stays, the drift does not.
 */
@Composable
private fun FlowLine(modifier: Modifier = Modifier) {
    val c = clipSyncColors
    val reducedMotion = LocalReducedMotion.current
    val time: Float
    if (reducedMotion) {
        time = 0f
    } else {
        val transition = rememberInfiniteTransition(label = "flowLine")
        val flowTime by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1800, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "flowTime",
        )
        time = flowTime
    }
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(width = 36.dp, height = 8.dp)) {
            val spacing = 11.dp.toPx()
            val drift = 5.dp.toPx()
            repeat(3) { index ->
                if (reducedMotion) {
                    drawCircle(
                        color = c.flow,
                        radius = 2.4.dp.toPx(),
                        center = Offset(x = 3.dp.toPx() + index * spacing, y = size.height / 2f),
                        alpha = 0.55f,
                    )
                    return@repeat
                }
                val phase = (time + index / 3f) % 1f
                val alpha = if (phase < 0.4f) phase / 0.4f else 1f - (phase - 0.4f) / 0.6f
                drawCircle(
                    color = c.flow,
                    radius = 2.4.dp.toPx(),
                    center = Offset(
                        x = 3.dp.toPx() + index * spacing + (phase - 0.5f) * 2f * drift,
                        y = size.height / 2f,
                    ),
                    alpha = alpha.coerceIn(0f, 1f),
                )
            }
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text = stringResource(R.string.conduit_flow_line),
            fontSize = 11.sp,
            color = c.t3,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun HealthScreenPreview() {
    ClipSyncTheme {
        HealthScreen(state = buildHealthScreenState(peer = null, clipboard = null, sync = null))
    }
}

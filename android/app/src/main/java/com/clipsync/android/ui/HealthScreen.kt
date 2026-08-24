package com.clipsync.android.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clipsync.android.ui.health.CapabilityWizard
import com.clipsync.android.ui.health.ReadRouteUi
import com.clipsync.android.ui.health.RouteActionId
import com.clipsync.android.ui.health.buildHealthScreenState
import com.clipsync.android.ui.theme.ClipSyncIcons
import com.clipsync.android.ui.theme.ClipSyncTheme
import com.clipsync.android.ui.theme.ClipSyncType
import com.clipsync.android.ui.theme.charterCard
import com.clipsync.android.ui.theme.clipSyncColors

/** Interaction easing shared by all charter motion (tokens.md §9). */
val CharterEase = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)

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
    val statusLabel: String,
    val detail: String,
    val status: ConduitStatus,
    /** Extra fact lines shown when the segment card is expanded. */
    val detailLines: List<String> = emptyList(),
    /** Rendered in error red; reserved for true errors such as a certificate change. */
    val errorDetail: String? = null,
    /**
     * Single-beckon rule (charter §5.6): at most one segment pulses ochre. The
     * builder demotes downstream NEEDS_ACTION segments to a quiet rendering.
     */
    val beckoning: Boolean = status == ConduitStatus.NEEDS_ACTION,
)

/** Transient outcome line of a 测试 button; never contains clipboard content. */
data class ConduitTestResult(
    val label: String,
    val success: Boolean,
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
    /** 本机写回 — the inbound write axis; null until the capability stack is wired. */
    val localWrite: ConduitSegmentState? = null,
    /** The wizard's three routes; empty until the capability stack is wired. */
    val routes: List<ReadRouteUi> = emptyList(),
    val serviceRunning: Boolean = false,
    val testResult: ConduitTestResult? = null,
    /** Null = notification probe not wired; false = the surface is off right now. */
    val notificationsEnabled: Boolean? = null,
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
                text = "通路",
                style = ClipSyncType.pageTitle,
                color = c.t1,
                modifier = Modifier.weight(1f),
            )
            if (onRefresh != null) {
                Text(
                    text = "重新探测",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = c.flow,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onRefresh)
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
        }
        PipelineSegment(
            title = "本机读取",
            icon = ClipSyncIcons.History,
            segment = state.localRead,
            actions = buildList {
                if (state.routes.isNotEmpty()) {
                    add(
                        SegmentActionUi(
                            label = if (wizardOpen) "收起引导" else "打开引导",
                            emphasized = !wizardOpen && state.localRead.beckoning,
                            onClick = { wizardOpen = !wizardOpen },
                        ),
                    )
                }
            },
        )
        if (wizardOpen && state.routes.isNotEmpty() && onRouteAction != null) {
            Spacer(Modifier.height(8.dp))
            CapabilityWizard(
                routes = state.routes,
                onRouteAction = onRouteAction,
            )
        }
        Spacer(Modifier.height(8.dp))
        PipelineSegment(
            title = "本机服务",
            icon = ClipSyncIcons.Service,
            segment = state.localService,
            actions = buildList {
                if (state.serviceRunning && onServiceStop != null) {
                    add(SegmentActionUi(label = "停止服务", onClick = onServiceStop))
                } else if (!state.serviceRunning && state.pairedDeviceCount > 0 && onServiceStart != null) {
                    add(SegmentActionUi(label = "启动服务", emphasized = true, onClick = onServiceStart))
                }
            },
        )
        Spacer(Modifier.height(8.dp))
        PipelineSegment(
            title = "网络",
            icon = ClipSyncIcons.Network,
            segment = state.network,
            actions = listOf(
                SegmentActionUi(
                    label = if (state.pairedDeviceCount > 0) "管理配对" else "去配对",
                    emphasized = state.network.beckoning,
                    onClick = onPairRequest,
                ),
            ),
        )
        Spacer(Modifier.height(8.dp))
        PipelineSegment(
            title = "对端写入",
            icon = ClipSyncIcons.Monitor,
            segment = state.peerWrite,
        )
        state.localWrite?.let { localWrite ->
            Spacer(Modifier.height(8.dp))
            PipelineSegment(
                title = "本机写回",
                icon = ClipSyncIcons.Conduit,
                segment = localWrite,
                actions = buildList {
                    if (onTestWrite != null) {
                        add(SegmentActionUi(label = "测试写入", onClick = onTestWrite))
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
                text = "已配对设备 · ${state.pairedDeviceCount}",
                style = ClipSyncType.groupHeader,
                color = c.t4,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                textAlign = TextAlign.Center,
            )
        }
    }
}

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
    val shape = RoundedCornerShape(10.dp)
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
            text = "通知已关闭",
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = c.t2,
        )
        Text(
            text = "同步照常进行，但「收到内容」与「需要恢复」的通知不会出现。",
            style = ClipSyncType.caption,
            color = c.t3,
        )
        if (onOpenSettings != null) {
            Text(
                text = "去系统设置开启 ›",
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
            text = "尚无已配对设备",
            style = ClipSyncType.groupHeader,
            color = c.t4,
        )
        Text(
            text = "在电脑上打开「剪剪相传」出示二维码，配对在网络段完成。",
            style = ClipSyncType.caption,
            color = c.t3,
            textAlign = TextAlign.Center,
        )
        val shape = RoundedCornerShape(10.dp)
        Text(
            text = "去配对 ›",
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
    val shape = RoundedCornerShape(10.dp)
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
        Text(
            text = result.label,
            style = ClipSyncType.caption,
            color = tint,
            modifier = Modifier.weight(1f),
        )
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
    val tint = if (needsAction) c.act else c.flow
    val bandBg = if (needsAction) c.actBg else c.flowBg
    val bandLn = if (needsAction) c.actLn else c.flowLn
    val title = when {
        needsAction -> "通路未接通"
        allReady -> "通路畅通"
        else -> "通路部分接通"
    }
    val subtitle = when {
        needsAction -> "尚未与电脑配对 · 轻触处理"
        allReady -> "内容正在两端流动"
        else -> "有环节降级或未探测"
    }
    val shape = RoundedCornerShape(12.dp)
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
    val shape = RoundedCornerShape(16.dp)
    val tint = when {
        segment.status == ConduitStatus.NEEDS_ACTION -> c.act
        segment.status == ConduitStatus.READY -> c.flow
        segment.status == ConduitStatus.DEGRADED -> c.flow.copy(alpha = 0.8f)
        else -> c.t4
    }
    val surface = if (beckons) {
        Modifier
            .clip(shape)
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
                text = segment.statusLabel,
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
            text = segment.detail,
            style = ClipSyncType.caption,
            color = c.t3,
        )
        if (expanded) {
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
                        text = line,
                        style = ClipSyncType.caption,
                        color = c.t3,
                    )
                }
            }
        }
        segment.errorDetail?.let { error ->
            Spacer(Modifier.height(8.dp))
            Text(
                text = error,
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
    val tint = if (action.emphasized) c.act else c.flow
    val bg = if (action.emphasized) c.actBg else c.flowBg
    val line = if (action.emphasized) c.actLn else c.flowLn
    val shape = RoundedCornerShape(10.dp)
    Text(
        text = "${action.label} ›",
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = tint,
        modifier = Modifier
            .clip(shape)
            .background(bg)
            .border(1.dp, line, shape)
            .clickable(onClick = action.onClick)
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

/** Empty capsule + ochre outline + 2.6s pulse — the reaching hand. */
@Composable
private fun PulsingBar(modifier: Modifier = Modifier) {
    val c = clipSyncColors
    val transition = rememberInfiniteTransition(label = "needsActionPulse")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2600, easing = CharterEase),
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

/** Three phase-shifted dots: content will flow through here (tokens.md §9). */
@Composable
private fun FlowLine(modifier: Modifier = Modifier) {
    val c = clipSyncColors
    val transition = rememberInfiniteTransition(label = "flowLine")
    val time by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "flowTime",
    )
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(width = 36.dp, height = 8.dp)) {
            val spacing = 11.dp.toPx()
            val drift = 5.dp.toPx()
            repeat(3) { index ->
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
            text = "内容将从这里流过",
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

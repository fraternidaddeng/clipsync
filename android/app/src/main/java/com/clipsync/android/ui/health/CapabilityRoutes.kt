package com.clipsync.android.ui.health

import com.clipsync.android.platform.clipboard.CapabilityReport
import com.clipsync.android.platform.clipboard.CapabilityState
import com.clipsync.android.platform.clipboard.ClipboardReadMode
import com.clipsync.android.platform.clipboard.RoutePrerequisites
import com.clipsync.android.ui.ConduitSegmentState
import com.clipsync.android.ui.ConduitStatus

/** The three background-read routes of the capability wizard (charter §4.1). */
enum class ReadRouteId {
    PRIVILEGED,
    LOG_OVERLAY,
    OVERLAY_POLLING,
}

enum class RouteStepId {
    PRIVILEGED_CHANNEL_READY,
    PRIVILEGED_AUTHORIZED,
    READ_LOGS_GRANTED,
    OVERLAY_GRANTED,
    BATTERY_UNRESTRICTED,
}

data class RouteStep(
    val id: RouteStepId,
    val label: String,
    val satisfied: Boolean,
)

/** What tapping the route's main button should do next; resolved to intents by the activity. */
enum class RouteActionId {
    REQUEST_PRIVILEGED_PERMISSION,
    COPY_ADB_READ_LOGS_COMMAND,
    OPEN_OVERLAY_SETTINGS,
    OPEN_BATTERY_SETTINGS,
    SET_PREFERRED,

    /**
     * Run a device-verified background read for this route: seed an app-generated token,
     * read it back through the route's real backend, clear it, and only then may the route
     * claim READY (plan §8.3). Offered once the prerequisites are met but the route is still
     * DEGRADED (授权但待实测).
     */
    RUN_READ_TEST,
}

data class ReadRouteUi(
    val id: ReadRouteId,
    val mode: ClipboardReadMode,
    val title: String,
    /** Filled dots out of 3 — the charter's quality column. */
    val quality: Int,
    val cost: String,
    val steps: List<RouteStep>,
    val stepsRemaining: Int,
    val readState: CapabilityState,
    val errorCode: String?,
    val nextAction: RouteActionId?,
    /**
     * Secondary action offered independently of [nextAction]: the device-verified read test,
     * shown when the prerequisites are met but the route is still awaiting实测验证 (DEGRADED).
     */
    val readTestAction: RouteActionId? = null,
    val preferred: Boolean,
)

/** Result of the last pinned `/v1/peer/health` reachability probe. */
enum class PeerReachability {
    UNKNOWN,
    REACHABLE,
    UNREACHABLE,
    CERTIFICATE_MISMATCH,
}

/**
 * Everything the capability stack learned from one full probe pass. Pure data so
 * the segment and wizard mapping stays unit-testable without Android.
 */
data class CapabilityFacts(
    val reports: Map<ClipboardReadMode, CapabilityReport>,
    val prerequisites: RoutePrerequisites,
    val preferredReadMode: ClipboardReadMode,
    val publicWriteState: CapabilityState,
    val publicWriteErrorCode: String? = null,
    val reachability: PeerReachability = PeerReachability.UNKNOWN,
)

private val BACKGROUND_READ_MODES = listOf(
    ClipboardReadMode.SHIZUKU_EVENT,
    ClipboardReadMode.ADB_LOG_OVERLAY,
    ClipboardReadMode.OVERLAY_POLLING,
)

/**
 * 本机读取 from the full capability ladder (charter §5.5). READY only when a
 * background route is actually open; all-routes-closed beckons toward the
 * wizard instead of pretending "unavailable" is fate.
 */
internal fun localReadSegmentFromFacts(facts: CapabilityFacts): ConduitSegmentState {
    val background = BACKGROUND_READ_MODES.mapNotNull { facts.reports[it] }
    val detailLines = buildList {
        BACKGROUND_READ_MODES.forEach { mode ->
            add("${readModeTitle(mode)}：${readStateWord(facts.reports[mode])}")
        }
        if (facts.reports[ClipboardReadMode.FOREGROUND_ONLY]?.readState == CapabilityState.READY) {
            add("前台读取：应用可见时始终可用（手动兜底）")
        }
        add("首选路线：${readModeTitle(facts.preferredReadMode)}")
    }
    val ready = background.firstOrNull { it.readState == CapabilityState.READY }
    return when {
        background.isEmpty() -> ConduitSegmentState(
            statusLabel = "降级 · 仅前台",
            detail = "应用在前台时可读取剪贴板；后台读取能力尚未接入。",
            status = ConduitStatus.DEGRADED,
            detailLines = detailLines,
        )
        ready != null -> ConduitSegmentState(
            statusLabel = "就绪",
            detail = "后台读取可用（${readModeTitle(ready.readMode)}）。",
            status = ConduitStatus.READY,
            detailLines = detailLines,
        )
        background.any { it.readState == CapabilityState.DEGRADED } -> ConduitSegmentState(
            statusLabel = "已授权 · 待实测",
            detail = "前提已就绪，读取通道等待实测验证；期间前台读取仍可用。",
            status = ConduitStatus.DEGRADED,
            detailLines = detailLines,
        )
        background.all { it.readState == CapabilityState.UNAVAILABLE } -> ConduitSegmentState(
            statusLabel = "需要你操作",
            detail = "后台读取尚未打通。三条路线任选一条完成即可；前台复制不受影响。",
            status = ConduitStatus.NEEDS_ACTION,
            detailLines = detailLines,
        )
        else -> ConduitSegmentState(
            statusLabel = "未探测",
            detail = "读取能力尚未探测。缺信息 ≠ 坏消息。",
            status = ConduitStatus.UNPROBED,
            detailLines = detailLines,
        )
    }
}

/**
 * 本机写回 — the inbound half of the charter's separate read/write axes. The
 * state is the last real write test, never "the API exists so it works".
 */
internal fun localWriteSegmentFromFacts(facts: CapabilityFacts): ConduitSegmentState {
    val detailLines = buildList {
        add("公开写入（setPrimaryClip）：${capabilityWord(facts.publicWriteState)}")
        facts.publicWriteErrorCode?.let { add("最近错误码：$it") }
        add("测试只写入应用生成的随机文本，校验后立即清除。")
    }
    return when (facts.publicWriteState) {
        CapabilityState.READY -> ConduitSegmentState(
            statusLabel = "已验证",
            detail = "公开写入已实测可用；对端内容可自动进入本机剪贴板。",
            status = ConduitStatus.READY,
            detailLines = detailLines,
        )
        CapabilityState.DEGRADED -> ConduitSegmentState(
            statusLabel = "受限",
            detail = "写入能力受限，部分内容可能需要从通知手动复制。",
            status = ConduitStatus.DEGRADED,
            detailLines = detailLines,
        )
        CapabilityState.UNAVAILABLE -> ConduitSegmentState(
            statusLabel = "不可用",
            detail = "自动写入暂不可用；内容仍会保存到历史，可从通知手动复制。",
            status = ConduitStatus.UNAVAILABLE,
            detailLines = detailLines,
        )
        CapabilityState.UNKNOWN -> ConduitSegmentState(
            statusLabel = "未测试",
            detail = "写入能力以实测为准；点「测试写入」验证一次。",
            status = ConduitStatus.UNPROBED,
            detailLines = detailLines,
        )
    }
}

/**
 * The wizard's three route cards (charter §4.1): quality / cost / steps
 * remaining, ordered best-first. The user picks by "能不能用、代价是什么",
 * never by permission names.
 */
internal fun buildReadRoutes(facts: CapabilityFacts): List<ReadRouteUi> {
    val p = facts.prerequisites
    return listOf(
        readRoute(
            id = ReadRouteId.PRIVILEGED,
            mode = ClipboardReadMode.SHIZUKU_EVENT,
            title = "特权直读",
            quality = 3,
            cost = "授权一次即可；设备重启后特权通道可能需要重新就绪",
            steps = listOf(
                RouteStep(
                    RouteStepId.PRIVILEGED_CHANNEL_READY,
                    "特权通道可用",
                    p.shizukuInstalled && p.shizukuRunning,
                ),
                RouteStep(RouteStepId.PRIVILEGED_AUTHORIZED, "已授权本应用", p.shizukuAuthorized),
            ),
            facts = facts,
        ),
        readRoute(
            id = ReadRouteId.LOG_OVERLAY,
            mode = ClipboardReadMode.ADB_LOG_OVERLAY,
            title = "日志感知 + 悬浮窗",
            quality = 2,
            cost = "需在电脑上用 adb 授予 READ_LOGS，并开启悬浮窗权限",
            steps = listOf(
                RouteStep(RouteStepId.READ_LOGS_GRANTED, "READ_LOGS 已授予（需电脑 adb）", p.readLogsGranted),
                RouteStep(RouteStepId.OVERLAY_GRANTED, "悬浮窗权限已开启", p.overlayGranted),
            ),
            facts = facts,
        ),
        readRoute(
            id = ReadRouteId.OVERLAY_POLLING,
            mode = ClipboardReadMode.OVERLAY_POLLING,
            title = "悬浮窗轮询",
            quality = 1,
            cost = "仅需悬浮窗权限；不需要电脑，代价是耗电和轮询延迟",
            steps = listOf(
                RouteStep(RouteStepId.OVERLAY_GRANTED, "悬浮窗权限已开启", p.overlayGranted),
                RouteStep(RouteStepId.BATTERY_UNRESTRICTED, "电池优化已放行", p.batteryUnrestricted),
            ),
            facts = facts,
        ),
    )
}

private fun readRoute(
    id: ReadRouteId,
    mode: ClipboardReadMode,
    title: String,
    quality: Int,
    cost: String,
    steps: List<RouteStep>,
    facts: CapabilityFacts,
): ReadRouteUi {
    val report = facts.reports[mode]
    val remaining = steps.count { !it.satisfied }
    val preferred = facts.preferredReadMode == mode
    // A step without an in-app action (e.g. privileged channel not available)
    // shows probe status only; it never falls through to "set preferred".
    val firstUnsatisfied = steps.firstOrNull { !it.satisfied }
    val nextAction = if (firstUnsatisfied != null) {
        stepAction(firstUnsatisfied.id)
    } else {
        RouteActionId.SET_PREFERRED.takeUnless { preferred }
    }
    // Prerequisites are met but the read path has not yet been device-verified: offer the
    // one-tap read test that promotes DEGRADED -> READY (plan §8.3). Kept separate from
    // nextAction so choosing the preferred route and verifying it stay independent.
    val readTestAction = RouteActionId.RUN_READ_TEST
        .takeIf { remaining == 0 && report?.readState == CapabilityState.DEGRADED }
    return ReadRouteUi(
        id = id,
        mode = mode,
        title = title,
        quality = quality,
        cost = cost,
        steps = steps,
        stepsRemaining = remaining,
        readState = report?.readState ?: CapabilityState.UNKNOWN,
        errorCode = report?.errorCode,
        nextAction = nextAction,
        readTestAction = readTestAction,
        preferred = preferred,
    )
}

/** Channel availability is a probed fact, not a chore — no in-app action can satisfy it. */
private fun stepAction(step: RouteStepId): RouteActionId? = when (step) {
    RouteStepId.PRIVILEGED_CHANNEL_READY -> null
    RouteStepId.PRIVILEGED_AUTHORIZED -> RouteActionId.REQUEST_PRIVILEGED_PERMISSION
    RouteStepId.READ_LOGS_GRANTED -> RouteActionId.COPY_ADB_READ_LOGS_COMMAND
    RouteStepId.OVERLAY_GRANTED -> RouteActionId.OPEN_OVERLAY_SETTINGS
    RouteStepId.BATTERY_UNRESTRICTED -> RouteActionId.OPEN_BATTERY_SETTINGS
}

fun readModeTitle(mode: ClipboardReadMode): String = when (mode) {
    ClipboardReadMode.SHIZUKU_EVENT -> "特权直读"
    ClipboardReadMode.ADB_LOG_OVERLAY -> "日志感知 + 悬浮窗"
    ClipboardReadMode.OVERLAY_POLLING -> "悬浮窗轮询"
    ClipboardReadMode.FOREGROUND_ONLY -> "前台/手动"
}

fun routeActionLabel(action: RouteActionId): String = when (action) {
    RouteActionId.REQUEST_PRIVILEGED_PERMISSION -> "授权特权直读"
    RouteActionId.COPY_ADB_READ_LOGS_COMMAND -> "复制 adb 命令"
    RouteActionId.OPEN_OVERLAY_SETTINGS -> "去设置悬浮窗"
    RouteActionId.OPEN_BATTERY_SETTINGS -> "去设置电池"
    RouteActionId.SET_PREFERRED -> "设为首选路线"
    RouteActionId.RUN_READ_TEST -> "测试后台读取"
}

private fun readStateWord(report: CapabilityReport?): String = when (report?.readState) {
    null -> "未探测"
    CapabilityState.READY -> "就绪"
    CapabilityState.DEGRADED -> "待实测（${report.errorCode ?: "未验证"}）"
    CapabilityState.UNAVAILABLE -> "未打通（${report.errorCode ?: "原因未知"}）"
    CapabilityState.UNKNOWN -> "未探测"
}

private fun capabilityWord(state: CapabilityState): String = when (state) {
    CapabilityState.READY -> "已验证可用"
    CapabilityState.DEGRADED -> "受限"
    CapabilityState.UNAVAILABLE -> "不可用"
    CapabilityState.UNKNOWN -> "未测试"
}

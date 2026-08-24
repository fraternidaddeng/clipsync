package com.clipsync.android.ui.conduit

import com.clipsync.android.platform.clipboard.CapabilityReport
import com.clipsync.android.platform.clipboard.CapabilityState
import com.clipsync.android.platform.clipboard.ClipboardReadMode
import com.clipsync.android.platform.clipboard.RoutePrerequisites

/** The mapper's full output: the four pipe segments plus the three wizard routes. */
data class ConduitDerivedState(
    val segments: List<ConduitSegmentUi>,
    val routes: List<RouteUi>,
)

/**
 * Pure mapping from probed facts to the conduit page (charter §5.5/§5.6). No Android types so
 * every rule here is unit-tested: the five-state fill encoding, the single-beckon rule, the
 * separate read/write axes and the wizard's remaining steps per route.
 */
object ConduitStateMapper {
    val BACKGROUND_READ_MODES = listOf(
        ClipboardReadMode.SHIZUKU_EVENT,
        ClipboardReadMode.ADB_LOG_OVERLAY,
        ClipboardReadMode.OVERLAY_POLLING,
    )

    fun derive(inputs: ConduitInputs): ConduitDerivedState {
        val segments = applySingleBeckon(
            listOf(
                readSegment(inputs),
                serviceSegment(inputs),
                networkSegment(inputs),
                writeSegment(inputs),
            ),
        )
        return ConduitDerivedState(segments = segments, routes = routes(inputs))
    }

    // ---- Segment: 后台读取 -------------------------------------------------------------

    fun readSegmentStatus(reports: Map<ClipboardReadMode, CapabilityReport>): SegmentStatus {
        val background = BACKGROUND_READ_MODES.mapNotNull { reports[it] }
        return when {
            background.isEmpty() -> SegmentStatus.UNPROBED
            background.any { it.readState == CapabilityState.READY } -> SegmentStatus.READY
            background.any { it.readState == CapabilityState.DEGRADED } -> SegmentStatus.DEGRADED
            background.all { it.readState == CapabilityState.UNAVAILABLE } -> SegmentStatus.NEEDS_ACTION
            else -> SegmentStatus.UNPROBED
        }
    }

    private fun readSegment(inputs: ConduitInputs): ConduitSegmentUi {
        val status = readSegmentStatus(inputs.reports)
        val summary = when (status) {
            SegmentStatus.READY -> "后台读取可用"
            SegmentStatus.DEGRADED -> "已授权，读取通道待实测"
            SegmentStatus.NEEDS_ACTION -> "三条路线都未打通"
            SegmentStatus.UNAVAILABLE -> "后台读取不可用"
            SegmentStatus.UNPROBED -> "尚未探测"
        }
        val detail = buildList {
            BACKGROUND_READ_MODES.forEach { mode ->
                val report = inputs.reports[mode]
                add("${modeTitle(mode)}：${readStateWord(report)}")
            }
            val foreground = inputs.reports[ClipboardReadMode.FOREGROUND_ONLY]
            if (foreground?.readState == CapabilityState.READY) {
                add("前台读取：应用可见时始终可用（手动兜底）")
            }
            add("首选路线：${modeTitle(inputs.preferredReadMode)}")
        }
        return ConduitSegmentUi(
            id = ConduitSegmentId.READ,
            title = "后台读取",
            status = status,
            summary = summary,
            detail = detail,
            actions = listOf(
                SegmentAction(SegmentActionId.OPEN_WIZARD, "打开引导"),
                SegmentAction(SegmentActionId.TEST_READ, "测试读取"),
            ),
        )
    }

    // ---- Segment: 同步服务 -------------------------------------------------------------

    fun serviceSegmentStatus(paired: Boolean, running: Boolean, errorCode: String?): SegmentStatus =
        when {
            !paired -> SegmentStatus.UNAVAILABLE
            running -> SegmentStatus.READY
            errorCode != null -> SegmentStatus.DEGRADED
            else -> SegmentStatus.NEEDS_ACTION
        }

    private fun serviceSegment(inputs: ConduitInputs): ConduitSegmentUi {
        val status = serviceSegmentStatus(inputs.paired, inputs.serviceRunning, inputs.serviceErrorCode)
        val summary = when {
            !inputs.paired -> "等待配对完成"
            inputs.serviceRunning -> "前台服务运行中"
            inputs.serviceErrorCode != null -> "启动失败：${inputs.serviceErrorCode}"
            else -> "未启动"
        }
        val detail = listOf(
            "前台服务只负责保持连接与调度，不等于获得剪贴板权限。",
            if (inputs.serviceRunning) "通知栏会显示常驻通知；停止服务即移除。" else "启动后会显示一条常驻通知。",
        )
        val actions = buildList {
            if (inputs.serviceRunning) {
                add(SegmentAction(SegmentActionId.STOP_SERVICE, "停止服务"))
            } else if (inputs.paired) {
                add(SegmentAction(SegmentActionId.START_SERVICE, "启动服务"))
            }
        }
        return ConduitSegmentUi(
            id = ConduitSegmentId.SERVICE,
            title = "同步服务",
            status = status,
            summary = summary,
            detail = detail,
            actions = actions,
        )
    }

    // ---- Segment: 网络 ----------------------------------------------------------------

    fun networkSegmentStatus(paired: Boolean, reachability: PeerReachability): SegmentStatus =
        when {
            !paired -> SegmentStatus.NEEDS_ACTION
            reachability == PeerReachability.CERTIFICATE_MISMATCH -> SegmentStatus.DEGRADED
            reachability == PeerReachability.REACHABLE -> SegmentStatus.READY
            reachability == PeerReachability.UNREACHABLE -> SegmentStatus.DEGRADED
            else -> SegmentStatus.UNPROBED
        }

    private fun networkSegment(inputs: ConduitInputs): ConduitSegmentUi {
        val status = networkSegmentStatus(inputs.paired, inputs.reachability)
        val summary = when {
            !inputs.paired -> "未配对"
            inputs.reachability == PeerReachability.CERTIFICATE_MISMATCH -> "证书不匹配，已阻断"
            inputs.reachability == PeerReachability.REACHABLE ->
                "对端可达：${inputs.peerName ?: "已配对设备"}"
            inputs.reachability == PeerReachability.UNREACHABLE -> "已配对，当前不可达"
            else -> "已配对，尚未探测"
        }
        val detail = buildList {
            if (inputs.paired) {
                add("已配对：${inputs.peerName ?: "未知设备"}")
                add("探测方式：固定证书 TLS 请求 /v1/peer/health。")
                if (inputs.reachability == PeerReachability.UNREACHABLE) {
                    add("确认两台设备在同一局域网 / VPN，且 Windows 端正在运行。")
                }
            } else {
                add("配对的本质就是接通网络段：扫描 Windows 端二维码完成一次配对。")
            }
        }
        val errorDetail = if (inputs.reachability == PeerReachability.CERTIFICATE_MISMATCH) {
            "对端出示的证书与固定指纹不符，连接已被阻止。若非你重装了 Windows 端，请检查网络环境。"
        } else {
            null
        }
        return ConduitSegmentUi(
            id = ConduitSegmentId.NETWORK,
            title = "网络",
            status = status,
            summary = summary,
            detail = detail,
            actions = listOf(
                SegmentAction(SegmentActionId.GO_PAIR, if (inputs.paired) "管理配对" else "配对"),
            ),
            errorDetail = errorDetail,
        )
    }

    // ---- Segment: 剪贴板写入 -----------------------------------------------------------

    fun writeSegmentStatus(publicState: CapabilityState, fallbackState: CapabilityState): SegmentStatus =
        when {
            publicState == CapabilityState.READY -> SegmentStatus.READY
            publicState == CapabilityState.UNKNOWN -> SegmentStatus.UNPROBED
            fallbackState == CapabilityState.READY -> SegmentStatus.DEGRADED
            else -> SegmentStatus.UNAVAILABLE
        }

    private fun writeSegment(inputs: ConduitInputs): ConduitSegmentUi {
        val status = writeSegmentStatus(inputs.publicWriteState, inputs.fallbackWriteState)
        val summary = when (status) {
            SegmentStatus.READY -> "公开写入已验证"
            SegmentStatus.UNPROBED -> "未测试"
            SegmentStatus.DEGRADED -> "公开写入不可用，特权回退可用"
            else -> "自动写入不可用（保留通知手动复制）"
        }
        val detail = buildList {
            add("公开写入（setPrimaryClip）：${capabilityWord(inputs.publicWriteState)}")
            add("特权回退（Shizuku/悬浮窗）：${capabilityWord(inputs.fallbackWriteState)}")
            inputs.publicWriteErrorCode?.let { add("最近错误码：$it") }
            add("测试只写入应用生成的随机文本，并在校验后立即清除。")
        }
        return ConduitSegmentUi(
            id = ConduitSegmentId.WRITE,
            title = "剪贴板写入",
            status = status,
            summary = summary,
            detail = detail,
            actions = listOf(SegmentAction(SegmentActionId.TEST_WRITE, "测试写入")),
        )
    }

    // ---- Single-beckon rule (charter §5.6) ----------------------------------------------

    /**
     * At most one segment beckons: when several need action, only the most upstream one in
     * pipe order lights up; downstream ones keep their actions but stay quiet.
     */
    fun applySingleBeckon(segments: List<ConduitSegmentUi>): List<ConduitSegmentUi> {
        var assigned = false
        return segments.map { segment ->
            if (segment.status == SegmentStatus.NEEDS_ACTION && !assigned) {
                assigned = true
                segment.copy(beckoning = true)
            } else {
                segment.copy(beckoning = false)
            }
        }
    }

    // ---- Wizard routes (charter §4.1) ----------------------------------------------------

    fun routes(inputs: ConduitInputs): List<RouteUi> {
        val p = inputs.prerequisites
        return listOf(
            route(
                id = ReadRouteId.SHIZUKU,
                mode = ClipboardReadMode.SHIZUKU_EVENT,
                title = "特权直读 · Shizuku",
                quality = 3,
                cost = "安装 Shizuku 并授权一次；无 Root 可用无线调试启动，重启后可能需恢复",
                steps = listOf(
                    RouteStep(RouteStepId.SHIZUKU_INSTALLED, "已安装 Shizuku", p.shizukuInstalled),
                    RouteStep(RouteStepId.SHIZUKU_RUNNING, "Shizuku 服务运行中", p.shizukuRunning),
                    RouteStep(RouteStepId.SHIZUKU_AUTHORIZED, "已授权本应用", p.shizukuAuthorized),
                ),
                inputs = inputs,
            ),
            route(
                id = ReadRouteId.LOG_OVERLAY,
                mode = ClipboardReadMode.ADB_LOG_OVERLAY,
                title = "日志感知 + 悬浮窗",
                quality = 2,
                cost = "需在电脑上用 adb 授予 READ_LOGS，并开启悬浮窗权限",
                steps = listOf(
                    RouteStep(RouteStepId.READ_LOGS_GRANTED, "READ_LOGS 已授予（需电脑 adb）", p.readLogsGranted),
                    RouteStep(RouteStepId.OVERLAY_GRANTED, "悬浮窗权限已开启", p.overlayGranted),
                ),
                inputs = inputs,
            ),
            route(
                id = ReadRouteId.OVERLAY_POLLING,
                mode = ClipboardReadMode.OVERLAY_POLLING,
                title = "悬浮窗轮询",
                quality = 1,
                cost = "仅需悬浮窗权限；不需要电脑，代价是耗电和轮询延迟",
                steps = listOf(
                    RouteStep(RouteStepId.OVERLAY_GRANTED, "悬浮窗权限已开启", p.overlayGranted),
                    RouteStep(RouteStepId.BATTERY_UNRESTRICTED, "电池优化已放行", p.batteryUnrestricted),
                ),
                inputs = inputs,
            ),
        )
    }

    private fun route(
        id: ReadRouteId,
        mode: ClipboardReadMode,
        title: String,
        quality: Int,
        cost: String,
        steps: List<RouteStep>,
        inputs: ConduitInputs,
    ): RouteUi {
        val report = inputs.reports[mode]
        val remaining = steps.count { !it.satisfied }
        val preferred = inputs.preferredReadMode == mode
        val nextAction = steps.firstOrNull { !it.satisfied }?.let { stepAction(it.id) }
            ?: if (!preferred) RouteActionId.SET_PREFERRED else null
        return RouteUi(
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
            preferred = preferred,
        )
    }

    private fun stepAction(step: RouteStepId): RouteActionId = when (step) {
        RouteStepId.SHIZUKU_INSTALLED -> RouteActionId.INSTALL_SHIZUKU
        RouteStepId.SHIZUKU_RUNNING -> RouteActionId.LAUNCH_SHIZUKU
        RouteStepId.SHIZUKU_AUTHORIZED -> RouteActionId.REQUEST_SHIZUKU_PERMISSION
        RouteStepId.READ_LOGS_GRANTED -> RouteActionId.COPY_ADB_READ_LOGS_COMMAND
        RouteStepId.OVERLAY_GRANTED -> RouteActionId.OPEN_OVERLAY_SETTINGS
        RouteStepId.BATTERY_UNRESTRICTED -> RouteActionId.OPEN_BATTERY_SETTINGS
    }

    // ---- Wording helpers -----------------------------------------------------------------

    fun modeTitle(mode: ClipboardReadMode): String = when (mode) {
        ClipboardReadMode.SHIZUKU_EVENT -> "特权直读 · Shizuku"
        ClipboardReadMode.ADB_LOG_OVERLAY -> "日志感知 + 悬浮窗"
        ClipboardReadMode.OVERLAY_POLLING -> "悬浮窗轮询"
        ClipboardReadMode.FOREGROUND_ONLY -> "前台/手动"
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
}

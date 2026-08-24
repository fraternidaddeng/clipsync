package com.clipsync.android.ui.conduit

import com.clipsync.android.platform.clipboard.CapabilityState
import com.clipsync.android.platform.clipboard.ClipboardReadMode
import com.clipsync.android.platform.clipboard.RoutePrerequisites

/**
 * The four segments of the conduit, in pipe order (charter §5.6): the journey of one clip is
 * `本机读取 → 本机服务 → 网络 → 剪贴板写入`, which is also the troubleshooting order.
 */
enum class ConduitSegmentId {
    READ,
    SERVICE,
    NETWORK,
    WRITE,
}

/**
 * Charter §5.5: one shape, five fills. `UNAVAILABLE` states a fact and must never look like an
 * error; `NEEDS_ACTION` is the only state that may beckon (ochre + pulse), and at most one
 * segment beckons at a time.
 */
enum class SegmentStatus {
    NEEDS_ACTION,
    READY,
    DEGRADED,
    UNAVAILABLE,
    UNPROBED,
}

enum class SegmentActionId {
    OPEN_WIZARD,
    TEST_READ,
    START_SERVICE,
    STOP_SERVICE,
    GO_PAIR,
    TEST_WRITE,
}

data class SegmentAction(
    val id: SegmentActionId,
    val label: String,
)

data class ConduitSegmentUi(
    val id: ConduitSegmentId,
    val title: String,
    val status: SegmentStatus,
    val summary: String,
    val detail: List<String>,
    val actions: List<SegmentAction>,
    /** Rendered in error color; reserved for true errors such as a certificate change. */
    val errorDetail: String? = null,
    /** True on at most one segment: the most upstream one whose status is NEEDS_ACTION. */
    val beckoning: Boolean = false,
)

/** The three background-read routes of the capability wizard (charter §4.1). */
enum class ReadRouteId {
    SHIZUKU,
    LOG_OVERLAY,
    OVERLAY_POLLING,
}

enum class RouteStepId {
    SHIZUKU_INSTALLED,
    SHIZUKU_RUNNING,
    SHIZUKU_AUTHORIZED,
    READ_LOGS_GRANTED,
    OVERLAY_GRANTED,
    BATTERY_UNRESTRICTED,
}

data class RouteStep(
    val id: RouteStepId,
    val label: String,
    val satisfied: Boolean,
)

/** What tapping the route's main button should do next; resolved to intents by the screen. */
enum class RouteActionId {
    INSTALL_SHIZUKU,
    LAUNCH_SHIZUKU,
    REQUEST_SHIZUKU_PERMISSION,
    COPY_ADB_READ_LOGS_COMMAND,
    OPEN_OVERLAY_SETTINGS,
    OPEN_BATTERY_SETTINGS,
    SET_PREFERRED,
}

data class RouteUi(
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
 * Everything the mapper needs to derive the conduit page. Pure data so the state mapping is
 * unit-testable without Android.
 */
data class ConduitInputs(
    val reports: Map<ClipboardReadMode, com.clipsync.android.platform.clipboard.CapabilityReport>,
    val prerequisites: RoutePrerequisites,
    val preferredReadMode: ClipboardReadMode,
    val paired: Boolean,
    val peerName: String?,
    val reachability: PeerReachability,
    val serviceRunning: Boolean,
    val serviceErrorCode: String?,
    val publicWriteState: CapabilityState,
    val publicWriteErrorCode: String?,
    val fallbackWriteState: CapabilityState,
) {
    companion object {
        fun initial(
            preferredReadMode: ClipboardReadMode,
            publicWriteState: CapabilityState,
        ) = ConduitInputs(
            reports = emptyMap(),
            prerequisites = RoutePrerequisites(),
            preferredReadMode = preferredReadMode,
            paired = false,
            peerName = null,
            reachability = PeerReachability.UNKNOWN,
            serviceRunning = false,
            serviceErrorCode = null,
            publicWriteState = publicWriteState,
            publicWriteErrorCode = null,
            fallbackWriteState = CapabilityState.UNAVAILABLE,
        )
    }
}

/** Transient outcome line of a 测试 button; never contains clipboard content. */
data class TestResult(
    val label: String,
    val success: Boolean,
)

data class ConduitUiState(
    val segments: List<ConduitSegmentUi>,
    val routes: List<RouteUi>,
    val preferredReadMode: ClipboardReadMode,
    val paired: Boolean,
    val peerName: String?,
    val refreshing: Boolean = false,
    val wizardOpen: Boolean = false,
    val testResult: TestResult? = null,
    val lastProbeAtMs: Long? = null,
)

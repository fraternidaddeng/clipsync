package com.clipsync.android.ui.health

import com.clipsync.android.R
import com.clipsync.android.i18n.UiText
import com.clipsync.android.pairing.PeerClipboardApply
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
    val label: UiText,
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
    val title: UiText,
    /** Filled dots out of 3 — the charter's quality column. */
    val quality: Int,
    val cost: UiText,
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
    /**
     * The peer's clipboard apply posture from the same `/v1/peer/health` probe; null while
     * unreachable or when the peer does not report (older build). Feeds 对端写入.
     */
    val peerClipboardApply: PeerClipboardApply? = null,
    /** Null = notification probe not wired; false = the surface is off right now. */
    val notificationsEnabled: Boolean? = null,
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
            add(UiText.Res(R.string.read_fact_route_state, readModeTitle(mode), readStateWord(facts.reports[mode])))
        }
        if (facts.reports[ClipboardReadMode.FOREGROUND_ONLY]?.readState == CapabilityState.READY) {
            add(UiText.Res(R.string.read_fact_foreground))
        }
        add(UiText.Res(R.string.read_fact_preferred, readModeTitle(facts.preferredReadMode)))
    }
    val ready = background.firstOrNull { it.readState == CapabilityState.READY }
    return when {
        background.isEmpty() -> ConduitSegmentState(
            statusLabel = UiText.Res(R.string.status_degraded_foreground),
            detail = UiText.Res(R.string.read_none_detail),
            status = ConduitStatus.DEGRADED,
            detailLines = detailLines,
        )
        ready != null -> ConduitSegmentState(
            statusLabel = UiText.Res(R.string.status_ready),
            detail = UiText.Res(R.string.read_ready_detail, readModeTitle(ready.readMode)),
            status = ConduitStatus.READY,
            detailLines = detailLines,
        )
        background.any { it.readState == CapabilityState.DEGRADED } -> ConduitSegmentState(
            statusLabel = UiText.Res(R.string.status_authorized_pending_test),
            detail = UiText.Res(R.string.localread_degraded_pending_detail),
            status = ConduitStatus.DEGRADED,
            detailLines = detailLines,
        )
        background.all { it.readState == CapabilityState.UNAVAILABLE } -> ConduitSegmentState(
            statusLabel = UiText.Res(R.string.status_needs_action),
            detail = UiText.Res(R.string.localread_needs_action_detail),
            status = ConduitStatus.NEEDS_ACTION,
            detailLines = detailLines,
        )
        else -> ConduitSegmentState(
            statusLabel = UiText.Res(R.string.status_unprobed),
            detail = UiText.Res(R.string.read_unknown_detail),
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
        add(UiText.Res(R.string.write_fact_public, capabilityWord(facts.publicWriteState)))
        facts.publicWriteErrorCode?.let { add(UiText.Res(R.string.write_fact_last_error, it)) }
        add(UiText.Res(R.string.write_fact_test_hygiene))
    }
    return when (facts.publicWriteState) {
        CapabilityState.READY -> ConduitSegmentState(
            statusLabel = UiText.Res(R.string.status_verified),
            detail = UiText.Res(R.string.write_ready_detail),
            status = ConduitStatus.READY,
            detailLines = detailLines,
        )
        CapabilityState.DEGRADED -> ConduitSegmentState(
            statusLabel = UiText.Res(R.string.status_limited),
            detail = UiText.Res(R.string.write_degraded_detail),
            status = ConduitStatus.DEGRADED,
            detailLines = detailLines,
        )
        CapabilityState.UNAVAILABLE -> ConduitSegmentState(
            statusLabel = UiText.Res(R.string.status_unavailable),
            detail = UiText.Res(R.string.write_unavailable_detail),
            status = ConduitStatus.UNAVAILABLE,
            detailLines = detailLines,
        )
        CapabilityState.UNKNOWN -> ConduitSegmentState(
            statusLabel = UiText.Res(R.string.status_untested),
            detail = UiText.Res(R.string.write_untested_detail),
            status = ConduitStatus.UNPROBED,
            detailLines = detailLines,
        )
        CapabilityState.NEEDS_USER_ACTION -> ConduitSegmentState(
            statusLabel = UiText.Res(R.string.status_needs_auth),
            detail = UiText.Res(R.string.write_needs_auth_detail),
            status = ConduitStatus.DEGRADED,
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
            title = UiText.Res(R.string.route_privileged),
            quality = 3,
            cost = UiText.Res(R.string.route_privileged_cost),
            steps = listOf(
                RouteStep(
                    RouteStepId.PRIVILEGED_CHANNEL_READY,
                    UiText.Res(R.string.step_privileged_channel),
                    p.shizukuInstalled && p.shizukuRunning,
                ),
                RouteStep(
                    RouteStepId.PRIVILEGED_AUTHORIZED,
                    UiText.Res(R.string.step_privileged_authorized),
                    p.shizukuAuthorized,
                ),
            ),
            facts = facts,
        ),
        readRoute(
            id = ReadRouteId.LOG_OVERLAY,
            mode = ClipboardReadMode.ADB_LOG_OVERLAY,
            title = UiText.Res(R.string.route_log_overlay),
            quality = 2,
            cost = UiText.Res(R.string.route_log_overlay_cost),
            steps = listOf(
                RouteStep(RouteStepId.READ_LOGS_GRANTED, UiText.Res(R.string.step_read_logs), p.readLogsGranted),
                RouteStep(RouteStepId.OVERLAY_GRANTED, UiText.Res(R.string.step_overlay), p.overlayGranted),
            ),
            facts = facts,
        ),
        readRoute(
            id = ReadRouteId.OVERLAY_POLLING,
            mode = ClipboardReadMode.OVERLAY_POLLING,
            title = UiText.Res(R.string.route_overlay_polling),
            quality = 1,
            cost = UiText.Res(R.string.route_overlay_polling_cost),
            steps = listOf(
                RouteStep(RouteStepId.OVERLAY_GRANTED, UiText.Res(R.string.step_overlay), p.overlayGranted),
                RouteStep(RouteStepId.BATTERY_UNRESTRICTED, UiText.Res(R.string.step_battery), p.batteryUnrestricted),
            ),
            facts = facts,
        ),
    )
}

private fun readRoute(
    id: ReadRouteId,
    mode: ClipboardReadMode,
    title: UiText,
    quality: Int,
    cost: UiText,
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

fun readModeTitle(mode: ClipboardReadMode): UiText = when (mode) {
    ClipboardReadMode.SHIZUKU_EVENT -> UiText.Res(R.string.route_privileged)
    ClipboardReadMode.ADB_LOG_OVERLAY -> UiText.Res(R.string.route_log_overlay)
    ClipboardReadMode.OVERLAY_POLLING -> UiText.Res(R.string.route_overlay_polling)
    ClipboardReadMode.FOREGROUND_ONLY -> UiText.Res(R.string.route_foreground)
}

fun routeActionLabel(action: RouteActionId): UiText = when (action) {
    RouteActionId.REQUEST_PRIVILEGED_PERMISSION -> UiText.Res(R.string.route_action_authorize)
    RouteActionId.COPY_ADB_READ_LOGS_COMMAND -> UiText.Res(R.string.route_action_copy_adb)
    RouteActionId.OPEN_OVERLAY_SETTINGS -> UiText.Res(R.string.route_action_overlay_settings)
    RouteActionId.OPEN_BATTERY_SETTINGS -> UiText.Res(R.string.route_action_battery_settings)
    RouteActionId.SET_PREFERRED -> UiText.Res(R.string.route_action_set_preferred)
    RouteActionId.RUN_READ_TEST -> UiText.Res(R.string.route_action_read_test)
}

private fun readStateWord(report: CapabilityReport?): UiText = when (report?.readState) {
    null -> UiText.Res(R.string.read_state_unprobed)
    CapabilityState.READY -> UiText.Res(R.string.read_state_ready)
    CapabilityState.DEGRADED ->
        UiText.Res(
            R.string.read_state_pending_test,
            report.errorCode ?: UiText.Res(R.string.read_state_unverified),
        )
    CapabilityState.UNAVAILABLE ->
        UiText.Res(
            R.string.read_state_blocked,
            report.errorCode ?: UiText.Res(R.string.read_state_reason_unknown),
        )
    CapabilityState.UNKNOWN -> UiText.Res(R.string.read_state_unprobed)
    CapabilityState.NEEDS_USER_ACTION ->
        UiText.Res(
            R.string.read_state_needs_auth,
            report.errorCode ?: UiText.Res(R.string.read_state_needs_user),
        )
}

private fun capabilityWord(state: CapabilityState): UiText = when (state) {
    CapabilityState.READY -> UiText.Res(R.string.capability_verified)
    CapabilityState.DEGRADED -> UiText.Res(R.string.capability_limited)
    CapabilityState.UNAVAILABLE -> UiText.Res(R.string.capability_unavailable)
    CapabilityState.UNKNOWN -> UiText.Res(R.string.capability_untested)
    CapabilityState.NEEDS_USER_ACTION -> UiText.Res(R.string.capability_needs_auth)
}

package com.clipsync.android.ui.health

import com.clipsync.android.R
import com.clipsync.android.i18n.UiText
import com.clipsync.android.platform.clipboard.CaptureGate
import com.clipsync.android.platform.clipboard.CaptureSessionStatus
import com.clipsync.android.platform.clipboard.ClipboardAccessState
import com.clipsync.android.platform.clipboard.ClipboardReadMode
import com.clipsync.android.platform.clipboard.ReadRouteShortfall
import com.clipsync.android.platform.clipboard.ReadRouteShortfallCause

/**
 * What the read ladder is doing right now, as the conduit states it: the route that actually
 * listens (not the one a probe would pick), why it is not the preferred one, since when, and
 * whether a user switch holds every backend closed. Pure data derived by [readRouteStatus] so the
 * wording is unit-testable without Android.
 */
data class ReadRouteStatus(
    /** Always-visible line under 本机读取. */
    val headline: UiText,
    /** Extra fact lines for the expanded card. */
    val facts: List<UiText>,
    /** The route currently listening; null when no backend runs. */
    val activeMode: ClipboardReadMode?,
    /** A backend runs, but below the preferred rung. */
    val degradedFromPreferred: Boolean,
    /** Nothing runs because this switch is on (the user's choice, a grey fact). */
    val stoppedByGate: CaptureGate?,
    /** A backend is running, so an upward re-probe could switch routes. */
    val recoverable: Boolean,
)

/**
 * Maps the coordinator's live state (plus the session's gate, when wired) to the conduit wording.
 * [formatClock] renders an epoch instant as a short wall-clock time (HH:mm) in the user's zone.
 */
fun readRouteStatus(
    access: ClipboardAccessState,
    session: CaptureSessionStatus?,
    formatClock: (Long) -> String,
): ReadRouteStatus {
    val gate = session?.gate?.takeIf { it != CaptureGate.OPEN && !session.running }
    val active = access.activeReadMode
    val shortfall = access.shortfall
    val headline =
        when {
            gate != null -> UiText.Res(R.string.read_live_stopped_by_gate, gateTitle(gate))
            active == null ->
                UiText.Res(
                    R.string.read_live_none,
                    ReadRouteReasons.phraseFor(access.lastErrorCode)
                        ?: UiText.Res(R.string.read_state_reason_unknown),
                )
            shortfall == null -> UiText.Res(R.string.read_live_active_preferred, readModeTitle(active))
            else -> degradedHeadline(active, access.requestedReadMode, shortfall, formatClock)
        }
    return ReadRouteStatus(
        headline = headline,
        facts = liveFacts(access, gate, formatClock),
        activeMode = if (gate == null) active else null,
        degradedFromPreferred = gate == null && active != null && shortfall != null,
        stoppedByGate = gate,
        recoverable = gate == null && (session?.running ?: (active != null)),
    )
}

private fun liveFacts(
    access: ClipboardAccessState,
    gate: CaptureGate?,
    formatClock: (Long) -> String,
): List<UiText> =
    buildList {
        if (gate == null && access.shortfall != null) {
            add(UiText.Res(R.string.read_fact_recovery_policy))
        }
        access.lastRecoveryAtEpochMillis?.let { at ->
            add(UiText.Res(R.string.read_fact_recovered_at, formatClock(at)))
        }
    }

/**
 * "Running X; preferred Y fell back at / not ready since HH:mm (reason)" — the two shortfall
 * causes. The reason is the human phrase for the shortfall's code; a code without one drops the
 * parenthetical instead of printing the constant.
 */
private fun degradedHeadline(
    active: ClipboardReadMode,
    requested: ClipboardReadMode,
    shortfall: ReadRouteShortfall,
    formatClock: (Long) -> String,
): UiText {
    val reason = ReadRouteReasons.phraseFor(shortfall.errorCode)
    val (withReason, plain) =
        when (shortfall.cause) {
            ReadRouteShortfallCause.HEALTH_FALLBACK ->
                R.string.read_live_degraded_fallback to R.string.read_live_degraded_fallback_plain
            ReadRouteShortfallCause.PREFERRED_NOT_READY ->
                R.string.read_live_degraded_not_ready to R.string.read_live_degraded_not_ready_plain
        }
    val args =
        listOfNotNull(readModeTitle(active), readModeTitle(requested), formatClock(shortfall.sinceEpochMillis), reason)
    return UiText.Res(if (reason == null) plain else withReason, args)
}

/** The preferences-page title of the switch behind [gate]; the same words the user flipped. */
fun gateTitle(gate: CaptureGate): UiText =
    when (gate) {
        CaptureGate.SYNC_PAUSED -> UiText.Res(R.string.prefs_pause_sync)
        CaptureGate.PRIVATE_MODE -> UiText.Res(R.string.prefs_private_mode)
        CaptureGate.CAPTURE_PAUSED -> UiText.Res(R.string.prefs_pause_capture)
        CaptureGate.OPEN -> UiText.Raw("")
    }

package com.clipsync.android.ui.prefs

import com.clipsync.android.R
import com.clipsync.android.i18n.UiText
import com.clipsync.android.platform.clipboard.CaptureGate
import com.clipsync.android.ui.health.readModeTitle

/** How a fact line under a switch is tinted: the charter's flow / ochre / grey vocabulary. */
enum class FactTone {
    /** In effect and healthy — flow blue, never green. */
    FLOW,

    /** The switch is on but something the user must do keeps it from taking effect — ochre. */
    ACT,

    /** A stated fact or the user's own choice — grey. */
    QUIET,
}

data class FactLine(
    val text: UiText,
    val tone: FactTone,
)

/**
 * The live fact under each switch that can differ from its position: null when the switch
 * position already tells the whole truth (or no runtime facts are wired).
 */
data class PreferencesStatusLines(
    val service: FactLine? = null,
    val pauseSync: FactLine? = null,
    val privateMode: FactLine? = null,
    val pauseCapture: FactLine? = null,
    val skipSensitive: FactLine? = null,
    val imageSync: FactLine? = null,
    val inboxNotify: FactLine? = null,
)

/**
 * Pure mapping from the preference positions plus [PreferencesRuntimeFacts] to the fact lines.
 * The rule per switch: state what the system is actually doing, name the reason when the
 * switch is on but not in effect, and use ochre only where the user can do something about it.
 */
fun preferencesStatusLines(state: PreferencesUiState): PreferencesStatusLines {
    val runtime = state.runtime ?: return PreferencesStatusLines()
    return PreferencesStatusLines(
        service = serviceLine(state.serviceEnabled, runtime),
        pauseSync =
            FactLine(UiText.Res(R.string.prefs_live_gate_sync_paused), FactTone.QUIET)
                .takeIf { state.pauseSync },
        privateMode =
            FactLine(UiText.Res(R.string.prefs_live_gate_private), FactTone.QUIET)
                .takeIf { state.privateMode },
        pauseCapture = captureLine(state.pauseCapture, runtime),
        skipSensitive = sensitiveLine(state.skipSensitive, runtime),
        imageSync = imageSyncLine(state.imageSync, runtime),
        inboxNotify = inboxNotifyLine(state.inboxNotify, runtime),
    )
}

private fun serviceLine(
    enabled: Boolean,
    runtime: PreferencesRuntimeFacts,
): FactLine =
    when {
        !enabled -> FactLine(UiText.Res(R.string.prefs_live_service_off), FactTone.QUIET)
        runtime.serviceRunning && runtime.connected ->
            FactLine(UiText.Res(R.string.prefs_live_service_connected), FactTone.FLOW)
        runtime.serviceRunning -> FactLine(UiText.Res(R.string.prefs_live_service_waiting), FactTone.QUIET)
        runtime.serviceStartErrorCode != null ->
            FactLine(
                UiText.Res(R.string.prefs_live_service_start_failed, runtime.serviceStartErrorCode),
                FactTone.ACT,
            )
        else -> FactLine(UiText.Res(R.string.prefs_live_service_not_running), FactTone.ACT)
    }

/**
 * Under 暂停自动捕获: while paused, the honest count is "nothing was read" (the backends stop),
 * so the line says exactly that instead of a zero; while running, the route that listens and
 * what it has captured this run.
 */
private fun captureLine(
    paused: Boolean,
    runtime: PreferencesRuntimeFacts,
): FactLine =
    when {
        paused -> FactLine(UiText.Res(R.string.prefs_live_gate_capture_paused), FactTone.QUIET)
        runtime.captureGate != CaptureGate.OPEN || !runtime.captureRunning || runtime.activeReadMode == null ->
            FactLine(UiText.Res(R.string.prefs_live_capture_stopped), FactTone.QUIET)
        else ->
            FactLine(
                UiText.Res(
                    R.string.prefs_live_capture_running,
                    readModeTitle(runtime.activeReadMode),
                    runtime.tally.captured,
                ),
                FactTone.FLOW,
            )
    }

private fun sensitiveLine(
    enabled: Boolean,
    runtime: PreferencesRuntimeFacts,
): FactLine? =
    when {
        !enabled -> null
        runtime.tally.skippedSensitive > 0 ->
            FactLine(
                UiText.Plural(R.plurals.prefs_live_skipped_sensitive, runtime.tally.skippedSensitive),
                FactTone.QUIET,
            )
        else -> FactLine(UiText.Res(R.string.prefs_live_skipped_sensitive_none), FactTone.QUIET)
    }

private fun imageSyncLine(
    enabled: Boolean,
    runtime: PreferencesRuntimeFacts,
): FactLine? =
    if (!enabled && runtime.tally.skippedImageSyncOff > 0) {
        FactLine(
            UiText.Plural(R.plurals.prefs_live_images_skipped, runtime.tally.skippedImageSyncOff),
            FactTone.QUIET,
        )
    } else {
        null
    }

private fun inboxNotifyLine(
    enabled: Boolean,
    runtime: PreferencesRuntimeFacts,
): FactLine? =
    if (enabled && runtime.systemNotificationsEnabled == false) {
        FactLine(UiText.Res(R.string.prefs_live_notifications_blocked), FactTone.ACT)
    } else {
        null
    }

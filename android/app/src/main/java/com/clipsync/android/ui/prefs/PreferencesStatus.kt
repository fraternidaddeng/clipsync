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
    val autoApply: FactLine? = null,
    val autoApplyImages: FactLine? = null,
    val imageSync: FactLine? = null,
    val inboxNotify: FactLine? = null,
    /** Under 蓝牙备援 (rendered on the conduit): the runtime permission the fallback needs is missing. */
    val bluetoothFallback: FactLine? = null,
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
        autoApply = autoApplyLine(state.autoApplyRemote, state.pauseSync, isImage = false, runtime),
        autoApplyImages = autoApplyLine(state.autoApplyImages, state.pauseSync, isImage = true, runtime),
        imageSync = imageSyncLine(state.imageSync, runtime),
        inboxNotify = inboxNotifyLine(state.inboxNotify, runtime),
        bluetoothFallback = bluetoothLine(state.bluetoothFallback, runtime),
    )
}

/**
 * Under 自动写入剪贴板 / 图片自动写入: the most recent real write of a received clip of that
 * kind — applied (flow) or failed with its error category (ochre). Nothing is said while the
 * switch is off, while 暂停同步 holds the write (that row already states the pause), or before
 * any clip of that kind was actually attempted.
 */
private fun autoApplyLine(
    enabled: Boolean,
    paused: Boolean,
    isImage: Boolean,
    runtime: PreferencesRuntimeFacts,
): FactLine? {
    val last = runtime.lastInboxApply?.takeIf { enabled && !paused && it.isImage == isImage }
    return when {
        last == null -> null
        last.applied -> FactLine(UiText.Res(R.string.prefs_live_auto_apply_applied), FactTone.FLOW)
        else ->
            FactLine(
                UiText.Res(R.string.prefs_live_auto_apply_failed_format, last.errorCode ?: "UNKNOWN"),
                FactTone.ACT,
            )
    }
}

/**
 * Under 蓝牙备援: on API 31+ the fallback cannot open a socket without BLUETOOTH_CONNECT, so an
 * enabled switch with the permission denied is ochre — the user can fix it in system settings.
 */
private fun bluetoothLine(
    enabled: Boolean,
    runtime: PreferencesRuntimeFacts,
): FactLine? =
    if (enabled && runtime.bluetoothPermissionGranted == false) {
        FactLine(UiText.Res(R.string.network_bt_permission_denied), FactTone.ACT)
    } else {
        null
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

/**
 * Under 图片同步: off states how many images stayed local; on, with a live IP session that the
 * PC only accepted on text-only v1 (the dialer tries v2 first and falls back when the PC's
 * listener refuses it because its own image sync is off), the line says the PC side is off.
 * A quiet fact, not ochre — the setting is the other device's, not something to fix here.
 */
private fun imageSyncLine(
    enabled: Boolean,
    runtime: PreferencesRuntimeFacts,
): FactLine? =
    when {
        !enabled && runtime.tally.skippedImageSyncOff > 0 ->
            FactLine(
                UiText.Plural(R.plurals.prefs_live_images_skipped, runtime.tally.skippedImageSyncOff),
                FactTone.QUIET,
            )
        enabled && runtime.ipSessionProtocolVersion == 1 ->
            FactLine(UiText.Res(R.string.prefs_live_image_sync_peer_text_only), FactTone.QUIET)
        else -> null
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

package com.clipsync.android.ui.health

/**
 * Wizard report-anchor line: privileged-host codes keep their dedicated hint;
 * every other closed read-route code falls back to [ReadRouteReasons.phraseFor]
 * so OVERLAY_PERMISSION_MISSING / READ_LOGS_NOT_GRANTED do not stand alone as
 * machine constants. Unknown codes still return null — the raw code below the
 * hint remains the report anchor either way.
 */
internal fun routeErrorHint(code: String) = PrivHostErrorHints.hintFor(code) ?: ReadRouteReasons.phraseFor(code)

package com.clipsync.android.ui.health

import com.clipsync.android.R
import com.clipsync.android.i18n.UiText
import com.clipsync.android.platform.clipboard.ShizukuClipboardBackend
import com.clipsync.android.platform.clipboard.shizuku.ShizukuErrorCodes

/**
 * One-line human hints for the closed set of 特权直读 (privileged host) error
 * codes: the seven stable PRIV_HOST_* / CLIPBOARD_BINDER_DEAD codes (plan 5.3,
 * [ShizukuErrorCodes]) plus the capability adapter's PRIVILEGED_* probe codes
 * ([ShizukuClipboardBackend]) that name the same conditions on route cards.
 *
 * The stable machine code stays visible next to the hint — it is the anchor
 * for bug reports and docs. The hint only says what the code means and which
 * move fixes it. A code outside the closed set gets no hint: inventing an
 * explanation for an unknown code would be a lie (charter: honesty first).
 */
object PrivHostErrorHints {
    fun hintFor(errorCode: String?): UiText? =
        when (errorCode) {
            ShizukuErrorCodes.NOT_INSTALLED,
            ShizukuClipboardBackend.ERROR_CHANNEL_MISSING,
            -> UiText.Res(R.string.priv_hint_not_installed)

            ShizukuErrorCodes.NOT_RUNNING,
            ShizukuClipboardBackend.ERROR_CHANNEL_OFFLINE,
            -> UiText.Res(R.string.priv_hint_not_running)

            ShizukuErrorCodes.NOT_AUTHORIZED,
            ShizukuClipboardBackend.ERROR_PERMISSION_DENIED,
            -> UiText.Res(R.string.priv_hint_not_authorized)

            ShizukuErrorCodes.BINDER_DEAD -> UiText.Res(R.string.priv_hint_binder_dead)

            ShizukuErrorCodes.USERSERVICE_DEAD -> UiText.Res(R.string.priv_hint_userservice_dead)

            ShizukuErrorCodes.CLIPBOARD_BINDER_DEAD ->
                UiText.Res(R.string.priv_hint_clipboard_binder_dead)

            ShizukuErrorCodes.API_MISMATCH -> UiText.Res(R.string.priv_hint_api_mismatch)

            ShizukuClipboardBackend.ERROR_READ_UNVERIFIED ->
                UiText.Res(R.string.priv_hint_read_unverified)

            else -> null
        }
}

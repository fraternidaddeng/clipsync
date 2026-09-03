package com.clipsync.android.ui.pairing

import com.clipsync.android.R
import com.clipsync.android.i18n.UiText
import com.clipsync.android.pairing.UnreachableReason

/**
 * The second paragraph under 无法连接到电脑: what the dominant connect failure most likely
 * means and what to do about it. A timeout is the firewall / AP-isolation case, and whether
 * the phone even shares the PC's network decides which of those to name; null means the
 * facts do not support a more specific statement, so nothing is added.
 */
fun unreachableHint(detail: UnreachableDetail): UiText? =
    when (detail.reason) {
        UnreachableReason.TIMEOUT ->
            when (detail.sameSubnet) {
                true -> UiText.Res(R.string.pairing_fail_unreachable_hint_timeout_same_subnet, detail.port)
                false -> UiText.Res(R.string.pairing_fail_unreachable_hint_timeout_other_subnet)
                null -> UiText.Res(R.string.pairing_fail_unreachable_hint_timeout_unknown, detail.port)
            }
        UnreachableReason.REFUSED -> UiText.Res(R.string.pairing_fail_unreachable_hint_refused)
        UnreachableReason.NO_ROUTE,
        UnreachableReason.HOST_UNREACHABLE,
        -> UiText.Res(R.string.pairing_fail_unreachable_hint_no_route)
        UnreachableReason.UNKNOWN -> null
    }

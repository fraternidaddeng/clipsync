package com.clipsync.android.ui.pairing

import com.clipsync.android.R
import com.clipsync.android.i18n.UiText
import com.clipsync.android.pairing.UnreachableReason

/**
 * The second paragraph under 无法连接到电脑: what the dominant connect failure most likely
 * means and what to do about it. A timeout is the firewall / AP-isolation case. Having heard
 * the PC's own beacon on this network is the strongest evidence there is (same broadcast
 * domain, PC awake) and names the firewall outright; otherwise whether the phone even shares
 * the PC's network decides which of those to name. Not hearing a beacon proves nothing —
 * guest networks and some ROMs drop broadcasts — so it never lowers the verdict. Null means
 * the facts do not support a more specific statement, so nothing is added.
 */
fun unreachableHint(detail: UnreachableDetail): UiText? =
    when (detail.reason) {
        UnreachableReason.TIMEOUT ->
            when {
                detail.beaconSeen -> UiText.Res(R.string.pairing_fail_unreachable_hint_timeout_beacon, detail.port)
                detail.sameSubnet == true ->
                    UiText.Res(R.string.pairing_fail_unreachable_hint_timeout_same_subnet, detail.port)
                detail.sameSubnet == false -> UiText.Res(R.string.pairing_fail_unreachable_hint_timeout_other_subnet)
                else -> UiText.Res(R.string.pairing_fail_unreachable_hint_timeout_unknown, detail.port)
            }
        UnreachableReason.REFUSED -> UiText.Res(R.string.pairing_fail_unreachable_hint_refused)
        UnreachableReason.NO_ROUTE,
        UnreachableReason.HOST_UNREACHABLE,
        -> UiText.Res(R.string.pairing_fail_unreachable_hint_no_route)
        UnreachableReason.UNKNOWN -> null
    }

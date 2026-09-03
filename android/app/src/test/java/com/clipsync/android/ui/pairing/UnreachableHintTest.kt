package com.clipsync.android.ui.pairing

import com.clipsync.android.R
import com.clipsync.android.i18n.UiText
import com.clipsync.android.pairing.UnreachableReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UnreachableHintTest {
    private fun detail(
        reason: UnreachableReason,
        sameSubnet: Boolean?,
    ) = UnreachableDetail(reason = reason, sameSubnet = sameSubnet, port = 47654)

    @Test
    fun `a timeout is explained by the subnet verdict and names the port when a firewall is suspected`() {
        assertEquals(
            UiText.Res(R.string.pairing_fail_unreachable_hint_timeout_same_subnet, 47654),
            unreachableHint(detail(UnreachableReason.TIMEOUT, sameSubnet = true)),
        )
        assertEquals(
            UiText.Res(R.string.pairing_fail_unreachable_hint_timeout_other_subnet),
            unreachableHint(detail(UnreachableReason.TIMEOUT, sameSubnet = false)),
        )
        assertEquals(
            UiText.Res(R.string.pairing_fail_unreachable_hint_timeout_unknown, 47654),
            unreachableHint(detail(UnreachableReason.TIMEOUT, sameSubnet = null)),
        )
    }

    @Test
    fun `refusal and routing failures have one hint each regardless of subnet`() {
        for (sameSubnet in listOf(true, false, null)) {
            assertEquals(
                UiText.Res(R.string.pairing_fail_unreachable_hint_refused),
                unreachableHint(detail(UnreachableReason.REFUSED, sameSubnet)),
            )
            assertEquals(
                UiText.Res(R.string.pairing_fail_unreachable_hint_no_route),
                unreachableHint(detail(UnreachableReason.NO_ROUTE, sameSubnet)),
            )
            assertEquals(
                UiText.Res(R.string.pairing_fail_unreachable_hint_no_route),
                unreachableHint(detail(UnreachableReason.HOST_UNREACHABLE, sameSubnet)),
            )
        }
    }

    @Test
    fun `an unknown reason adds nothing`() {
        assertNull(unreachableHint(detail(UnreachableReason.UNKNOWN, sameSubnet = true)))
        assertNull(unreachableHint(detail(UnreachableReason.UNKNOWN, sameSubnet = null)))
    }
}

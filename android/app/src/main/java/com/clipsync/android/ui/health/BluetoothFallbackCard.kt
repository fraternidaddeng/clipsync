package com.clipsync.android.ui.health

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clipsync.android.R
import com.clipsync.android.ui.prefs.BondedBluetoothDevice
import com.clipsync.android.ui.theme.ClipSyncType
import com.clipsync.android.ui.theme.charterCard
import com.clipsync.android.ui.theme.clipSyncColors

/**
 * 蓝牙备援 as rendered on the conduit, attached under the network segment
 * (settings-roadmap §四: the fallback changes whether content can reach the
 * peer, so it lives with pairing on the conduit rather than in 偏好). The
 * state stays in PreferencesViewModel — only the surface moved.
 */
data class BluetoothFallbackUi(
    val enabled: Boolean,
    /** 用户选定的蓝牙目标设备名；null 表示尚未选择（备援不会拨号）。 */
    val deviceName: String? = null,
)

@Composable
fun BluetoothFallbackCard(
    state: BluetoothFallbackUi,
    onEnabledChange: (Boolean) -> Unit,
    /** Bonded devices to choose from; null keeps the inline chooser collapsed. */
    devices: List<BondedBluetoothDevice>?,
    onRequestDevices: () -> Unit,
    onDeviceChosen: (BondedBluetoothDevice) -> Unit,
    onDismissDevices: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = clipSyncColors
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .charterCard(corner = 16.dp),
    ) {
        Row(
            // A11y (ui-gap-audit P3): the whole header row is the switch — one TalkBack stop
            // reads title, description and state together; the Switch below is display-only.
            modifier =
                Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = state.enabled,
                        role = Role.Switch,
                        onValueChange = onEnabledChange,
                    ).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(text = stringResource(R.string.bt_fallback_title), fontSize = 14.sp, color = c.t1)
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.bt_fallback_desc),
                    style = ClipSyncType.caption,
                    color = c.t3,
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = state.enabled,
                onCheckedChange = null,
                colors =
                    SwitchDefaults.colors(
                        checkedThumbColor = c.onFlow,
                        checkedTrackColor = c.flow,
                        uncheckedThumbColor = c.t4,
                        uncheckedTrackColor = c.sfIn,
                        uncheckedBorderColor = c.ln2,
                    ),
            )
        }
        if (state.enabled) {
            CardDivider()
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onRequestDevices)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(text = stringResource(R.string.bt_target_device), fontSize = 14.sp, color = c.t1)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text =
                            state.deviceName
                                ?.let { stringResource(R.string.bt_target_current, it) }
                                ?: stringResource(R.string.bt_target_none),
                        style = ClipSyncType.caption,
                        color = c.t3,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(text = "›", fontSize = 16.sp, color = c.flow)
            }
            if (devices != null) {
                BondedDeviceChooser(
                    devices = devices,
                    onChosen = onDeviceChosen,
                    onDismiss = onDismissDevices,
                )
            }
        }
    }
}

/**
 * Inline chooser for the fallback's dial target, rendered as plain rows inside
 * the same charter card (no dialogs in this app). An empty list states the
 * honest reasons instead of pretending the feature is broken.
 */
@Composable
private fun BondedDeviceChooser(
    devices: List<BondedBluetoothDevice>,
    onChosen: (BondedBluetoothDevice) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = clipSyncColors
    if (devices.isEmpty()) {
        CardDivider()
        Text(
            text = stringResource(R.string.bt_no_devices),
            style = ClipSyncType.caption,
            color = c.t3,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
        )
    } else {
        devices.forEach { device ->
            CardDivider()
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClick = { onChosen(device) })
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = device.name,
                    fontSize = 14.sp,
                    color = c.t1,
                    modifier = Modifier.weight(1f),
                )
                Text(text = device.address, style = ClipSyncType.caption, color = c.t4)
            }
        }
    }
    CardDivider()
    Text(
        text = stringResource(R.string.common_collapse),
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = c.flow,
        modifier =
            Modifier
                .clickable(onClick = onDismiss)
                .padding(horizontal = 14.dp, vertical = 10.dp),
    )
}

@Composable
private fun CardDivider() {
    Spacer(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .height(1.dp)
            .background(clipSyncColors.ln),
    )
}

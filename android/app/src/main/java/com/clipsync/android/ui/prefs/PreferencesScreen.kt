package com.clipsync.android.ui.prefs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clipsync.android.ui.theme.CharterShapes
import com.clipsync.android.ui.theme.ClipSyncTheme
import com.clipsync.android.ui.theme.ClipSyncType
import com.clipsync.android.ui.theme.charterCard
import com.clipsync.android.ui.theme.clipSyncColors

/**
 * 偏好: the key product commitments (product-scope.md) — pause sync, private
 * mode, auto apply, history expiry. State lives in [PreferencesViewModel] and
 * every change is persisted immediately; this screen only renders and reports.
 */
@Composable
fun PreferencesScreen(
    state: PreferencesUiState,
    onPauseSyncChange: (Boolean) -> Unit,
    onPrivateModeChange: (Boolean) -> Unit,
    onAutoApplyRemoteChange: (Boolean) -> Unit,
    onAutoExpireChange: (Boolean) -> Unit,
    onBootRestoreChange: (Boolean) -> Unit = {},
    onImageSyncChange: (Boolean) -> Unit = {},
    onAutoApplyImagesChange: (Boolean) -> Unit = {},
    onBluetoothFallbackChange: (Boolean) -> Unit = {},
    /** Bonded devices to choose from; null keeps the inline chooser collapsed. */
    bluetoothDevices: List<BondedBluetoothDevice>? = null,
    onRequestBluetoothDevices: () -> Unit = {},
    onBluetoothDeviceChosen: (BondedBluetoothDevice) -> Unit = {},
    onDismissBluetoothDevices: () -> Unit = {},
    /** Display name of the paired Windows peer; null while unpaired. */
    pairedDeviceName: String? = null,
    onOpenConduit: () -> Unit = {},
    onExportHistory: () -> Unit = {},
    onImportHistory: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val c = clipSyncColors
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            text = "偏好",
            style = ClipSyncType.pageTitle,
            color = c.t1,
            modifier = Modifier.padding(start = 2.dp, bottom = 14.dp),
        )

        GroupHeader("同步")
        Column(
            Modifier
                .fillMaxWidth()
                .charterCard(),
        ) {
            ToggleRow(
                title = "暂停同步",
                description = "暂停后两端不再交换内容，已有历史保留。",
                checked = state.pauseSync,
                onCheckedChange = onPauseSyncChange,
            )
            RowDivider()
            ToggleRow(
                title = "私密模式",
                description = "开启时本机复制的内容不离开这台设备。",
                checked = state.privateMode,
                onCheckedChange = onPrivateModeChange,
            )
            RowDivider()
            ToggleRow(
                title = "自动写入剪贴板",
                description = "收到对端内容时，优先自动写入本机剪贴板。",
                checked = state.autoApplyRemote,
                onCheckedChange = onAutoApplyRemoteChange,
            )
            RowDivider()
            ToggleRow(
                title = "图片同步",
                description = "同步复制或分享的 PNG/JPEG 图片（单张最大 16 MiB）。" +
                    "需要两端都开启；对新连接生效。",
                checked = state.imageSync,
                onCheckedChange = onImageSyncChange,
            )
            RowDivider()
            ToggleRow(
                title = "自动写入远端图片",
                description = "对端发来的图片自动写入本机剪贴板。关闭后只保留在历史中，需手动复制。",
                checked = state.autoApplyImages,
                onCheckedChange = onAutoApplyImagesChange,
            )
            RowDivider()
            ToggleRow(
                title = "开机恢复",
                description = "设备重启后自动恢复同步服务。若系统阻止启动，会以通知提醒你手动恢复。",
                checked = state.bootRestore,
                onCheckedChange = onBootRestoreChange,
            )
        }

        Spacer(Modifier.height(20.dp))
        GroupHeader("蓝牙备援")
        Column(
            Modifier
                .fillMaxWidth()
                .charterCard(),
        ) {
            ToggleRow(
                title = "蓝牙备援",
                description = "IP 路径全部不可达时（例如代理/VPN 全局接管），通过系统已配对的蓝牙设备" +
                    "继续同步文本。仅文本、速度较慢；IP 恢复后自动切回。",
                checked = state.bluetoothFallback,
                onCheckedChange = onBluetoothFallbackChange,
            )
            if (state.bluetoothFallback) {
                RowDivider()
                ActionRow(
                    title = "蓝牙目标设备",
                    description = state.bluetoothDeviceName
                        ?.let { "当前：$it" }
                        ?: "未选择 · 需先在系统设置里与电脑完成蓝牙配对",
                    onClick = onRequestBluetoothDevices,
                )
                if (bluetoothDevices != null) {
                    BondedDeviceChooser(
                        devices = bluetoothDevices,
                        onChosen = onBluetoothDeviceChosen,
                        onDismiss = onDismissBluetoothDevices,
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        GroupHeader("历史")
        Column(
            Modifier
                .fillMaxWidth()
                .charterCard(),
        ) {
            ToggleRow(
                title = "自动过期清理",
                description = "到期的历史条目自动删除，删除只作用于本机。",
                checked = state.autoExpire,
                onCheckedChange = onAutoExpireChange,
            )
            RowDivider()
            ValueRow(
                title = "保留时长",
                value = if (state.autoExpire) "${state.retentionDays} 天" else "永久保留",
            )
            RowDivider()
            ValueRow(title = "单条上限", value = "${formatByteCap(state.maxSyncTextBytes)} · 纯文本")
        }

        Spacer(Modifier.height(20.dp))
        GroupHeader("数据")
        Column(
            Modifier
                .fillMaxWidth()
                .charterCard(),
        ) {
            ActionRow(
                title = "导出历史",
                description = "把全部历史（含删除标记）写成 JSON Lines 备份文件；" +
                    "不含密钥与配对信息。导出内容为明文，请妥善保管。",
                onClick = onExportHistory,
            )
            RowDivider()
            ActionRow(
                title = "导入历史",
                description = "从备份文件合并历史：按「来源设备 + 序号」幂等去重，" +
                    "重复导入不产生重复条目。校验失败时不做任何改动。",
                onClick = onImportHistory,
            )
            if (state.transferStatus != null) {
                RowDivider()
                Text(
                    text = state.transferStatus,
                    style = ClipSyncType.caption,
                    color = c.t3,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        GroupHeader("设备")
        Column(
            Modifier
                .fillMaxWidth()
                .charterCard(),
        ) {
            if (pairedDeviceName != null) {
                ValueRow(title = "已配对设备", value = pairedDeviceName)
                RowDivider()
                LinkRow(title = "管理配对", value = "通路 · 网络", onClick = onOpenConduit)
            } else {
                DeviceEmptyState(onOpenConduit = onOpenConduit)
            }
        }

        Spacer(Modifier.height(28.dp))
        Text(
            text = "CLIPSYNC 0.1.0",
            style = ClipSyncType.groupHeader,
            color = c.t4,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
        )
    }
}

/** The cap is a power-of-two byte count (1 MiB by default); shown in the nearest whole unit. */
private fun formatByteCap(bytes: Int): String = when {
    bytes >= 1 shl 20 -> "${bytes / (1 shl 20)} MiB"
    bytes >= 1 shl 10 -> "${bytes / (1 shl 10)} KiB"
    else -> "$bytes B"
}

@Composable
private fun GroupHeader(text: String) {
    Text(
        text = text,
        style = ClipSyncType.groupHeader,
        color = clipSyncColors.t4,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
    )
}

@Composable
private fun RowDivider() {
    Spacer(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .height(1.dp)
            .background(clipSyncColors.ln),
    )
}

@Composable
private fun ToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val c = clipSyncColors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = title, fontSize = 14.sp, color = c.t1)
            Spacer(Modifier.height(2.dp))
            Text(text = description, style = ClipSyncType.caption, color = c.t3)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = c.onFlow,
                checkedTrackColor = c.flow,
                uncheckedThumbColor = c.t4,
                uncheckedTrackColor = c.sfIn,
                uncheckedBorderColor = c.ln2,
            ),
        )
    }
}

@Composable
private fun ValueRow(title: String, value: String) {
    val c = clipSyncColors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            fontSize = 14.sp,
            color = c.t1,
            modifier = Modifier.weight(1f),
        )
        Text(text = value, style = ClipSyncType.caption, color = c.t3)
    }
}

/**
 * A row that runs an action here (file pickers for 导出历史/导入历史): title and
 * honest description on the left, a flow-coloured chevron marking the tap target.
 */
@Composable
private fun ActionRow(title: String, description: String, onClick: () -> Unit) {
    val c = clipSyncColors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = title, fontSize = 14.sp, color = c.t1)
            Spacer(Modifier.height(2.dp))
            Text(text = description, style = ClipSyncType.caption, color = c.t3)
        }
        Spacer(Modifier.width(12.dp))
        Text(text = "›", fontSize = 16.sp, color = c.flow)
    }
}

/**
 * Inline chooser for the fallback's dial target, rendered as plain rows inside the same
 * charter card (no dialogs in this app). An empty list states the honest reasons instead
 * of pretending the feature is broken.
 */
@Composable
private fun BondedDeviceChooser(
    devices: List<BondedBluetoothDevice>,
    onChosen: (BondedBluetoothDevice) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = clipSyncColors
    if (devices.isEmpty()) {
        RowDivider()
        Text(
            text = "没有可选的已配对设备。请确认系统蓝牙已开启、连接权限已授予，" +
                "并先在系统设置里与电脑完成蓝牙配对。",
            style = ClipSyncType.caption,
            color = c.t3,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
        )
    } else {
        devices.forEach { device ->
            RowDivider()
            Row(
                modifier = Modifier
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
    RowDivider()
    Text(
        text = "收起",
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = c.flow,
        modifier = Modifier
            .clickable(onClick = onDismiss)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    )
}

/** A row that navigates elsewhere in the app; the chevron says so honestly. */
@Composable
private fun LinkRow(title: String, value: String, onClick: () -> Unit) {
    val c = clipSyncColors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            fontSize = 14.sp,
            color = c.t1,
            modifier = Modifier.weight(1f),
        )
        Text(text = value, style = ClipSyncType.caption, color = c.flow)
        Spacer(Modifier.width(4.dp))
        Text(text = "›", fontSize = 14.sp, color = c.t4)
    }
}

/**
 * The device section with nothing in it: a stated fact plus the pointer to
 * the real pairing entrance (charter: pairing hangs under 通路 · 网络).
 */
@Composable
private fun DeviceEmptyState(onOpenConduit: () -> Unit) {
    val c = clipSyncColors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(text = "尚无已配对设备", fontSize = 14.sp, color = c.t1)
        Text(
            text = "配对入口在「通路」页的网络段；配对后这里会显示对端名称。",
            style = ClipSyncType.caption,
            color = c.t3,
        )
        val shape = CharterShapes.control
        Text(
            text = "去配对 ›",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = c.flow,
            modifier = Modifier
                .clip(shape)
                .border(1.dp, c.flowLn, shape)
                .clickable(onClick = onOpenConduit)
                .padding(horizontal = 14.dp, vertical = 7.dp),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun PreferencesScreenPreview() {
    ClipSyncTheme {
        PreferencesScreen(
            state = PreferencesUiState(),
            onPauseSyncChange = {},
            onPrivateModeChange = {},
            onAutoApplyRemoteChange = {},
            onAutoExpireChange = {},
        )
    }
}

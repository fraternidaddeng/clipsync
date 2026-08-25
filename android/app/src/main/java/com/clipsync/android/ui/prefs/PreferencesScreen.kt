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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clipsync.android.storage.SyncSettingsStore
import com.clipsync.android.ui.theme.CharterShapes
import com.clipsync.android.ui.theme.ClipSyncTheme
import com.clipsync.android.ui.theme.ClipSyncType
import com.clipsync.android.ui.theme.charterCard
import com.clipsync.android.ui.theme.clipSyncColors

/**
 * 偏好: the key product commitments (product-scope.md) grouped per
 * settings-roadmap §4.1 — 显示 · 同步 · 捕获 · 历史 · 运行 · 数据 · 设备.
 * State lives in [PreferencesViewModel] and every change is persisted
 * immediately; this screen only renders and reports.
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
    /** Display name of the paired Windows peer; null while unpaired. */
    pairedDeviceName: String? = null,
    onOpenConduit: () -> Unit = {},
    onExportHistory: () -> Unit = {},
    onImportHistory: () -> Unit = {},
    onHistoryFontScaleChange: (Float) -> Unit = {},
    onPreviewLinesChange: (Int) -> Unit = {},
    onThemeOverrideChange: (String) -> Unit = {},
    onSkipSensitiveChange: (Boolean) -> Unit = {},
    onInboxNotifyChange: (Boolean) -> Unit = {},
    onRetentionDaysChange: (Int) -> Unit = {},
    onMaxEntriesChange: (Int) -> Unit = {},
    onClearHistory: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val c = clipSyncColors
    Column(
        modifier =
            modifier
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

        GroupHeader("显示")
        Column(
            Modifier
                .fillMaxWidth()
                .charterCard(),
        ) {
            ChoiceRow(
                title = "历史字号",
                description = "只缩放历史里的内容文字，界面其余部分不变；与系统字体大小叠加。",
                options =
                    listOf(
                        "小" to SyncSettingsStore.HISTORY_FONT_SCALE_SMALL,
                        "标准" to SyncSettingsStore.HISTORY_FONT_SCALE_STANDARD,
                        "大" to SyncSettingsStore.HISTORY_FONT_SCALE_LARGE,
                    ),
                selected = state.historyFontScale,
                onSelect = onHistoryFontScaleChange,
            )
            RowDivider()
            ChoiceRow(
                title = "预览行数",
                description = "历史列表每条内容最多显示的预览行数。",
                options = SyncSettingsStore.PREVIEW_LINE_CHOICES.map { "$it 行" to it },
                selected = state.previewLines,
                onSelect = onPreviewLinesChange,
            )
            RowDivider()
            // 外观（settings-roadmap P1-6）：只在两套既有配色之间选择，绝无取色器。
            ChoiceRow(
                title = "外观",
                description = "跟随系统或手动固定日间/夜间；配色始终是既有的两套，不可自定义。",
                options =
                    listOf(
                        "跟随系统" to SyncSettingsStore.THEME_SYSTEM,
                        "日间" to SyncSettingsStore.THEME_DAY,
                        "夜间" to SyncSettingsStore.THEME_NIGHT,
                    ),
                selected = state.themeOverride,
                onSelect = onThemeOverrideChange,
            )
        }

        Spacer(Modifier.height(20.dp))
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
                description =
                    "同步复制或分享的 PNG/JPEG 图片（单张最大 16 MiB）。" +
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
        }

        Spacer(Modifier.height(20.dp))
        // IA 迁移过渡（settings-roadmap §4.2）：蓝牙备援已迁往通路网络段，
        // 此链接行保留一个发布版本后删除。
        GroupHeader("蓝牙备援")
        Column(
            Modifier
                .fillMaxWidth()
                .charterCard(),
        ) {
            LinkRow(title = "蓝牙备援", value = "已移至通路 · 网络", onClick = onOpenConduit)
        }

        Spacer(Modifier.height(20.dp))
        GroupHeader("捕获")
        Column(
            Modifier
                .fillMaxWidth()
                .charterCard(),
        ) {
            ToggleRow(
                title = "跳过敏感内容",
                description =
                    "来源应用标记为敏感的复制（密码管理器等）不进历史、不同步。" +
                        "依赖来源应用打标记；通过分享面板主动发送不受此限制。",
                checked = state.skipSensitive,
                onCheckedChange = onSkipSensitiveChange,
            )
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
            StepperRow(
                title = "保留时长",
                value = if (state.autoExpire) "${state.retentionDays} 天" else "永久保留",
                enabled = state.autoExpire,
                canDecrement = state.retentionDays > SyncSettingsStore.MIN_RETENTION_DAYS,
                canIncrement = state.retentionDays < SyncSettingsStore.MAX_RETENTION_DAYS,
                onDecrement = { onRetentionDaysChange(state.retentionDays - 1) },
                onIncrement = { onRetentionDaysChange(state.retentionDays + 1) },
            )
            RowDivider()
            StepperRow(
                title = "保留条数",
                value = "${state.maxEntries} 条",
                enabled = true,
                canDecrement = state.maxEntries > SyncSettingsStore.MIN_MAX_ENTRIES,
                canIncrement = state.maxEntries < SyncSettingsStore.MAX_MAX_ENTRIES,
                onDecrement = { onMaxEntriesChange(state.maxEntries - MAX_ENTRIES_STEP) },
                onIncrement = { onMaxEntriesChange(state.maxEntries + MAX_ENTRIES_STEP) },
            )
            RowDivider()
            ValueRow(title = "单条上限", value = "${formatByteCap(state.maxSyncTextBytes)} · 纯文本")
        }

        Spacer(Modifier.height(20.dp))
        GroupHeader("运行")
        Column(
            Modifier
                .fillMaxWidth()
                .charterCard(),
        ) {
            ToggleRow(
                title = "开机恢复",
                description = "设备重启后自动恢复同步服务。若系统阻止启动，会以通知提醒你手动恢复。",
                checked = state.bootRestore,
                onCheckedChange = onBootRestoreChange,
            )
            RowDivider()
            ToggleRow(
                title = "收到内容通知",
                description = "收到对端内容时发出通知（永不含内容正文）。关闭后同步与历史照常，仅不再提醒。",
                checked = state.inboxNotify,
                onCheckedChange = onInboxNotifyChange,
            )
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
                description =
                    "把全部历史（含删除标记）写成 JSON Lines 备份文件；" +
                        "不含密钥与配对信息。导出内容为明文，请妥善保管。",
                onClick = onExportHistory,
            )
            RowDivider()
            ActionRow(
                title = "导入历史",
                description =
                    "从备份文件合并历史：按「来源设备 + 序号」幂等去重，" +
                        "重复导入不产生重复条目。校验失败时不做任何改动。",
                onClick = onImportHistory,
            )
            RowDivider()
            ClearHistoryRow(onClearHistory = onClearHistory)
            if (state.transferStatus != null) {
                RowDivider()
                Text(
                    text = state.transferStatus,
                    style = ClipSyncType.caption,
                    color = c.t3,
                    modifier =
                        Modifier
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
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
        )
    }
}

/** 保留条数 moves in hundreds — single-entry steps over a 100–10000 range are busywork. */
private const val MAX_ENTRIES_STEP = 100

/** The cap is a power-of-two byte count (1 MiB by default); shown in the nearest whole unit. */
private fun formatByteCap(bytes: Int): String =
    when {
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
        modifier =
            Modifier
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
}

/**
 * A titled row with an inline segmented choice (历史字号, 预览行数): every option
 * visible at once, the selected one on the flow tint — no hidden dropdown state.
 */
@Composable
private fun <T> ChoiceRow(
    title: String,
    description: String,
    options: List<Pair<String, T>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    val c = clipSyncColors
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(text = title, fontSize = 14.sp, color = c.t1)
        Spacer(Modifier.height(2.dp))
        Text(text = description, style = ClipSyncType.caption, color = c.t3)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val shape = CharterShapes.control
            options.forEach { (label, value) ->
                val isSelected = value == selected
                Text(
                    text = label,
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isSelected) c.flow else c.t3,
                    modifier =
                        Modifier
                            .clip(shape)
                            .background(if (isSelected) c.flowBg else c.sf3)
                            .border(1.dp, if (isSelected) c.flowLn else c.ln2, shape)
                            .clickable { onSelect(value) }
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/**
 * A titled row with −/+ steppers (保留时长, 保留条数) aligned with the Windows
 * preferences steppers. Disabled state (自动过期清理 off) greys the whole row and
 * states the fact in the value slot instead of hiding it.
 */
@Composable
private fun StepperRow(
    title: String,
    value: String,
    enabled: Boolean,
    canDecrement: Boolean,
    canIncrement: Boolean,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
) {
    val c = clipSyncColors
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            fontSize = 14.sp,
            color = if (enabled) c.t1 else c.t4,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = ClipSyncType.caption,
            color = if (enabled) c.t2 else c.t4,
        )
        Spacer(Modifier.width(10.dp))
        StepperButton(label = "−", enabled = enabled && canDecrement, onClick = onDecrement)
        Spacer(Modifier.width(6.dp))
        StepperButton(label = "+", enabled = enabled && canIncrement, onClick = onIncrement)
    }
}

@Composable
private fun StepperButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val c = clipSyncColors
    val shape = CharterShapes.control
    Text(
        text = label,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        color = if (enabled) c.t2 else c.t4,
        textAlign = TextAlign.Center,
        modifier =
            Modifier
                .clip(shape)
                .background(if (enabled) c.sf3 else c.sfIn)
                .border(1.dp, c.ln2, shape)
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 5.dp),
    )
}

/**
 * 清空历史 (settings-roadmap P0-5): two taps, both visible in place. The first
 * tap swaps the row for an inline confirmation that restates the consequences
 * (local-only, irreversible, export first) — grey facts, no red drama.
 */
@Composable
private fun ClearHistoryRow(onClearHistory: () -> Unit) {
    val c = clipSyncColors
    var confirming by remember { mutableStateOf(false) }
    if (!confirming) {
        ActionRow(
            title = "清空历史",
            description = "一次删除本机全部历史（含图片），不影响对端。建议先导出历史。",
            onClick = { confirming = true },
        )
    } else {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Text(text = "确定清空全部历史？", fontSize = 14.sp, color = c.t1)
            Spacer(Modifier.height(2.dp))
            Text(
                text = "删除仅作用于本机、无法撤销；对端的历史不受影响。建议先导出历史。",
                style = ClipSyncType.caption,
                color = c.t3,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val shape = CharterShapes.control
                Text(
                    text = "确认清空",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = c.t1,
                    modifier =
                        Modifier
                            .clip(shape)
                            .background(c.sfIn)
                            .border(1.dp, c.ln2, shape)
                            .clickable {
                                confirming = false
                                onClearHistory()
                            }
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                )
                Text(
                    text = "取消",
                    fontSize = 13.sp,
                    color = c.flow,
                    modifier =
                        Modifier
                            .clip(shape)
                            .border(1.dp, c.flowLn, shape)
                            .clickable { confirming = false }
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                )
            }
        }
    }
}

@Composable
private fun ValueRow(
    title: String,
    value: String,
) {
    val c = clipSyncColors
    Row(
        modifier =
            Modifier
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
private fun ActionRow(
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    val c = clipSyncColors
    Row(
        modifier =
            Modifier
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

/** A row that navigates elsewhere in the app; the chevron says so honestly. */
@Composable
private fun LinkRow(
    title: String,
    value: String,
    onClick: () -> Unit,
) {
    val c = clipSyncColors
    Row(
        modifier =
            Modifier
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
        modifier =
            Modifier
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
            modifier =
                Modifier
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

package com.clipsync.android.ui.prefs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clipsync.android.R
import com.clipsync.android.i18n.LanguageCatalog
import com.clipsync.android.i18n.string
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
    /** 语言 (P1#16): receives a catalog tag or [LanguageCatalog.FOLLOW_SYSTEM]. */
    onLanguageChange: (String) -> Unit = {},
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
            text = stringResource(R.string.prefs_title),
            style = ClipSyncType.pageTitle,
            color = c.t1,
            modifier = Modifier.padding(start = 2.dp, bottom = 14.dp),
        )

        GroupHeader(stringResource(R.string.prefs_group_display))
        Column(
            Modifier
                .fillMaxWidth()
                .charterCard(),
        ) {
            ChoiceRow(
                title = stringResource(R.string.prefs_history_font_size),
                description = stringResource(R.string.prefs_history_font_size_desc),
                options =
                    listOf(
                        stringResource(R.string.font_small) to SyncSettingsStore.HISTORY_FONT_SCALE_SMALL,
                        stringResource(R.string.font_standard) to SyncSettingsStore.HISTORY_FONT_SCALE_STANDARD,
                        stringResource(R.string.font_large) to SyncSettingsStore.HISTORY_FONT_SCALE_LARGE,
                    ),
                selected = state.historyFontScale,
                onSelect = onHistoryFontScaleChange,
            )
            RowDivider()
            ChoiceRow(
                title = stringResource(R.string.prefs_preview_lines),
                description = stringResource(R.string.prefs_preview_lines_desc),
                options =
                    SyncSettingsStore.PREVIEW_LINE_CHOICES.map {
                        stringResource(R.string.prefs_preview_lines_option, it) to it
                    },
                selected = state.previewLines,
                onSelect = onPreviewLinesChange,
            )
            RowDivider()
            // 外观（settings-roadmap P1-6）：只在两套既有配色之间选择，绝无取色器。
            ChoiceRow(
                title = stringResource(R.string.prefs_theme),
                description = stringResource(R.string.prefs_theme_desc),
                options =
                    listOf(
                        stringResource(R.string.theme_system) to SyncSettingsStore.THEME_SYSTEM,
                        stringResource(R.string.theme_day) to SyncSettingsStore.THEME_DAY,
                        stringResource(R.string.theme_night) to SyncSettingsStore.THEME_NIGHT,
                    ),
                selected = state.themeOverride,
                onSelect = onThemeOverrideChange,
            )
            RowDivider()
            LanguageRow(selectedTag = state.languageTag, onSelect = onLanguageChange)
        }

        Spacer(Modifier.height(20.dp))
        GroupHeader(stringResource(R.string.prefs_group_sync))
        Column(
            Modifier
                .fillMaxWidth()
                .charterCard(),
        ) {
            ToggleRow(
                title = stringResource(R.string.prefs_pause_sync),
                description = stringResource(R.string.prefs_pause_sync_desc),
                checked = state.pauseSync,
                onCheckedChange = onPauseSyncChange,
            )
            RowDivider()
            ToggleRow(
                title = stringResource(R.string.prefs_private_mode),
                description = stringResource(R.string.prefs_private_mode_desc),
                checked = state.privateMode,
                onCheckedChange = onPrivateModeChange,
            )
            RowDivider()
            ToggleRow(
                title = stringResource(R.string.prefs_auto_apply),
                description = stringResource(R.string.prefs_auto_apply_desc),
                checked = state.autoApplyRemote,
                onCheckedChange = onAutoApplyRemoteChange,
            )
            RowDivider()
            ToggleRow(
                title = stringResource(R.string.prefs_image_sync),
                description = stringResource(R.string.prefs_image_sync_desc),
                checked = state.imageSync,
                onCheckedChange = onImageSyncChange,
            )
            RowDivider()
            ToggleRow(
                title = stringResource(R.string.prefs_auto_apply_images),
                description = stringResource(R.string.prefs_auto_apply_images_desc),
                checked = state.autoApplyImages,
                onCheckedChange = onAutoApplyImagesChange,
            )
        }

        Spacer(Modifier.height(20.dp))
        // IA 迁移过渡（settings-roadmap §4.2）：蓝牙备援已迁往通路网络段，
        // 此链接行保留一个发布版本后删除。
        GroupHeader(stringResource(R.string.prefs_group_bt))
        Column(
            Modifier
                .fillMaxWidth()
                .charterCard(),
        ) {
            LinkRow(
                title = stringResource(R.string.bt_fallback_title),
                value = stringResource(R.string.prefs_bt_moved),
                onClick = onOpenConduit,
            )
        }

        Spacer(Modifier.height(20.dp))
        GroupHeader(stringResource(R.string.prefs_group_capture))
        Column(
            Modifier
                .fillMaxWidth()
                .charterCard(),
        ) {
            ToggleRow(
                title = stringResource(R.string.prefs_skip_sensitive),
                description = stringResource(R.string.prefs_skip_sensitive_desc),
                checked = state.skipSensitive,
                onCheckedChange = onSkipSensitiveChange,
            )
        }

        Spacer(Modifier.height(20.dp))
        GroupHeader(stringResource(R.string.prefs_group_history))
        Column(
            Modifier
                .fillMaxWidth()
                .charterCard(),
        ) {
            ToggleRow(
                title = stringResource(R.string.prefs_auto_expire),
                description = stringResource(R.string.prefs_auto_expire_desc),
                checked = state.autoExpire,
                onCheckedChange = onAutoExpireChange,
            )
            RowDivider()
            StepperRow(
                title = stringResource(R.string.prefs_retention_days),
                value =
                    if (state.autoExpire) {
                        stringResource(R.string.prefs_retention_days_value, state.retentionDays)
                    } else {
                        stringResource(R.string.prefs_retention_forever)
                    },
                enabled = state.autoExpire,
                canDecrement = state.retentionDays > SyncSettingsStore.MIN_RETENTION_DAYS,
                canIncrement = state.retentionDays < SyncSettingsStore.MAX_RETENTION_DAYS,
                onDecrement = { onRetentionDaysChange(state.retentionDays - 1) },
                onIncrement = { onRetentionDaysChange(state.retentionDays + 1) },
            )
            RowDivider()
            StepperRow(
                title = stringResource(R.string.prefs_max_entries),
                value = stringResource(R.string.prefs_max_entries_value, state.maxEntries),
                enabled = true,
                canDecrement = state.maxEntries > SyncSettingsStore.MIN_MAX_ENTRIES,
                canIncrement = state.maxEntries < SyncSettingsStore.MAX_MAX_ENTRIES,
                onDecrement = { onMaxEntriesChange(state.maxEntries - MAX_ENTRIES_STEP) },
                onIncrement = { onMaxEntriesChange(state.maxEntries + MAX_ENTRIES_STEP) },
            )
            RowDivider()
            ValueRow(
                title = stringResource(R.string.prefs_item_cap),
                value = stringResource(R.string.prefs_item_cap_value, formatByteCap(state.maxSyncTextBytes)),
            )
        }

        Spacer(Modifier.height(20.dp))
        GroupHeader(stringResource(R.string.prefs_group_run))
        Column(
            Modifier
                .fillMaxWidth()
                .charterCard(),
        ) {
            ToggleRow(
                title = stringResource(R.string.prefs_boot_restore),
                description = stringResource(R.string.prefs_boot_restore_desc),
                checked = state.bootRestore,
                onCheckedChange = onBootRestoreChange,
            )
            RowDivider()
            ToggleRow(
                title = stringResource(R.string.prefs_inbox_notify),
                description = stringResource(R.string.prefs_inbox_notify_desc),
                checked = state.inboxNotify,
                onCheckedChange = onInboxNotifyChange,
            )
        }

        Spacer(Modifier.height(20.dp))
        GroupHeader(stringResource(R.string.prefs_group_data))
        Column(
            Modifier
                .fillMaxWidth()
                .charterCard(),
        ) {
            ActionRow(
                title = stringResource(R.string.prefs_export),
                description = stringResource(R.string.prefs_export_desc),
                onClick = onExportHistory,
            )
            RowDivider()
            ActionRow(
                title = stringResource(R.string.prefs_import),
                description = stringResource(R.string.prefs_import_desc),
                onClick = onImportHistory,
            )
            RowDivider()
            ClearHistoryRow(onClearHistory = onClearHistory)
            if (state.transferStatus != null) {
                RowDivider()
                Text(
                    text = state.transferStatus.string(),
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
        GroupHeader(stringResource(R.string.prefs_group_device))
        Column(
            Modifier
                .fillMaxWidth()
                .charterCard(),
        ) {
            if (pairedDeviceName != null) {
                ValueRow(title = stringResource(R.string.prefs_paired_device), value = pairedDeviceName)
                RowDivider()
                LinkRow(
                    title = stringResource(R.string.prefs_manage_pairing),
                    value = stringResource(R.string.prefs_conduit_network),
                    onClick = onOpenConduit,
                )
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
        // A11y (ui-gap-audit P3): the whole row is the switch — TalkBack reads title,
        // description and state as one stop instead of an unlabeled bare switch, and the
        // touch target grows to the full row. The Switch below is display-only.
        modifier =
            Modifier
                .fillMaxWidth()
                .toggleable(
                    value = checked,
                    role = Role.Switch,
                    onValueChange = onCheckedChange,
                ).padding(horizontal = 14.dp, vertical = 12.dp),
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
}

/**
 * 语言 (settings-roadmap P1#16): 跟随系统 plus the 19 catalog languages as a
 * wrapping chip field. Every language shows its own endonym — never translated
 * (charter: a person hunting for their language must be able to recognise it).
 * The catalog is the single cross-platform authority; no list is invented here.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LanguageRow(
    selectedTag: String,
    onSelect: (String) -> Unit,
) {
    val c = clipSyncColors
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(text = stringResource(R.string.prefs_language), fontSize = 14.sp, color = c.t1)
        Spacer(Modifier.height(2.dp))
        Text(text = stringResource(R.string.prefs_language_desc), style = ClipSyncType.caption, color = c.t3)
        Spacer(Modifier.height(8.dp))
        val options =
            listOf(LanguageCatalog.FOLLOW_SYSTEM to stringResource(R.string.prefs_language_follow_system)) +
                LanguageCatalog.LANGUAGES.map { it.tag to it.nativeName }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val shape = CharterShapes.control
            options.forEach { (tag, label) ->
                val isSelected = tag == selectedTag
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
                            .clickable { onSelect(tag) }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
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
            title = stringResource(R.string.prefs_clear_history),
            description = stringResource(R.string.prefs_clear_history_desc),
            onClick = { confirming = true },
        )
    } else {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Text(text = stringResource(R.string.prefs_clear_confirm_title), fontSize = 14.sp, color = c.t1)
            Spacer(Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.prefs_clear_confirm_body),
                style = ClipSyncType.caption,
                color = c.t3,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val shape = CharterShapes.control
                Text(
                    text = stringResource(R.string.prefs_clear_confirm_action),
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
                            }.padding(horizontal = 14.dp, vertical = 7.dp),
                )
                Text(
                    text = stringResource(R.string.common_cancel),
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
        Text(text = stringResource(R.string.prefs_no_device), fontSize = 14.sp, color = c.t1)
        Text(
            text = stringResource(R.string.prefs_no_device_hint),
            style = ClipSyncType.caption,
            color = c.t3,
        )
        val shape = CharterShapes.control
        Text(
            text = stringResource(R.string.action_go_pair) + " ›",
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

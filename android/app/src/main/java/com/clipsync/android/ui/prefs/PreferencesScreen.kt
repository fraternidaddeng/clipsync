package com.clipsync.android.ui.prefs

import androidx.compose.foundation.background
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clipsync.android.ui.theme.ClipSyncTheme
import com.clipsync.android.ui.theme.ClipSyncType
import com.clipsync.android.ui.theme.charterCard
import com.clipsync.android.ui.theme.clipSyncColors

/**
 * 偏好: placeholder for the key product commitments (product-scope.md) —
 * pause sync, private mode, history expiry. Toggles hold local UI state only
 * until the sync engine lands in a later stage.
 */
@Composable
fun PreferencesScreen(modifier: Modifier = Modifier) {
    val c = clipSyncColors
    var pauseSync by rememberSaveable { mutableStateOf(false) }
    var privateMode by rememberSaveable { mutableStateOf(false) }
    var autoExpire by rememberSaveable { mutableStateOf(true) }
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
                checked = pauseSync,
                onCheckedChange = { pauseSync = it },
            )
            RowDivider()
            ToggleRow(
                title = "私密模式",
                description = "开启时本机复制的内容不离开这台设备。",
                checked = privateMode,
                onCheckedChange = { privateMode = it },
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
                checked = autoExpire,
                onCheckedChange = { autoExpire = it },
            )
            RowDivider()
            ValueRow(title = "单条上限", value = "1 MiB · 纯文本")
        }

        Spacer(Modifier.height(20.dp))
        GroupHeader("设备")
        Column(
            Modifier
                .fillMaxWidth()
                .charterCard(),
        ) {
            ValueRow(title = "已配对设备", value = "在「通路 · 网络」管理")
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

@Preview(showBackground = true)
@Composable
private fun PreferencesScreenPreview() {
    ClipSyncTheme {
        PreferencesScreen()
    }
}

package com.clipsync.android.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clipsync.android.ui.ConduitStatusBand
import com.clipsync.android.ui.HealthScreenState
import com.clipsync.android.ui.health.buildHealthScreenState
import com.clipsync.android.ui.theme.ClipSyncIcons
import com.clipsync.android.ui.theme.ClipSyncTheme
import com.clipsync.android.ui.theme.ClipSyncType
import com.clipsync.android.ui.theme.charterCard
import com.clipsync.android.ui.theme.charterSunken
import com.clipsync.android.ui.theme.clipSyncColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 一屏: the single screen where clipboard history lives. Entries stream from
 * [HomeViewModel] once the Room-backed history stage provides a source; until
 * then the empty state says so honestly.
 */
@Composable
fun HomeScreen(
    state: HealthScreenState,
    home: HomeUiState,
    onOpenConduit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = clipSyncColors
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 12.dp, start = 2.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "剪剪相传",
                style = ClipSyncType.pageTitle,
                color = c.t1,
                modifier = Modifier.weight(1f),
            )
        }
        ConduitStatusBand(state = state, onClick = onOpenConduit)
        Spacer(Modifier.height(12.dp))
        // Search slot (z−1 sunken face). Inert until history search exists.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .charterSunken(corner = 12.dp)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = ClipSyncIcons.Search,
                contentDescription = null,
                tint = c.t3,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(text = "搜索", fontSize = 13.sp, color = c.t4)
        }
        if (home.entries.isEmpty()) {
            EmptyHistory(
                historyAvailable = home.historyAvailable,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(home.entries, key = { it.id }) { entry ->
                    HistoryEntryCard(entry)
                }
            }
        }
    }
}

@Composable
private fun EmptyHistory(historyAvailable: Boolean, modifier: Modifier = Modifier) {
    val c = clipSyncColors
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = ClipSyncIcons.Conduit,
                contentDescription = null,
                tint = c.t4,
                modifier = Modifier.size(28.dp),
            )
            Text(
                text = "静候第一条剪贴",
                style = ClipSyncType.brand.copy(fontSize = 18.sp),
                color = c.t2,
            )
            Text(
                text = if (historyAvailable) {
                    "两端复制的内容会在这里汇合。"
                } else {
                    "配对完成后，两端复制的内容会在这里汇合。历史存储将在后续阶段接入。"
                },
                style = ClipSyncType.caption,
                color = c.t3,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun HistoryEntryCard(entry: HistoryEntry, modifier: Modifier = Modifier) {
    val c = clipSyncColors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .charterCard(corner = 16.dp)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(
            text = entry.preview,
            fontSize = 14.sp,
            color = c.t1,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (entry.fromThisDevice) "本机" else "对端",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (entry.fromThisDevice) c.t3 else c.flow,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = formatCapturedAt(entry.capturedAtMs),
                style = ClipSyncType.meta,
                color = c.t4,
            )
        }
    }
}

private val capturedAtFormat = DateTimeFormatter.ofPattern("MM-dd HH:mm")

private fun formatCapturedAt(epochMs: Long): String =
    Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).format(capturedAtFormat)

@Preview(showBackground = true)
@Composable
private fun HomeScreenPreview() {
    ClipSyncTheme {
        HomeScreen(
            state = buildHealthScreenState(peer = null, clipboard = null, sync = null),
            home = HomeUiState(historyAvailable = false),
            onOpenConduit = {},
        )
    }
}

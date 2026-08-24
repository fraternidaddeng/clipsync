package com.clipsync.android.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clipsync.android.ui.ConduitStatusBand
import com.clipsync.android.ui.HealthScreenState
import com.clipsync.android.ui.theme.ClipSyncIcons
import com.clipsync.android.ui.theme.ClipSyncTheme
import com.clipsync.android.ui.theme.ClipSyncType
import com.clipsync.android.ui.theme.charterSunken
import com.clipsync.android.ui.theme.clipSyncColors

/**
 * 一屏: the single screen where clipboard history will live. History itself
 * arrives with the sync engine in a later stage; today it shows the brand,
 * the 44dp conduit status band, and a serif empty state.
 */
@Composable
fun HomeScreen(
    state: HealthScreenState,
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
        // Search slot (z−1 sunken face). Inert until history exists.
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
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
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
                    text = "配对完成后，两端复制的内容会在这里汇合。",
                    style = ClipSyncType.caption,
                    color = c.t3,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun HomeScreenPreview() {
    ClipSyncTheme {
        HomeScreen(state = HealthScreenState.initial(), onOpenConduit = {})
    }
}

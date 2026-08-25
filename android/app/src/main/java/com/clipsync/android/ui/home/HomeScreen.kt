package com.clipsync.android.ui.home

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clipsync.android.ui.ConduitStatusBand
import com.clipsync.android.ui.HealthScreenState
import com.clipsync.android.ui.health.buildHealthScreenState
import com.clipsync.android.ui.theme.CharterMotion
import com.clipsync.android.ui.theme.CharterShapes
import com.clipsync.android.ui.theme.ClipSyncIcons
import com.clipsync.android.ui.theme.ClipSyncTheme
import com.clipsync.android.ui.theme.ClipSyncType
import com.clipsync.android.ui.theme.charterCard
import com.clipsync.android.ui.theme.charterSunken
import com.clipsync.android.ui.theme.clipSyncColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 一屏: clipboard history behind the 44dp conduit band. Tap copies (honestly —
 * the writer's real result shows), swipe left removes the local copy only.
 * Remote clips carry a neighbour-hue source box; locals carry no tag
 * (charter: 缺省即本地).
 */
@Composable
fun HomeScreen(
    conduit: HealthScreenState,
    home: HomeUiState,
    onQueryChange: (String) -> Unit,
    onCopy: (String) -> Unit,
    onDelete: (String) -> Unit,
    onOpenConduit: () -> Unit,
    modifier: Modifier = Modifier,
    onFormatFilterChange: (ClipContentFormat?) -> Unit = {},
    nowMs: () -> Long = System::currentTimeMillis,
    thumbnail: suspend (contentHash: String) -> Bitmap? = { null },
    /**
     * 历史字号 (settings-roadmap P0-1): scales only the preview body — the one place the
     * app shows someone else's content — on top of sp, so system font size still stacks.
     * Chrome (headers, meta, badges) keeps the charter type scale untouched.
     */
    historyFontScale: Float = 1f,
    /** 预览行数 (settings-roadmap P1-7): the preview body's maxLines, 2 / 4 / 6. */
    previewLines: Int = 4,
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
        ConduitStatusBand(state = conduit, onClick = onOpenConduit)
        Spacer(Modifier.height(12.dp))
        SearchField(query = home.query, onQueryChange = onQueryChange)
        Spacer(Modifier.height(8.dp))
        FormatFilterRow(selected = home.formatFilter, onSelect = onFormatFilterChange)
        // The strip slides in and out on the charter curve; the last notice is
        // kept so the exit animation has content to fade.
        var lastNotice by remember { mutableStateOf(home.notice) }
        if (home.notice != null) {
            lastNotice = home.notice
        }
        AnimatedVisibility(
            visible = home.notice != null,
            enter = fadeIn(CharterMotion.spec(CharterMotion.DUR_QUICK_MS)) +
                expandVertically(CharterMotion.spec(CharterMotion.DUR_STANDARD_MS)),
            exit = fadeOut(CharterMotion.spec(CharterMotion.DUR_QUICK_MS)) +
                shrinkVertically(CharterMotion.spec(CharterMotion.DUR_STANDARD_MS)),
        ) {
            lastNotice?.let { notice ->
                Column {
                    Spacer(Modifier.height(8.dp))
                    NoticeStrip(notice)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        when {
            !home.loaded -> Box(Modifier.weight(1f))
            home.items.isEmpty() && home.searchActive -> NoMatchState(
                message = "没有匹配「${home.query.trim()}」的记录",
                modifier = Modifier.weight(1f),
            )
            home.items.isEmpty() && home.formatFilter != null -> NoMatchState(
                message = "没有「${formatLabel(home.formatFilter)}」类的记录",
                modifier = Modifier.weight(1f),
            )
            home.items.isEmpty() -> EmptyState(
                paired = conduit.pairedDeviceCount > 0,
                onPair = onOpenConduit,
                modifier = Modifier.weight(1f),
            )
            else -> LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(home.items, key = { it.eventId }) { item ->
                    DismissableClipCard(
                        item = item,
                        nowMs = nowMs,
                        thumbnail = thumbnail,
                        fontScale = historyFontScale,
                        previewLines = previewLines,
                        onCopy = { onCopy(item.eventId) },
                        onDelete = { onDelete(item.eventId) },
                        modifier = Modifier.animateItem(
                            fadeInSpec = CharterMotion.spec(CharterMotion.DUR_STANDARD_MS),
                            placementSpec = CharterMotion.spec(CharterMotion.DUR_STANDARD_MS),
                            fadeOutSpec = CharterMotion.spec(CharterMotion.DUR_QUICK_MS),
                        ),
                    )
                }
                item { Spacer(Modifier.height(4.dp)) }
            }
        }
    }
}

/** z−1 sunken search input, wired to the repository query (debounced upstream). */
@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = clipSyncColors
    Row(
        modifier = modifier
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
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(text = "搜索", fontSize = 13.sp, color = c.t4)
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(fontSize = 13.sp, color = c.t1),
                cursorBrush = SolidColor(c.flow),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = "×",
                fontSize = 15.sp,
                color = c.t3,
                modifier = Modifier.clickable { onQueryChange("") },
            )
        }
    }
}

/** Chip label for a format (ADR 0003 词汇); PLAIN reads 文本 on the chip row. */
internal fun formatLabel(format: ClipContentFormat?): String = when (format) {
    null -> "全部"
    ClipContentFormat.LINK -> "链接"
    ClipContentFormat.OTP -> "验证码"
    ClipContentFormat.EMAIL -> "账号"
    ClipContentFormat.CREDENTIAL -> "密码"
    ClipContentFormat.PLAIN -> "文本"
}

/**
 * Fixed neighbour-hue slot per format (ADR 0003): the badge borrows the
 * annotation chroma tier (tokens.md §4), never the state colours — a
 * credential is a fact to find again, not an alarm, so no red anywhere.
 * PLAIN carries no badge (缺省即普通文本).
 */
internal fun formatAccentSlot(format: ClipContentFormat): Int? = when (format) {
    ClipContentFormat.EMAIL -> 1 // 青灰
    ClipContentFormat.LINK -> 2 // 水蓝
    ClipContentFormat.OTP -> 3 // 蓝紫
    ClipContentFormat.CREDENTIAL -> 4 // 藕紫
    ClipContentFormat.PLAIN -> null
}

/**
 * The format chips: 全部 plus the five formats, single-select, scrollable.
 * Selected = flow-blue tinted box (same vocabulary as nav selection);
 * unselected chips stay in the quiet grey family.
 */
@Composable
private fun FormatFilterRow(
    selected: ClipContentFormat?,
    onSelect: (ClipContentFormat?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = clipSyncColors
    val options = listOf(
        null,
        ClipContentFormat.LINK,
        ClipContentFormat.OTP,
        ClipContentFormat.EMAIL,
        ClipContentFormat.CREDENTIAL,
        ClipContentFormat.PLAIN,
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        options.forEach { option ->
            val active = option == selected
            val shape = CharterShapes.control
            Text(
                text = formatLabel(option),
                fontSize = 12.sp,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                color = if (active) c.flow else c.t3,
                modifier = Modifier
                    .clip(shape)
                    .background(if (active) c.flowBg else c.sf3)
                    .border(1.dp, if (active) c.flowLn else c.ln, shape)
                    .clickable { onSelect(option) }
                    .padding(horizontal = 11.dp, vertical = 5.dp),
            )
        }
    }
}

/** Honest action feedback: state colours only, red strictly for failures. */
@Composable
private fun NoticeStrip(notice: HomeNotice, modifier: Modifier = Modifier) {
    val c = clipSyncColors
    val (text, fg, bg, ln) = when (notice) {
        HomeNotice.Copied ->
            NoticeStyle("已写入系统剪贴板", c.flow, c.flowBg, c.flowLn)
        is HomeNotice.CopyFailed ->
            NoticeStyle("复制失败 · ${notice.errorCode}", c.err, c.errBg, c.errLn)
        HomeNotice.DeletedLocal ->
            NoticeStyle("已从本机移除 · 不影响其他设备", c.t2, c.sf3, c.ln)
    }
    val shape = CharterShapes.control
    Text(
        text = text,
        fontSize = 12.sp,
        color = fg,
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(bg)
            .border(1.dp, ln, shape)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

private data class NoticeStyle(
    val text: String,
    val fg: Color,
    val bg: Color,
    val ln: Color,
)

@Composable
private fun DismissableClipCard(
    item: HomeClipItem,
    nowMs: () -> Long,
    thumbnail: suspend (String) -> Bitmap?,
    fontScale: Float,
    previewLines: Int,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = clipSyncColors
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onDelete()
                true
            } else {
                false
            }
        },
    )
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        modifier = modifier,
        backgroundContent = {
            // Removal is a stated fact, not an error: grey face, honest copy.
            val shape = CharterShapes.card
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(shape)
                    .background(c.sfIn)
                    .padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "移除 · 仅本机", fontSize = 12.sp, color = c.t3)
            }
        },
    ) {
        ClipCard(
            item = item,
            nowMs = nowMs,
            thumbnail = thumbnail,
            fontScale = fontScale,
            previewLines = previewLines,
            onCopy = onCopy,
        )
    }
}

/**
 * One history card: header row (source tag / time), then the preview body —
 * text clips show the collapsed preview, image clips a bounded thumbnail.
 * z1 charter face — sh-1 shadow, top light, hairline (tokens.md §8).
 */
@Composable
private fun ClipCard(
    item: HomeClipItem,
    nowMs: () -> Long,
    thumbnail: suspend (String) -> Bitmap?,
    fontScale: Float,
    previewLines: Int,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = clipSyncColors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .charterCard()
            .clickable(onClick = onCopy)
            .padding(horizontal = 13.dp, vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val source = item.remoteSourceLabel
            if (source != null) {
                SourceTag(label = source, accentSlot = item.sourceAccentSlot)
            }
            if (item.isImage) {
                ImageBadge()
            } else {
                FormatBadge(format = item.format)
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = remember(item.createdAtMs) { clipTimeLabel(item.createdAtMs, nowMs()) },
                style = ClipSyncType.meta,
                fontSize = 10.sp,
                color = c.t4,
            )
        }
        Spacer(Modifier.height(5.dp))
        if (item.isImage) {
            ClipThumbnail(contentHash = item.contentHash, thumbnail = thumbnail)
        } else {
            // 历史字号 scales only this body (someone else's content), on top of sp so the
            // system font size still stacks; chrome above keeps the charter scale.
            Text(
                text = item.preview,
                fontSize = 13.sp * fontScale,
                lineHeight = 19.sp * fontScale,
                color = c.t2,
                maxLines = previewLines,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Bounded history thumbnail (512px cap upstream, ≤120dp here). Loads off the
 * main thread keyed by blob hash; while absent, a quiet grey stand-in states
 * the fact instead of flashing an error.
 */
@Composable
private fun ClipThumbnail(
    contentHash: String,
    thumbnail: suspend (String) -> Bitmap?,
    modifier: Modifier = Modifier,
) {
    val c = clipSyncColors
    val bitmap by produceState<ImageBitmap?>(initialValue = null, contentHash) {
        value = withContext(Dispatchers.IO) {
            if (contentHash.isEmpty()) null else thumbnail(contentHash)?.asImageBitmap()
        }
    }
    val shape = RoundedCornerShape(8.dp)
    val preview = bitmap
    if (preview != null) {
        Image(
            bitmap = preview,
            contentDescription = "图片剪贴",
            modifier = modifier
                .heightIn(max = 120.dp)
                .clip(shape)
                .border(1.dp, c.ln2, shape),
            contentScale = ContentScale.Fit,
        )
    } else {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(72.dp)
                .clip(shape)
                .background(c.sf3)
                .border(1.dp, c.ln2, shape),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "图片", fontSize = 11.sp, color = c.t4)
        }
    }
}

/**
 * Neighbour-hue source box (charter: 来源标记 = 低彩度着色盒). Only remote
 * clips get one. The hue is the device's effective slot through the charter
 * ladder (tokens.md §4: slot 1 青灰 … slot 5 灰粉) — pairing order by default,
 * a manual per-device choice when one was made (P1#14), never a hash;
 * a remote without a slot states the fact in grey instead.
 */
@Composable
private fun SourceTag(label: String, accentSlot: Int?, modifier: Modifier = Modifier) {
    val c = clipSyncColors
    val tone = accentSlot?.let { c.device(it) } ?: c.t3
    val boxBg = accentSlot?.let { c.deviceBg(it) } ?: c.sf3
    val boxLn = accentSlot?.let { c.deviceLn(it) } ?: c.ln2
    val shape = RoundedCornerShape(6.dp)
    Row(
        modifier = modifier
            .clip(shape)
            .background(boxBg)
            .border(1.dp, boxLn, shape)
            .padding(horizontal = 7.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = ClipSyncIcons.Monitor,
            contentDescription = "来自",
            tint = tone,
            modifier = Modifier.size(10.dp),
        )
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            color = tone,
        )
    }
}

/**
 * Format tag (ADR 0003): the same low-chroma annotation box as the source
 * tag, but on a fixed neighbour-hue slot per format. Plain text renders
 * nothing — a quiet card is the default, tags mark the exceptions.
 */
@Composable
private fun FormatBadge(format: ClipContentFormat, modifier: Modifier = Modifier) {
    val slot = formatAccentSlot(format) ?: return
    val c = clipSyncColors
    val shape = RoundedCornerShape(6.dp)
    Text(
        text = formatLabel(format),
        fontSize = 10.sp,
        fontWeight = FontWeight.Medium,
        color = c.device(slot),
        modifier = modifier
            .clip(shape)
            .background(c.deviceBg(slot))
            .border(1.dp, c.deviceLn(slot), shape)
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

/**
 * Image clip tag: the same low-chroma annotation box on the last neighbour-hue
 * slot (灰粉) — image is a content kind, not a text format, so it sits outside
 * the ADR 0003 ladder.
 */
@Composable
private fun ImageBadge(modifier: Modifier = Modifier) {
    val c = clipSyncColors
    val shape = RoundedCornerShape(6.dp)
    Text(
        text = "图片",
        fontSize = 10.sp,
        fontWeight = FontWeight.Medium,
        color = c.device(IMAGE_BADGE_SLOT),
        modifier = modifier
            .clip(shape)
            .background(c.deviceBg(IMAGE_BADGE_SLOT))
            .border(1.dp, c.deviceLn(IMAGE_BADGE_SLOT), shape)
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

private const val IMAGE_BADGE_SLOT = 5

/**
 * The history empty state — one of the app's serif moments (charter 3.5).
 * Unpaired, it is also the first-run landing: a serif short line plus the
 * ghost pairing button the audit calls for; paired, it just waits quietly.
 */
@Composable
private fun EmptyState(
    paired: Boolean,
    onPair: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = clipSyncColors
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
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
                text = if (paired) "静候第一条剪贴" else "先把两端接起来",
                style = ClipSyncType.brand.copy(fontSize = 18.sp),
                color = c.t2,
            )
            Text(
                text = if (paired) {
                    "在任意一端复制文本，它会出现在这里并流向对面。"
                } else {
                    "尚未与电脑配对。配对入口在「通路」页的网络段。"
                },
                style = ClipSyncType.caption,
                color = c.t3,
                textAlign = TextAlign.Center,
            )
            if (!paired) {
                val shape = CharterShapes.control
                Text(
                    text = "去配对 ›",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = c.flow,
                    modifier = Modifier
                        .clip(shape)
                        .border(1.dp, c.flowLn, shape)
                        .clickable(onClick = onPair)
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                )
            }
        }
    }
}

@Composable
private fun NoMatchState(message: String, modifier: Modifier = Modifier) {
    val c = clipSyncColors
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = ClipSyncIcons.Search,
                contentDescription = null,
                tint = c.t4,
                modifier = Modifier.size(22.dp),
            )
            Text(
                text = message,
                style = ClipSyncType.caption,
                color = c.t3,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun HomeScreenEmptyPreview() {
    ClipSyncTheme {
        HomeScreen(
            conduit = buildHealthScreenState(peer = null, clipboard = null, sync = null),
            home = HomeUiState(loaded = true),
            onQueryChange = {},
            onCopy = {},
            onDelete = {},
            onOpenConduit = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun HomeScreenListPreview() {
    ClipSyncTheme {
        HomeScreen(
            conduit = buildHealthScreenState(peer = null, clipboard = null, sync = null),
            home = HomeUiState(
                loaded = true,
                items = listOf(
                    HomeClipItem(
                        eventId = "e1",
                        preview = "https://github.com/clipsync/core",
                        createdAtMs = System.currentTimeMillis() - 240_000,
                        remoteSourceLabel = "PC-STUDIO",
                        sourceAccentSlot = 1,
                        format = ClipContentFormat.LINK,
                    ),
                    HomeClipItem(
                        eventId = "e2",
                        preview = "pwsh .\\scripts\\build-windows.ps1",
                        createdAtMs = System.currentTimeMillis() - 2_040_000,
                        remoteSourceLabel = null,
                    ),
                    HomeClipItem(
                        eventId = "e3",
                        preview = "【剪剪相传】验证码 843921，五分钟内有效。",
                        createdAtMs = System.currentTimeMillis() - 3_000_000,
                        remoteSourceLabel = null,
                        format = ClipContentFormat.OTP,
                    ),
                    HomeClipItem(
                        eventId = "e4",
                        preview = "会议纪要：本周五完成 Stage 4 端到端握手测试",
                        createdAtMs = System.currentTimeMillis() - 90_000_000,
                        remoteSourceLabel = "PC-STUDIO",
                        sourceAccentSlot = 1,
                    ),
                ),
            ),
            onQueryChange = {},
            onCopy = {},
            onDelete = {},
            onOpenConduit = {},
        )
    }
}

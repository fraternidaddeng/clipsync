@file:Suppress("ktlint:standard:function-naming")

package com.clipsync.android.ui.history

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.clipsync.android.R
import com.clipsync.android.media.ImageThumbnail
import com.clipsync.android.ui.settings.ClipServices
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun HistoryScreen(
    viewModel: HistoryViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    HistoryBody(state = state, viewModel = viewModel, modifier = modifier)
    val selected = state.selectedItem
    if (selected != null) {
        HistoryDetailDialog(
            item = selected,
            onCopy = { viewModel.copy(selected.eventId) },
            onDelete = { viewModel.delete(selected.eventId) },
            onClose = viewModel::closeDetail,
        )
    }
}

@Suppress("FunctionNaming") // Compose requires PascalCase identifiers.
@Composable
private fun HistoryBody(
    state: HistoryUiState,
    viewModel: HistoryViewModel,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.history_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        OutlinedTextField(
            value = state.query,
            onValueChange = viewModel::search,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(stringResource(R.string.history_search)) },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = viewModel::clear) {
                Text(stringResource(R.string.history_clear))
            }
        }
        Text(
            text = stringResource(R.string.history_clear_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(modifier = Modifier.weight(1f)) {
            HistoryNotices(state)
            HistoryClipList(
                state = state,
                viewModel = viewModel,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Suppress("FunctionNaming") // Compose requires PascalCase identifiers.
@Composable
private fun HistoryNotices(state: HistoryUiState) {
    state.notices.forEach { notice ->
        NoticeCard(notice)
    }
    if (state.copyFailed) {
        NoticeCard(message = stringResource(R.string.history_copy_failed), warning = true)
    }
}

@Suppress("FunctionNaming") // Compose requires PascalCase identifiers.
@Composable
private fun HistoryClipList(
    state: HistoryUiState,
    viewModel: HistoryViewModel,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val newestEventId = state.items.firstOrNull()?.eventId
    LaunchedEffect(newestEventId) {
        if (newestEventId != null) {
            listState.scrollToItem(0)
        }
    }
    if (!state.empty) {
        LazyColumn(
            modifier = modifier,
            state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.items, key = { it.eventId }) { item ->
                HistoryRow(
                    item = item,
                    onOpen = { viewModel.openDetail(item.eventId) },
                    onCopy = { viewModel.copy(item.eventId) },
                    onDelete = { viewModel.delete(item.eventId) },
                )
            }
        }
    }
}

@Composable
private fun NoticeCard(notice: HistoryNotice) {
    val (message, warning) =
        when (notice) {
            HistoryNotice.EMPTY -> stringResource(R.string.history_empty) to false
            HistoryNotice.OVERSIZED -> stringResource(R.string.history_oversized) to true
            HistoryNotice.UNPAIRED -> stringResource(R.string.history_unpaired) to true
            HistoryNotice.WINDOWS_UNREACHABLE -> stringResource(R.string.history_windows_unreachable) to true
        }
    NoticeCard(message = message, warning = warning)
}

@Suppress("FunctionNaming") // Compose requires PascalCase identifiers.
@Composable
private fun NoticeCard(
    message: String,
    warning: Boolean,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (warning) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
            ),
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Suppress("FunctionNaming") // Compose requires PascalCase identifiers.
@Composable
private fun HistoryRow(
    item: HistoryItemUi,
    onOpen: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpen),
            ) {
                if (item.isImage) {
                    HistoryImageThumb(
                        item = item,
                        modifier =
                            Modifier
                                .size(HISTORY_THUMB_SIZE)
                                .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Crop,
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = historyPreview(item), style = MaterialTheme.typography.bodyLarge)
                    item.sourceApp?.let { source ->
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = source,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Row {
                TextButton(onClick = onCopy) {
                    Text(stringResource(R.string.history_copy))
                }
                TextButton(onClick = onDelete) {
                    Text(stringResource(R.string.history_delete))
                }
            }
        }
    }
}

@Suppress("FunctionNaming") // Compose requires PascalCase identifiers.
@Composable
private fun HistoryDetailDialog(
    item: HistoryItemUi,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit,
) {
    val maxDialogHeight =
        (LocalConfiguration.current.screenHeightDp * DETAIL_DIALOG_MAX_HEIGHT_FRACTION).dp
    Dialog(onDismissRequest = onClose) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier =
                    Modifier
                        .padding(16.dp)
                        .heightIn(max = maxDialogHeight),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.history_detail_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                item.sourceApp?.let { source ->
                    Text(
                        text = stringResource(R.string.history_detail_source, source),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = stringResource(R.string.history_detail_created, formatHistoryTime(item.createdAtMs)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (item.isImage) {
                    HistoryImageThumb(
                        item = item,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 120.dp, max = 320.dp)
                                .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Fit,
                    )
                }
                SelectionContainer(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = historyPreview(item),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onCopy) {
                        Text(stringResource(R.string.history_copy))
                    }
                    TextButton(onClick = onDelete) {
                        Text(stringResource(R.string.history_delete))
                    }
                    TextButton(onClick = onClose) {
                        Text(stringResource(R.string.history_close))
                    }
                }
            }
        }
    }
}

@Composable
private fun historyPreview(item: HistoryItemUi): String =
    if (item.isImage) {
        stringResource(
            R.string.history_image_preview,
            item.mimeType ?: "image",
            item.pixelWidth ?: 0,
            item.pixelHeight ?: 0,
        )
    } else {
        item.preview
    }

@Composable
private fun HistoryImageThumb(
    item: HistoryItemUi,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val context = LocalContext.current
    val description =
        stringResource(
            R.string.history_image_preview,
            item.mimeType ?: "image",
            item.pixelWidth ?: 0,
            item.pixelHeight ?: 0,
        )
    val bitmap by produceState<ImageBitmap?>(initialValue = null, item.contentHash) {
        value =
            withContext(Dispatchers.IO) {
                val hash = item.contentHash
                if (hash.isEmpty()) {
                    null
                } else {
                    ImageThumbnail.decodePreview(ClipServices.repository(context).media, hash)
                        ?.asImageBitmap()
                }
            }
    }
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        val preview = bitmap
        if (preview != null) {
            Image(
                bitmap = preview,
                contentDescription = description,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale,
            )
        }
    }
}

internal fun formatHistoryTime(createdAtMs: Long): String =
    HISTORY_TIME_FORMAT.format(Instant.ofEpochMilli(createdAtMs).atZone(ZoneId.systemDefault()))

private val HISTORY_TIME_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

private const val DETAIL_DIALOG_MAX_HEIGHT_FRACTION = 0.7f

private val HISTORY_THUMB_SIZE = 72.dp

package com.clipsync.android.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.clipsync.android.platform.clipboard.ClipboardWriteCoordinator
import com.clipsync.android.platform.clipboard.ClipboardWriteResult
import com.clipsync.android.storage.CaptureRejectReason
import com.clipsync.android.storage.CaptureResult
import com.clipsync.android.storage.ClipEntry
import com.clipsync.android.storage.ClipRepository
import com.clipsync.android.storage.SETTING_PAIRED_PEER_ID
import com.clipsync.android.ui.settings.SyncConnectionStatus
import com.clipsync.android.ui.settings.SyncStatusProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class HistoryNotice {
    EMPTY,
    OVERSIZED,
    UNPAIRED,
    WINDOWS_UNREACHABLE,
}

data class HistoryItemUi(
    val eventId: String,
    val preview: String,
    val content: String,
    val createdAtMs: Long,
    val sourceApp: String?,
    val isImage: Boolean = false,
    val mimeType: String? = null,
    val pixelWidth: Int? = null,
    val pixelHeight: Int? = null,
    val contentHash: String = "",
) {
    override fun toString(): String =
        "HistoryItemUi(eventId=$eventId, createdAtMs=$createdAtMs, sourceApp=$sourceApp, isImage=$isImage)"
}

data class HistoryUiState(
    val query: String = "",
    val items: List<HistoryItemUi> = emptyList(),
    val empty: Boolean = true,
    val unpaired: Boolean = true,
    val windowsUnreachable: Boolean = false,
    val copyFailed: Boolean = false,
    val lastReject: CaptureRejectReason? = null,
    val notices: List<HistoryNotice> = listOf(HistoryNotice.EMPTY, HistoryNotice.UNPAIRED),
    val selectedEventId: String? = null,
) {
    val selectedItem: HistoryItemUi?
        get() = selectedEventId?.let { id -> items.find { it.eventId == id } }
}

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModel(
    private val repository: ClipRepository,
    private val writeCoordinator: ClipboardWriteCoordinator,
    private val syncStatus: SyncStatusProvider,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val copyFailureClearAfterMs: Long = COPY_FAILURE_NOTICE_MS,
    private val delayMs: suspend (Long) -> Unit = { delay(it) },
) : ViewModel() {
    private val mutableState = MutableStateFlow(HistoryUiState())
    val state: StateFlow<HistoryUiState> = mutableState.asStateFlow()
    private val queryFlow = MutableStateFlow("")
    private val lastRejectFlow = MutableStateFlow<CaptureRejectReason?>(null)
    private var copyFailureClearJob: Job? = null

    init {
        viewModelScope.launch {
            combine(
                queryFlow.flatMapLatest { query ->
                    repository.observeSearch(query).map { entries -> query to entries }
                },
                repository.observeSetting(SETTING_PAIRED_PEER_ID),
                syncStatus.snapshots(),
                lastRejectFlow,
            ) { queried, peerId, sync, reject ->
                HistorySnapshot(queried.first, queried.second, peerId, sync, reject)
            }.collect { snapshot ->
                mutableState.update { previous ->
                    val query = queryFlow.value
                    if (snapshot.query != query) {
                        previous.withConnectionNotices(
                            peerId = snapshot.peerId,
                            sync = snapshot.sync,
                            reject = snapshot.reject,
                            query = query,
                        )
                    } else {
                        previous.withHistory(
                            entries = snapshot.entries,
                            peerId = snapshot.peerId,
                            sync = snapshot.sync,
                            reject = snapshot.reject,
                            query = query,
                        )
                    }
                }
            }
        }
    }

    fun search(query: String) {
        queryFlow.value = query
        mutableState.update { it.copy(query = query) }
    }

    fun refresh() {
        viewModelScope.launch { reload() }
    }

    fun copy(eventId: String) {
        viewModelScope.launch {
            val entry = repository.findVisibleEntry(eventId) ?: return@launch
            val result = if (entry.isImage) {
                copyImage(entry)
            } else {
                writeCoordinator.writeText(entry.content, eventId).result
            }
            val failed = result is ClipboardWriteResult.Failure
            copyFailureClearJob?.cancel()
            copyFailureClearJob = null
            mutableState.update { it.copy(copyFailed = failed) }
            if (failed) {
                copyFailureClearJob =
                    viewModelScope.launch {
                        delayMs(copyFailureClearAfterMs)
                        mutableState.update { it.copy(copyFailed = false) }
                    }
            }
        }
    }

    private fun copyImage(entry: ClipEntry): ClipboardWriteResult {
        val mime = entry.mimeType
        val bytes = runCatching { repository.media.readAllBytes(entry.contentHash) }.getOrNull()
        if (mime == null || bytes == null) {
            return ClipboardWriteResult.Failure(
                com.clipsync.android.platform.clipboard.ClipboardWriter.IMAGE_WRITE_UNAVAILABLE,
            )
        }
        return writeCoordinator.writeImage(bytes, mime, entry.eventId).result
    }

    fun openDetail(eventId: String) {
        mutableState.update { previous ->
            if (previous.items.none { it.eventId == eventId }) {
                previous
            } else {
                previous.copy(selectedEventId = eventId)
            }
        }
    }

    fun closeDetail() {
        mutableState.update { it.copy(selectedEventId = null) }
    }

    fun delete(eventId: String) {
        viewModelScope.launch {
            repository.delete(eventId, nowMs())
            mutableState.update { previous ->
                if (previous.selectedEventId == eventId) {
                    previous.copy(selectedEventId = null)
                } else {
                    previous
                }
            }
        }
    }

    fun clear() {
        viewModelScope.launch {
            repository.clear(nowMs())
        }
    }

    fun noteCaptureResult(result: CaptureResult) {
        lastRejectFlow.value = (result as? CaptureResult.Rejected)?.reason
    }

    fun close() {
        onCleared()
    }

    private suspend fun reload() {
        val query = queryFlow.value
        val entries = repository.search(query)
        if (query != queryFlow.value) {
            return
        }
        val peerId = repository.getSetting(SETTING_PAIRED_PEER_ID)
        val sync = syncStatus.current()
        val reject = lastRejectFlow.value
        mutableState.update { previous ->
            previous.withHistory(entries, peerId, sync, reject, query)
        }
    }

    private data class HistorySnapshot(
        val query: String,
        val entries: List<ClipEntry>,
        val peerId: String?,
        val sync: SyncConnectionStatus,
        val reject: CaptureRejectReason?,
    )

    companion object {
        const val PREVIEW_LIMIT = 160
        const val COPY_FAILURE_NOTICE_MS = 4_000L

        fun factory(
            repository: ClipRepository,
            writeCoordinator: ClipboardWriteCoordinator,
            syncStatus: SyncStatusProvider,
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    HistoryViewModel(
                        repository,
                        writeCoordinator,
                        syncStatus,
                    ) as T
            }
    }
}

private fun HistoryUiState.withHistory(
    entries: List<ClipEntry>,
    peerId: String?,
    sync: SyncConnectionStatus,
    reject: CaptureRejectReason?,
    query: String,
): HistoryUiState {
    val unpaired = peerId.isNullOrBlank()
    val windowsUnreachable = sync.paired && !sync.windowsReachable
    val mapped = entries.map { entry -> entry.toUi() }
    val notices =
        buildList {
            if (entries.isEmpty()) add(HistoryNotice.EMPTY)
            if (reject == CaptureRejectReason.TOO_LARGE) add(HistoryNotice.OVERSIZED)
            if (unpaired) add(HistoryNotice.UNPAIRED)
            if (windowsUnreachable) add(HistoryNotice.WINDOWS_UNREACHABLE)
        }
    val keptSelection = selectedEventId?.takeIf { id -> mapped.any { it.eventId == id } }
    return copy(
        query = query,
        items = mapped,
        empty = entries.isEmpty(),
        unpaired = unpaired,
        windowsUnreachable = windowsUnreachable,
        lastReject = reject,
        notices = notices,
        selectedEventId = keptSelection,
    )
}

private fun HistoryUiState.withConnectionNotices(
    peerId: String?,
    sync: SyncConnectionStatus,
    reject: CaptureRejectReason?,
    query: String,
): HistoryUiState {
    val unpaired = peerId.isNullOrBlank()
    val windowsUnreachable = sync.paired && !sync.windowsReachable
    val notices =
        buildList {
            if (empty) add(HistoryNotice.EMPTY)
            if (reject == CaptureRejectReason.TOO_LARGE) add(HistoryNotice.OVERSIZED)
            if (unpaired) add(HistoryNotice.UNPAIRED)
            if (windowsUnreachable) add(HistoryNotice.WINDOWS_UNREACHABLE)
        }
    return copy(
        query = query,
        unpaired = unpaired,
        windowsUnreachable = windowsUnreachable,
        lastReject = reject,
        notices = notices,
    )
}

internal fun ClipEntry.toUi(): HistoryItemUi {
    val imagePreview = imagePreviewText(mimeType, pixelWidth, pixelHeight)
    return HistoryItemUi(
        eventId = eventId,
        preview = if (isImage) imagePreview else previewText(content),
        content = if (isImage) imagePreview else content,
        createdAtMs = createdAtMs,
        sourceApp = sourceApp,
        isImage = isImage,
        mimeType = mimeType,
        pixelWidth = pixelWidth,
        pixelHeight = pixelHeight,
        contentHash = contentHash,
    )
}

internal fun imagePreviewText(
    mimeType: String?,
    pixelWidth: Int?,
    pixelHeight: Int?,
): String {
    val mime = mimeType ?: "image"
    return if (pixelWidth != null && pixelHeight != null) {
        "Image $mime ${pixelWidth}×$pixelHeight"
    } else {
        "Image $mime"
    }
}

internal fun previewText(
    content: String,
    limit: Int = HistoryViewModel.PREVIEW_LIMIT,
): String {
    val collapsed = content.replace(Regex("\\s+"), " ").trim()
    return if (collapsed.length <= limit) collapsed else collapsed.take(limit) + "…"
}

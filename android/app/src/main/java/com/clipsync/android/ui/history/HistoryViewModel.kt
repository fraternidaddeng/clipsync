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
import com.clipsync.android.ui.settings.SyncStatusProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
) {
    override fun toString(): String =
        "HistoryItemUi(eventId=$eventId, createdAtMs=$createdAtMs, sourceApp=$sourceApp)"
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
)

class HistoryViewModel(
    private val repository: ClipRepository,
    private val writeCoordinator: ClipboardWriteCoordinator,
    private val syncStatus: SyncStatusProvider,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val mutableState = MutableStateFlow(HistoryUiState())
    val state: StateFlow<HistoryUiState> = mutableState.asStateFlow()

    init {
        refresh()
    }

    fun search(query: String) {
        mutableState.update { it.copy(query = query) }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch { reload() }
    }

    fun copy(eventId: String) {
        viewModelScope.launch {
            val item = mutableState.value.items.find { it.eventId == eventId } ?: return@launch
            val outcome = writeCoordinator.writeText(item.content, eventId)
            mutableState.update {
                it.copy(copyFailed = outcome.result is ClipboardWriteResult.Failure)
            }
        }
    }

    fun delete(eventId: String) {
        viewModelScope.launch {
            repository.delete(eventId, nowMs())
            reload()
        }
    }

    fun clear() {
        viewModelScope.launch {
            repository.clear(nowMs())
            reload()
        }
    }

    fun noteCaptureResult(result: CaptureResult) {
        val reject = (result as? CaptureResult.Rejected)?.reason
        mutableState.update { it.copy(lastReject = reject) }
        refresh()
    }

    fun close() {
        onCleared()
    }

    private suspend fun reload() {
        val query = mutableState.value.query
        val entries = repository.search(query)
        val unpaired = repository.getSetting(SETTING_PAIRED_PEER_ID).isNullOrBlank()
        val sync = syncStatus.current()
        val windowsUnreachable = sync.paired && !sync.windowsReachable
        val lastReject = mutableState.value.lastReject
        val notices = buildList {
            if (entries.isEmpty()) add(HistoryNotice.EMPTY)
            if (lastReject == CaptureRejectReason.TOO_LARGE) add(HistoryNotice.OVERSIZED)
            if (unpaired) add(HistoryNotice.UNPAIRED)
            if (windowsUnreachable) add(HistoryNotice.WINDOWS_UNREACHABLE)
        }
        mutableState.update {
            it.copy(
                items = entries.map { entry -> entry.toUi() },
                empty = entries.isEmpty(),
                unpaired = unpaired,
                windowsUnreachable = windowsUnreachable,
                lastReject = lastReject,
                notices = notices,
            )
        }
    }

    companion object {
        const val PREVIEW_LIMIT = 160

        fun factory(
            repository: ClipRepository,
            writeCoordinator: ClipboardWriteCoordinator,
            syncStatus: SyncStatusProvider,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                HistoryViewModel(repository, writeCoordinator, syncStatus) as T
        }
    }
}

internal fun ClipEntry.toUi(): HistoryItemUi =
    HistoryItemUi(
        eventId = eventId,
        preview = previewText(content),
        content = content,
        createdAtMs = createdAtMs,
        sourceApp = sourceApp,
    )

internal fun previewText(content: String, limit: Int = HistoryViewModel.PREVIEW_LIMIT): String {
    val collapsed = content.replace(Regex("\\s+"), " ").trim()
    return if (collapsed.length <= limit) collapsed else collapsed.take(limit) + "…"
}

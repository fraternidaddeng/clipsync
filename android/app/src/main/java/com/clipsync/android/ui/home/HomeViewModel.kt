package com.clipsync.android.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.clipsync.android.pairing.PairedPeer
import com.clipsync.android.pairing.PairingStore
import com.clipsync.android.platform.clipboard.ClipboardWriteCoordinator
import com.clipsync.android.platform.clipboard.ClipboardWriteResult
import com.clipsync.android.platform.clipboard.ClipboardWriter
import com.clipsync.android.storage.ClipHistoryEntry
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One row of the 一屏 history list. */
data class HomeClipItem(
    val eventId: String,
    val preview: String,
    val createdAtMs: Long,
    /** Null for clips this phone produced (缺省即本地 — locals carry no tag). */
    val remoteSourceLabel: String?,
    /**
     * 1-based pairing slot of the origin device (charter §3.4: neighbour hues
     * follow pairing order, never a hash). Null for locals and for remotes
     * that hold no pairing slot.
     */
    val sourcePairingOrder: Int? = null,
    /** Render-time format tag (ADR 0003) — classified from content, never persisted. */
    val format: ClipContentFormat = ClipContentFormat.PLAIN,
    /** True for image clips (kind = image); the card renders a thumbnail instead of text. */
    val isImage: Boolean = false,
    /** Blob hash for image clips — keys the thumbnail lookup. Empty for text. */
    val contentHash: String = "",
)

/** Transient, honest feedback for the last item action. */
sealed interface HomeNotice {
    data object Copied : HomeNotice

    data class CopyFailed(val errorCode: String) : HomeNotice

    data object DeletedLocal : HomeNotice
}

data class HomeUiState(
    val query: String = "",
    val items: List<HomeClipItem> = emptyList(),
    /** True once the repository answered at least once; gates empty states. */
    val loaded: Boolean = false,
    /** True when [items] reflect a non-blank search query. */
    val searchActive: Boolean = false,
    /** Format chip in effect (null = 全部). Filters render-time, never the DB. */
    val formatFilter: ClipContentFormat? = null,
    val notice: HomeNotice? = null,
)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class HomeViewModel(
    private val history: HistoryGateway,
    private val writeCoordinator: ClipboardWriteCoordinator,
    private val pairingStore: PairingStore,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val searchDebounceMs: Long = SEARCH_DEBOUNCE_MS,
    private val noticeClearAfterMs: Long = NOTICE_CLEAR_MS,
    private val delayMs: suspend (Long) -> Unit = { delay(it) },
) : ViewModel() {
    private val mutableState = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = mutableState.asStateFlow()

    private val queryInput = MutableStateFlow("")
    private val formatFilterInput = MutableStateFlow<ClipContentFormat?>(null)
    private val peersSnapshot = MutableStateFlow(pairingStore.pairedPeers())
    private val localDeviceId = pairingStore.localDeviceId()
    private var noticeClearJob: Job? = null

    init {
        viewModelScope.launch {
            combine(
                queryInput
                    .debounce { query -> if (query.isBlank()) 0L else searchDebounceMs }
                    .flatMapLatest { query ->
                        history.observeSearch(query).map { entries -> query to entries }
                    },
                peersSnapshot,
                formatFilterInput,
            ) { (query, entries), peers, filter ->
                // Format filtering happens here, render-time, on the classified
                // rows (ADR 0003) — the repository query stays untouched.
                // Format chips are text vocabulary, so images only show under 全部.
                val rows = entries.map { it.toHomeItem(localDeviceId, peers) }
                Triple(
                    query,
                    if (filter == null) rows else rows.filter { it.format == filter && !it.isImage },
                    filter,
                )
            }.collect { (query, rows, filter) ->
                mutableState.update { previous ->
                    previous.copy(
                        items = rows,
                        loaded = true,
                        searchActive = query.isNotBlank(),
                        formatFilter = filter,
                    )
                }
            }
        }
    }

    fun setQuery(query: String) {
        mutableState.update { it.copy(query = query) }
        queryInput.value = query
    }

    /** Applies a format chip (null = 全部); takes effect immediately, no debounce. */
    fun setFormatFilter(format: ClipContentFormat?) {
        mutableState.update { it.copy(formatFilter = format) }
        formatFilterInput.value = format
    }

    /** Re-reads the pairing store so remote source tags pick up a new peer name. */
    fun refreshPeer() {
        peersSnapshot.value = pairingStore.pairedPeers()
    }

    /**
     * Copies the clip into the system clipboard and reports the writer's real
     * outcome — a rejected write must never look like a success.
     */
    fun copy(eventId: String) {
        viewModelScope.launch {
            val entry = history.findVisible(eventId) ?: return@launch
            val result = if (entry.isImage) {
                // The blob lives on disk, not in the row; a missing blob is an
                // honest failure, never a silent empty-text write.
                val payload = history.imagePayload(entry.eventId)
                if (payload == null) {
                    ClipboardWriteResult.Failure(ClipboardWriter.IMAGE_WRITE_UNAVAILABLE)
                } else {
                    writeCoordinator.writeImage(payload.bytes, payload.mimeType, entry.eventId).result
                }
            } else {
                writeCoordinator.writeText(entry.content, entry.eventId).result
            }
            showNotice(
                when (result) {
                    is ClipboardWriteResult.Success -> HomeNotice.Copied
                    is ClipboardWriteResult.Failure -> HomeNotice.CopyFailed(result.errorCode)
                },
            )
        }
    }

    /** Local delete only; other devices keep their copies (plan.md §3.3 rule 6). */
    fun delete(eventId: String) {
        viewModelScope.launch {
            history.delete(eventId, nowMs())
            showNotice(HomeNotice.DeletedLocal)
        }
    }

    private fun showNotice(notice: HomeNotice) {
        noticeClearJob?.cancel()
        mutableState.update { it.copy(notice = notice) }
        noticeClearJob = viewModelScope.launch {
            delayMs(noticeClearAfterMs)
            mutableState.update { it.copy(notice = null) }
        }
    }

    companion object {
        const val SEARCH_DEBOUNCE_MS = 250L
        const val NOTICE_CLEAR_MS = 2_500L
        const val PREVIEW_LIMIT = 160

        fun factory(
            history: HistoryGateway,
            writeCoordinator: ClipboardWriteCoordinator,
            pairingStore: PairingStore,
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    HomeViewModel(history, writeCoordinator, pairingStore) as T
            }
    }
}

internal fun ClipHistoryEntry.toHomeItem(localDeviceId: String, peers: List<PairedPeer>): HomeClipItem {
    val remote = originDeviceId != localDeviceId
    // The device's 1-based pairing slot picks its neighbour hue (charter §3.4:
    // by pairing order, never a hash); an unslotted remote stays unhued.
    val slot = peers.indexOfFirst { it.deviceId == originDeviceId }.takeIf { it >= 0 }?.plus(1)
    val label = when {
        !remote -> null
        slot != null -> peers[slot - 1].displayName
        else -> "远端设备"
    }
    return HomeClipItem(
        eventId = eventId,
        preview = if (isImage) "图片" else previewText(content),
        createdAtMs = createdAtMs,
        remoteSourceLabel = label,
        sourcePairingOrder = if (remote) slot else null,
        // Classified from the full content (the preview may be truncated).
        // Image rows carry no text to classify — they get the 图片 tag instead.
        format = if (isImage) ClipContentFormat.PLAIN else classifyClipContent(content),
        isImage = isImage,
        contentHash = if (isImage) contentHash else "",
    )
}

/** Single-paragraph preview: collapse whitespace, cap the length. */
internal fun previewText(content: String, limit: Int = HomeViewModel.PREVIEW_LIMIT): String {
    val collapsed = content.replace(Regex("\\s+"), " ").trim()
    return if (collapsed.length <= limit) collapsed else collapsed.take(limit) + "…"
}

private val timeOfDay = DateTimeFormatter.ofPattern("HH:mm")
private val sameYear = DateTimeFormatter.ofPattern("M月d日")
private val otherYear = DateTimeFormatter.ofPattern("yyyy年M月d日")

/** Monospace meta timestamp: today by clock, yesterday by name, older by date. */
internal fun clipTimeLabel(
    createdAtMs: Long,
    nowMs: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    val created = Instant.ofEpochMilli(createdAtMs).atZone(zone)
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    val createdDate = created.toLocalDate()
    return when {
        createdDate == today -> created.format(timeOfDay)
        createdDate == today.minusDays(1) -> "昨天"
        createdDate.year == today.year -> created.format(sameYear)
        else -> created.format(otherYear)
    }
}

package com.clipsync.android.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.clipsync.android.pairing.PairedPeer
import com.clipsync.android.pairing.PairingStore
import com.clipsync.android.platform.clipboard.ClipboardWriteCoordinator
import com.clipsync.android.platform.clipboard.ClipboardWriteResult
import com.clipsync.android.storage.ClipEntry
import com.clipsync.android.storage.ClipSyncRepository
import com.clipsync.android.ui.ConduitSegmentState
import com.clipsync.android.ui.ConduitStatus
import com.clipsync.android.ui.HealthScreenState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One row of the 一屏 history list. */
data class HomeClipItem(
    val eventId: String,
    val preview: String,
    val createdAtMs: Long,
    /** Null for clips this phone produced (缺省即本地 — locals carry no tag). */
    val remoteSourceLabel: String?,
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
    val notice: HomeNotice? = null,
)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class HomeViewModel(
    private val repository: ClipSyncRepository,
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
    private val peerSnapshot = MutableStateFlow(pairingStore.peer())
    private val localDeviceId = pairingStore.localDeviceId()
    private var noticeClearJob: Job? = null

    /** Conduit state for the 44dp band and the 通路 page — one source of truth. */
    val conduit: StateFlow<HealthScreenState> = peerSnapshot
        .map { conduitStateFor(it) }
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            conduitStateFor(peerSnapshot.value),
        )

    init {
        viewModelScope.launch {
            combine(
                queryInput
                    .debounce { query -> if (query.isBlank()) 0L else searchDebounceMs }
                    .flatMapLatest { query ->
                        repository.observeSearch(query).map { entries -> query to entries }
                    },
                peerSnapshot,
            ) { (query, entries), peer ->
                Triple(query, entries, peer)
            }.collect { (query, entries, peer) ->
                mutableState.update { previous ->
                    previous.copy(
                        items = entries.map { it.toHomeItem(localDeviceId, peer) },
                        loaded = true,
                        searchActive = query.isNotBlank(),
                    )
                }
            }
        }
    }

    fun setQuery(query: String) {
        mutableState.update { it.copy(query = query) }
        queryInput.value = query
    }

    /** Re-reads the pairing store; call when returning to the screen or after pairing. */
    fun refreshConduit() {
        peerSnapshot.value = pairingStore.peer()
    }

    /**
     * Copies the clip into the system clipboard and reports the writer's real
     * outcome — a rejected write must never look like a success.
     */
    fun copy(eventId: String) {
        viewModelScope.launch {
            val entry = repository.findVisible(eventId) ?: return@launch
            val outcome = writeCoordinator.writeText(entry.content, entry.eventId)
            showNotice(
                when (val result = outcome.result) {
                    is ClipboardWriteResult.Success -> HomeNotice.Copied
                    is ClipboardWriteResult.Failure -> HomeNotice.CopyFailed(result.errorCode)
                },
            )
        }
    }

    /** Local delete only; other devices keep their copies (plan.md §3.3 rule 6). */
    fun delete(eventId: String) {
        viewModelScope.launch {
            repository.delete(eventId, nowMs())
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

    private fun conduitStateFor(peer: PairedPeer?): HealthScreenState {
        val base = HealthScreenState.initial()
        if (peer == null) {
            return base
        }
        return base.copy(
            network = ConduitSegmentState(
                statusLabel = "已配对 · 未探测",
                detail = "已与「${peer.displayName}」配对。实时同步连接随同步服务接入后探测。",
                status = ConduitStatus.UNPROBED,
            ),
            pairedDeviceCount = 1,
        )
    }

    companion object {
        const val SEARCH_DEBOUNCE_MS = 250L
        const val NOTICE_CLEAR_MS = 2_500L
        const val PREVIEW_LIMIT = 160

        fun factory(
            repository: ClipSyncRepository,
            writeCoordinator: ClipboardWriteCoordinator,
            pairingStore: PairingStore,
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    HomeViewModel(repository, writeCoordinator, pairingStore) as T
            }
    }
}

internal fun ClipEntry.toHomeItem(localDeviceId: String, peer: PairedPeer?): HomeClipItem {
    val remote = originDeviceId != localDeviceId
    val label = when {
        !remote -> null
        peer != null && peer.deviceId == originDeviceId -> peer.displayName
        else -> "远端设备"
    }
    return HomeClipItem(
        eventId = eventId,
        preview = previewText(content),
        createdAtMs = createdAtMs,
        remoteSourceLabel = label,
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

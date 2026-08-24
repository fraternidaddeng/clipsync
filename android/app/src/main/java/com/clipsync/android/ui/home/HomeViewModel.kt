package com.clipsync.android.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class HomeUiState(
    /** False until the Room-backed history stage provides a [HistorySource]. */
    val historyAvailable: Boolean,
    val entries: List<HistoryEntry> = emptyList(),
)

/**
 * Observes clipboard history when a source exists. Without one the state is an
 * honest empty list flagged unavailable — the screen must not pretend a store
 * is merely empty when there is no store at all.
 */
class HomeViewModel(
    historySource: HistorySource? = null,
) : ViewModel() {
    private val mutableState = MutableStateFlow(
        HomeUiState(historyAvailable = historySource != null),
    )

    val state: StateFlow<HomeUiState> = mutableState.asStateFlow()

    init {
        if (historySource != null) {
            viewModelScope.launch {
                historySource.entries().collect { entries ->
                    mutableState.value = HomeUiState(historyAvailable = true, entries = entries)
                }
            }
        }
    }

    companion object {
        fun factory(historySource: HistorySource?): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    HomeViewModel(historySource) as T
            }
    }
}

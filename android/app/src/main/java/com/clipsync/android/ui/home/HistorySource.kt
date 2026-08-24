package com.clipsync.android.ui.home

import kotlinx.coroutines.flow.Flow

/** One history row as the home screen renders it; never more than a preview. */
data class HistoryEntry(
    val id: String,
    val preview: String,
    val fromThisDevice: Boolean,
    val capturedAtMs: Long,
)

/**
 * Read-side seam for clipboard history, defined by the UI that consumes it.
 * The Room storage stage implements this (e.g. by adapting a DAO flow);
 * until then the app passes null and the home screen says so honestly.
 */
fun interface HistorySource {
    fun entries(): Flow<List<HistoryEntry>>
}

package com.clipsync.android.sync

import com.clipsync.android.storage.SyncSettingsStore

/**
 * Decorates a [ClipOutbox] with the user's pause/private switches so every entry point (share
 * sheet, quick tile, foreground capture) enforces them in the plan 3.4 gate order: 暂停/私密
 * first, then the delegate's size and dedup rules. Settings are re-read on every call so a
 * toggle applies to the very next enqueue. [pending] and [remove] pass through untouched:
 * pausing must not hide or drop entries that were accepted before the pause.
 */
class SettingsGatedClipOutbox(
    private val delegate: ClipOutbox,
    private val settings: SyncSettingsStore,
) : ClipOutbox {
    override fun enqueue(text: String, source: ClipSource): EnqueueResult {
        if (settings.privateMode) {
            return EnqueueResult.PrivateMode
        }
        if (settings.syncPaused) {
            return EnqueueResult.SyncPaused
        }
        return delegate.enqueue(text, source)
    }

    override fun pending(): List<OutboxEntry> = delegate.pending()

    override fun remove(eventId: String) = delegate.remove(eventId)
}

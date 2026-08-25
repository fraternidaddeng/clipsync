package com.clipsync.android.sync

import com.clipsync.android.pairing.KeyValueStore
import com.clipsync.android.storage.ClipSyncRepository
import kotlinx.coroutines.runBlocking

/**
 * Inbox port for the Windows -> Android direction. The copy-action notification never carries
 * the clipboard text itself; the broadcast receiver resolves the event id through this
 * interface instead, so notification listeners of other apps cannot read the content.
 */
interface ClipInbox {
    fun record(
        eventId: String,
        text: String,
        receivedAtEpochMillis: Long,
    )

    fun textFor(eventId: String): String?
}

/**
 * Room-backed inbox (replaces the retired SharedPreferences stub): a committed remote clip
 * is a Room history row before [InboxDelivery] ever runs (入站先落库, plan 5.6), so the
 * notification copy action resolves the text straight from history instead of keeping a
 * second plaintext copy of received content in the preferences file. That also means
 * deleting a history entry invalidates its notification copy action — deleted is gone,
 * which is the honest behaviour the old 50-item stub could not offer.
 */
class RoomClipInbox(
    private val repository: () -> ClipSyncRepository,
) : ClipInbox {
    /**
     * No separate write: the sync engine's transactional Room commit that precedes every
     * delivery is the record-first step the plan requires.
     */
    override fun record(
        eventId: String,
        text: String,
        receivedAtEpochMillis: Long,
    ) = Unit

    /**
     * Live text rows only: images never enter the text inbox, and a soft-deleted row
     * resolves to null so the receiver shows the honest 内容已不存在 toast.
     */
    override fun textFor(eventId: String): String? =
        runBlocking {
            repository()
                .getById(eventId)
                ?.takeUnless { it.isImage }
                ?.content
        }

    companion object {
        /** Key the retired [KeyValueStore] stub kept its JSON blob under. */
        private const val LEGACY_STORAGE_KEY = "inbox.recent"

        /**
         * Removes the stub's plaintext residue: the old inbox kept the last 50 received
         * texts in SharedPreferences forever, surviving history deletion and cleanup.
         */
        fun purgeLegacyStub(store: KeyValueStore) {
            store.write(mapOf(LEGACY_STORAGE_KEY to null))
        }
    }
}

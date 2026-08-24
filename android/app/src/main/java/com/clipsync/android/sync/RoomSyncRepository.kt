package com.clipsync.android.sync

import com.clipsync.android.platform.clipboard.Sha256ContentHasher
import com.clipsync.android.storage.ClipSyncRepository
import com.clipsync.android.storage.LocalClipDraft
import com.clipsync.android.storage.OriginAckRanges
import java.nio.charset.StandardCharsets

/**
 * Bridges the [SyncEngine]'s storage contract onto the Room-backed
 * [ClipSyncRepository], which owns the transactional invariants (sequence
 * allocation, receive-vector advance, outbox fan-out all commit together).
 */
class RoomSyncRepository(
    private val store: ClipSyncRepository,
    /** Peers a newly captured local clip fans out to; re-read per call so re-pairing applies. */
    private val fanOutPeerIds: () -> List<String>,
) : SyncRepository {
    override suspend fun knownVector(): Map<String, OriginReceiveState> = store.knownVector()

    override suspend fun findLiveContentByHash(contentHash: String): String? =
        store.findLiveContentByHash(contentHash)

    override suspend fun storeRemoteEvent(event: RemoteClipEvent, viaDeviceId: String): RemoteStoreResult =
        store.storeRemoteEvent(
            com.clipsync.android.storage.RemoteClipEvent(
                eventId = event.eventId,
                originDeviceId = event.originDeviceId,
                originSeq = event.originSeq,
                content = event.content,
                contentHash = event.contentHash,
                sourceApp = event.sourceApp,
                createdAtMs = event.createdAtMs,
                expiresAtMs = event.expiresAtMs,
            ),
            sourcePeerId = viaDeviceId,
        ).toEngineResult()

    override suspend fun storeRemoteTerminal(marker: RemoteTerminalMarker, viaDeviceId: String): RemoteStoreResult =
        try {
            store.storeRemoteTerminal(
                com.clipsync.android.storage.RemoteTerminalMarker(
                    eventId = marker.eventId,
                    originDeviceId = marker.originDeviceId,
                    originSeq = marker.originSeq,
                    reason = marker.reason,
                ),
                sourcePeerId = viaDeviceId,
                receivedAtMs = System.currentTimeMillis(),
            ).toEngineResult()
        } catch (_: IllegalArgumentException) {
            // An unknown terminal reason is an identity we refuse to persist.
            RemoteStoreResult.IdentityConflict("unknown terminal reason")
        }

    override suspend fun getSyncableEvents(
        originDeviceId: String,
        ranges: List<SequenceRange>,
        limit: Int,
    ): List<SyncableClipEvent> =
        store.getSyncableEvents(originDeviceId, ranges, limit).map { it.toEngineEvent() }

    override suspend fun getSyncableEventsByIds(eventIds: List<String>): List<SyncableClipEvent> =
        store.getSyncableEventsByIds(eventIds).map { it.toEngineEvent() }

    override suspend fun applyPeerAckRanges(
        peerDeviceId: String,
        ranges: List<OriginSequenceRanges>,
        nowMs: Long,
    ) {
        store.applyPeerAckRanges(
            peerDeviceId,
            ranges.map { OriginAckRanges(it.originDeviceId, it.ranges) },
            nowMs,
        )
    }

    override suspend fun resetOutboxToPending(peerDeviceId: String) {
        store.resetOutboxToPending(peerDeviceId)
    }

    override suspend fun getOutboxBatch(peerDeviceId: String, limit: Int): List<OutboxRow> =
        store.outboxBatch(peerDeviceId, limit).map { OutboxRow(it.outboxId, it.event.toEngineEvent()) }

    override suspend fun markOutboxAnnounced(entryIds: List<Long>) {
        store.markOutboxAnnounced(entryIds)
    }

    override suspend fun recordLocalClip(text: String, sourceApp: String?, nowMs: Long): SyncableClipEvent? {
        val utf8Bytes = text.toByteArray(StandardCharsets.UTF_8).size
        if (utf8Bytes == 0 || utf8Bytes > SyncLimits.MAX_CONTENT_UTF8_BYTES) {
            return null
        }
        val contentHash = Sha256ContentHasher.hash(text)
        val stored = store.storeLocalEvent(
            LocalClipDraft(
                content = text,
                contentHash = contentHash,
                sourceApp = sourceApp,
                capturedAtMs = nowMs,
            ),
            fanOutPeerIds = fanOutPeerIds(),
        )
        return SyncableClipEvent(
            eventId = stored.eventId,
            originDeviceId = stored.originDeviceId,
            originSeq = stored.originSeq,
            isTerminal = false,
            content = text,
            contentHash = contentHash,
            sourceApp = sourceApp,
            createdAtMs = nowMs,
        )
    }

    private fun com.clipsync.android.storage.RemoteStoreResult.toEngineResult(): RemoteStoreResult =
        when (this) {
            is com.clipsync.android.storage.RemoteStoreResult.Stored -> RemoteStoreResult.Stored
            is com.clipsync.android.storage.RemoteStoreResult.AlreadyPersisted -> RemoteStoreResult.Duplicate
            is com.clipsync.android.storage.RemoteStoreResult.IdentityConflict ->
                RemoteStoreResult.IdentityConflict(detail)
        }

    private fun com.clipsync.android.storage.SyncableClipEvent.toEngineEvent(): SyncableClipEvent =
        SyncableClipEvent(
            eventId = eventId,
            originDeviceId = originDeviceId,
            originSeq = originSeq,
            isTerminal = isTerminal,
            terminalReason = terminalReason,
            content = content,
            contentHash = contentHash,
            sourceApp = sourceApp,
            createdAtMs = createdAtMs,
            expiresAtMs = expiresAtMs,
        )
}

package com.clipsync.android.sync

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

/**
 * Process-lifetime implementation of [SyncRepository] used until the Room-backed store lands.
 * All invariants (idempotency keys, receive-state advancement, outbox lifecycle) match what the
 * persistent implementation must provide, so [SyncEngine] does not change when Room arrives.
 */
class InMemorySyncRepository(
    private val localDeviceId: String,
) : SyncRepository {
    private val lock = Any()

    /** Idempotency key (origin_device_id, origin_seq) -> event. */
    private val eventsByKey = HashMap<Pair<String, Long>, SyncableClipEvent>()
    private val eventsById = HashMap<String, SyncableClipEvent>()
    private val receiveStates = HashMap<String, OriginReceiveState>()
    private var nextLocalSeq = 1L
    private var nextOutboxId = 1L

    private data class OutboxEntry(val id: Long, val key: Pair<String, Long>, var announced: Boolean)

    private val outbox = ArrayList<OutboxEntry>()

    override suspend fun knownVector(): Map<String, OriginReceiveState> = synchronized(lock) {
        receiveStates.toMap()
    }

    override suspend fun findLiveContentByHash(contentHash: String): String? = synchronized(lock) {
        eventsByKey.values.firstOrNull { !it.isTerminal && it.contentHash == contentHash }?.content
    }

    override suspend fun storeRemoteEvent(event: RemoteClipEvent, viaDeviceId: String): RemoteStoreResult =
        synchronized(lock) {
            storeLocked(
                SyncableClipEvent(
                    eventId = event.eventId,
                    originDeviceId = event.originDeviceId,
                    originSeq = event.originSeq,
                    isTerminal = false,
                    content = event.content,
                    contentHash = event.contentHash,
                    sourceApp = event.sourceApp,
                    createdAtMs = event.createdAtMs,
                    expiresAtMs = event.expiresAtMs,
                ),
            )
        }

    override suspend fun storeRemoteTerminal(marker: RemoteTerminalMarker, viaDeviceId: String): RemoteStoreResult =
        synchronized(lock) {
            storeLocked(
                SyncableClipEvent(
                    eventId = marker.eventId,
                    originDeviceId = marker.originDeviceId,
                    originSeq = marker.originSeq,
                    isTerminal = true,
                    terminalReason = marker.reason,
                ),
            )
        }

    private fun storeLocked(event: SyncableClipEvent): RemoteStoreResult {
        val key = event.originDeviceId to event.originSeq
        eventsByKey[key]?.let { existing ->
            return if (existing.eventId == event.eventId &&
                existing.isTerminal == event.isTerminal &&
                existing.contentHash == event.contentHash
            ) {
                RemoteStoreResult.Duplicate
            } else {
                RemoteStoreResult.IdentityConflict("idempotency key bound to a different identity")
            }
        }
        eventsById[event.eventId]?.let {
            return RemoteStoreResult.IdentityConflict("event id bound to a different origin sequence")
        }
        eventsByKey[key] = event
        eventsById[event.eventId] = event
        val state = receiveStates[event.originDeviceId] ?: OriginReceiveState.EMPTY
        receiveStates[event.originDeviceId] = state.accept(event.originSeq)
        return RemoteStoreResult.Stored
    }

    override suspend fun getSyncableEvents(
        originDeviceId: String,
        ranges: List<SequenceRange>,
        limit: Int,
    ): List<SyncableClipEvent> = synchronized(lock) {
        eventsByKey.values
            .asSequence()
            .filter { it.originDeviceId == originDeviceId && ranges.any { range -> range.contains(it.originSeq) } }
            .sortedBy { it.originSeq }
            .take(limit)
            .toList()
    }

    override suspend fun getSyncableEventsByIds(eventIds: List<String>): List<SyncableClipEvent> =
        synchronized(lock) { eventIds.mapNotNull { eventsById[it] } }

    override suspend fun applyPeerAckRanges(
        peerDeviceId: String,
        ranges: List<OriginSequenceRanges>,
        nowMs: Long,
    ): Unit = synchronized(lock) {
        outbox.removeAll { entry ->
            val (origin, seq) = entry.key
            ranges.any { acked -> acked.originDeviceId == origin && acked.ranges.any { it.contains(seq) } }
        }
    }

    override suspend fun resetOutboxToPending(peerDeviceId: String): Unit = synchronized(lock) {
        outbox.forEach { it.announced = false }
    }

    override suspend fun getOutboxBatch(peerDeviceId: String, limit: Int): List<OutboxRow> =
        synchronized(lock) {
            outbox.asSequence()
                .filter { !it.announced }
                .sortedBy { it.id }
                .take(limit)
                .mapNotNull { entry -> eventsByKey[entry.key]?.let { OutboxRow(entry.id, it) } }
                .toList()
        }

    override suspend fun markOutboxAnnounced(entryIds: List<Long>): Unit = synchronized(lock) {
        val ids = entryIds.toHashSet()
        outbox.forEach { if (it.id in ids) it.announced = true }
    }

    override suspend fun recordLocalClip(text: String, sourceApp: String?, nowMs: Long): SyncableClipEvent? {
        val utf8 = text.toByteArray(StandardCharsets.UTF_8)
        if (utf8.isEmpty() || utf8.size > SyncLimits.MAX_CONTENT_UTF8_BYTES) {
            return null
        }
        return synchronized(lock) {
            val seq = nextLocalSeq++
            val event = SyncableClipEvent(
                eventId = UUID.randomUUID().toString(),
                originDeviceId = localDeviceId,
                originSeq = seq,
                isTerminal = false,
                content = text,
                contentHash = sha256Hex(utf8),
                sourceApp = sourceApp,
                createdAtMs = nowMs,
            )
            eventsByKey[event.originDeviceId to seq] = event
            eventsById[event.eventId] = event
            val state = receiveStates[localDeviceId] ?: OriginReceiveState.EMPTY
            receiveStates[localDeviceId] = state.accept(seq)
            outbox.add(OutboxEntry(nextOutboxId++, event.originDeviceId to seq, announced = false))
            event
        }
    }

    private fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
}

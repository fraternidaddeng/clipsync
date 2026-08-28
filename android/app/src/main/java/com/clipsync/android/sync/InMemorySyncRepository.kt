package com.clipsync.android.sync

import com.clipsync.android.storage.TerminalReasons
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

    /** Event ids of local images announced as `local_only` markers (ADR 0005 §4). */
    private val localOnlyEventIds = HashSet<String>()

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
            val key = marker.originDeviceId to marker.originSeq
            val existing = eventsByKey[key]
            if (existing != null && existing.eventId == marker.eventId) {
                // Only the origin-authoritative "deleted" reason upgrades a stored live body
                // to a tombstone (mirroring the Room and Windows stores); every other reason
                // keeps what this device already owns and stays an idempotent success.
                if (!existing.isTerminal && marker.reason == TerminalReasons.DELETED) {
                    val tombstone =
                        existing.copy(
                            isTerminal = true,
                            terminalReason = marker.reason,
                            content = null,
                            contentHash = null,
                            sourceApp = null,
                        )
                    eventsByKey[key] = tombstone
                    eventsById[marker.eventId] = tombstone
                    return@synchronized RemoteStoreResult.Stored
                }
                return@synchronized RemoteStoreResult.Duplicate
            }
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
        dropTerminalOutbox: Boolean,
    ): Unit = synchronized(lock) {
        outbox.removeAll { entry ->
            val (origin, seq) = entry.key
            val covered =
                ranges.any { acked ->
                    acked.originDeviceId == origin && acked.ranges.any { it.contains(seq) }
                }
            if (!covered) {
                return@removeAll false
            }
            // A tombstone row leaves only after its own announce went out (and only for a
            // live ack): the ack may confirm the long-gone content, never a pending deletion.
            val isTerminal = eventsByKey[entry.key]?.isTerminal == true
            !isTerminal || (dropTerminalOutbox && entry.announced)
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

    override suspend fun markImagesLocalOnly(eventIds: List<String>, nowMs: Long): Unit =
        synchronized(lock) {
            eventIds.forEach { id ->
                val event = eventsById[id]
                if (event != null && !event.isTerminal && event.isImage) {
                    localOnlyEventIds.add(id)
                }
            }
        }

    override suspend fun clearImagesLocalOnly(eventIds: List<String>): Unit = synchronized(lock) {
        localOnlyEventIds.removeAll(eventIds.toSet())
    }

    /** Test hook: event ids currently badged 仅本机保留. */
    fun imagesMarkedLocalOnly(): Set<String> = synchronized(lock) { localOnlyEventIds.toSet() }

    /**
     * Test hook: soft-deletes a stored event the way the Room store's local delete does, so
     * a still-queued outbox row for it projects a tombstone announce.
     */
    fun markEventDeletedForTest(eventId: String): Unit =
        synchronized(lock) {
            val event = eventsById.getValue(eventId)
            val tombstone =
                event.copy(
                    isTerminal = true,
                    terminalReason = TerminalReasons.DELETED,
                    content = null,
                    contentHash = null,
                    sourceApp = null,
                )
            eventsByKey[event.originDeviceId to event.originSeq] = tombstone
            eventsById[eventId] = tombstone
        }

    /**
     * Test hook: injects a local image event straight into the store and outbox, standing in
     * for the Room repository's `recordLocalImageClip` (the in-memory store has no blob store).
     */
    fun injectLocalImageEventForTest(
        contentHash: String,
        encodedBytes: Int,
        nowMs: Long,
    ): SyncableClipEvent = synchronized(lock) {
        val seq = nextLocalSeq++
        val event = SyncableClipEvent(
            eventId = UUID.randomUUID().toString(),
            originDeviceId = localDeviceId,
            originSeq = seq,
            isTerminal = false,
            content = "",
            contentHash = contentHash,
            sourceApp = null,
            createdAtMs = nowMs,
            kind = com.clipsync.android.media.MediaLimits.KIND_IMAGE,
            mimeType = com.clipsync.android.media.MediaLimits.MIME_PNG,
            encodedBytes = encodedBytes,
            pixelWidth = 1,
            pixelHeight = 1,
        )
        eventsByKey[event.originDeviceId to seq] = event
        eventsById[event.eventId] = event
        val state = receiveStates[localDeviceId] ?: OriginReceiveState.EMPTY
        receiveStates[localDeviceId] = state.accept(seq)
        outbox.add(OutboxEntry(nextOutboxId++, event.originDeviceId to seq, announced = false))
        event
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

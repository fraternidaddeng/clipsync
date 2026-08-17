package com.clipsync.android.storage

import android.content.Context
import com.clipsync.android.platform.clipboard.ContentHasher
import com.clipsync.android.platform.clipboard.Sha256ContentHasher
import java.nio.charset.StandardCharsets
import java.util.UUID

fun createClipRepository(context: Context, localDeviceId: String): ClipRepository =
    ClipRepository(ClipDatabase.persistent(context), localDeviceId)

/**
 * Local clip history, outbox, and origin cursors. Clipboard bodies are never written to logs.
 */
class ClipRepository internal constructor(
    private val persistence: ClipPersistence,
    private val localDeviceId: String,
    private val hasher: ContentHasher = Sha256ContentHasher,
) {
    constructor(
        database: ClipDatabase,
        localDeviceId: String,
        hasher: ContentHasher = Sha256ContentHasher,
    ) : this(RoomClipPersistence(database), localDeviceId, hasher)

    suspend fun initialize() {
        persistence.read { /* force first open / no-op for in-memory */ }
    }

    suspend fun captureLocalText(
        text: String,
        sourceApp: String? = null,
        nowMs: Long,
        peerId: String? = null,
    ): CaptureResult {
        if (text.isEmpty()) {
            return CaptureResult.Rejected(CaptureRejectReason.EMPTY_TEXT)
        }
        val utf8Bytes = text.toByteArray(StandardCharsets.UTF_8).size
        if (utf8Bytes > MAX_CLIP_UTF8_BYTES) {
            return CaptureResult.Rejected(CaptureRejectReason.TOO_LARGE)
        }
        val contentHash = hasher.hash(text)
        val source = normalizeSource(sourceApp)
        return persistence.transaction {
            val recent = findRecentLiveByHash(
                localDeviceId,
                contentHash,
                nowMs - LOCAL_DEDUP_WINDOW_MS,
            )
            if (recent != null && nowMs - recent.createdAt < LOCAL_DEDUP_WINDOW_MS) {
                return@transaction CaptureResult.Rejected(CaptureRejectReason.DUPLICATE)
            }
            val originSeq = allocateOriginSeq(localDeviceId)
            val eventId = newEventId()
            insertClip(
                ClipEntity(
                    eventId = eventId,
                    originDeviceId = localDeviceId,
                    originSeq = originSeq,
                    kind = CLIP_KIND_TEXT,
                    content = text,
                    contentHash = contentHash,
                    sourceApp = source,
                    createdAt = nowMs,
                    expiresAt = null,
                    deletedAt = null,
                    terminalReason = null,
                ),
            )
            val nextState = receiveState(localDeviceId).accept(originSeq)
            upsertReceiveState(localDeviceId, nextState)
            fanOutOutbox(eventId, originDeviceId = localDeviceId, excludedPeerId = null, peerIdOverride = peerId)
            CaptureResult.Stored(eventId, originSeq, contentHash)
        }
    }

    suspend fun ingestRemoteClip(
        event: RemoteClipEvent,
        sourcePeerId: String? = null,
    ): RemoteStoreResult {
        require(event.originDeviceId != localDeviceId) { "Remote events cannot claim this device as origin." }
        require(event.originSeq >= 1) { "Sequences begin at 1." }
        require(event.content.isNotEmpty()) { "Empty text is not a clipboard event." }
        val utf8Bytes = event.content.toByteArray(StandardCharsets.UTF_8).size
        require(utf8Bytes <= MAX_CLIP_UTF8_BYTES) { "Remote clip exceeds 1 MiB UTF-8." }
        val computed = hasher.hash(event.content)
        require(computed == event.contentHash) { "content_hash does not match content." }
        if (event.expiresAtMs != null) {
            require(event.expiresAtMs > event.createdAtMs) { "expires_at_ms must be greater than created_at_ms." }
        }
        return persistence.transaction {
            checkIdentity(event.eventId, event.originDeviceId, event.originSeq, event.contentHash)?.let {
                return@transaction it
            }
            insertClip(
                ClipEntity(
                    eventId = event.eventId,
                    originDeviceId = event.originDeviceId,
                    originSeq = event.originSeq,
                    kind = CLIP_KIND_TEXT,
                    content = event.content,
                    contentHash = event.contentHash,
                    sourceApp = normalizeSource(event.sourceApp),
                    createdAt = event.createdAtMs,
                    expiresAt = event.expiresAtMs,
                    deletedAt = null,
                    terminalReason = null,
                ),
            )
            val state = receiveState(event.originDeviceId).accept(event.originSeq)
            upsertReceiveState(event.originDeviceId, state)
            fanOutOutbox(event.eventId, event.originDeviceId, excludedPeerId = sourcePeerId, peerIdOverride = null)
            RemoteStoreResult.Stored(state)
        }
    }

    suspend fun ingestTerminalMarker(
        marker: RemoteTerminalMarker,
        sourcePeerId: String? = null,
        receivedAtMs: Long,
    ): RemoteStoreResult {
        require(marker.originDeviceId != localDeviceId) { "Terminal markers cannot claim this device as origin." }
        require(marker.originSeq >= 1) { "Sequences begin at 1." }
        require(marker.reason in TerminalReasons.ALL) { "Unknown terminal_reason." }
        return persistence.transaction {
            checkIdentity(marker.eventId, marker.originDeviceId, marker.originSeq, expectedContentHash = null)?.let {
                return@transaction it
            }
            insertClip(
                ClipEntity(
                    eventId = marker.eventId,
                    originDeviceId = marker.originDeviceId,
                    originSeq = marker.originSeq,
                    kind = CLIP_KIND_TEXT,
                    content = "",
                    contentHash = "",
                    sourceApp = null,
                    createdAt = receivedAtMs,
                    expiresAt = null,
                    deletedAt = receivedAtMs,
                    terminalReason = marker.reason,
                ),
            )
            val state = receiveState(marker.originDeviceId).accept(marker.originSeq)
            upsertReceiveState(marker.originDeviceId, state)
            fanOutOutbox(marker.eventId, marker.originDeviceId, excludedPeerId = sourcePeerId, peerIdOverride = null)
            RemoteStoreResult.Stored(state)
        }
    }

    suspend fun knownVector(): KnownVector =
        KnownVector(persistence.read { allReceiveStates() })

    suspend fun outboxPending(peerId: String): List<OutboxEntry> =
        persistence.read { pendingOutbox(peerId).map { it.toEntry() } }

    suspend fun markAnnounced(outboxIds: List<Long>) {
        persistence.transaction { markOutboxAnnounced(outboxIds) }
    }

    suspend fun ackRanges(peerId: String, acks: List<OriginSequenceRanges>, nowMs: Long) {
        if (acks.isEmpty()) {
            return
        }
        persistence.transaction {
            for (ack in acks) {
                var cursor = peerCursor(peerId, ack.originDeviceId)
                for (range in ack.ranges) {
                    cursor = cursor.acceptRange(range)
                }
                upsertPeerCursor(peerId, ack.originDeviceId, cursor, nowMs)
                for (range in ack.ranges) {
                    deleteOutboxInRange(peerId, ack.originDeviceId, range.startSeq, range.endSeq)
                }
            }
        }
    }

    suspend fun search(query: String, limit: Int = MAX_SEARCH_LIMIT): List<ClipEntry> {
        require(limit in 1..MAX_SEARCH_LIMIT) { "Limit must be between 1 and $MAX_SEARCH_LIMIT." }
        return persistence.read {
            searchVisible(query, limit).map { it.toEntry() }
        }
    }

    suspend fun delete(eventId: String, nowMs: Long): Boolean =
        persistence.transaction {
            val deleted = softDelete(eventId, nowMs)
            if (deleted) {
                deleteOutboxForEvent(eventId)
            }
            deleted
        }

    suspend fun clear(nowMs: Long): Int =
        persistence.transaction {
            val ids = softDeleteAllVisible(nowMs)
            deleteOutboxForEvents(ids)
            ids.size
        }

    suspend fun getSetting(key: String): String? {
        require(key.isNotBlank()) { "Setting key is required." }
        return persistence.read { getSetting(key) }
    }

    suspend fun setSetting(key: String, value: String) {
        require(key.isNotBlank()) { "Setting key is required." }
        persistence.transaction { setSetting(key, value) }
    }

    suspend fun getSyncableEvents(
        originDeviceId: String,
        ranges: List<SequenceRange>,
        maximumEvents: Int,
    ): List<SyncableClipEvent> {
        require(maximumEvents >= 1) { "maximumEvents must be positive." }
        return persistence.read {
            val events = mutableListOf<SyncableClipEvent>()
            for (range in ranges) {
                if (events.size >= maximumEvents) {
                    break
                }
                val rows = clipsInRange(
                    originDeviceId,
                    range.startSeq,
                    range.endSeq,
                    maximumEvents - events.size,
                )
                events += rows.map { it.toSyncable() }
            }
            events
        }
    }

    suspend fun findLiveContentByHash(contentHash: String): String? =
        persistence.read { findLiveContentByHash(contentHash) }

    suspend fun resetOutboxToPending(peerId: String) {
        persistence.transaction { resetOutboxToPending(peerId) }
    }

    suspend fun getPeerCursors(peerId: String): Map<String, OriginReceiveState> =
        persistence.read { peerCursors(peerId) }

    private suspend fun ClipSession.checkIdentity(
        eventId: String,
        originDeviceId: String,
        originSeq: Long,
        expectedContentHash: String?,
    ): RemoteStoreResult? {
        val byKey = findClipByOriginSeq(originDeviceId, originSeq)
        if (byKey != null) {
            if (byKey.eventId != eventId) {
                return RemoteStoreResult.IdentityConflict("origin sequence maps to a different event id")
            }
            val isTerminal = byKey.deletedAt != null
            if (!isTerminal &&
                expectedContentHash != null &&
                byKey.contentHash != expectedContentHash
            ) {
                return RemoteStoreResult.IdentityConflict("origin sequence maps to different content")
            }
            return RemoteStoreResult.AlreadyPersisted
        }
        val byEvent = findClipByEventId(eventId)
        return if (byEvent == null) {
            null
        } else {
            RemoteStoreResult.IdentityConflict("event id maps to a different origin sequence")
        }
    }

    private suspend fun ClipSession.fanOutOutbox(
        eventId: String,
        originDeviceId: String,
        excludedPeerId: String?,
        peerIdOverride: String?,
    ) {
        val target = peerIdOverride?.takeIf { it.isNotBlank() } ?: getSetting(SETTING_PAIRED_PEER_ID)
        if (target.isNullOrBlank()) {
            return
        }
        if (target == originDeviceId || target == excludedPeerId) {
            return
        }
        insertOutbox(target, eventId)
    }

    private fun newEventId(): String = UUID.randomUUID().toString()

    private fun normalizeSource(sourceApp: String?): String? =
        sourceApp?.trim()?.takeIf { it.isNotEmpty() }

    private fun ClipEntity.toEntry(): ClipEntry =
        ClipEntry(
            eventId = eventId,
            originDeviceId = originDeviceId,
            originSeq = originSeq,
            content = content.orEmpty(),
            contentHash = contentHash,
            sourceApp = sourceApp,
            createdAtMs = createdAt,
            expiresAtMs = expiresAt,
        )

    private fun ClipEntity.toSyncable(): SyncableClipEvent {
        val terminal = terminalReason
        return SyncableClipEvent(
            eventId = eventId,
            originDeviceId = originDeviceId,
            originSeq = originSeq,
            content = if (terminal == null) content else null,
            contentHash = if (terminal == null) contentHash else null,
            sourceApp = sourceApp,
            createdAtMs = createdAt,
            expiresAtMs = expiresAt,
            terminalReason = terminal,
        )
    }

    private fun OutboxEntity.toEntry(): OutboxEntry =
        OutboxEntry(
            id = id,
            peerId = peerId,
            eventId = eventId,
            state = state,
            attempts = attempts,
            nextAttemptAt = nextAttemptAt,
            lastError = lastError,
        )
}

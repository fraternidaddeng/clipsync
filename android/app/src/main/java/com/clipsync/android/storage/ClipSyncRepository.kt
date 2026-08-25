package com.clipsync.android.storage

import androidx.room.withTransaction
import com.clipsync.android.media.MediaBlobStore
import com.clipsync.android.media.MediaLimits
import com.clipsync.android.sync.OriginReceiveState
import com.clipsync.android.sync.SequenceRange
import com.clipsync.android.sync.SequenceRangeJson
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The single storage entry point for the sync engine and UI. Mirrors the Windows
 * `SqliteClipboardEventStore` semantics: events commit locally (with sequence allocation,
 * receive-vector advance, and outbox fan-out) in one transaction before any network send;
 * remote stores are idempotent on `(origin_device_id, origin_seq)`; deletes are local soft
 * deletes that keep terminal markers so peers' sequence gaps still close.
 *
 * Image clips (protocol v2 / ADR 0004) keep their encoded bytes in [media]; the clips row
 * carries `kind = image`, an empty body, and the blob hash, with `media_blobs`/`clip_media`
 * rows joining event to blob. [media] is optional so text-only tests need no filesystem.
 */
class ClipSyncRepository(
    private val database: ClipSyncDatabase,
    val localDeviceId: String,
    val media: MediaBlobStore? = null,
) {
    init {
        require(localDeviceId.isNotBlank()) { "localDeviceId cannot be blank" }
    }

    private val clips get() = database.clipEvents()
    private val outbox get() = database.outbox()
    private val cursors get() = database.peerCursors()
    private val receiveState get() = database.originReceiveState()
    private val sequences get() = database.localSequences()
    private val mediaBlobs get() = database.mediaBlobs()
    private val clipMedia get() = database.clipMedia()

    // ---- Local capture ----

    /**
     * Commits a locally captured clip: allocates the next origin sequence, stores the row,
     * advances this device's own receive vector, and enqueues one outbox row per peer, all
     * atomically. The event only reaches the network after this returns.
     */
    suspend fun storeLocalEvent(
        draft: LocalClipDraft,
        fanOutPeerIds: List<String>,
    ): StoredClipEvent = database.withTransaction {
        val originSeq = allocateSequence()
        val eventId = UUID.randomUUID().toString()
        clips.insert(
            ClipEventEntity(
                eventId = eventId,
                originDeviceId = localDeviceId,
                originSeq = originSeq,
                content = draft.content,
                contentHash = draft.contentHash,
                sourceApp = draft.sourceApp,
                createdAtMs = draft.capturedAtMs,
                expiresAtMs = draft.expiresAtMs,
                deletedAtMs = null,
                terminalReason = null,
                appliedAtMs = null,
            ),
        )
        advanceReceiveState(localDeviceId, originSeq)
        enqueueOutbox(eventId, localDeviceId, originSeq, fanOutPeerIds, excludedPeerId = null)
        StoredClipEvent(eventId, localDeviceId, originSeq)
    }

    /**
     * Commits a locally captured image clip. The caller has already validated the bytes and
     * committed the blob into [media]; this transaction writes the clips row (`kind = image`,
     * empty body, blob hash), the blob metadata, the event-to-blob link, the receive-vector
     * advance, and the outbox fan-out together.
     */
    suspend fun storeLocalImageEvent(
        draft: LocalImageDraft,
        fanOutPeerIds: List<String>,
    ): StoredClipEvent = database.withTransaction {
        val originSeq = allocateSequence()
        val eventId = UUID.randomUUID().toString()
        clips.insert(
            ClipEventEntity(
                eventId = eventId,
                originDeviceId = localDeviceId,
                originSeq = originSeq,
                kind = ClipKinds.IMAGE,
                content = "",
                contentHash = draft.contentHash,
                sourceApp = draft.sourceApp,
                createdAtMs = draft.capturedAtMs,
                expiresAtMs = draft.expiresAtMs,
                deletedAtMs = null,
                terminalReason = null,
                appliedAtMs = null,
            ),
        )
        writeMediaRows(
            eventId,
            ClipMediaRef(
                contentHash = draft.contentHash,
                mimeType = draft.mimeType,
                encodedBytes = draft.encodedBytes,
                pixelWidth = draft.pixelWidth,
                pixelHeight = draft.pixelHeight,
            ),
            draft.capturedAtMs,
        )
        advanceReceiveState(localDeviceId, originSeq)
        enqueueOutbox(eventId, localDeviceId, originSeq, fanOutPeerIds, excludedPeerId = null)
        StoredClipEvent(eventId, localDeviceId, originSeq)
    }

    // ---- Remote inbox ----

    /**
     * Persists a peer-delivered event idempotently. Retries of the identical event return
     * [RemoteStoreResult.AlreadyPersisted]; a different identity under the same key returns
     * [RemoteStoreResult.IdentityConflict], which the protocol treats as fatal.
     */
    suspend fun storeRemoteEvent(
        remoteEvent: RemoteClipEvent,
        sourcePeerId: String?,
        fanOutPeerIds: List<String> = emptyList(),
    ): RemoteStoreResult {
        require(remoteEvent.originDeviceId != localDeviceId) {
            "Remote events cannot claim this device as origin."
        }
        require(remoteEvent.kind != ClipKinds.IMAGE || remoteEvent.media != null) {
            "Remote image events must carry blob metadata."
        }
        return database.withTransaction {
            checkRemoteIdentity(
                remoteEvent.eventId,
                remoteEvent.originDeviceId,
                remoteEvent.originSeq,
                expectedContentHash = remoteEvent.contentHash,
            )?.let { return@withTransaction it }

            clips.insert(
                ClipEventEntity(
                    eventId = remoteEvent.eventId,
                    originDeviceId = remoteEvent.originDeviceId,
                    originSeq = remoteEvent.originSeq,
                    kind = remoteEvent.kind,
                    content = remoteEvent.content,
                    contentHash = remoteEvent.contentHash,
                    sourceApp = remoteEvent.sourceApp,
                    createdAtMs = remoteEvent.createdAtMs,
                    expiresAtMs = remoteEvent.expiresAtMs,
                    deletedAtMs = null,
                    terminalReason = null,
                    appliedAtMs = null,
                ),
            )
            remoteEvent.media?.let { writeMediaRows(remoteEvent.eventId, it, remoteEvent.createdAtMs) }
            val state = advanceReceiveState(remoteEvent.originDeviceId, remoteEvent.originSeq)
            enqueueOutbox(
                remoteEvent.eventId,
                remoteEvent.originDeviceId,
                remoteEvent.originSeq,
                fanOutPeerIds,
                excludedPeerId = sourcePeerId,
            )
            RemoteStoreResult.Stored(state)
        }
    }

    /** Persists an origin-authoritative unavailable marker; advances cursors without content. */
    suspend fun storeRemoteTerminal(
        marker: RemoteTerminalMarker,
        sourcePeerId: String?,
        receivedAtMs: Long,
        fanOutPeerIds: List<String> = emptyList(),
    ): RemoteStoreResult {
        require(marker.originDeviceId != localDeviceId) {
            "Terminal markers cannot claim this device as origin."
        }
        return database.withTransaction {
            checkRemoteIdentity(
                marker.eventId,
                marker.originDeviceId,
                marker.originSeq,
                expectedContentHash = null,
            )?.let { return@withTransaction it }

            clips.insert(
                ClipEventEntity(
                    eventId = marker.eventId,
                    originDeviceId = marker.originDeviceId,
                    originSeq = marker.originSeq,
                    content = "",
                    contentHash = "",
                    sourceApp = null,
                    createdAtMs = receivedAtMs,
                    expiresAtMs = null,
                    deletedAtMs = receivedAtMs,
                    terminalReason = marker.reason,
                    appliedAtMs = null,
                ),
            )
            val state = advanceReceiveState(marker.originDeviceId, marker.originSeq)
            enqueueOutbox(
                marker.eventId,
                marker.originDeviceId,
                marker.originSeq,
                fanOutPeerIds,
                excludedPeerId = sourcePeerId,
            )
            RemoteStoreResult.Stored(state)
        }
    }

    // ---- History ----

    suspend fun searchHistory(query: HistoryQuery = HistoryQuery()): List<ClipHistoryEntry> {
        return clips.search(likePattern(query), query.limit, query.offset).map { it.toHistoryEntry() }
    }

    /** [searchHistory] as a Room-invalidation-driven stream; [HistoryQuery.offset] is ignored. */
    fun observeHistory(query: HistoryQuery = HistoryQuery()): Flow<List<ClipHistoryEntry>> =
        clips.observeSearch(likePattern(query), query.limit)
            .map { entities -> entities.map { it.toHistoryEntry() } }

    suspend fun getById(eventId: String, includeDeleted: Boolean = false): ClipHistoryEntry? =
        clips.getByEventId(eventId, includeDeleted)?.toHistoryEntry()

    /** Records that a remote event's text reached this device's system clipboard. */
    suspend fun markApplied(eventId: String, appliedAtMs: Long): Boolean =
        clips.markApplied(eventId, appliedAtMs) == 1

    /**
     * Local delete: erases the content but keeps the row as a `deleted` terminal marker so the
     * same acknowledged event never reappears and peers' gap requests get a terminal answer.
     * A still-queued outbox row now announces `unavailable` instead of the body.
     */
    suspend fun deleteEvent(eventId: String, deletedAtMs: Long): Boolean =
        clips.softDelete(eventId, deletedAtMs) == 1

    /** Clears all visible history the same way [deleteEvent] does; returns rows affected. */
    suspend fun clearHistory(deletedAtMs: Long): Int = clips.softDeleteAll(deletedAtMs)

    /** Expires rows older than the policy age or beyond the entry cap; returns rows affected. */
    suspend fun cleanup(policy: RetentionPolicy, nowMs: Long): Int {
        val expired = database.withTransaction {
            clips.cleanup(nowMs - policy.maximumAgeMs, policy.maximumEntries, nowMs)
        }
        collectMediaGarbage(nowMs)
        return expired
    }

    /**
     * Media housekeeping: drops event-to-blob links whose clip row was deleted or expired,
     * removes now-unreferenced blob metadata, then deletes the on-disk bytes that no metadata
     * row references anymore (with the protocol grace period so in-flight sends finish).
     */
    suspend fun collectMediaGarbage(nowMs: Long) {
        val store = media ?: return
        database.withTransaction {
            clipMedia.deleteOrphaned()
            mediaBlobs.deleteUnreferenced()
        }
        runCatching {
            store.recoverTemps(nowMs)
            store.deleteUnreferenced(
                liveHashes = mediaBlobs.allHashes(),
                gracePeriodMs = MediaLimits.BLOB_GC_GRACE_MS,
            )
        }
    }

    // ---- Sync projection ----

    /** Every origin's persisted receive state, including this device's own contiguous history. */
    suspend fun knownVector(): Map<String, OriginReceiveState> =
        receiveState.all().associate { it.originDeviceId to it.toState() }

    /** Rows for the requested ranges of one origin, capped to [maximumEvents], ordered by sequence. */
    suspend fun getSyncableEvents(
        originDeviceId: String,
        ranges: List<SequenceRange>,
        maximumEvents: Int,
    ): List<SyncableClipEvent> {
        require(maximumEvents >= 1) { "maximumEvents must be positive." }
        val events = mutableListOf<SyncableClipEvent>()
        for (range in ranges) {
            if (events.size >= maximumEvents) {
                break
            }
            clips.syncableInRange(originDeviceId, range.startSeq, range.endSeq, maximumEvents - events.size)
                .mapTo(events) { it.toSyncableWithMedia() }
        }
        return events
    }

    suspend fun getSyncableEventsByIds(eventIds: List<String>): List<SyncableClipEvent> =
        if (eventIds.isEmpty()) {
            emptyList()
        } else {
            clips.syncableByIds(eventIds).map { it.toSyncableWithMedia() }
        }

    /** Finds live content with the given hash so an announced event can be materialized without a fetch. */
    suspend fun findLiveContentByHash(contentHash: String): String? =
        clips.findLiveContentByHash(contentHash)

    /** True when a live image row with this blob hash exists and its bytes are still on disk. */
    suspend fun findLiveImageByHash(contentHash: String): Boolean {
        if (clips.countLiveImagesByHash(contentHash) == 0) {
            return false
        }
        return media?.exists(contentHash) == true
    }

    /** Blob metadata for one image clip; null for text rows or when the metadata is gone. */
    suspend fun mediaRefFor(eventId: String): ClipMediaRef? {
        val link = clipMedia.find(eventId) ?: return null
        return mediaBlobs.find(link.contentHash)?.toRef()
    }

    // ---- Outbox ----

    suspend fun outboxBatch(peerId: String, limit: Int): List<OutboxBatchItem> {
        require(limit >= 1) { "limit must be positive." }
        return outbox.pendingBatch(peerId, limit).map { row ->
            OutboxBatchItem(
                outboxId = row.outboxId,
                peerId = row.peerId,
                state = row.state,
                attempts = row.attempts,
                event = row.clip.toSyncableWithMedia(),
            )
        }
    }

    suspend fun markOutboxAnnounced(outboxIds: List<Long>) {
        if (outboxIds.isNotEmpty()) {
            outbox.markAnnounced(outboxIds)
        }
    }

    /** Returns announced-but-unacked entries to pending, e.g. at the start of a new session. */
    suspend fun resetOutboxToPending(peerId: String) = outbox.resetToPending(peerId)

    suspend fun pendingOutboxCount(peerId: String): Int = outbox.pendingCount(peerId)

    suspend fun totalPendingOutboxCount(): Int = outbox.totalPendingCount()

    /**
     * Removes outbox rows the peer has persisted according to its acks or known vector, and
     * advances the peer cursor, in one transaction.
     */
    suspend fun applyPeerAckRanges(
        peerId: String,
        acks: List<OriginAckRanges>,
        nowMs: Long,
    ) {
        if (acks.isEmpty()) {
            return
        }
        database.withTransaction {
            for (ack in acks) {
                var cursor = cursors.get(peerId, ack.originDeviceId)?.toState()
                    ?: OriginReceiveState.EMPTY
                for (range in ack.ranges) {
                    cursor = cursor.acceptRange(range)
                }
                cursors.upsert(
                    PeerCursorEntity(
                        peerId = peerId,
                        originDeviceId = ack.originDeviceId,
                        contiguousSeq = cursor.contiguousSeq,
                        receivedRangesJson = SequenceRangeJson.serialize(cursor.receivedRanges),
                        updatedAtMs = nowMs,
                    ),
                )
                for (range in ack.ranges) {
                    outbox.deleteAckedRange(peerId, ack.originDeviceId, range.startSeq, range.endSeq)
                }
            }
        }
    }

    suspend fun peerCursors(peerId: String): Map<String, OriginReceiveState> =
        cursors.listForPeer(peerId).associate { it.originDeviceId to it.toState() }

    // ---- History export/import (docs/export-format-v1.md / docs/export-format-v2.md) ----

    /**
     * Streams the whole clips table — text and image, live rows and terminal tombstones —
     * as an export-format JSON Lines document. The header declares format_version 1 when
     * nothing needs v2 (so older builds keep importing) and 2 when image rows or
     * unsupported_media tombstones exist. Live image records embed the encoded blob bytes
     * as base64 when they are on disk and within the 16 MiB cap; a missing blob degrades
     * that record to metadata-only instead of failing the export. Read-only; returns the
     * exported event count. The stream is flushed but not closed (the caller owns it).
     */
    suspend fun exportHistory(output: java.io.OutputStream, exportedAtMs: Long): Int {
        val rows = clips.exportAll()
        val needsV2 = rows.any {
            it.kind == ClipKinds.IMAGE || it.terminalReason == TerminalReasons.UNSUPPORTED_MEDIA
        }
        val formatVersion = if (needsV2) {
            HistoryExportFormat.FORMAT_VERSION
        } else {
            HistoryExportFormat.TEXT_ONLY_FORMAT_VERSION
        }
        val writer = output.bufferedWriter(Charsets.UTF_8)
        writer.write(
            HistoryExportFormat.writeHeaderLine(
                HistoryExportHeader(formatVersion, exportedAtMs, localDeviceId, "android", rows.size),
            ),
        )
        writer.write("\n")
        for (row in rows) {
            writer.write(HistoryExportFormat.writeClipLine(row.toExportedClip()))
            writer.write("\n")
        }
        writer.flush()
        return rows.size
    }

    /**
     * Merge-imports an export-format v1/v2 document. The whole file is parsed and validated
     * first (including embedded image bytes: base64, size cap, hash, magic/dimensions), then
     * applied in one transaction: idempotent on (origin_device_id, origin_seq), no outbox
     * fan-out, receive vector advanced, and the local sequence allocator bumped past any
     * restored own-origin events. A validation failure changes nothing.
     */
    suspend fun importHistory(input: java.io.InputStream): HistoryImportResult {
        val reader = input.bufferedReader(Charsets.UTF_8)
        var headerLine: String?
        do {
            headerLine = reader.readLine()
                ?: throw HistoryTransferException(HistoryTransferErrorCodes.BAD_HEADER, "The file is empty.")
        } while (headerLine.isBlank())

        val header = HistoryExportFormat.parseHeaderLine(headerLine)
        val records = mutableListOf<HistoryExportedClip>()
        var lineNumber = 1
        while (true) {
            val line = reader.readLine() ?: break
            lineNumber++
            if (line.isBlank()) {
                continue
            }
            records.add(HistoryExportFormat.parseClipLine(line, lineNumber, header.formatVersion))
        }
        if (records.size != header.eventCount) {
            throw HistoryTransferException(
                HistoryTransferErrorCodes.COUNT_MISMATCH,
                "The header announces ${header.eventCount} events but the file carries ${records.size}.",
            )
        }
        return importParsedHistory(records)
    }

    /** The transactional half of [importHistory]; also the unit-test entry point. */
    suspend fun importParsedHistory(records: List<HistoryExportedClip>): HistoryImportResult =
        database.withTransaction {
            var imported = 0
            var skipped = 0
            var conflicts = 0
            var maxOwnOriginSeq = 0L
            for (record in records) {
                when (checkRemoteIdentity(record.eventId, record.originDeviceId, record.originSeq, record.contentHash)) {
                    is RemoteStoreResult.AlreadyPersisted -> {
                        skipped++
                        continue
                    }
                    is RemoteStoreResult.IdentityConflict -> {
                        conflicts++
                        continue
                    }
                    else -> Unit
                }
                clips.insert(
                    ClipEventEntity(
                        eventId = record.eventId,
                        originDeviceId = record.originDeviceId,
                        originSeq = record.originSeq,
                        kind = record.kind,
                        content = record.content ?: "",
                        contentHash = record.contentHash ?: "",
                        sourceApp = record.sourceApp,
                        createdAtMs = record.createdAtMs,
                        expiresAtMs = record.expiresAtMs,
                        deletedAtMs = record.deletedAtMs,
                        terminalReason = record.terminalReason,
                        appliedAtMs = null,
                    ),
                )
                if (record.isImage && !record.isTerminal) {
                    record.media?.let { importMediaRows(record, it) }
                }
                advanceReceiveState(record.originDeviceId, record.originSeq)
                if (record.originDeviceId == localDeviceId) {
                    maxOwnOriginSeq = maxOf(maxOwnOriginSeq, record.originSeq)
                }
                imported++
            }
            // Never lower the allocator: restored own-origin events must not collide with
            // future captures.
            if (maxOwnOriginSeq > 0) {
                val next = sequences.nextSeq(localDeviceId) ?: 1L
                sequences.upsert(LocalSequenceEntity(localDeviceId, maxOf(next, maxOwnOriginSeq + 1)))
            }
            HistoryImportResult(imported, skipped, conflicts)
        }

    /**
     * Restores the blob and its rows for one imported live image record. Embedded bytes are
     * committed into the content-addressed [media] store (idempotent by hash; re-validated
     * exactly like the sync ingress). The blob-metadata row never downgrades from ready; the
     * event-to-blob link is ready exactly when the bytes are on disk, missing otherwise
     * (metadata-only records, or a repository built without a blob store).
     */
    private suspend fun importMediaRows(record: HistoryExportedClip, blob: HistoryExportedMedia) {
        val hash = requireNotNull(record.contentHash)
        val store = media
        if (store != null && blob.encodedData != null && !store.exists(hash)) {
            store.commitBytes(blob.encodedData, hash)
        }
        val bytesOnDisk = store?.exists(hash) == true
        val existing = mediaBlobs.find(hash)
        if (existing == null || (existing.state != MediaLimits.BLOB_STATE_READY && bytesOnDisk)) {
            mediaBlobs.upsert(
                MediaBlobEntity(
                    contentHash = hash,
                    mimeType = blob.mimeType,
                    encodedBytes = blob.encodedBytes,
                    pixelWidth = blob.pixelWidth,
                    pixelHeight = blob.pixelHeight,
                    state = if (bytesOnDisk) MediaLimits.BLOB_STATE_READY else MediaLimits.BLOB_STATE_PENDING,
                    createdAtMs = record.createdAtMs,
                ),
            )
        }
        clipMedia.upsert(
            ClipMediaEntity(
                eventId = record.eventId,
                contentHash = hash,
                state = if (bytesOnDisk) MediaLimits.CLIP_MEDIA_READY else MediaLimits.CLIP_MEDIA_MISSING,
            ),
        )
    }

    private suspend fun ClipEventEntity.toExportedClip(): HistoryExportedClip = HistoryExportedClip(
        eventId = eventId,
        originDeviceId = originDeviceId,
        originSeq = originSeq,
        kind = kind,
        content = if (terminalReason == null && kind == ClipKinds.TEXT) content else null,
        contentHash = if (terminalReason == null) contentHash else null,
        sourceApp = if (terminalReason == null) sourceApp else null,
        createdAtMs = createdAtMs,
        expiresAtMs = expiresAtMs,
        deletedAtMs = deletedAtMs,
        terminalReason = terminalReason,
        media = if (kind == ClipKinds.IMAGE && terminalReason == null) {
            val ref = checkNotNull(mediaRefFor(eventId)) { "A live image clip has no media metadata row." }
            HistoryExportedMedia(
                mimeType = ref.mimeType,
                encodedBytes = ref.encodedBytes,
                pixelWidth = ref.pixelWidth,
                pixelHeight = ref.pixelHeight,
                encodedData = readBlobForEmbedding(contentHash),
            )
        } else {
            null
        },
    )

    /** Encoded blob bytes for embedding, or null when the file is missing/unreadable/over the cap. */
    private fun readBlobForEmbedding(contentHash: String): ByteArray? {
        val store = media ?: return null
        return runCatching {
            if (!store.exists(contentHash)) {
                return null
            }
            store.readAllBytes(contentHash)
                .takeIf { it.size <= HistoryExportFormat.MAX_EMBEDDED_MEDIA_BYTES }
        }.getOrNull()
    }

    /** Drops all queue and cursor state for a revoked/unpaired peer; history is untouched. */
    suspend fun forgetPeer(peerId: String) = database.withTransaction {
        outbox.deleteForPeer(peerId)
        cursors.deleteForPeer(peerId)
    }

    // ---- Internals (all callers hold the enclosing transaction) ----

    private suspend fun allocateSequence(): Long {
        val next = sequences.nextSeq(localDeviceId) ?: 1L
        sequences.upsert(LocalSequenceEntity(localDeviceId, next + 1))
        return next
    }

    private suspend fun advanceReceiveState(originDeviceId: String, originSeq: Long): OriginReceiveState {
        val state = (receiveState.get(originDeviceId)?.toState() ?: OriginReceiveState.EMPTY)
            .accept(originSeq)
        receiveState.upsert(
            OriginReceiveStateEntity(
                originDeviceId = originDeviceId,
                contiguousSeq = state.contiguousSeq,
                receivedRangesJson = SequenceRangeJson.serialize(state.receivedRanges),
            ),
        )
        return state
    }

    private suspend fun enqueueOutbox(
        eventId: String,
        originDeviceId: String,
        originSeq: Long,
        peerIds: List<String>,
        excludedPeerId: String?,
    ) {
        val entries = peerIds
            .distinct()
            .filter { it != originDeviceId && it != excludedPeerId }
            .map { peerId ->
                OutboxEntryEntity(
                    peerId = peerId,
                    eventId = eventId,
                    originDeviceId = originDeviceId,
                    originSeq = originSeq,
                )
            }
        if (entries.isNotEmpty()) {
            outbox.insertAll(entries)
        }
    }

    private suspend fun checkRemoteIdentity(
        eventId: String,
        originDeviceId: String,
        originSeq: Long,
        expectedContentHash: String?,
    ): RemoteStoreResult? {
        val byKey = clips.getByOriginSeq(originDeviceId, originSeq)
        if (byKey != null) {
            if (byKey.eventId != eventId) {
                return RemoteStoreResult.IdentityConflict("origin sequence maps to a different event id")
            }
            val isTerminal = byKey.deletedAtMs != null
            if (!isTerminal && expectedContentHash != null && byKey.contentHash != expectedContentHash) {
                return RemoteStoreResult.IdentityConflict("origin sequence maps to different content")
            }
            return RemoteStoreResult.AlreadyPersisted
        }

        return if (clips.getByEventId(eventId, includeDeleted = true) == null) {
            null
        } else {
            RemoteStoreResult.IdentityConflict("event id maps to a different origin sequence")
        }
    }

    /** Writes/refreshes the blob-metadata row and the event-to-blob link for one image event. */
    private suspend fun writeMediaRows(eventId: String, ref: ClipMediaRef, createdAtMs: Long) {
        mediaBlobs.upsert(
            MediaBlobEntity(
                contentHash = ref.contentHash,
                mimeType = ref.mimeType,
                encodedBytes = ref.encodedBytes,
                pixelWidth = ref.pixelWidth,
                pixelHeight = ref.pixelHeight,
                state = MediaLimits.BLOB_STATE_READY,
                createdAtMs = createdAtMs,
            ),
        )
        clipMedia.upsert(
            ClipMediaEntity(
                eventId = eventId,
                contentHash = ref.contentHash,
                state = MediaLimits.CLIP_MEDIA_READY,
            ),
        )
    }

    private suspend fun ClipEventEntity.toSyncableWithMedia(): SyncableClipEvent = toSyncable(
        media = if (kind == ClipKinds.IMAGE && terminalReason == null) mediaRefFor(eventId) else null,
    )

    private fun MediaBlobEntity.toRef(): ClipMediaRef = ClipMediaRef(
        contentHash = contentHash,
        mimeType = mimeType,
        encodedBytes = encodedBytes,
        pixelWidth = pixelWidth,
        pixelHeight = pixelHeight,
    )

    private fun PeerCursorEntity.toState(): OriginReceiveState =
        OriginReceiveState(contiguousSeq, SequenceRangeJson.deserialize(receivedRangesJson))

    private fun OriginReceiveStateEntity.toState(): OriginReceiveState =
        OriginReceiveState(contiguousSeq, SequenceRangeJson.deserialize(receivedRangesJson))

    private companion object {
        fun likePattern(query: HistoryQuery): String? = query.searchText
            ?.takeIf { it.isNotEmpty() }
            ?.let { "%${escapeLikePattern(it)}%" }

        fun escapeLikePattern(value: String): String = value
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
    }
}

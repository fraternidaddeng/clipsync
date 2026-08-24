package com.clipsync.android.storage

import android.content.Context
import com.clipsync.android.media.MediaBlobStore
import com.clipsync.android.media.MediaLimits
import com.clipsync.android.media.ValidatedImage
import com.clipsync.android.platform.clipboard.ContentHasher
import com.clipsync.android.platform.clipboard.Sha256ContentHasher
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.transform

fun createClipRepository(context: Context, localDeviceId: String): ClipRepository {
    val app = context.applicationContext
    val database = ClipDatabase.persistent(app)
    val media = MediaBlobStore(File(app.filesDir, "media"))
    return ClipRepository(RoomClipPersistence(database), localDeviceId, Sha256ContentHasher, media)
}

/**
 * Local clip history, outbox, and origin cursors. Clipboard bodies are never written to logs.
 */
class ClipRepository internal constructor(
    private val persistence: ClipPersistence,
    private val localDeviceId: String,
    private val hasher: ContentHasher = Sha256ContentHasher,
    val media: MediaBlobStore = MediaBlobStore(
        File(System.getProperty("java.io.tmpdir"), "clipsync-media-$localDeviceId"),
    ),
) {
    constructor(
        database: ClipDatabase,
        localDeviceId: String,
        hasher: ContentHasher = Sha256ContentHasher,
        media: MediaBlobStore = MediaBlobStore(
            File(System.getProperty("java.io.tmpdir"), "clipsync-media-$localDeviceId"),
        ),
    ) : this(RoomClipPersistence(database), localDeviceId, hasher, media)

    suspend fun initialize() {
        persistence.read { /* force first open / no-op for in-memory */ }
        media.recoverTemps(System.currentTimeMillis())
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
        val contentHash = hasher.hash(text)
        val source = normalizeSource(sourceApp)
        return persistence.transaction {
            val policy = CapturePolicy.load { getSetting(it) }
            when (val decision = CapturePolicy.evaluate(source, utf8Bytes, policy)) {
                is PolicyDecision.Reject -> return@transaction CaptureResult.Rejected(decision.reason)
                PolicyDecision.Allow -> Unit
            }
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
            fanOutOutbox(eventId, originDeviceId = localDeviceId, excludedPeerId = null, peerId = peerId)
            CaptureResult.Stored(eventId, originSeq, contentHash)
        }
    }

    suspend fun captureLocalImage(
        encoded: ByteArray,
        sourceApp: String? = null,
        nowMs: Long,
        peerId: String? = null,
        expectedHash: String? = null,
    ): CaptureResult {
        if (encoded.isEmpty()) {
            return CaptureResult.Rejected(CaptureRejectReason.EMPTY_TEXT)
        }
        val source = normalizeSource(sourceApp)
        val policy = persistence.read { CapturePolicy.load { getSetting(it) } }
        when (val decision = CapturePolicy.evaluateImage(source, encoded.size, policy)) {
            is PolicyDecision.Reject -> return CaptureResult.Rejected(decision.reason)
            PolicyDecision.Allow -> Unit
        }
        val validated = try {
            media.commitBytes(encoded, expectedHash)
        } catch (_: Exception) {
            return CaptureResult.Rejected(CaptureRejectReason.DECODE_FAILED)
        }
        return persistence.transaction {
            val recent = findRecentLiveByHash(
                localDeviceId,
                validated.contentHash,
                nowMs - LOCAL_DEDUP_WINDOW_MS,
            )
            if (recent != null && nowMs - recent.createdAt < LOCAL_DEDUP_WINDOW_MS) {
                return@transaction CaptureResult.Rejected(CaptureRejectReason.DUPLICATE)
            }
            val originSeq = allocateOriginSeq(localDeviceId)
            val eventId = newEventId()
            insertImageClip(eventId, localDeviceId, originSeq, validated, source, nowMs, expiresAt = null)
            val nextState = receiveState(localDeviceId).accept(originSeq)
            upsertReceiveState(localDeviceId, nextState)
            fanOutOutbox(eventId, originDeviceId = localDeviceId, excludedPeerId = null, peerId = peerId)
            CaptureResult.Stored(eventId, originSeq, validated.contentHash, CLIP_KIND_IMAGE)
        }
    }

    suspend fun ingestRemoteClip(
        event: RemoteClipEvent,
        sourcePeerId: String? = null,
    ): RemoteStoreResult {
        require(event.originDeviceId != localDeviceId) { "Remote events cannot claim this device as origin." }
        require(event.originSeq >= 1) { "Sequences begin at 1." }
        if (event.expiresAtMs != null) {
            require(event.expiresAtMs > event.createdAtMs) { "expires_at_ms must be greater than created_at_ms." }
        }
        if (event.isImage) {
            require(event.content == null) { "Image events must not carry a text body." }
            require(media.exists(event.contentHash)) { "MEDIA_STORAGE_FAILED" }
        } else {
            val content = event.content
            require(!content.isNullOrEmpty()) { "Empty text is not a clipboard event." }
            val utf8Bytes = content.toByteArray(StandardCharsets.UTF_8).size
            require(utf8Bytes <= MAX_CLIP_UTF8_BYTES) { "Remote clip exceeds 1 MiB UTF-8." }
            val computed = hasher.hash(content)
            require(computed == event.contentHash) { "content_hash does not match content." }
        }
        return persistence.transaction {
            checkIdentity(event.eventId, event.originDeviceId, event.originSeq, event.contentHash)?.let {
                return@transaction it
            }
            if (event.isImage) {
                val blob = media.requirePath(event.contentHash)
                val inspect = com.clipsync.android.media.ImageCodec.tryInspectFile(blob, event.contentHash)
                val validated = inspect.second
                require(inspect.first == com.clipsync.android.media.ImageCodecError.OK && validated != null) {
                    "MEDIA_DECODE_FAILED"
                }
                insertImageClip(
                    event.eventId,
                    event.originDeviceId,
                    event.originSeq,
                    validated,
                    normalizeSource(event.sourceApp),
                    event.createdAtMs,
                    event.expiresAtMs,
                )
            } else {
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
            }
            val state = receiveState(event.originDeviceId).accept(event.originSeq)
            upsertReceiveState(event.originDeviceId, state)
            fanOutOutbox(event.eventId, event.originDeviceId, excludedPeerId = sourcePeerId, peerId = null)
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
        val result = persistence.transaction {
            val existing = findClipByOriginSeq(marker.originDeviceId, marker.originSeq)
            if (existing != null) {
                if (existing.eventId != marker.eventId) {
                    return@transaction RemoteStoreResult.IdentityConflict(
                        "origin sequence maps to a different event id",
                    )
                }
                val state = receiveState(marker.originDeviceId).accept(marker.originSeq)
                upsertReceiveState(marker.originDeviceId, state)
                if (existing.deletedAt != null || marker.reason != TerminalReasons.DELETED) {
                    return@transaction RemoteStoreResult.AlreadyPersisted
                }
                softDelete(existing.eventId, receivedAtMs)
                fanOutOutbox(existing.eventId, marker.originDeviceId, excludedPeerId = sourcePeerId, peerId = null)
                return@transaction RemoteStoreResult.Stored(state)
            }
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
            fanOutOutbox(marker.eventId, marker.originDeviceId, excludedPeerId = sourcePeerId, peerId = null)
            RemoteStoreResult.Stored(state)
        }
        if (result is RemoteStoreResult.Stored) {
            collectUnreferencedMedia()
        }
        return result
    }

    suspend fun knownVector(): KnownVector =
        KnownVector(persistence.read { allReceiveStates() })

    /**
     * Raises the local allocator so the next capture is above [highestSeq].
     * Used when a peer's known_vector shows it already holds our origin
     * further than this database (same device id after a local reset).
     */
    suspend fun adoptPeerCoverageOfLocalOrigin(highestSeq: Long) {
        if (highestSeq < 1) {
            return
        }
        persistence.transaction { advanceOriginSeq(localDeviceId, highestSeq) }
    }

    suspend fun outboxPending(peerId: String): List<OutboxEntry> =
        persistence.read { pendingOutbox(peerId).map { it.toEntry() } }

    suspend fun markAnnounced(outboxIds: List<Long>) {
        persistence.transaction { markOutboxAnnounced(outboxIds) }
    }

    suspend fun ackRanges(
        peerId: String,
        acks: List<OriginSequenceRanges>,
        nowMs: Long,
        dropTerminalOutbox: Boolean = true,
    ) {
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
                    deleteOutboxInRange(
                        peerId,
                        ack.originDeviceId,
                        range.startSeq,
                        range.endSeq,
                        dropTerminalOutbox,
                    )
                }
            }
        }
    }

    suspend fun search(query: String, limit: Int = MAX_SEARCH_LIMIT, offset: Int = 0): List<ClipEntry> {
        require(limit in 1..MAX_SEARCH_LIMIT) { "Limit must be between 1 and $MAX_SEARCH_LIMIT." }
        require(offset >= 0) { "Offset must be >= 0." }
        return persistence.read {
            searchVisible(query, limit, offset).map { row ->
                row.toEntry(if (row.kind == CLIP_KIND_IMAGE) findMediaBlob(row.contentHash) else null)
            }
        }
    }

    /**
     * Visible history row for [eventId], or null if missing, tombstoned, or body-less.
     * Used by notification copy so it is not capped by [search]'s 2000-row window.
     */
    suspend fun findVisibleEntry(eventId: String): ClipEntry? =
        persistence.read {
            val entity = findClipByEventId(eventId)
            val nowMs = System.currentTimeMillis()
            if (entity == null || entity.deletedAt != null) {
                null
            } else if (entity.expiresAt != null && entity.expiresAt <= nowMs) {
                null
            } else if (entity.kind == CLIP_KIND_IMAGE) {
                entity.toEntry(findMediaBlob(entity.contentHash))
            } else if (entity.content == null) {
                null
            } else {
                entity.toEntry()
            }
        }

    fun observeSearch(query: String, limit: Int = MAX_SEARCH_LIMIT): Flow<List<ClipEntry>> {
        require(limit in 1..MAX_SEARCH_LIMIT) { "Limit must be between 1 and $MAX_SEARCH_LIMIT." }
        return persistence.observeSearchVisible(query, limit).transform { rows ->
            emit(
                persistence.read {
                    rows.map { row ->
                        row.toEntry(if (row.kind == CLIP_KIND_IMAGE) findMediaBlob(row.contentHash) else null)
                    }
                },
            )
        }
    }

    suspend fun delete(eventId: String, nowMs: Long): Boolean {
        val deleted = persistence.transaction {
            val ok = softDelete(eventId, nowMs)
            if (ok) {
                enqueueTombstone(eventId)
            }
            ok
        }
        if (deleted) {
            collectUnreferencedMedia()
        }
        return deleted
    }

    suspend fun clear(nowMs: Long): Int {
        val removed = persistence.transaction {
            softDeleteAllVisible(nowMs).size
        }
        collectUnreferencedMedia()
        return removed
    }

    suspend fun searchAllVisible(): List<ClipEntry> {
        val all = mutableListOf<ClipEntry>()
        var offset = 0
        while (true) {
            val page = search("", MAX_SEARCH_LIMIT, offset)
            if (page.isEmpty()) {
                break
            }
            all += page
            if (page.size < MAX_SEARCH_LIMIT) {
                break
            }
            offset += page.size
        }
        return all
    }

    suspend fun exportJsonLines(mediaDirectory: File? = null): String {
        val rows = searchAllVisible()
        if (mediaDirectory != null) {
            mediaDirectory.mkdirs()
            for (row in rows) {
                if (!row.isImage) {
                    continue
                }
                val source = media.blobPath(row.contentHash)
                if (source.isFile) {
                    source.copyTo(File(mediaDirectory, row.contentHash), overwrite = true)
                }
            }
        }
        return ClipExport.encodeJsonLines(rows, includeHeader = false, originDeviceId = localDeviceId)
    }

    suspend fun getSyncableEventsByIds(eventIds: Collection<String>): List<SyncableClipEvent> {
        if (eventIds.isEmpty()) {
            return emptyList()
        }
        return persistence.read {
            findClipsByEventIds(eventIds.toList()).map { row ->
                row.toSyncable(if (row.kind == CLIP_KIND_IMAGE) findMediaBlob(row.contentHash) else null)
            }
        }
    }

    suspend fun deleteOrphanOutbox(eventIds: Collection<String>) {
        if (eventIds.isEmpty()) {
            return
        }
        persistence.transaction { deleteOutboxForEvents(eventIds.toList()) }
    }

    suspend fun getSetting(key: String): String? {
        require(key.isNotBlank()) { "Setting key is required." }
        return persistence.read { getSetting(key) }
    }

    fun observeSetting(key: String): Flow<String?> {
        require(key.isNotBlank()) { "Setting key is required." }
        return persistence.observeSetting(key)
    }

    /** Live pending-outbox count for [peerId]; the sync engine drains on emission. */
    fun observeOutboxPending(peerId: String): Flow<Int> {
        require(peerId.isNotBlank()) { "Peer id is required." }
        return persistence.observeOutboxPendingCount(peerId)
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
                events += rows.map { row ->
                    row.toSyncable(if (row.kind == CLIP_KIND_IMAGE) findMediaBlob(row.contentHash) else null)
                }
            }
            events
        }
    }

    suspend fun findLiveContentByHash(contentHash: String): String? =
        persistence.read { findLiveContentByHash(contentHash) }

    suspend fun findLiveImageByHash(contentHash: String): Boolean =
        media.exists(contentHash) && persistence.read { findLiveImageByHash(contentHash) }

    suspend fun markLocalUnsupportedMedia(eventId: String, originDeviceId: String, originSeq: Long) {
        persistence.transaction {
            setTerminalReason(eventId, originDeviceId, originSeq, TerminalReasons.UNSUPPORTED_MEDIA)
        }
    }

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
            val state = receiveState(originDeviceId).accept(originSeq)
            upsertReceiveState(originDeviceId, state)
            return RemoteStoreResult.AlreadyPersisted
        }
        val byEvent = findClipByEventId(eventId)
        return if (byEvent == null) {
            null
        } else {
            RemoteStoreResult.IdentityConflict("event id maps to a different origin sequence")
        }
    }

    private suspend fun ClipSession.enqueueTombstone(eventId: String) {
        val peerId = getSetting(SETTING_PAIRED_PEER_ID)?.takeIf { it.isNotBlank() } ?: return
        insertOutbox(peerId, eventId)
    }

    private suspend fun ClipSession.fanOutOutbox(
        eventId: String,
        originDeviceId: String,
        excludedPeerId: String?,
        peerId: String?,
    ) {
        val target = peerId?.takeIf { it.isNotBlank() }
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

    private suspend fun ClipSession.insertImageClip(
        eventId: String,
        originDeviceId: String,
        originSeq: Long,
        image: ValidatedImage,
        sourceApp: String?,
        createdAtMs: Long,
        expiresAt: Long?,
    ) {
        insertClip(
            ClipEntity(
                eventId = eventId,
                originDeviceId = originDeviceId,
                originSeq = originSeq,
                kind = CLIP_KIND_IMAGE,
                content = null,
                contentHash = image.contentHash,
                sourceApp = sourceApp,
                createdAt = createdAtMs,
                expiresAt = expiresAt,
                deletedAt = null,
                terminalReason = null,
            ),
        )
        upsertMediaBlob(
            MediaBlobEntity(
                contentHash = image.contentHash,
                mimeType = image.mimeType,
                encodedBytes = image.encodedBytes,
                pixelWidth = image.pixelWidth,
                pixelHeight = image.pixelHeight,
                state = CLIP_MEDIA_READY,
                createdAt = createdAtMs,
            ),
        )
        upsertClipMedia(
            ClipMediaEntity(
                eventId = eventId,
                contentHash = image.contentHash,
                state = CLIP_MEDIA_READY,
            ),
        )
    }

    private fun ClipEntity.toEntry(blob: MediaBlobEntity? = null): ClipEntry =
        ClipEntry(
            eventId = eventId,
            originDeviceId = originDeviceId,
            originSeq = originSeq,
            content = content.orEmpty(),
            contentHash = contentHash,
            sourceApp = sourceApp,
            createdAtMs = createdAt,
            expiresAtMs = expiresAt,
            kind = kind,
            mimeType = blob?.mimeType,
            encodedBytes = blob?.encodedBytes,
            pixelWidth = blob?.pixelWidth,
            pixelHeight = blob?.pixelHeight,
        )

    private fun ClipEntity.toSyncable(blob: MediaBlobEntity? = null): SyncableClipEvent {
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
            kind = kind,
            mimeType = blob?.mimeType,
            encodedBytes = blob?.encodedBytes,
            pixelWidth = blob?.pixelWidth,
            pixelHeight = blob?.pixelHeight,
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

    /**
     * Hard-deletes expired live clips (no outbox row in any state) and expired
     * tombstones in one transaction. Receive coverage is untouched.
     */
    suspend fun purgeExpired(nowMs: Long): PurgeCounts =
        persistence.transaction {
            val cutoffMs =
                retentionCutoffMs(
                    nowMs,
                    parseRetentionDays(getSetting(SETTING_RETENTION_DAYS)),
                )
            PurgeCounts(
                liveClipsDeleted = hardDeleteExpiredLive(cutoffMs),
                tombstonesDeleted = hardDeleteExpiredTombstones(cutoffMs),
            )
        }.also {
            collectUnreferencedMedia()
        }

    /**
     * Restores live clip rows from a [ClipExport] JSONL snapshot.
     * Insert-if-absent by `event_id`. Does not write outbox or peer cursors.
     * Receive coverage is advanced so a later reconnect does not re-request
     * already-imported sequences.
     */
    suspend fun importJsonLines(
        jsonl: String,
        mediaDirectory: File? = null,
    ): ClipImportCounts {
        if (jsonl.length > ClipImport.MAX_IMPORT_CHARS) {
            throw IllegalArgumentException("import exceeds the size limit")
        }
        var imported = 0
        var skipped = 0
        val pending = mutableListOf<String>()
        for (line in jsonl.lineSequence()) {
            if (line.isBlank()) {
                continue
            }
            if (line.length > ClipImport.MAX_LINE_CHARS) {
                skipped++
                continue
            }
            pending += line
            if (pending.size >= ClipImport.IMPORT_BATCH_LINES) {
                val batch = importLineBatch(pending, mediaDirectory)
                imported += batch.imported
                skipped += batch.skipped
                pending.clear()
            }
        }
        if (pending.isNotEmpty()) {
            val batch = importLineBatch(pending, mediaDirectory)
            imported += batch.imported
            skipped += batch.skipped
        }
        return ClipImportCounts(imported, skipped)
    }

    private suspend fun importLineBatch(
        lines: List<String>,
        mediaDirectory: File?,
    ): ClipImportCounts =
        persistence.transaction {
            var imported = 0
            var skipped = 0
            for (line in lines) {
                val entry = ClipImport.decodeLine(line)
                if (entry == null) {
                    skipped++
                    continue
                }
                val exists =
                    findClipByEventId(entry.eventId) != null ||
                        findClipByOriginSeq(entry.originDeviceId, entry.originSeq) != null
                if (exists) {
                    skipped++
                    continue
                }
                if (entry.isImage) {
                    val blobFile = ClipImport.resolveMediaFile(entry, mediaDirectory)
                    if (blobFile == null || !ClipImport.commitImportedImage(media, entry, blobFile)) {
                        skipped++
                        continue
                    }
                    insertClip(ClipImport.toLiveEntity(entry))
                    val blob = ClipImport.toMediaBlob(entry)
                    if (blob != null) {
                        upsertMediaBlob(blob)
                        upsertClipMedia(
                            ClipMediaEntity(
                                eventId = entry.eventId,
                                contentHash = entry.contentHash,
                                state = CLIP_MEDIA_READY,
                            ),
                        )
                    }
                } else {
                    insertClip(ClipImport.toLiveEntity(entry))
                }
                val nextState = receiveState(entry.originDeviceId).accept(entry.originSeq)
                upsertReceiveState(entry.originDeviceId, nextState)
                if (entry.originDeviceId == localDeviceId) {
                    advanceOriginSeq(localDeviceId, entry.originSeq)
                }
                imported++
            }
            ClipImportCounts(imported, skipped)
        }

    /**
     * Drops every outbox row for [peerId] and clears the Room peer-id mirror.
     * PairingStore remains the pairing source of truth; call this after
     * [com.clipsync.android.pairing.PairingStore.forgetPeer].
     */
    suspend fun clearPeerState(peerId: String) {
        require(peerId.isNotBlank()) { "Peer id is required." }
        persistence.transaction {
            deleteOutboxForPeer(peerId)
            setSetting(SETTING_PAIRED_PEER_ID, "")
        }
    }

    /** Clears outbox + mirror when the Room peer-id mirror still names a forgotten peer. */
    suspend fun clearForgottenPeerState() {
        val previous = getSetting(SETTING_PAIRED_PEER_ID)?.takeIf { it.isNotBlank() } ?: return
        clearPeerState(previous)
    }

    private suspend fun collectUnreferencedMedia() {
        val keep = persistence.transaction {
            deleteOrphanedClipMedia()
            referencedBlobHashes().toSet()
        }
        val stale = persistence.read { allBlobHashes() }.filter { it !in keep }
        if (stale.isNotEmpty()) {
            persistence.transaction {
                for (hash in stale) {
                    deleteMediaBlob(hash)
                }
            }
            for (hash in stale) {
                media.deleteBlob(hash)
            }
        }
        media.deleteUnreferenced(keep, gracePeriodMs = MediaLimits.BLOB_GC_GRACE_MS)
    }
}

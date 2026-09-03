package com.clipsync.android.sync

import com.clipsync.android.media.ImageChunks
import com.clipsync.android.media.MediaLimits
import com.clipsync.android.media.MediaStoreException
import com.clipsync.android.media.PendingMediaWrite
import com.clipsync.android.media.ValidatedImage
import com.clipsync.android.protocol.ProtocolJson
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException

/** Tuning knobs for one session. Defaults follow protocol v1 and match the Windows engine. */
data class SyncSessionConfig(
    val localDeviceId: String,
    val peerDeviceId: String,
    val trustEpoch: Long,
    val clientVersion: String,
    val platform: String = "android",
    /**
     * The negotiated wire version for this connection: 1 (text only) or 2 (adds image bodies,
     * `hello.capabilities`, and the v2 auth proof). Decided before dialing; never changes
     * mid-session.
     */
    val protocolVersion: Int = ProtocolJson.PROTOCOL_V1,
    val handshakeTimeoutMs: Long = 15_000,
    val pingIntervalMs: Long = 30_000,
    val maxMissedPings: Int = 3,
    val outboxDrainIntervalMs: Long = 2_000,
    val wantSequencesPerOrigin: Long = 1_024,
    val maxRequestedSequencesPerMessage: Long = 16_384,
    val nowMs: () -> Long = System::currentTimeMillis,
    /** Re-checked on every data message so a forgotten pairing kills live sessions. */
    val peerStillTrusted: () -> Boolean = { true },
    /**
     * Re-checked before every announce so pausing sync (or turning private mode on) stops
     * outbound content immediately. Inbound stays untouched, and in-flight fetches of clips
     * announced earlier still complete; pending outbox entries flow on the next drain tick
     * after the gate reopens.
     */
    val outboundAllowed: () -> Boolean = { true },
)

/** Why the session ended; [errorCode] is a protocol code when one applies. */
data class SyncSessionResult(val authenticated: Boolean, val errorCode: String?, val detail: String)

/**
 * One protocol v1 sync session in the dialer role, mirroring the Windows SyncSessionEngine:
 * hello -> challenge -> auth -> known_vector -> want_ranges -> clips -> acks, plus the
 * application ping heartbeat and the periodic outbox drain. All storage effects go through
 * [SyncRepository]. Nothing here logs clipboard content, nonces, proofs, or secrets.
 */
class SyncEngine(
    private val repository: SyncRepository,
    private val config: SyncSessionConfig,
    pairSecret: ByteArray,
    private val onRemoteClipsCommitted: (List<RemoteClipApplied>) -> Unit = {},
) {
    private val pairSecret = pairSecret.copyOf()
    private val sendMutex = Mutex()
    private val replayWindow = ReplayWindow(capacity = 512)
    private val outstandingFetches = HashMap<String, ClipHeaderDto>()
    private val incomingImages = LinkedHashMap<String, IncomingImageTransfer>()
    private val unansweredPings = AtomicInteger(0)
    private val completionLock = Any()

    private val isV2: Boolean get() = config.protocolVersion == ProtocolJson.PROTOCOL_V2

    private lateinit var transport: SyncTransport
    private var sessionJob: Job? = null
    private var state = State.EXPECT_CHALLENGE
    private var peerConfirmed = false
    private var peerVector: Map<String, OriginReceiveState> = emptyMap()
    private var wantBacklogPending = false
    private var lastPeerErrorCode: String? = null
    private var completion: SyncSessionResult? = null

    /**
     * True only when both directions confirmed the handshake: the dialer proved itself and
     * then saw the listener continue past auth with a data message.
     */
    private val isAuthenticated: Boolean get() = state == State.READY && peerConfirmed

    // Sticky so a close that arrives before [run] has assigned sessionJob still lands:
    // the session then cancels itself on start instead of running to the next disconnect.
    @Volatile
    private var closeRequested = false

    /** Asks the session to stop; used on revocation and shutdown. */
    fun requestClose() {
        closeRequested = true
        sessionJob?.cancel()
    }

    suspend fun run(sessionTransport: SyncTransport): SyncSessionResult {
        transport = sessionTransport
        try {
            coroutineScope {
                sessionJob = coroutineContext[Job]
                if (closeRequested) {
                    sessionJob?.cancel()
                }
                val watchdog = launch {
                    delay(config.handshakeTimeoutMs)
                    if (state != State.READY) {
                        complete(SyncSessionResult(false, null, "handshake_timeout"))
                        requestClose()
                    }
                }
                var pingLoop: Job? = null
                var outboxLoop: Job? = null

                sendHello()
                while (currentCoroutineContext().isActive) {
                    when (val frame = transport.receive()) {
                        is TransportFrame.Closed -> {
                            // When the peer closed before we authenticated, its last reported
                            // error (for example a retryable RATE_LIMITED) is the best reason.
                            complete(
                                SyncSessionResult(
                                    isAuthenticated,
                                    if (isAuthenticated) null else lastPeerErrorCode,
                                    "peer_closed",
                                ),
                            )
                            break
                        }
                        is TransportFrame.Binary -> {
                            fail(SyncErrorCodes.SCHEMA_VIOLATION, "binary_frame")
                            break
                        }
                        is TransportFrame.TooLarge -> {
                            fail(SyncErrorCodes.PAYLOAD_TOO_LARGE, "frame_over_limit")
                            break
                        }
                        is TransportFrame.Text -> if (!dispatch(frame.payload)) break
                    }
                    if (state == State.READY && pingLoop == null) {
                        watchdog.cancel()
                        pingLoop = launch { runPingLoop() }
                        outboxLoop = launch { runOutboxLoop() }
                    }
                }
                watchdog.cancel()
                pingLoop?.cancel()
                outboxLoop?.cancel()
            }
        } catch (_: CancellationException) {
            complete(SyncSessionResult(isAuthenticated, null, "cancelled"))
        } catch (_: IOException) {
            complete(SyncSessionResult(isAuthenticated, null, "send_failed"))
        } finally {
            withContext(NonCancellable) {
                runCatching { transport.close(SyncCloseCodes.NORMAL, "session_end") }
            }
            abortIncompleteImageTransfers()
            pairSecret.fill(0)
        }
        return completion ?: SyncSessionResult(isAuthenticated, null, "closed")
    }

    /** Drops half-received image temp files; the peer re-announces on the next session. */
    private fun abortIncompleteImageTransfers() {
        val store = repository.media
        for (transfer in incomingImages.values) {
            runCatching { store?.abort(transfer.pending) }
        }
        incomingImages.clear()
    }

    private suspend fun sendHello() {
        send(
            SyncMessageTypes.HELLO,
            HelloBody(
                deviceId = config.localDeviceId,
                platform = config.platform,
                clientVersion = config.clientVersion,
                trustEpoch = config.trustEpoch,
                // Required on v2; a v2 dialer always advertises image support because the
                // supervisor only chooses v2 while the image-sync setting is on.
                capabilities = if (isV2) listOf(ProtocolJson.CAPABILITY_IMAGE_CLIP_V2) else null,
                knownVector = buildKnownVector(),
            ),
        )
    }

    /** Returns false when the session must stop. */
    private suspend fun dispatch(text: String): Boolean {
        val message = try {
            SyncWire.decode(text, config.protocolVersion)
        } catch (_: SerializationException) {
            fail(SyncErrorCodes.SCHEMA_VIOLATION, "invalid_frame")
            return false
        } catch (_: IllegalArgumentException) {
            fail(SyncErrorCodes.SCHEMA_VIOLATION, "invalid_frame")
            return false
        }

        when (replayWindow.classify(message.requestId, text)) {
            ReplayVerdict.IDENTICAL_RETRY -> return true
            ReplayVerdict.CONFLICT -> {
                fail(SyncErrorCodes.REPLAY_DETECTED, "request_id_reuse")
                return false
            }
            ReplayVerdict.FRESH -> {}
        }

        when (message.type) {
            SyncMessageTypes.PING -> {
                send(
                    SyncMessageTypes.PONG,
                    PongBody(pingSentAtMs = (message.body as PingBody).sentAtMs, sentAtMs = config.nowMs()),
                )
                return true
            }
            SyncMessageTypes.PONG -> {
                unansweredPings.set(0)
                return true
            }
            SyncMessageTypes.ERROR -> return handleError(message.body as ErrorBody)
            SyncMessageTypes.CHALLENGE -> return handleChallenge(message.body as ChallengeBody, message.requestId)
            SyncMessageTypes.HELLO, SyncMessageTypes.AUTH -> return unexpected()
        }

        if (state != State.READY) {
            fail(SyncErrorCodes.AUTH_REQUIRED, "data_before_auth")
            return false
        }

        // A data message from the listener is the dialer's confirmation that auth passed.
        peerConfirmed = true

        if (!config.peerStillTrusted()) {
            fail(SyncErrorCodes.DEVICE_REVOKED, "peer_no_longer_trusted")
            return false
        }

        return when (message.type) {
            SyncMessageTypes.KNOWN_VECTOR -> handleKnownVector(message.body as SyncStateBody)
            SyncMessageTypes.WANT_RANGES -> handleWantRanges(message.body as WantRangesBody)
            SyncMessageTypes.CLIP_ANNOUNCE -> handleClipAnnounce(message.body as ClipAnnounceBody)
            SyncMessageTypes.CLIP_FETCH -> handleClipFetch(message.body as ClipFetchBody)
            SyncMessageTypes.CLIP_PAYLOAD -> handleClipPayload(message.body as ClipPayloadBody)
            SyncMessageTypes.CLIP_PAYLOAD_BEGIN -> handleClipPayloadBegin(message.body as ClipPayloadBeginBody)
            SyncMessageTypes.CLIP_PAYLOAD_CHUNK -> handleClipPayloadChunk(message.body as ClipPayloadChunkBody)
            SyncMessageTypes.CLIP_PAYLOAD_END -> handleClipPayloadEnd(message.body as ClipPayloadEndBody)
            SyncMessageTypes.ACK_RANGES -> handleAckRanges(message.body as AckRangesBody)
            else -> unexpected()
        }
    }

    private suspend fun unexpected(): Boolean {
        fail(SyncErrorCodes.MESSAGE_OUT_OF_ORDER, "unexpected_type")
        return false
    }

    private suspend fun handleError(error: ErrorBody): Boolean {
        lastPeerErrorCode = error.code
        if (error.retryable) {
            return true
        }
        complete(SyncSessionResult(isAuthenticated, error.code, "peer_reported_fatal_error"))
        transport.close(SyncCloseCodes.NORMAL, "peer_error")
        return false
    }

    private suspend fun handleChallenge(body: ChallengeBody, requestId: String): Boolean {
        if (state != State.EXPECT_CHALLENGE) {
            return unexpected()
        }
        if (body.challengerDeviceId != config.peerDeviceId || body.responderDeviceId != config.localDeviceId) {
            fail(SyncErrorCodes.AUTH_FAILED, "challenge_identity_mismatch")
            return false
        }
        if (body.trustEpoch != config.trustEpoch) {
            fail(SyncErrorCodes.TRUST_EPOCH_MISMATCH, "challenge_epoch_mismatch")
            return false
        }
        if (body.expiresAtMs <= config.nowMs()) {
            fail(SyncErrorCodes.CHALLENGE_EXPIRED, "challenge_already_expired")
            return false
        }
        if (body.algorithm != HMAC_ALGORITHM) {
            fail(SyncErrorCodes.AUTH_FAILED, "unsupported_algorithm")
            return false
        }
        val nonce = Base64Url.decodeExact(body.nonce, PairAuthProof.NONCE_LENGTH)
        if (nonce == null) {
            fail(SyncErrorCodes.SCHEMA_VIOLATION, "nonce_invalid")
            return false
        }

        val proof = PairAuthProof.compute(
            pairSecret,
            challengeRequestId = requestId,
            nonce = nonce,
            challengerDeviceId = config.peerDeviceId,
            responderDeviceId = config.localDeviceId,
            trustEpoch = body.trustEpoch,
            protocolVersion = config.protocolVersion,
        )
        send(
            SyncMessageTypes.AUTH,
            AuthBody(
                algorithm = HMAC_ALGORITHM,
                challengeRequestId = requestId,
                responderDeviceId = config.localDeviceId,
                trustEpoch = body.trustEpoch,
                proof = Base64Url.encode(proof),
            ),
        )

        // The listener's next data message implies acceptance; a failure arrives as error/close.
        state = State.READY
        repository.resetOutboxToPending(config.peerDeviceId)
        send(SyncMessageTypes.KNOWN_VECTOR, buildKnownVector())
        return true
    }

    private suspend fun handleKnownVector(vector: SyncStateBody): Boolean {
        val parsed = HashMap<String, OriginReceiveState>()
        try {
            for (origin in vector.origins) {
                parsed[origin.originDeviceId] = OriginReceiveState(
                    origin.contiguousSeq,
                    origin.receivedRanges.orEmpty().map { SequenceRange(it.startSeq, it.endSeq) },
                )
            }
        } catch (_: IllegalArgumentException) {
            fail(SyncErrorCodes.SCHEMA_VIOLATION, "vector_invariants")
            return false
        }

        peerVector = parsed

        // Their persisted coverage is acknowledgment evidence: prune what they already hold.
        // Coverage proves the peer holds the sequence's long-gone content, not that it heard
        // about a later deletion, so tombstone outbox rows survive this prune (like Windows).
        val covered = parsed
            .filterKeys { it != config.peerDeviceId }
            .map { (origin, held) -> OriginSequenceRanges(origin, held.toCoverage()) }
            .filter { it.ranges.isNotEmpty() }
        if (covered.isNotEmpty()) {
            repository.applyPeerAckRanges(config.peerDeviceId, covered, config.nowMs(), dropTerminalOutbox = false)
        }

        sendWants()
        return true
    }

    private suspend fun sendWants() {
        val mine = repository.knownVector()
        val requests = mutableListOf<OriginRangesDto>()
        var capped = false
        for ((origin, theirs) in peerVector) {
            if (origin == config.localDeviceId) {
                continue
            }
            // Android pairs exactly one device, so only the peer's own origin is trusted;
            // third-party origins would need their own pairing record (protocol section 5).
            if (origin != config.peerDeviceId) {
                continue
            }
            val local = mine[origin] ?: OriginReceiveState.EMPTY
            val missing = local.missingFrom(theirs)
            if (missing.isEmpty()) {
                continue
            }
            val limited = SequenceRangeMath.take(missing, config.wantSequencesPerOrigin)
            capped = capped || SequenceRangeMath.totalCount(limited) < SequenceRangeMath.totalCount(missing)
            requests.add(
                OriginRangesDto(origin, limited.map { RangeDto(it.startSeq, it.endSeq) }),
            )
        }

        wantBacklogPending = capped
        if (requests.isNotEmpty()) {
            send(SyncMessageTypes.WANT_RANGES, WantRangesBody(requests))
        }
    }

    private suspend fun handleWantRanges(wants: WantRangesBody): Boolean {
        val totalRequested = wants.requests.sumOf { request ->
            SequenceRangeMath.totalCount(request.ranges.map { SequenceRange(it.startSeq, it.endSeq) })
        }
        if (totalRequested > config.maxRequestedSequencesPerMessage) {
            send(
                SyncMessageTypes.ERROR,
                ErrorBody(
                    code = SyncErrorCodes.RATE_LIMITED,
                    retryable = true,
                    failedType = SyncMessageTypes.WANT_RANGES,
                    retryAfterMs = 1_000,
                ),
            )
            return true
        }

        if (!config.outboundAllowed()) {
            // Paused/private: the peer's pull is not served. It re-requests on the next
            // vector exchange, and the outbox drain announces backlog once the gate reopens.
            return true
        }

        for (request in wants.requests) {
            var remaining: List<SequenceRange> = request.ranges.map { SequenceRange(it.startSeq, it.endSeq) }
            while (remaining.isNotEmpty()) {
                val events = repository.getSyncableEvents(
                    request.originDeviceId,
                    remaining,
                    SyncLimits.MAX_ANNOUNCE_CLIPS,
                )
                if (events.isEmpty()) {
                    break
                }
                val marks = ImageLocalOnlyMarks()
                send(SyncMessageTypes.CLIP_ANNOUNCE, ClipAnnounceBody(events.map { buildHeader(it, marks) }))
                applyImageLocalOnlyMarks(marks)
                if (events.size < SyncLimits.MAX_ANNOUNCE_CLIPS) {
                    break
                }
                val served = SequenceRangeMath.normalize(events.map { SequenceRange(it.originSeq, it.originSeq) })
                remaining = SequenceRangeMath.subtract(remaining, served)
            }
        }
        return true
    }

    private suspend fun handleClipAnnounce(announce: ClipAnnounceBody): Boolean {
        val mine = repository.knownVector()
        val acks = mutableListOf<Pair<String, Long>>()
        val committed = mutableListOf<RemoteClipApplied>()
        val fetchIds = mutableListOf<String>()

        for (header in announce.clips) {
            val origin = header.originDeviceId
            if (origin == config.localDeviceId) {
                continue
            }
            if (origin != config.peerDeviceId) {
                // Unknown third-party origin: skipped, never trusted implicitly.
                continue
            }

            val localState = mine[origin] ?: OriginReceiveState.EMPTY
            // A covered sequence is normally just re-acked, but an unavailable header must
            // still reach the store even then (mirroring Windows): the origin re-announces a
            // sequence it already delivered exactly when it deleted the clip afterwards, and
            // skipping here would ack the tombstone away without ever applying the deletion.
            if (localState.contains(header.originSeq) && header.availability != ClipAvailability.UNAVAILABLE) {
                acks.add(origin to header.originSeq)
                continue
            }

            if (header.availability == ClipAvailability.UNAVAILABLE) {
                val stored = repository.storeRemoteTerminal(
                    RemoteTerminalMarker(header.eventId, origin, header.originSeq, header.reason!!),
                    config.peerDeviceId,
                )
                if (stored is RemoteStoreResult.IdentityConflict) {
                    fail(SyncErrorCodes.EVENT_CONFLICT, "terminal_conflict")
                    return false
                }
                acks.add(origin to header.originSeq)
                continue
            }

            if (header.kind == MediaLimits.KIND_IMAGE) {
                // The v1 validator rejects image headers before this point; belt and braces.
                if (!isV2) {
                    fail(SyncErrorCodes.UNSUPPORTED_MEDIA, "image_on_v1")
                    return false
                }
                val knownBlob = header.contentHash?.let { repository.findLiveImageByHash(it) }
                if (knownBlob != null) {
                    // The exact bytes are already on disk: commit the event row without a
                    // transfer (protocol v2 section 5, dedup by content hash).
                    if (!ingestAvailableImage(header, knownBlob, committed)) {
                        return false
                    }
                    acks.add(origin to header.originSeq)
                    continue
                }
                if (!outstandingFetches.containsKey(header.eventId)) {
                    outstandingFetches[header.eventId] = header
                    fetchIds.add(header.eventId)
                }
                continue
            }

            val known = repository.findLiveContentByHash(header.contentHash!!)
            if (known != null && known.toByteArray(StandardCharsets.UTF_8).size.toLong() == header.utf8Bytes) {
                val replay = RemoteClipEvent(
                    eventId = header.eventId,
                    originDeviceId = origin,
                    originSeq = header.originSeq,
                    content = known,
                    contentHash = header.contentHash,
                    sourceApp = header.sourceApp,
                    createdAtMs = header.createdAtMs!!,
                    expiresAtMs = header.expiresAtMs,
                )
                val stored = repository.storeRemoteEvent(replay, config.peerDeviceId)
                if (stored is RemoteStoreResult.IdentityConflict) {
                    fail(SyncErrorCodes.EVENT_CONFLICT, "announce_conflict")
                    return false
                }
                acks.add(origin to header.originSeq)
                if (stored is RemoteStoreResult.Stored) {
                    committed.add(
                        RemoteClipApplied(header.eventId, origin, header.originSeq, known, replay.createdAtMs),
                    )
                }
                continue
            }

            if (outstandingFetches.containsKey(header.eventId)) {
                // Already fetching this event: the outbox drain and want_ranges serving can
                // both announce the same clip, and a second fetch would make the second
                // payload look out-of-order and kill the session.
                continue
            }
            outstandingFetches[header.eventId] = header
            fetchIds.add(header.eventId)
        }

        for (chunk in fetchIds.chunked(SyncLimits.MAX_FETCH_EVENT_IDS)) {
            send(SyncMessageTypes.CLIP_FETCH, ClipFetchBody(chunk))
        }
        sendAcks(acks)
        raiseCommitted(committed)
        return true
    }

    /**
     * Commits an announced image whose blob is already stored locally (hash replay). Blob
     * metadata comes from [blob] — the record of the bytes' own validated commit — never from
     * the header: Windows re-inspects the on-disk file for the same reason, and honoring the
     * announce's claims here would let a peer overwrite validated MIME/dimensions metadata.
     */
    private suspend fun ingestAvailableImage(
        header: ClipHeaderDto,
        blob: ValidatedImage,
        committed: MutableList<RemoteClipApplied>,
    ): Boolean {
        val stored = repository.storeRemoteEvent(
            RemoteClipEvent(
                eventId = header.eventId,
                originDeviceId = header.originDeviceId,
                originSeq = header.originSeq,
                content = "",
                contentHash = blob.contentHash,
                sourceApp = header.sourceApp,
                createdAtMs = header.createdAtMs ?: config.nowMs(),
                expiresAtMs = header.expiresAtMs,
                kind = MediaLimits.KIND_IMAGE,
                mimeType = blob.mimeType,
                encodedBytes = blob.encodedBytes,
                pixelWidth = blob.pixelWidth,
                pixelHeight = blob.pixelHeight,
            ),
            config.peerDeviceId,
        )
        if (stored is RemoteStoreResult.IdentityConflict) {
            fail(SyncErrorCodes.EVENT_CONFLICT, "announce_conflict")
            return false
        }
        if (stored is RemoteStoreResult.Stored) {
            committed.add(
                RemoteClipApplied(
                    eventId = header.eventId,
                    originDeviceId = header.originDeviceId,
                    originSeq = header.originSeq,
                    content = "",
                    createdAtMs = header.createdAtMs ?: config.nowMs(),
                    kind = MediaLimits.KIND_IMAGE,
                    contentHash = blob.contentHash,
                    mimeType = blob.mimeType,
                ),
            )
        }
        return true
    }

    private suspend fun handleClipFetch(fetch: ClipFetchBody): Boolean {
        val events = repository.getSyncableEventsByIds(fetch.eventIds)
        val byId = events.associateBy { it.eventId }

        val payloadItems = mutableListOf<ClipPayloadItemDto>()
        val terminalHeaders = mutableListOf<ClipHeaderDto>()
        val marks = ImageLocalOnlyMarks()
        var missing = 0
        for (id in fetch.eventIds) {
            val item = byId[id]
            if (item == null) {
                missing++
                continue
            }
            if (item.isTerminal) {
                terminalHeaders.add(buildHeader(item))
                continue
            }
            if (item.isImage) {
                if (!isV2) {
                    // A v1 peer cannot carry image bodies; answer with the terminal marker
                    // buildHeader produces (`local_only`) so its sequence gap still closes.
                    terminalHeaders.add(buildHeader(item, marks))
                    continue
                }
                if (!sendImagePayload(item)) {
                    return false
                }
                // The body went out in full, so any stale 仅本机保留 badge is wrong now.
                if (item.originDeviceId == config.localDeviceId) {
                    marks.available.add(item.eventId)
                }
                continue
            }
            payloadItems.add(
                ClipPayloadItemDto(
                    eventId = item.eventId,
                    originDeviceId = item.originDeviceId,
                    originSeq = item.originSeq,
                    kind = "text",
                    content = item.content!!,
                    contentHash = item.contentHash!!,
                    utf8Bytes = item.content.toByteArray(StandardCharsets.UTF_8).size.toLong(),
                    sourceApp = item.sourceApp,
                    createdAtMs = item.createdAtMs,
                    expiresAtMs = item.expiresAtMs,
                ),
            )
        }

        for (chunk in terminalHeaders.chunked(SyncLimits.MAX_ANNOUNCE_CLIPS)) {
            send(SyncMessageTypes.CLIP_ANNOUNCE, ClipAnnounceBody(chunk))
        }
        for (batch in chunkPayloads(payloadItems)) {
            send(SyncMessageTypes.CLIP_PAYLOAD, ClipPayloadBody(batch))
        }
        applyImageLocalOnlyMarks(marks)
        if (missing > 0) {
            send(
                SyncMessageTypes.ERROR,
                ErrorBody(
                    code = SyncErrorCodes.PAYLOAD_NOT_FOUND,
                    retryable = true,
                    failedType = SyncMessageTypes.CLIP_FETCH,
                ),
            )
        }
        return true
    }

    private suspend fun handleClipPayload(payload: ClipPayloadBody): Boolean {
        val acks = mutableListOf<Pair<String, Long>>()
        val committed = mutableListOf<RemoteClipApplied>()

        for (item in payload.clips) {
            val header = outstandingFetches.remove(item.eventId)
            if (header == null) {
                fail(SyncErrorCodes.MESSAGE_OUT_OF_ORDER, "payload_without_fetch")
                return false
            }
            if (item.originDeviceId != header.originDeviceId ||
                item.originSeq != header.originSeq ||
                item.contentHash != header.contentHash ||
                item.utf8Bytes != header.utf8Bytes
            ) {
                fail(SyncErrorCodes.EVENT_CONFLICT, "payload_header_mismatch")
                return false
            }

            val remoteEvent = RemoteClipEvent(
                eventId = item.eventId,
                originDeviceId = item.originDeviceId,
                originSeq = item.originSeq,
                content = item.content,
                contentHash = item.contentHash,
                sourceApp = item.sourceApp,
                createdAtMs = item.createdAtMs,
                expiresAtMs = item.expiresAtMs,
            )
            val stored = repository.storeRemoteEvent(remoteEvent, config.peerDeviceId)
            if (stored is RemoteStoreResult.IdentityConflict) {
                fail(SyncErrorCodes.EVENT_CONFLICT, "payload_conflict")
                return false
            }
            acks.add(item.originDeviceId to item.originSeq)
            if (stored is RemoteStoreResult.Stored) {
                committed.add(
                    RemoteClipApplied(item.eventId, item.originDeviceId, item.originSeq, item.content, item.createdAtMs),
                )
            }
        }

        sendAcks(acks)
        raiseCommitted(committed)
        if (wantBacklogPending) {
            sendWants()
        }
        return true
    }

    private suspend fun handleAckRanges(body: AckRangesBody): Boolean {
        val ranges = body.acks.map { ack ->
            OriginSequenceRanges(ack.originDeviceId, ack.ranges.map { SequenceRange(it.startSeq, it.endSeq) })
        }
        repository.applyPeerAckRanges(config.peerDeviceId, ranges, config.nowMs())
        return true
    }

    private suspend fun runPingLoop() {
        try {
            while (true) {
                delay(config.pingIntervalMs)
                if (unansweredPings.incrementAndGet() > config.maxMissedPings) {
                    complete(SyncSessionResult(true, null, "ping_timeout"))
                    requestClose()
                    return
                }
                send(SyncMessageTypes.PING, PingBody(sentAtMs = config.nowMs()))
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            complete(SyncSessionResult(true, null, "send_failed"))
            requestClose()
        }
    }

    private suspend fun runOutboxLoop() {
        try {
            drainOutbox()
            while (true) {
                delay(config.outboxDrainIntervalMs)
                drainOutbox()
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            complete(SyncSessionResult(true, null, "send_failed"))
            requestClose()
        }
    }

    private suspend fun drainOutbox() {
        while (currentCoroutineContext().isActive) {
            if (!config.outboundAllowed()) {
                // Entries stay pending (never marked announced), so nothing is lost: the
                // next drain tick after unpausing announces them.
                return
            }
            val batch = repository.getOutboxBatch(config.peerDeviceId, SyncLimits.MAX_ANNOUNCE_CLIPS)
            if (batch.isEmpty()) {
                return
            }
            val marks = ImageLocalOnlyMarks()
            send(SyncMessageTypes.CLIP_ANNOUNCE, ClipAnnounceBody(batch.map { buildHeader(it.event, marks) }))
            applyImageLocalOnlyMarks(marks)
            repository.markOutboxAnnounced(batch.map { it.entryId })
            if (batch.size < SyncLimits.MAX_ANNOUNCE_CLIPS) {
                return
            }
        }
    }

    private suspend fun sendAcks(acks: List<Pair<String, Long>>) {
        if (acks.isEmpty()) {
            return
        }
        val grouped = acks
            .groupBy({ it.first }, { it.second })
            .map { (origin, sequences) ->
                OriginRangesDto(
                    originDeviceId = origin,
                    ranges = SequenceRangeMath.normalize(sequences.map { SequenceRange(it, it) })
                        .map { RangeDto(it.startSeq, it.endSeq) },
                )
            }
        send(SyncMessageTypes.ACK_RANGES, AckRangesBody(grouped))
    }

    private fun raiseCommitted(committed: List<RemoteClipApplied>) {
        if (committed.isNotEmpty()) {
            onRemoteClipsCommitted(committed)
        }
    }

    private suspend fun buildKnownVector(): SyncStateBody {
        val vector = repository.knownVector()
        return SyncStateBody(
            origins = vector.entries
                .sortedBy { it.key }
                .map { (origin, held) ->
                    OriginStateDto(
                        originDeviceId = origin,
                        contiguousSeq = held.contiguousSeq,
                        receivedRanges = held.receivedRanges
                            .takeIf { it.isNotEmpty() }
                            ?.map { RangeDto(it.startSeq, it.endSeq) },
                    )
                },
        )
    }

    /**
     * Image event ids whose headers one send downgraded to `local_only` or announced available;
     * applied to the store only after the frame actually went out.
     */
    private class ImageLocalOnlyMarks {
        val downgraded = mutableListOf<String>()
        val available = mutableListOf<String>()
    }

    /**
     * Persists the 仅本机保留 marks (ADR 0005 §5): a downgraded local image gets the history
     * badge, and an available announce (or a delivered body) clears a stale badge left by an
     * earlier text-only session.
     */
    private suspend fun applyImageLocalOnlyMarks(marks: ImageLocalOnlyMarks) {
        if (marks.downgraded.isNotEmpty()) {
            repository.markImagesLocalOnly(marks.downgraded, config.nowMs())
        }
        if (marks.available.isNotEmpty()) {
            repository.clearImagesLocalOnly(marks.available)
        }
    }

    private fun buildHeader(item: SyncableClipEvent, marks: ImageLocalOnlyMarks? = null): ClipHeaderDto {
        // Images cannot travel a v1 session: announce them as `local_only` terminal markers so
        // the peer's contiguous cursor still advances (docs/protocol-v2.md section 7).
        val v1Image = item.isImage && !isV2
        return when {
            item.isTerminal || v1Image -> {
                if (v1Image && !item.isTerminal && item.originDeviceId == config.localDeviceId) {
                    marks?.downgraded?.add(item.eventId)
                }
                ClipHeaderDto(
                    eventId = item.eventId,
                    originDeviceId = item.originDeviceId,
                    originSeq = item.originSeq,
                    availability = ClipAvailability.UNAVAILABLE,
                    reason = if (v1Image) "local_only" else item.terminalReason,
                )
            }
            item.isImage -> {
                if (item.originDeviceId == config.localDeviceId) {
                    marks?.available?.add(item.eventId)
                }
                ClipHeaderDto(
                    eventId = item.eventId,
                    originDeviceId = item.originDeviceId,
                    originSeq = item.originSeq,
                    availability = ClipAvailability.AVAILABLE,
                    kind = MediaLimits.KIND_IMAGE,
                    contentHash = item.contentHash,
                    mimeType = item.mimeType,
                    encodedBytes = item.encodedBytes?.toLong(),
                    pixelWidth = item.pixelWidth?.toLong(),
                    pixelHeight = item.pixelHeight?.toLong(),
                    sourceApp = item.sourceApp,
                    createdAtMs = item.createdAtMs,
                    expiresAtMs = item.expiresAtMs,
                )
            }
            else -> ClipHeaderDto(
                eventId = item.eventId,
                originDeviceId = item.originDeviceId,
                originSeq = item.originSeq,
                availability = ClipAvailability.AVAILABLE,
                kind = "text",
                contentHash = item.contentHash,
                utf8Bytes = item.content!!.toByteArray(StandardCharsets.UTF_8).size.toLong(),
                sourceApp = item.sourceApp,
                createdAtMs = item.createdAtMs,
                expiresAtMs = item.expiresAtMs,
            )
        }
    }

    // ---- Protocol v2 image body transfer (docs/protocol-v2.md section 5) ----

    /** Streams one stored image blob as begin -> chunk* -> end; false kills the session. */
    private suspend fun sendImagePayload(item: SyncableClipEvent): Boolean {
        val store = repository.media
        val hash = item.contentHash
        if (store == null || hash == null) {
            fail(SyncErrorCodes.MEDIA_STORAGE_FAILED, "blob_missing")
            return false
        }
        val bytes = try {
            store.readAllBytes(hash)
        } catch (_: Exception) {
            fail(SyncErrorCodes.MEDIA_STORAGE_FAILED, "blob_missing")
            return false
        }
        val chunks = ImageChunks.split(bytes)
        val transferId = SyncWire.newRequestId()
        send(
            SyncMessageTypes.CLIP_PAYLOAD_BEGIN,
            ClipPayloadBeginBody(
                transferId = transferId,
                eventId = item.eventId,
                chunkCount = chunks.size,
                encodedBytes = bytes.size.toLong(),
                contentHash = hash,
                mimeType = item.mimeType ?: MediaLimits.MIME_PNG,
            ),
        )
        for (chunk in chunks) {
            send(
                SyncMessageTypes.CLIP_PAYLOAD_CHUNK,
                ClipPayloadChunkBody(
                    transferId = transferId,
                    eventId = item.eventId,
                    chunkIndex = chunk.index,
                    chunkCount = chunk.count,
                    chunkBytes = chunk.byteCount,
                    data = chunk.data,
                ),
            )
        }
        send(
            SyncMessageTypes.CLIP_PAYLOAD_END,
            ClipPayloadEndBody(transferId = transferId, eventId = item.eventId, contentHash = hash),
        )
        return true
    }

    private suspend fun handleClipPayloadBegin(begin: ClipPayloadBeginBody): Boolean {
        val header = outstandingFetches[begin.eventId]
        if (header == null ||
            header.contentHash != begin.contentHash ||
            header.mimeType != begin.mimeType ||
            header.encodedBytes != begin.encodedBytes
        ) {
            fail(SyncErrorCodes.MESSAGE_OUT_OF_ORDER, "payload_without_fetch")
            return false
        }
        if (incomingImages.containsKey(begin.eventId)) {
            // A second begin while the first transfer is still open would silently overwrite
            // the map entry and orphan its half-written temp (open stream, never aborted).
            // Failing instead lets the session-end cleanup abort the live transfer properly.
            fail(SyncErrorCodes.MEDIA_OUT_OF_ORDER, "begin_duplicate")
            return false
        }
        if (incomingImages.size >= MediaLimits.MAX_CONCURRENT_DOWNLOADS) {
            fail(SyncErrorCodes.RATE_LIMITED, "too_many_image_downloads")
            return false
        }
        val store = repository.media
        if (store == null) {
            fail(SyncErrorCodes.MEDIA_STORAGE_FAILED, "media_store_unavailable")
            return false
        }
        val pending = try {
            store.beginWrite()
        } catch (_: Exception) {
            fail(SyncErrorCodes.MEDIA_STORAGE_FAILED, "temp_open_failed")
            return false
        }
        incomingImages[begin.eventId] = IncomingImageTransfer(
            transferId = begin.transferId,
            header = header,
            pending = pending,
            chunkCount = begin.chunkCount,
            contentHash = begin.contentHash,
        )
        return true
    }

    private suspend fun handleClipPayloadChunk(chunk: ClipPayloadChunkBody): Boolean {
        val transfer = incomingImages[chunk.eventId]
        if (transfer == null ||
            transfer.transferId != chunk.transferId ||
            transfer.chunkCount != chunk.chunkCount
        ) {
            fail(SyncErrorCodes.MEDIA_OUT_OF_ORDER, "chunk_unbound")
            return false
        }
        if (chunk.chunkIndex != transfer.nextIndex) {
            if (chunk.chunkIndex < transfer.nextIndex) {
                // Idempotent retry of an already-appended index.
                return true
            }
            fail(SyncErrorCodes.MEDIA_OUT_OF_ORDER, "chunk_out_of_order")
            return false
        }
        val bytes = ImageChunks.tryDecodeChunk(chunk.data, chunk.chunkBytes)
        if (bytes == null) {
            fail(SyncErrorCodes.MEDIA_DECODE_FAILED, "chunk_decode")
            return false
        }
        try {
            repository.media?.append(transfer.pending, bytes)
        } catch (_: MediaStoreException) {
            fail(SyncErrorCodes.MEDIA_TOO_LARGE, "chunk_overflow")
            return false
        }
        transfer.nextIndex += 1
        return true
    }

    private suspend fun handleClipPayloadEnd(end: ClipPayloadEndBody): Boolean {
        val transfer = incomingImages.remove(end.eventId)
        if (transfer == null ||
            transfer.transferId != end.transferId ||
            transfer.contentHash != end.contentHash ||
            transfer.nextIndex != transfer.chunkCount
        ) {
            // Already removed from the map, so the session-end sweep cannot see it: abort
            // here or the mismatched transfer's temp file outlives the failing session.
            if (transfer != null) {
                runCatching { repository.media?.abort(transfer.pending) }
            }
            fail(SyncErrorCodes.MEDIA_OUT_OF_ORDER, "end_unbound")
            return false
        }
        outstandingFetches.remove(end.eventId)
        val store = repository.media
        if (store == null) {
            runCatching { transfer.pending.close() }
            fail(SyncErrorCodes.MEDIA_STORAGE_FAILED, "media_store_unavailable")
            return false
        }
        // Validates magic bytes, dimensions, size, and hash, then moves the temp file to its
        // content-addressed home. Only after this commit is the event row persisted and acked.
        val validated = try {
            store.commit(transfer.pending, transfer.contentHash, transfer.header.mimeType)
        } catch (error: MediaStoreException) {
            fail(error.code, "image_commit_failed")
            return false
        } catch (_: Exception) {
            fail(SyncErrorCodes.MEDIA_STORAGE_FAILED, "image_commit_failed")
            return false
        }
        val header = transfer.header
        val stored = repository.storeRemoteEvent(
            RemoteClipEvent(
                eventId = end.eventId,
                originDeviceId = header.originDeviceId,
                originSeq = header.originSeq,
                content = "",
                contentHash = validated.contentHash,
                sourceApp = header.sourceApp,
                createdAtMs = header.createdAtMs ?: config.nowMs(),
                expiresAtMs = header.expiresAtMs,
                kind = MediaLimits.KIND_IMAGE,
                mimeType = validated.mimeType,
                encodedBytes = validated.encodedBytes,
                pixelWidth = validated.pixelWidth,
                pixelHeight = validated.pixelHeight,
            ),
            config.peerDeviceId,
        )
        if (stored is RemoteStoreResult.IdentityConflict) {
            fail(SyncErrorCodes.EVENT_CONFLICT, "image_payload_conflict")
            return false
        }
        sendAcks(listOf(header.originDeviceId to header.originSeq))
        if (stored is RemoteStoreResult.Stored) {
            raiseCommitted(
                listOf(
                    RemoteClipApplied(
                        eventId = end.eventId,
                        originDeviceId = header.originDeviceId,
                        originSeq = header.originSeq,
                        content = "",
                        createdAtMs = header.createdAtMs ?: config.nowMs(),
                        kind = MediaLimits.KIND_IMAGE,
                        contentHash = validated.contentHash,
                        mimeType = validated.mimeType,
                    ),
                ),
            )
        }
        if (wantBacklogPending) {
            sendWants()
        }
        return true
    }

    /** Receive-side state of one in-flight image body; keyed by event id. */
    private class IncomingImageTransfer(
        val transferId: String,
        val header: ClipHeaderDto,
        val pending: PendingMediaWrite,
        val chunkCount: Int,
        val contentHash: String,
    ) {
        var nextIndex: Int = 0
    }

    private fun chunkPayloads(items: List<ClipPayloadItemDto>): List<List<ClipPayloadItemDto>> {
        val batches = mutableListOf<List<ClipPayloadItemDto>>()
        var batch = mutableListOf<ClipPayloadItemDto>()
        var batchBytes = 0L
        for (item in items) {
            if (batch.isNotEmpty() &&
                (batch.size >= SyncLimits.MAX_PAYLOAD_CLIPS ||
                    batchBytes + item.utf8Bytes > SyncLimits.MAX_PAYLOAD_BATCH_CONTENT_BYTES)
            ) {
                batches.add(batch)
                batch = mutableListOf()
                batchBytes = 0
            }
            batch.add(item)
            batchBytes += item.utf8Bytes
        }
        if (batch.isNotEmpty()) {
            batches.add(batch)
        }
        return batches
    }

    private suspend fun fail(errorCode: String, detail: String) {
        complete(SyncSessionResult(isAuthenticated, errorCode, detail))
        runCatching {
            send(SyncMessageTypes.ERROR, ErrorBody(code = errorCode, retryable = false))
        }
        runCatching { transport.close(SyncCloseCodes.POLICY_VIOLATION, errorCode) }
    }

    private fun complete(result: SyncSessionResult) {
        synchronized(completionLock) {
            if (completion == null) {
                completion = result
            }
        }
    }

    private suspend fun send(type: String, body: Any) {
        val frame = SyncWire.encode(type, SyncWire.newRequestId(), body, config.protocolVersion)
        sendMutex.withLock {
            transport.send(frame)
        }
    }

    private enum class State { EXPECT_CHALLENGE, READY }

    private enum class ReplayVerdict { FRESH, IDENTICAL_RETRY, CONFLICT }

    /** Bounded request-id replay detector per protocol section 2. */
    private class ReplayWindow(private val capacity: Int) {
        private val hashes = HashMap<String, String>()
        private val order = ArrayDeque<String>()

        fun classify(requestId: String, rawFrame: String): ReplayVerdict {
            val hash = MessageDigest.getInstance("SHA-256")
                .digest(rawFrame.toByteArray(StandardCharsets.UTF_8))
                .joinToString(separator = "") { byte -> "%02x".format(byte) }
            hashes[requestId]?.let { existing ->
                return if (existing == hash) ReplayVerdict.IDENTICAL_RETRY else ReplayVerdict.CONFLICT
            }
            hashes[requestId] = hash
            order.addLast(requestId)
            if (order.size > capacity) {
                hashes.remove(order.removeFirst())
            }
            return ReplayVerdict.FRESH
        }
    }
}

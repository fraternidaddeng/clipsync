package com.clipsync.android.sync

import com.clipsync.android.pairing.PairedPeer
import com.clipsync.android.protocol.AckRangesBody
import com.clipsync.android.protocol.AuthBody
import com.clipsync.android.protocol.ChallengeBody
import com.clipsync.android.protocol.ClipAnnounceBody
import com.clipsync.android.protocol.ClipAvailability
import com.clipsync.android.protocol.ClipFetchBody
import com.clipsync.android.protocol.ClipHeaderDto
import com.clipsync.android.protocol.ClipPayloadBody
import com.clipsync.android.protocol.ClipPayloadItemDto
import com.clipsync.android.protocol.ErrorBody
import com.clipsync.android.protocol.HelloBody
import com.clipsync.android.protocol.OriginRangesDto
import com.clipsync.android.protocol.OriginStateDto
import com.clipsync.android.protocol.PairAuthProof
import com.clipsync.android.protocol.PingBody
import com.clipsync.android.protocol.PongBody
import com.clipsync.android.protocol.ProtocolErrorCodes
import com.clipsync.android.protocol.ProtocolLimits
import com.clipsync.android.protocol.ProtocolMessageTypes
import com.clipsync.android.protocol.RangeDto
import com.clipsync.android.protocol.SyncMessageWriter
import com.clipsync.android.protocol.SyncMessages
import com.clipsync.android.protocol.SyncStateDto
import com.clipsync.android.protocol.WantRangesBody
import com.clipsync.android.storage.ClipRepository
import com.clipsync.android.storage.OriginReceiveState
import com.clipsync.android.storage.OriginSequenceRanges
import com.clipsync.android.storage.RemoteClipEvent
import com.clipsync.android.storage.RemoteStoreResult
import com.clipsync.android.storage.RemoteTerminalMarker
import com.clipsync.android.storage.SequenceRange
import com.clipsync.android.storage.SequenceRangeMath
import com.clipsync.android.storage.SyncableClipEvent
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException

/**
 * Protocol v1 sync session for the Android dialer. Handshake is
 * hello → challenge → auth → known_vector → want_ranges → announce/fetch/payload → ack.
 * Storage goes through [ClipRepository] only. Log lines never contain clipboard
 * content, nonces, proofs, or secrets.
 */
class SyncSessionEngine(
    private val repository: ClipRepository,
    private val localDeviceId: String,
    private val peer: PairedPeer,
    pairSecret: ByteArray,
    private val options: SyncSessionOptions = SyncSessionOptions(),
    private val logger: SyncLogger = SyncLogger.NoOp,
    private val isPeerTrusted: () -> Boolean = { true },
    private val onReady: () -> Unit = {},
    private val onRemoteClipsCommitted: (List<RemoteClipApplied>) -> Unit = {},
) {
    private val pairSecret: ByteArray = pairSecret.copyOf()
    private val sendLock = Mutex()
    private val outstandingFetches = LinkedHashMap<String, ClipHeaderDto>()
    private val fetchPermittedIds = HashSet<String>()
    private val peerVectorArrived = CompletableDeferred<Unit>()
    private val replayWindow = ReplayWindow(capacity = 512)
    private val unansweredPings = AtomicInteger(0)
    private val completionLock = Any()
    @Volatile private var sendsEnabled = true

    private lateinit var transport: ISyncTransport
    private var state = SessionState.ExpectChallenge
    private var peerVector: Map<String, OriginReceiveState> = emptyMap()
    private var wantBacklogPending = false
    private var peerConfirmed = false
    private var lastPeerErrorCode: String? = null
    private var completion: SyncSessionResult? = null
    private var closeRequested: (() -> Unit)? = null

    val peerDeviceId: String get() = peer.deviceId

    private val isAuthenticated: Boolean
        get() = state == SessionState.Ready && peerConfirmed

    suspend fun run(sessionTransport: ISyncTransport): SyncSessionResult = coroutineScope {
        transport = sessionTransport
        val sessionJob = coroutineContext[Job]!!
        closeRequested = { sessionJob.cancel() }

        val handshakeWatch = launch {
            options.delayMs(options.handshakeTimeoutMs)
            if (state != SessionState.Ready) {
                complete(SyncSessionResult(false, null, "handshake_timeout"))
                requestClose()
            }
        }

        var pingJob: Job? = null
        var outboxJob: Job? = null
        try {
            if (!startAsDialer()) {
                return@coroutineScope completion
                    ?: SyncSessionResult(false, ProtocolErrorCodes.AUTH_FAILED, "dialer_prerequisites_missing")
            }

            while (isActive) {
                val frame = try {
                    transport.receive()
                } catch (_: CancellationException) {
                    complete(SyncSessionResult(isAuthenticated, null, "cancelled"))
                    break
                }

                when (frame) {
                    is TransportFrame.Closed -> {
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
                        fail(ProtocolErrorCodes.SCHEMA_VIOLATION, "binary_frame")
                        break
                    }
                    is TransportFrame.TooLarge -> {
                        fail(ProtocolErrorCodes.PAYLOAD_TOO_LARGE, "frame_over_limit")
                        break
                    }
                    is TransportFrame.Text -> {
                        if (!dispatch(frame.payload)) {
                            break
                        }
                        if (state == SessionState.Ready && pingJob == null) {
                            handshakeWatch.cancel()
                            onReady()
                            pingJob = launch { runPingLoop() }
                            outboxJob = launch { runOutboxLoop() }
                        }
                    }
                }
            }
        } catch (_: CancellationException) {
            complete(SyncSessionResult(isAuthenticated, null, "cancelled"))
        } finally {
            handshakeWatch.cancel()
            pingJob?.cancel()
            outboxJob?.cancel()
            peerVectorArrived.cancel()
            sendsEnabled = false
            try {
                transport.close("session_end")
            } catch (_: Exception) {
            }
            pairSecret.fill(0)
        }

        completion ?: SyncSessionResult(isAuthenticated, null, "closed")
    }

    private fun requestClose() {
        try {
            closeRequested?.invoke()
        } catch (_: Exception) {
        }
    }

    private suspend fun startAsDialer(): Boolean {
        if (pairSecret.size != PairAuthProof.SECRET_LENGTH) {
            complete(SyncSessionResult(false, ProtocolErrorCodes.AUTH_FAILED, "secret_unavailable"))
            return false
        }
        state = SessionState.ExpectChallenge
        send(
            ProtocolMessageTypes.HELLO,
            HelloBody(
                deviceId = localDeviceId,
                platform = options.platform,
                clientVersion = options.clientVersion,
                trustEpoch = peer.trustEpoch,
                knownVector = buildKnownVector(),
            ),
        )
        return true
    }

    private suspend fun dispatch(text: String): Boolean {
        val message = try {
            SyncMessages.parse(text)
        } catch (error: SerializationException) {
            logger.event("frame_rejected", "invalid_frame")
            val code = if (error.message?.contains("parse", ignoreCase = true) == true) {
                ProtocolErrorCodes.MALFORMED_JSON
            } else {
                ProtocolErrorCodes.SCHEMA_VIOLATION
            }
            fail(code, "invalid_frame")
            return false
        } catch (_: IllegalArgumentException) {
            logger.event("frame_rejected", "invalid_frame")
            fail(ProtocolErrorCodes.SCHEMA_VIOLATION, "invalid_frame")
            return false
        }

        when (replayWindow.classify(message.requestId, text)) {
            ReplayVerdict.IdenticalRetry -> return true
            ReplayVerdict.Conflict -> {
                fail(ProtocolErrorCodes.REPLAY_DETECTED, "request_id_reuse")
                return false
            }
            ReplayVerdict.Fresh -> Unit
        }

        logger.event("received", message.type)
        when (message.type) {
            ProtocolMessageTypes.PING -> {
                val ping = message.body as PingBody
                send(
                    ProtocolMessageTypes.PONG,
                    PongBody(pingSentAtMs = ping.sentAtMs, sentAtMs = options.nowMs()),
                )
                return true
            }
            ProtocolMessageTypes.PONG -> {
                unansweredPings.set(0)
                return true
            }
            ProtocolMessageTypes.ERROR -> return handleError(message.body as ErrorBody)
            ProtocolMessageTypes.CHALLENGE ->
                return handleChallenge(message.body as ChallengeBody, message.requestId)
            ProtocolMessageTypes.HELLO,
            ProtocolMessageTypes.AUTH,
            -> {
                fail(ProtocolErrorCodes.MESSAGE_OUT_OF_ORDER, "unexpected_type")
                return false
            }
        }

        if (state != SessionState.Ready) {
            fail(ProtocolErrorCodes.AUTH_REQUIRED, "data_before_auth")
            return false
        }

        peerConfirmed = true
        if (!isPeerTrusted()) {
            fail(ProtocolErrorCodes.DEVICE_REVOKED, "peer_no_longer_trusted")
            return false
        }

        return when (message.type) {
            ProtocolMessageTypes.KNOWN_VECTOR -> handleKnownVector(message.body as SyncStateDto)
            ProtocolMessageTypes.WANT_RANGES -> handleWantRanges(message.body as WantRangesBody)
            ProtocolMessageTypes.CLIP_ANNOUNCE -> handleClipAnnounce(message.body as ClipAnnounceBody)
            ProtocolMessageTypes.CLIP_FETCH -> handleClipFetch(message.body as ClipFetchBody)
            ProtocolMessageTypes.CLIP_PAYLOAD -> handleClipPayload(message.body as ClipPayloadBody)
            ProtocolMessageTypes.ACK_RANGES -> handleAckRanges(message.body as AckRangesBody)
            else -> {
                fail(ProtocolErrorCodes.MESSAGE_OUT_OF_ORDER, "unexpected_type")
                false
            }
        }
    }

    private suspend fun handleError(error: ErrorBody): Boolean {
        logger.event("peer_error", error.code)
        lastPeerErrorCode = error.code
        if (error.retryable) {
            return true
        }
        complete(SyncSessionResult(isAuthenticated, error.code, "peer_reported_fatal_error"))
        transport.close("peer_error")
        return false
    }

    private suspend fun handleChallenge(body: ChallengeBody, requestId: String): Boolean {
        if (state != SessionState.ExpectChallenge) {
            fail(ProtocolErrorCodes.MESSAGE_OUT_OF_ORDER, "unexpected_type")
            return false
        }
        if (body.challengerDeviceId != peer.deviceId || body.responderDeviceId != localDeviceId) {
            fail(ProtocolErrorCodes.AUTH_FAILED, "challenge_identity_mismatch")
            return false
        }
        if (body.trustEpoch != peer.trustEpoch) {
            fail(ProtocolErrorCodes.TRUST_EPOCH_MISMATCH, "challenge_epoch_mismatch")
            return false
        }
        if (body.algorithm != PairAuthProof.ALGORITHM) {
            fail(ProtocolErrorCodes.AUTH_FAILED, "challenge_algorithm")
            return false
        }
        if (body.expiresAtMs <= options.nowMs()) {
            fail(ProtocolErrorCodes.CHALLENGE_EXPIRED, "challenge_already_expired")
            return false
        }
        val nonce = try {
            decodeBase64Url(body.nonce)
        } catch (_: IllegalArgumentException) {
            fail(ProtocolErrorCodes.AUTH_FAILED, "challenge_nonce")
            return false
        }
        if (nonce.size != PairAuthProof.NONCE_LENGTH) {
            fail(ProtocolErrorCodes.AUTH_FAILED, "challenge_nonce")
            return false
        }
        val proof = PairAuthProof.compute(
            pairSecret = pairSecret,
            challengeRequestId = requestId,
            nonce = nonce,
            challengerDeviceId = peer.deviceId,
            responderDeviceId = localDeviceId,
            trustEpoch = body.trustEpoch,
        )
        send(
            ProtocolMessageTypes.AUTH,
            AuthBody(
                algorithm = PairAuthProof.ALGORITHM,
                challengeRequestId = requestId,
                responderDeviceId = localDeviceId,
                trustEpoch = body.trustEpoch,
                proof = PairAuthProof.encodeBase64Url(proof),
            ),
        )
        enterReady()
        return true
    }

    private suspend fun enterReady() {
        state = SessionState.Ready
        repository.resetOutboxToPending(peer.deviceId)
        // resetOutboxToPending re-pends "announced" rows. Re-apply persisted peer
        // cursors so a prior ack that raced session close still deletes those rows
        // before the outbox loop can re-announce them.
        applyPersistedPeerAcks()
        send(ProtocolMessageTypes.KNOWN_VECTOR, buildKnownVector())
        logger.event("session_ready", "dialer")
    }

    private suspend fun applyPersistedPeerAcks() {
        val covered = repository.getPeerCursors(peer.deviceId)
            .filter { it.key != peer.deviceId }
            .map { (origin, state) -> OriginSequenceRanges(origin, SyncRangeMath.coverage(state)) }
            .filter { it.ranges.isNotEmpty() }
        if (covered.isNotEmpty()) {
            repository.ackRanges(peer.deviceId, covered, options.nowMs())
        }
    }

    private suspend fun handleKnownVector(vector: SyncStateDto): Boolean {
        val parsed = LinkedHashMap<String, OriginReceiveState>()
        try {
            for (origin in vector.origins) {
                parsed[origin.originDeviceId] = OriginReceiveState(
                    origin.contiguousSeq,
                    origin.receivedRanges.orEmpty().map { SequenceRange(it.startSeq, it.endSeq) },
                )
            }
        } catch (_: IllegalArgumentException) {
            fail(ProtocolErrorCodes.SCHEMA_VIOLATION, "vector_invariants")
            return false
        }
        peerVector = parsed

        val covered = parsed
            .filter { it.key != peer.deviceId }
            .map { (origin, state) -> OriginSequenceRanges(origin, SyncRangeMath.coverage(state)) }
            .filter { it.ranges.isNotEmpty() }
        if (covered.isNotEmpty()) {
            repository.ackRanges(peer.deviceId, covered, options.nowMs())
        }
        if (!peerVectorArrived.isCompleted) {
            peerVectorArrived.complete(Unit)
        }
        sendWants()
        return true
    }

    private suspend fun sendWants() {
        val mine = repository.knownVector().origins
        val requests = mutableListOf<OriginRangesDto>()
        var capped = false
        for ((origin, theirs) in peerVector) {
            if (origin == localDeviceId || !isTrustedOrigin(origin)) {
                continue
            }
            val local = mine[origin] ?: OriginReceiveState.EMPTY
            val missing = SyncRangeMath.missingFrom(local, theirs)
            if (missing.isEmpty()) {
                continue
            }
            val limited = SyncRangeMath.take(missing, options.wantSequencesPerOrigin)
            capped = capped || SyncRangeMath.totalCount(limited) < SyncRangeMath.totalCount(missing)
            requests.add(
                OriginRangesDto(
                    originDeviceId = origin,
                    ranges = limited.map { RangeDto(it.startSeq, it.endSeq) },
                ),
            )
        }
        wantBacklogPending = capped
        if (requests.isNotEmpty()) {
            send(ProtocolMessageTypes.WANT_RANGES, WantRangesBody(requests))
        }
    }

    private suspend fun handleWantRanges(wants: WantRangesBody): Boolean {
        val totalRequested = wants.requests.sumOf { request ->
            SyncRangeMath.totalCount(request.ranges.map { SequenceRange(it.startSeq, it.endSeq) })
        }
        if (totalRequested > options.maxRequestedSequencesPerMessage) {
            send(
                ProtocolMessageTypes.ERROR,
                ErrorBody(
                    code = ProtocolErrorCodes.RATE_LIMITED,
                    retryable = true,
                    failedType = ProtocolMessageTypes.WANT_RANGES,
                    retryAfterMs = 1_000,
                ),
            )
            return true
        }
        for (request in wants.requests) {
            var remaining: List<SequenceRange> = request.ranges.map { SequenceRange(it.startSeq, it.endSeq) }
            while (remaining.isNotEmpty()) {
                val events = repository.getSyncableEvents(
                    request.originDeviceId,
                    remaining,
                    ProtocolLimits.MAX_ANNOUNCE_CLIPS,
                )
                if (events.isEmpty()) {
                    break
                }
                send(ProtocolMessageTypes.CLIP_ANNOUNCE, ClipAnnounceBody(events.map { buildHeader(it) }))
                if (events.size < ProtocolLimits.MAX_ANNOUNCE_CLIPS) {
                    break
                }
                val served = SequenceRangeMath.normalize(events.map { SequenceRange(it.originSeq, it.originSeq) })
                remaining = SyncRangeMath.subtract(remaining, served)
            }
        }
        return true
    }

    private suspend fun handleClipAnnounce(announce: ClipAnnounceBody): Boolean {
        val mine = repository.knownVector().origins
        val acks = mutableListOf<Pair<String, Long>>()
        val committed = mutableListOf<RemoteClipApplied>()
        val fetchIds = mutableListOf<String>()

        for (header in announce.clips) {
            val origin = header.originDeviceId
            if (origin == localDeviceId) {
                continue
            }
            if (!isTrustedOrigin(origin)) {
                logger.event("untrusted_origin_skipped", "origin")
                continue
            }
            val localState = mine[origin] ?: OriginReceiveState.EMPTY
            if (localState.contains(header.originSeq)) {
                acks.add(origin to header.originSeq)
                continue
            }
            if (header.availability == ClipAvailability.UNAVAILABLE) {
                val stored = repository.ingestTerminalMarker(
                    RemoteTerminalMarker(
                        eventId = header.eventId,
                        originDeviceId = origin,
                        originSeq = header.originSeq,
                        reason = header.reason ?: return failAndFalse(
                            ProtocolErrorCodes.SCHEMA_VIOLATION,
                            "terminal_reason",
                        ),
                    ),
                    sourcePeerId = peer.deviceId,
                    receivedAtMs = options.nowMs(),
                )
                if (stored is RemoteStoreResult.IdentityConflict) {
                    logger.event("store_conflict", "terminal")
                    fail(ProtocolErrorCodes.EVENT_CONFLICT, "terminal_conflict")
                    return false
                }
                acks.add(origin to header.originSeq)
                continue
            }

            val known = header.contentHash?.let { repository.findLiveContentByHash(it) }
            val utf8Bytes = header.utf8Bytes
            if (known != null && utf8Bytes != null &&
                known.toByteArray(StandardCharsets.UTF_8).size.toLong() == utf8Bytes
            ) {
                val stored = ingestAvailable(header, known, committed)
                if (!stored) {
                    return false
                }
                acks.add(origin to header.originSeq)
                continue
            }

            outstandingFetches[header.eventId] = header
            fetchIds.add(header.eventId)
        }

        for (chunk in fetchIds.chunked(ProtocolLimits.MAX_FETCH_EVENT_IDS)) {
            send(ProtocolMessageTypes.CLIP_FETCH, ClipFetchBody(chunk))
        }
        sendAcks(acks)
        raiseCommitted(committed)
        return true
    }

    private suspend fun ingestAvailable(
        header: ClipHeaderDto,
        content: String,
        committed: MutableList<RemoteClipApplied>,
    ): Boolean {
        val stored = repository.ingestRemoteClip(
            RemoteClipEvent(
                eventId = header.eventId,
                originDeviceId = header.originDeviceId,
                originSeq = header.originSeq,
                content = content,
                contentHash = header.contentHash!!,
                sourceApp = header.sourceApp,
                createdAtMs = header.createdAtMs ?: options.nowMs(),
                expiresAtMs = header.expiresAtMs,
            ),
            sourcePeerId = peer.deviceId,
        )
        if (stored is RemoteStoreResult.IdentityConflict) {
            logger.event("store_conflict", "announce")
            fail(ProtocolErrorCodes.EVENT_CONFLICT, "announce_conflict")
            return false
        }
        if (stored is RemoteStoreResult.Stored) {
            committed.add(
                RemoteClipApplied(
                    eventId = header.eventId,
                    originDeviceId = header.originDeviceId,
                    originSeq = header.originSeq,
                    content = content,
                    createdAtMs = header.createdAtMs ?: options.nowMs(),
                ),
            )
        }
        return true
    }

    private suspend fun handleClipFetch(fetch: ClipFetchBody): Boolean {
        fetchPermittedIds.addAll(fetch.eventIds)
        val events = loadByEventIds(fetch.eventIds.toSet())
        val payloadItems = mutableListOf<ClipPayloadItemDto>()
        val terminalHeaders = mutableListOf<ClipHeaderDto>()
        var missing = 0
        for (id in fetch.eventIds) {
            val item = events[id]
            if (item == null) {
                missing++
                continue
            }
            if (item.isTerminal) {
                terminalHeaders.add(buildHeader(item))
                continue
            }
            val content = item.content ?: continue
            if (item.eventId !in fetchPermittedIds) {
                continue
            }
            payloadItems.add(
                ClipPayloadItemDto(
                    eventId = item.eventId,
                    originDeviceId = item.originDeviceId,
                    originSeq = item.originSeq,
                    kind = "text",
                    content = content,
                    contentHash = item.contentHash!!,
                    utf8Bytes = content.toByteArray(StandardCharsets.UTF_8).size.toLong(),
                    sourceApp = item.sourceApp,
                    createdAtMs = item.createdAtMs,
                    expiresAtMs = item.expiresAtMs,
                ),
            )
        }
        for (chunk in terminalHeaders.chunked(ProtocolLimits.MAX_ANNOUNCE_CLIPS)) {
            send(ProtocolMessageTypes.CLIP_ANNOUNCE, ClipAnnounceBody(chunk))
        }
        for (batch in chunkPayloads(payloadItems)) {
            send(ProtocolMessageTypes.CLIP_PAYLOAD, ClipPayloadBody(batch))
        }
        if (missing > 0) {
            logger.event("fetch_missing_ids", "count")
            send(
                ProtocolMessageTypes.ERROR,
                ErrorBody(
                    code = ProtocolErrorCodes.PAYLOAD_NOT_FOUND,
                    retryable = true,
                    failedType = ProtocolMessageTypes.CLIP_FETCH,
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
                fail(ProtocolErrorCodes.MESSAGE_OUT_OF_ORDER, "payload_without_announce")
                return false
            }
            if (item.originDeviceId != header.originDeviceId ||
                item.originSeq != header.originSeq ||
                item.contentHash != header.contentHash ||
                item.utf8Bytes != header.utf8Bytes
            ) {
                fail(ProtocolErrorCodes.EVENT_CONFLICT, "payload_header_mismatch")
                return false
            }
            val stored = repository.ingestRemoteClip(
                RemoteClipEvent(
                    eventId = item.eventId,
                    originDeviceId = item.originDeviceId,
                    originSeq = item.originSeq,
                    content = item.content,
                    contentHash = item.contentHash,
                    sourceApp = item.sourceApp,
                    createdAtMs = item.createdAtMs,
                    expiresAtMs = item.expiresAtMs,
                ),
                sourcePeerId = peer.deviceId,
            )
            if (stored is RemoteStoreResult.IdentityConflict) {
                logger.event("store_conflict", "payload")
                fail(ProtocolErrorCodes.EVENT_CONFLICT, "payload_conflict")
                return false
            }
            acks.add(item.originDeviceId to item.originSeq)
            if (stored is RemoteStoreResult.Stored) {
                committed.add(
                    RemoteClipApplied(
                        eventId = item.eventId,
                        originDeviceId = item.originDeviceId,
                        originSeq = item.originSeq,
                        content = item.content,
                        createdAtMs = item.createdAtMs,
                    ),
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
            OriginSequenceRanges(
                ack.originDeviceId,
                ack.ranges.map { SequenceRange(it.startSeq, it.endSeq) },
            )
        }
        repository.ackRanges(peer.deviceId, ranges, options.nowMs())
        return true
    }

    private suspend fun runPingLoop() {
        try {
            while (true) {
                options.delayMs(options.pingIntervalMs)
                if (unansweredPings.incrementAndGet() > options.maxMissedPings) {
                    complete(SyncSessionResult(true, null, "ping_timeout"))
                    try {
                        transport.close("ping_timeout")
                    } catch (_: Exception) {
                    }
                    requestClose()
                    return
                }
                send(ProtocolMessageTypes.PING, PingBody(sentAtMs = options.nowMs()))
            }
        } catch (_: CancellationException) {
            failLoopUnlessShuttingDown("ping_cancellation")
        } catch (_: Exception) {
            logger.event("background_loop_stopped", "ping")
            complete(SyncSessionResult(true, null, "send_failed"))
            requestClose()
        }
    }

    private suspend fun runOutboxLoop() {
        try {
            // Wait for the peer's authoritative known_vector so already-held
            // coverage can prune the reset outbox before the first announce.
            peerVectorArrived.await()
            drainOutbox()
            coroutineScope {
                // New local captures must announce immediately (plan 5.7 P95), not on
                // the next poll tick. The interval stays as a fallback re-check.
                val signals = Channel<Unit>(Channel.CONFLATED)
                launch {
                    repository.observeOutboxPending(peer.deviceId).collect { pending ->
                        if (pending > 0) {
                            signals.trySend(Unit)
                        }
                    }
                }
                while (true) {
                    withTimeoutOrNull(options.outboxDrainIntervalMs) { signals.receive() }
                    drainOutbox()
                }
            }
        } catch (_: CancellationException) {
            failLoopUnlessShuttingDown("outbox_cancellation")
        } catch (_: Exception) {
            logger.event("background_loop_stopped", "outbox")
            complete(SyncSessionResult(true, null, "send_failed"))
            requestClose()
        }
    }

    /**
     * A CancellationException reaching a background loop while the session job is
     * still active is not a shutdown: it is a swallowed timeout-style cancellation
     * from inside the loop body (observed on device as a READY session whose outbox
     * loop had silently died — captures piled up until the next reconnect). Fail the
     * session so the controller reconnects and enterReady() re-drains.
     */
    private suspend fun failLoopUnlessShuttingDown(tag: String) {
        if (!currentCoroutineContext().isActive) {
            return
        }
        logger.event("background_loop_stopped", tag)
        complete(SyncSessionResult(true, null, "send_failed"))
        requestClose()
    }

    private suspend fun drainOutbox() {
        while (true) {
            val pending = repository.outboxPending(peer.deviceId)
            if (pending.isEmpty()) {
                return
            }
            val batch = pending.take(ProtocolLimits.MAX_ANNOUNCE_CLIPS)
            val events = loadByEventIds(batch.map { it.eventId }.toSet())
            val announced = mutableListOf<Long>()
            val headers = mutableListOf<ClipHeaderDto>()
            for (entry in batch) {
                val event = events[entry.eventId] ?: continue
                headers.add(buildHeader(event))
                announced.add(entry.id)
            }
            if (headers.isEmpty()) {
                return
            }
            send(ProtocolMessageTypes.CLIP_ANNOUNCE, ClipAnnounceBody(headers))
            repository.markAnnounced(announced)
            if (batch.size < ProtocolLimits.MAX_ANNOUNCE_CLIPS) {
                return
            }
        }
    }

    /**
     * ClipRepository has no get-by-event-id. Resolve IDs through knownVector coverage
     * plus [ClipRepository.getSyncableEvents] — no second Room schema.
     */
    private suspend fun loadByEventIds(eventIds: Set<String>): Map<String, SyncableClipEvent> {
        if (eventIds.isEmpty()) {
            return emptyMap()
        }
        val found = LinkedHashMap<String, SyncableClipEvent>()
        val vector = repository.knownVector()
        for ((origin, state) in vector.origins) {
            if (found.size >= eventIds.size) {
                break
            }
            var remaining = SyncRangeMath.coverage(state)
            while (remaining.isNotEmpty() && found.size < eventIds.size) {
                val batch = repository.getSyncableEvents(origin, remaining, ProtocolLimits.MAX_ANNOUNCE_CLIPS)
                if (batch.isEmpty()) {
                    break
                }
                for (event in batch) {
                    if (event.eventId in eventIds) {
                        found[event.eventId] = event
                    }
                }
                val served = SequenceRangeMath.normalize(batch.map { SequenceRange(it.originSeq, it.originSeq) })
                remaining = SyncRangeMath.subtract(remaining, served)
            }
        }
        return found
    }

    private fun isTrustedOrigin(originDeviceId: String): Boolean =
        originDeviceId == peer.deviceId

    private suspend fun sendAcks(acks: List<Pair<String, Long>>) {
        if (acks.isEmpty()) {
            return
        }
        val grouped = acks
            .groupBy { it.first }
            .map { (origin, items) ->
                OriginRangesDto(
                    originDeviceId = origin,
                    ranges = SequenceRangeMath.normalize(items.map { SequenceRange(it.second, it.second) })
                        .map { RangeDto(it.startSeq, it.endSeq) },
                )
            }
        send(ProtocolMessageTypes.ACK_RANGES, AckRangesBody(grouped))
    }

    private fun raiseCommitted(committed: List<RemoteClipApplied>) {
        if (committed.isNotEmpty()) {
            onRemoteClipsCommitted(committed)
        }
    }

    private suspend fun buildKnownVector(): SyncStateDto {
        val vector = repository.knownVector()
        return SyncStateDto(
            origins = vector.origins.entries
                .sortedBy { it.key }
                .map { (origin, state) ->
                    OriginStateDto(
                        originDeviceId = origin,
                        contiguousSeq = state.contiguousSeq,
                        receivedRanges = state.receivedRanges
                            .takeIf { it.isNotEmpty() }
                            ?.map { RangeDto(it.startSeq, it.endSeq) },
                    )
                },
        )
    }

    private fun buildHeader(item: SyncableClipEvent): ClipHeaderDto {
        if (item.isTerminal) {
            return ClipHeaderDto(
                eventId = item.eventId,
                originDeviceId = item.originDeviceId,
                originSeq = item.originSeq,
                availability = ClipAvailability.UNAVAILABLE,
                reason = item.terminalReason,
            )
        }
        val content = item.content ?: ""
        return ClipHeaderDto(
            eventId = item.eventId,
            originDeviceId = item.originDeviceId,
            originSeq = item.originSeq,
            availability = ClipAvailability.AVAILABLE,
            kind = "text",
            contentHash = item.contentHash,
            utf8Bytes = content.toByteArray(StandardCharsets.UTF_8).size.toLong(),
            sourceApp = item.sourceApp,
            createdAtMs = item.createdAtMs,
            expiresAtMs = item.expiresAtMs,
        )
    }

    private fun chunkPayloads(items: List<ClipPayloadItemDto>): List<List<ClipPayloadItemDto>> {
        val batches = mutableListOf<List<ClipPayloadItemDto>>()
        var batch = mutableListOf<ClipPayloadItemDto>()
        var batchBytes = 0L
        for (item in items) {
            if (batch.isNotEmpty() &&
                (batch.size >= ProtocolLimits.MAX_PAYLOAD_CLIPS ||
                    batchBytes + item.utf8Bytes > ProtocolLimits.MAX_PAYLOAD_BATCH_CONTENT_BYTES)
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

    private suspend fun failAndFalse(errorCode: String, detail: String): Boolean {
        fail(errorCode, detail)
        return false
    }

    private suspend fun fail(errorCode: String, detail: String) {
        complete(SyncSessionResult(isAuthenticated, errorCode, detail))
        try {
            send(
                ProtocolMessageTypes.ERROR,
                ErrorBody(code = errorCode, retryable = false),
            )
        } catch (_: Exception) {
        }
        try {
            transport.close(errorCode)
        } catch (_: Exception) {
        }
    }

    private fun complete(result: SyncSessionResult) {
        synchronized(completionLock) {
            if (completion == null) {
                completion = result
            }
        }
    }

    private suspend fun send(type: String, body: com.clipsync.android.protocol.SyncMessageBody) {
        if (type == ProtocolMessageTypes.CLIP_PAYLOAD) {
            val payload = body as ClipPayloadBody
            if (payload.clips.any { it.eventId !in fetchPermittedIds }) {
                logger.event("payload_suppressed", "unfetched")
                return
            }
        }
        val json = SyncMessageWriter.encode(type, SyncMessageWriter.newRequestId(), body)
        sendLock.withLock {
            if (!sendsEnabled) {
                return
            }
            transport.sendText(json)
        }
        logger.event("sent", type)
    }

    private enum class SessionState {
        ExpectChallenge,
        Ready,
    }

    private enum class ReplayVerdict {
        Fresh,
        IdenticalRetry,
        Conflict,
    }

    private class ReplayWindow(private val capacity: Int) {
        private val hashes = LinkedHashMap<String, String>()

        fun classify(requestId: String, rawFrame: String): ReplayVerdict {
            val hash = sha256Hex(rawFrame.toByteArray(StandardCharsets.UTF_8))
            val existing = hashes[requestId]
            if (existing != null) {
                return if (existing == hash) ReplayVerdict.IdenticalRetry else ReplayVerdict.Conflict
            }
            hashes[requestId] = hash
            if (hashes.size > capacity) {
                val oldest = hashes.keys.first()
                hashes.remove(oldest)
            }
            return ReplayVerdict.Fresh
        }

        private fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private companion object {
        fun decodeBase64Url(value: String): ByteArray =
            Base64.getUrlDecoder().decode(value)
    }
}

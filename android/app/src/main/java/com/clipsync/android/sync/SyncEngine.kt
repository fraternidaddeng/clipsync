package com.clipsync.android.sync

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
    private val unansweredPings = AtomicInteger(0)
    private val completionLock = Any()

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

    /** Asks the session to stop; used on revocation and shutdown. */
    fun requestClose() {
        sessionJob?.cancel()
    }

    suspend fun run(sessionTransport: SyncTransport): SyncSessionResult {
        transport = sessionTransport
        try {
            coroutineScope {
                sessionJob = coroutineContext[Job]
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
            pairSecret.fill(0)
        }
        return completion ?: SyncSessionResult(isAuthenticated, null, "closed")
    }

    private suspend fun sendHello() {
        send(
            SyncMessageTypes.HELLO,
            HelloBody(
                deviceId = config.localDeviceId,
                platform = config.platform,
                clientVersion = config.clientVersion,
                trustEpoch = config.trustEpoch,
                knownVector = buildKnownVector(),
            ),
        )
    }

    /** Returns false when the session must stop. */
    private suspend fun dispatch(text: String): Boolean {
        val message = try {
            SyncWire.decode(text)
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
        val covered = parsed
            .filterKeys { it != config.peerDeviceId }
            .map { (origin, held) -> OriginSequenceRanges(origin, held.toCoverage()) }
            .filter { it.ranges.isNotEmpty() }
        if (covered.isNotEmpty()) {
            repository.applyPeerAckRanges(config.peerDeviceId, covered, config.nowMs())
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
                send(SyncMessageTypes.CLIP_ANNOUNCE, ClipAnnounceBody(events.map(::buildHeader)))
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
            if (localState.contains(header.originSeq)) {
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

    private suspend fun handleClipFetch(fetch: ClipFetchBody): Boolean {
        val events = repository.getSyncableEventsByIds(fetch.eventIds)
        val byId = events.associateBy { it.eventId }

        val payloadItems = mutableListOf<ClipPayloadItemDto>()
        val terminalHeaders = mutableListOf<ClipHeaderDto>()
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
            send(SyncMessageTypes.CLIP_ANNOUNCE, ClipAnnounceBody(batch.map { buildHeader(it.event) }))
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
        return ClipHeaderDto(
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
        val frame = SyncWire.encode(type, SyncWire.newRequestId(), body)
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

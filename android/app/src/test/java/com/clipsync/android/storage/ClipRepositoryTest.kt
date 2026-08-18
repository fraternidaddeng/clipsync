package com.clipsync.android.storage

import com.clipsync.android.platform.clipboard.Sha256ContentHasher
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ClipRepositoryTest {
    private val hasher = Sha256ContentHasher

    @Test
    fun `local capture rejects empty text without allocating a sequence`() = runTest {
        val repo = repository()
        val result = repo.captureLocalText("", sourceApp = "app", nowMs = NOW, peerId = PEER)
        assertEquals(CaptureResult.Rejected(CaptureRejectReason.EMPTY_TEXT), result)
        assertTrue(repo.search("").isEmpty())
        assertTrue(repo.knownVector().origins.isEmpty())
        assertTrue(repo.outboxPending(PEER).isEmpty())
    }

    @Test
    fun `local capture accepts exactly one mebibyte and rejects one extra byte without truncating`() = runTest {
        val repo = repository()
        val exact = "a".repeat(MAX_CLIP_UTF8_BYTES)
        val stored = repo.captureLocalText(exact, nowMs = NOW, peerId = PEER)
        assertTrue(stored is CaptureResult.Stored)

        val oversized = "a".repeat(MAX_CLIP_UTF8_BYTES + 1)
        assertEquals(
            CaptureResult.Rejected(CaptureRejectReason.TOO_LARGE),
            repo.captureLocalText(oversized, nowMs = NOW + 3_000, peerId = PEER),
        )
        val history = repo.search("")
        assertEquals(1, history.size)
        assertEquals(MAX_CLIP_UTF8_BYTES, history[0].content.toByteArray(StandardCharsets.UTF_8).size)
        assertEquals(1, (repo.knownVector().origins[LOCAL] ?: error("missing")).contiguousSeq)
    }

    @Test
    fun `local capture dedups the same hash inside two seconds then accepts after the window`() = runTest {
        val repo = repository()
        val first = repo.captureLocalText("same", nowMs = NOW, peerId = PEER) as CaptureResult.Stored
        assertEquals(1, first.originSeq)
        assertEquals(
            CaptureResult.Rejected(CaptureRejectReason.DUPLICATE),
            repo.captureLocalText("same", nowMs = NOW + 1_999, peerId = PEER),
        )
        val second = repo.captureLocalText("same", nowMs = NOW + 2_000, peerId = PEER) as CaptureResult.Stored
        assertEquals(2, second.originSeq)
        assertEquals(2, repo.search("").size)
    }

    @Test
    fun `local capture allocates origin seq atomically and fans out outbox to the peer argument`() = runTest {
        val repo = repository()
        val first = repo.captureLocalText("one", sourceApp = "com.example", nowMs = NOW, peerId = PEER)
            as CaptureResult.Stored
        val second = repo.captureLocalText("two", nowMs = NOW + 10, peerId = PEER) as CaptureResult.Stored
        assertEquals(1, first.originSeq)
        assertEquals(2, second.originSeq)
        assertEquals(hasher.hash("one"), first.contentHash)
        assertEquals(2, (repo.knownVector().origins[LOCAL] ?: error("missing")).contiguousSeq)

        val pending = repo.outboxPending(PEER)
        assertEquals(2, pending.size)
        assertEquals(first.eventId, pending[0].eventId)
        assertEquals(OUTBOX_PENDING, pending[0].state)
        assertTrue(repo.outboxPending(OTHER_PEER).isEmpty())
    }

    @Test
    fun `local capture fans out using paired_peer_id when no peer argument is given`() = runTest {
        val repo = repository()
        repo.setSetting(SETTING_PAIRED_PEER_ID, PEER)
        repo.captureLocalText("via-setting", nowMs = NOW)
        val pending = repo.outboxPending(PEER)
        assertEquals(1, pending.size)
        assertEquals("via-setting", repo.search("")[0].content)
    }

    @Test
    fun `local capture without a peer list leaves history and no outbox`() = runTest {
        val repo = repository()
        val stored = repo.captureLocalText("offline", nowMs = NOW) as CaptureResult.Stored
        assertEquals(1, stored.originSeq)
        assertEquals("offline", repo.search("")[0].content)
        assertTrue(repo.outboxPending(PEER).isEmpty())
    }

    @Test
    fun `local capture rejects blocked source without allocating a sequence or outbox`() = runTest {
        val repo = repository()
        val blocked = CapturePolicy.BUILTIN_BLOCKED_PACKAGES.first()
        val result = repo.captureLocalText("from vault", sourceApp = blocked, nowMs = NOW, peerId = PEER)
        assertEquals(CaptureResult.Rejected(CaptureRejectReason.BLOCKED_SOURCE), result)
        assertTrue(repo.search("").isEmpty())
        assertTrue(repo.knownVector().origins.isEmpty())
        assertTrue(repo.outboxPending(PEER).isEmpty())
    }

    @Test
    fun `local capture rejects user extra blacklist without allocating a sequence or outbox`() = runTest {
        val repo = repository()
        repo.setSetting(SETTING_CAPTURE_BLACKLIST_EXTRA, "com.example.vault")
        val result = repo.captureLocalText(
            "from vault",
            sourceApp = "com.example.vault",
            nowMs = NOW,
            peerId = PEER,
        )
        assertEquals(CaptureResult.Rejected(CaptureRejectReason.BLOCKED_SOURCE), result)
        assertTrue(repo.search("").isEmpty())
        assertTrue(repo.knownVector().origins.isEmpty())
        assertTrue(repo.outboxPending(PEER).isEmpty())
    }

    @Test
    fun `local capture allows a built-in package when blacklist is disabled`() = runTest {
        val repo = repository()
        repo.setSetting(SETTING_CAPTURE_BLACKLIST_ENABLED, "false")
        val blocked = CapturePolicy.BUILTIN_BLOCKED_PACKAGES.first()
        val stored = repo.captureLocalText("from vault", sourceApp = blocked, nowMs = NOW, peerId = PEER)
        assertTrue(stored is CaptureResult.Stored)
        assertEquals(1, (stored as CaptureResult.Stored).originSeq)
        assertEquals(1, repo.outboxPending(PEER).size)
    }

    @Test
    fun `local capture rejects paused mode without allocating a sequence or outbox`() = runTest {
        val repo = repository()
        repo.setSetting("is_paused", "true")
        val result = repo.captureLocalText("paused body", nowMs = NOW, peerId = PEER)
        assertEquals(CaptureResult.Rejected(CaptureRejectReason.POLICY_PAUSED), result)
        assertTrue(repo.search("").isEmpty())
        assertTrue(repo.knownVector().origins.isEmpty())
        assertTrue(repo.outboxPending(PEER).isEmpty())
    }

    @Test
    fun `remote ingest is not filtered by the local capture blacklist`() = runTest {
        val repo = repository()
        val blocked = CapturePolicy.BUILTIN_BLOCKED_PACKAGES.first()
        val stored = repo.ingestRemoteClip(remote("from windows", 1).copy(sourceApp = blocked), PEER)
        assertTrue(stored is RemoteStoreResult.Stored)
        assertEquals(1, repo.search("").size)
        assertEquals(blocked, repo.search("")[0].sourceApp)
    }

    @Test
    fun `remote ingest of seq 12 with 11 missing keeps contiguous at 10`() = runTest {
        val repo = repository()
        for (seq in 1L..10L) {
            val stored = repo.ingestRemoteClip(remote("clip $seq", seq), sourcePeerId = PEER)
            assertTrue(stored is RemoteStoreResult.Stored)
        }
        val afterTwelve = repo.ingestRemoteClip(remote("clip 12", 12), sourcePeerId = PEER)
            as RemoteStoreResult.Stored
        assertEquals(10, afterTwelve.receiveState.contiguousSeq)
        assertEquals(listOf(SequenceRange(12, 12)), afterTwelve.receiveState.receivedRanges)

        val afterEleven = repo.ingestRemoteClip(remote("clip 11", 11), sourcePeerId = PEER)
            as RemoteStoreResult.Stored
        assertEquals(12, afterEleven.receiveState.contiguousSeq)
        assertTrue(afterEleven.receiveState.receivedRanges.isEmpty())
        assertEquals(12, (repo.knownVector().origins[PEER] ?: error("missing")).contiguousSeq)
    }

    @Test
    fun `remote replay is already persisted and identity conflicts are distinct`() = runTest {
        val repo = repository()
        val original = remote("original", 1)
        assertTrue(repo.ingestRemoteClip(original, PEER) is RemoteStoreResult.Stored)
        assertTrue(repo.ingestRemoteClip(original, PEER) is RemoteStoreResult.AlreadyPersisted)

        val differentId = repo.ingestRemoteClip(original.copy(eventId = UUID.randomUUID().toString()), PEER)
        assertTrue(differentId is RemoteStoreResult.IdentityConflict)

        val differentHash = repo.ingestRemoteClip(
            original.copy(content = "tampered", contentHash = hasher.hash("tampered")),
            PEER,
        )
        assertTrue(differentHash is RemoteStoreResult.IdentityConflict)
        assertNotEquals(differentId::class, RemoteStoreResult.AlreadyPersisted::class)
        assertNotEquals(differentHash::class, RemoteStoreResult.AlreadyPersisted::class)

        val reusedEventId = repo.ingestRemoteClip(original.copy(originSeq = 2), PEER)
        assertTrue(reusedEventId is RemoteStoreResult.IdentityConflict)
        assertEquals("original", repo.search("")[0].content)
    }

    @Test
    fun `terminal marker advances the cursor and never replaces a stored body`() = runTest {
        val repo = repository()
        val stored = remote("kept body", 1)
        assertTrue(repo.ingestRemoteClip(stored, PEER) is RemoteStoreResult.Stored)

        val replay = repo.ingestTerminalMarker(
            RemoteTerminalMarker(stored.eventId, PEER, 1, TerminalReasons.DELETED),
            sourcePeerId = PEER,
            receivedAtMs = NOW + 1,
        )
        assertTrue(replay is RemoteStoreResult.AlreadyPersisted)
        assertEquals("kept body", repo.search("")[0].content)

        val markerId = UUID.randomUUID().toString()
        val result = repo.ingestTerminalMarker(
            RemoteTerminalMarker(markerId, PEER, 2, TerminalReasons.LOCAL_ONLY),
            sourcePeerId = PEER,
            receivedAtMs = NOW + 2,
        ) as RemoteStoreResult.Stored
        assertEquals(2, result.receiveState.contiguousSeq)

        val syncable = repo.getSyncableEvents(PEER, listOf(SequenceRange(2, 2)), 10)
        assertEquals(1, syncable.size)
        assertTrue(syncable[0].isTerminal)
        assertEquals(TerminalReasons.LOCAL_ONLY, syncable[0].terminalReason)
        assertNull(syncable[0].content)
        assertTrue(repo.search("").none { it.eventId == markerId })
    }

    @Test
    fun `remote ingest from the only paired peer does not bounce an outbox row back`() = runTest {
        val repo = repository()
        repo.setSetting(SETTING_PAIRED_PEER_ID, PEER)
        assertTrue(repo.ingestRemoteClip(remote("from windows", 1), sourcePeerId = PEER) is RemoteStoreResult.Stored)
        assertTrue(repo.outboxPending(PEER).isEmpty())
    }

    @Test
    fun `delete writes a tombstone cancels unacked outbox and keeps the terminal marker`() = runTest {
        val repo = repository()
        val stored = repo.captureLocalText("secret", nowMs = NOW, peerId = PEER) as CaptureResult.Stored
        assertEquals(1, repo.outboxPending(PEER).size)

        assertTrue(repo.delete(stored.eventId, NOW + 5))
        assertTrue(repo.search("").isEmpty())
        assertTrue(repo.search("secret").isEmpty())
        assertTrue(repo.outboxPending(PEER).isEmpty())

        val marker = repo.getSyncableEvents(LOCAL, listOf(SequenceRange(1, 1)), 1).single()
        assertTrue(marker.isTerminal)
        assertEquals(TerminalReasons.DELETED, marker.terminalReason)
        assertEquals("", marker.content ?: "")
        assertEquals(1, (repo.knownVector().origins[LOCAL] ?: error("missing")).contiguousSeq)
        assertNull(repo.findLiveContentByHash(hasher.hash("secret")))
    }

    @Test
    fun `clear tombstones every visible row and cancels unacked outbox`() = runTest {
        val repo = repository()
        repo.captureLocalText("first", nowMs = NOW, peerId = PEER)
        repo.captureLocalText("second", nowMs = NOW + 10, peerId = PEER)
        assertEquals(2, repo.clear(NOW + 20))
        assertTrue(repo.search("").isEmpty())
        assertTrue(repo.outboxPending(PEER).isEmpty())
        assertEquals(2, (repo.knownVector().origins[LOCAL] ?: error("missing")).contiguousSeq)
        val markers = repo.getSyncableEvents(LOCAL, listOf(SequenceRange(1, 2)), 10)
        assertEquals(2, markers.size)
        assertTrue(markers.all { it.isTerminal && it.terminalReason == TerminalReasons.DELETED })
    }

    @Test
    fun `mark announced hides pending rows and ack ranges remove them`() = runTest {
        val repo = repository()
        val first = repo.captureLocalText("one", nowMs = NOW, peerId = PEER) as CaptureResult.Stored
        val second = repo.captureLocalText("two", nowMs = NOW + 10, peerId = PEER) as CaptureResult.Stored
        val third = repo.captureLocalText("three", nowMs = NOW + 20, peerId = PEER) as CaptureResult.Stored

        val pending = repo.outboxPending(PEER)
        repo.markAnnounced(listOf(pending[0].id))
        assertEquals(2, repo.outboxPending(PEER).size)

        repo.ackRanges(
            PEER,
            listOf(OriginSequenceRanges(LOCAL, listOf(SequenceRange(1, 2)))),
            NOW + 30,
        )
        val remaining = repo.outboxPending(PEER)
        assertEquals(1, remaining.size)
        assertEquals(third.eventId, remaining[0].eventId)
        assertEquals(2, (repo.getPeerCursors(PEER)[LOCAL] ?: error("missing")).contiguousSeq)
        assertTrue(listOf(first.eventId, second.eventId).none { it == remaining[0].eventId })
    }

    @Test
    fun `announced entries reset to pending for a new session`() = runTest {
        val repo = repository()
        repo.captureLocalText("first", nowMs = NOW, peerId = PEER)
        val batch = repo.outboxPending(PEER)
        repo.markAnnounced(listOf(batch[0].id))
        assertTrue(repo.outboxPending(PEER).isEmpty())
        repo.resetOutboxToPending(PEER)
        val restored = repo.outboxPending(PEER)
        assertEquals(1, restored.size)
        assertEquals(1, restored[0].attempts)
    }

    @Test
    fun `search is literal and excludes soft-deleted rows`() = runTest {
        val repo = repository()
        val literal = repo.captureLocalText("value 100%_literal", nowMs = NOW, peerId = PEER)
            as CaptureResult.Stored
        repo.captureLocalText("unrelated", nowMs = NOW + 10, peerId = PEER)
        repo.captureLocalText("%' OR 1=1 -- appears literally", nowMs = NOW + 20, peerId = PEER)

        val wildcard = repo.search("100%_literal")
        assertEquals(1, wildcard.size)
        assertEquals(literal.eventId, wildcard[0].eventId)
        assertEquals(1, repo.search("%' OR 1=1 --").size)

        assertTrue(repo.delete(literal.eventId, NOW + 30))
        assertTrue(repo.search("100%_literal").isEmpty())
    }

    @Test
    fun `settings and history survive reopening a new repository on the same store`() = runTest {
        val store = InMemoryClipPersistence()
        val first = ClipRepository(store, LOCAL, hasher)
        first.initialize()
        first.setSetting(SETTING_PAIRED_PEER_ID, PEER)
        val stored = first.captureLocalText("persist-me", sourceApp = "app", nowMs = NOW) as CaptureResult.Stored

        val reopened = ClipRepository(store, LOCAL, hasher)
        reopened.initialize()
        assertEquals(PEER, reopened.getSetting(SETTING_PAIRED_PEER_ID))
        assertEquals("persist-me", reopened.search("")[0].content)
        assertEquals(stored.eventId, reopened.search("")[0].eventId)
        assertEquals(1, reopened.outboxPending(PEER).size)
        assertEquals(1, (reopened.knownVector().origins[LOCAL] ?: error("missing")).contiguousSeq)
    }

    @Test
    fun `unicode and line endings are stored unchanged`() = runTest {
        val repo = repository()
        val text = "第一行\r\nsecond line\nemoji 😀"
        repo.captureLocalText(text, nowMs = NOW, peerId = PEER)
        assertEquals(text, repo.search("")[0].content)
        assertEquals(hasher.hash(text), repo.search("")[0].contentHash)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `observeSearch emits captured clips without a later search call`() = runTest(UnconfinedTestDispatcher()) {
        val repo = repository()
        val seen = mutableListOf<List<String>>()
        val job = backgroundScope.launch {
            repo.observeSearch("").collect { rows -> seen += rows.map { it.content } }
        }
        repo.captureLocalText("live observe", nowMs = NOW, peerId = PEER)
        assertTrue(seen.any { "live observe" in it })
        job.cancel()
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `observeOutboxPending emits on capture and after announce`() = runTest(UnconfinedTestDispatcher()) {
        val repo = repository()
        val seen = mutableListOf<Int>()
        val job = backgroundScope.launch {
            repo.observeOutboxPending(PEER).collect { seen += it }
        }
        repo.captureLocalText("outbox observe", nowMs = NOW, peerId = PEER)
        assertTrue("capture must raise the pending count", seen.contains(1))
        repo.markAnnounced(repo.outboxPending(PEER).map { it.id })
        assertEquals(0, seen.last())
        job.cancel()
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `observeSetting emits paired peer id`() = runTest(UnconfinedTestDispatcher()) {
        val repo = repository()
        val seen = mutableListOf<String?>()
        val job = backgroundScope.launch {
            repo.observeSetting(SETTING_PAIRED_PEER_ID).collect { seen += it }
        }
        repo.setSetting(SETTING_PAIRED_PEER_ID, PEER)
        assertTrue(seen.contains(PEER))
        job.cancel()
    }

    @Test
    fun `transaction rollback leaves no partial remote state`() = runTest {
        val store = InMemoryClipPersistence()
        val repo = ClipRepository(ThrowingAfterInsert(store), LOCAL, hasher)
        repo.initialize()
        try {
            repo.ingestRemoteClip(remote("must roll back", 1), PEER)
            fail("expected rollback")
        } catch (_: InjectedStorageException) {
        }
        val healthy = ClipRepository(store, LOCAL, hasher)
        assertTrue(healthy.search("").isEmpty())
        assertTrue(healthy.knownVector().origins.isEmpty())
        assertTrue(healthy.getSyncableEvents(PEER, listOf(SequenceRange(1, 1)), 10).isEmpty())
    }

    private suspend fun repository(): ClipRepository {
        val repo = ClipRepository(InMemoryClipPersistence(), LOCAL, hasher)
        repo.initialize()
        return repo
    }

    private fun remote(content: String, seq: Long): RemoteClipEvent =
        RemoteClipEvent(
            eventId = UUID.randomUUID().toString(),
            originDeviceId = PEER,
            originSeq = seq,
            content = content,
            contentHash = hasher.hash(content),
            sourceApp = "app",
            createdAtMs = NOW,
        )

    private class InjectedStorageException : RuntimeException()

    private class ThrowingAfterInsert(
        private val inner: InMemoryClipPersistence,
    ) : ClipPersistence {
        override suspend fun <T> transaction(block: suspend ClipSession.() -> T): T =
            inner.transaction {
                val wrapped = object : ClipSession by this {
                    override suspend fun insertClip(entity: ClipEntity) {
                        this@transaction.insertClip(entity)
                        throw InjectedStorageException()
                    }
                }
                wrapped.block()
            }

        override suspend fun <T> read(block: suspend ClipSession.() -> T): T = inner.read(block)

        override fun observeSearchVisible(query: String, limit: Int) =
            inner.observeSearchVisible(query, limit)

        override fun observeSetting(key: String) = inner.observeSetting(key)

        override fun observeOutboxPendingCount(peerId: String) =
            inner.observeOutboxPendingCount(peerId)
    }

    @Test
    fun `purgeExpired hard-deletes an old synced clip and returns the live count`() {
        runTest {
            val repo = repository()
            val stored =
                repo.captureLocalText("old synced", nowMs = NOW - FORTY_DAYS_MS, peerId = PEER)
                    as CaptureResult.Stored
            repo.ackRanges(
                PEER,
                listOf(OriginSequenceRanges(LOCAL, listOf(SequenceRange(1, 1)))),
                NOW,
            )
            assertTrue(repo.outboxPending(PEER).isEmpty())

            val counts = repo.purgeExpired(NOW)
            assertEquals(1, counts.liveClipsDeleted)
            assertEquals(0, counts.tombstonesDeleted)
            assertTrue(repo.search("").isEmpty())
            assertTrue(repo.getSyncableEvents(LOCAL, listOf(SequenceRange(1, 1)), 10).isEmpty())
            assertTrue(repo.search("").none { it.eventId == stored.eventId })
        }
    }

    @Test
    fun `purgeExpired keeps an old clip that still has a pending outbox row`() {
        runTest {
            val repo = repository()
            repo.captureLocalText("old pending", nowMs = NOW - FORTY_DAYS_MS, peerId = PEER)
            assertEquals(1, repo.outboxPending(PEER).size)

            val counts = repo.purgeExpired(NOW)
            assertEquals(0, counts.liveClipsDeleted)
            assertEquals(0, counts.tombstonesDeleted)
            assertEquals(1, repo.search("").size)
            assertEquals(1, repo.outboxPending(PEER).size)
            assertEquals("old pending", repo.search("")[0].content)
        }
    }

    @Test
    fun `purgeExpired keeps a fresh clip`() {
        runTest {
            val repo = repository()
            repo.captureLocalText("fresh", nowMs = NOW, peerId = PEER)

            val counts = repo.purgeExpired(NOW)
            assertEquals(0, counts.liveClipsDeleted)
            assertEquals(1, repo.search("").size)
            assertEquals("fresh", repo.search("")[0].content)
        }
    }

    @Test
    fun `purgeExpired hard-deletes an old tombstone and returns the tombstone count`() {
        runTest {
            val repo = repository()
            val stored =
                repo.captureLocalText("old deleted", nowMs = NOW - TEN_DAYS_MS) as CaptureResult.Stored
            assertTrue(repo.delete(stored.eventId, NOW - FORTY_DAYS_MS))
            assertEquals(1, repo.getSyncableEvents(LOCAL, listOf(SequenceRange(1, 1)), 10).size)

            val counts = repo.purgeExpired(NOW)
            assertEquals(0, counts.liveClipsDeleted)
            assertEquals(1, counts.tombstonesDeleted)
            assertTrue(repo.search("").isEmpty())
            assertTrue(repo.getSyncableEvents(LOCAL, listOf(SequenceRange(1, 1)), 10).isEmpty())
        }
    }

    companion object {
        private const val LOCAL = "11111111-1111-4111-8111-111111111111"
        private const val PEER = "22222222-2222-4222-8222-222222222222"
        private const val OTHER_PEER = "33333333-3333-4333-8333-333333333333"
        private const val NOW = 1_700_000_000_000L
        private const val TEN_DAYS_MS = 10L * 24 * 60 * 60 * 1000
        private const val FORTY_DAYS_MS = 40L * 24 * 60 * 60 * 1000
    }
}

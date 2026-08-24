package com.clipsync.android.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.clipsync.android.platform.clipboard.Sha256ContentHasher
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Export/import per docs/export-format-v1.md: JSON Lines out, whole-file validation in,
 * merge idempotent on (origin_device_id, origin_seq), no outbox fan-out, and the local
 * sequence allocator always ahead of restored own-origin events. Mirrors the Windows
 * `HistoryTransferTests` so both clients honour the same file semantics.
 */
@RunWith(RobolectricTestRunner::class)
class HistoryTransferTest {
    private lateinit var sourceDb: ClipSyncDatabase
    private lateinit var targetDb: ClipSyncDatabase
    private lateinit var source: ClipSyncRepository
    private lateinit var target: ClipSyncRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        sourceDb = Room.inMemoryDatabaseBuilder(context, ClipSyncDatabase::class.java)
            .allowMainThreadQueries().build()
        targetDb = Room.inMemoryDatabaseBuilder(context, ClipSyncDatabase::class.java)
            .allowMainThreadQueries().build()
        source = ClipSyncRepository(sourceDb, SOURCE_DEVICE)
        target = ClipSyncRepository(targetDb, TARGET_DEVICE)
    }

    @After
    fun tearDown() {
        sourceDb.close()
        targetDb.close()
    }

    // ---- Export ----

    @Test
    fun exportWritesHeaderThenOneLinePerEventIncludingTombstones() = runBlocking {
        source.storeLocalEvent(draft("first"), emptyList())
        val second = source.storeLocalEvent(draft("second"), emptyList())
        source.deleteEvent(second.eventId, NOW + 2)

        val lines = export(source).trimEnd('\n').split('\n')

        assertEquals(3, lines.size)
        val header = HistoryExportFormat.parseHeaderLine(lines[0])
        assertEquals(2, header.eventCount)
        assertEquals(SOURCE_DEVICE, header.exportingDeviceId)
        assertEquals("android", header.platform)

        val live = HistoryExportFormat.parseClipLine(lines[1], 2)
        assertEquals("first", live.content)
        assertEquals(Sha256ContentHasher.hash("first"), live.contentHash)
        assertNull(live.terminalReason)

        val tombstone = HistoryExportFormat.parseClipLine(lines[2], 3)
        assertTrue(tombstone.isTerminal)
        assertEquals("deleted", tombstone.terminalReason)
        assertNull(tombstone.content)
        assertEquals(NOW + 2, tombstone.deletedAtMs)
    }

    // ---- Import ----

    @Test
    fun importIntoEmptyRepositoryRestoresHistoryAndReceiveVector() = runBlocking {
        source.storeLocalEvent(draft("own one"), emptyList())
        source.storeLocalEvent(draft("own two"), emptyList())
        source.storeRemoteEvent(remoteEvent(1), sourcePeerId = null)
        val document = export(source)

        val result = import(target, document)

        assertEquals(HistoryImportResult(3, 0, 0), result)
        val history = target.searchHistory()
        assertEquals(3, history.size)
        assertTrue(history.any { it.content == "own one" })
        assertTrue(history.any { it.content == "remote 1" })

        val vector = target.knownVector()
        assertEquals(2, vector.getValue(SOURCE_DEVICE).contiguousSeq)
        assertEquals(1, vector.getValue(REMOTE_ORIGIN).contiguousSeq)
    }

    @Test
    fun importingTheSameFileTwiceChangesNothing() = runBlocking {
        source.storeLocalEvent(draft("repeat me"), emptyList())
        val document = export(source)

        assertEquals(HistoryImportResult(1, 0, 0), import(target, document))
        assertEquals(HistoryImportResult(0, 1, 0), import(target, document))
        assertEquals(1, target.searchHistory().size)
    }

    @Test
    fun importMergesOnlyTheEventsTheTargetIsMissing() = runBlocking {
        val shared = remoteEvent(1)
        source.storeRemoteEvent(shared, sourcePeerId = null)
        source.storeRemoteEvent(remoteEvent(2), sourcePeerId = null)
        target.storeRemoteEvent(shared, sourcePeerId = null)

        val result = import(target, export(source))

        assertEquals(HistoryImportResult(1, 1, 0), result)
        assertEquals(2, target.searchHistory().size)
        assertEquals(2, target.knownVector().getValue(REMOTE_ORIGIN).contiguousSeq)
    }

    @Test
    fun restoringOwnEventsBumpsTheSequenceAllocatorPastTheImport() = runBlocking {
        repeat(3) { source.storeLocalEvent(draft("own $it"), emptyList()) }
        val document = export(source)

        // The restore case: a fresh install that kept its device identity.
        val context = ApplicationProvider.getApplicationContext<Context>()
        val restoredDb = Room.inMemoryDatabaseBuilder(context, ClipSyncDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val restored = ClipSyncRepository(restoredDb, SOURCE_DEVICE)
            assertEquals(HistoryImportResult(3, 0, 0), import(restored, document))

            val next = restored.storeLocalEvent(draft("after restore"), emptyList())
            assertEquals(4, next.originSeq)
        } finally {
            restoredDb.close()
        }
    }

    @Test
    fun importedTombstoneNeverErasesALiveLocalRowAndViceVersa() = runBlocking {
        // Source deleted the event; target still holds it live.
        val shared = remoteEvent(1)
        source.storeRemoteEvent(shared, sourcePeerId = null)
        source.deleteEvent(shared.eventId, NOW + 1)
        target.storeRemoteEvent(shared, sourcePeerId = null)

        assertEquals(HistoryImportResult(0, 1, 0), import(target, export(source)))
        assertEquals("remote 1", target.searchHistory().single().content)

        // The mirror image: the target deleted it; a live import must not revive it.
        val context = ApplicationProvider.getApplicationContext<Context>()
        val liveDb = Room.inMemoryDatabaseBuilder(context, ClipSyncDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val liveHolder = ClipSyncRepository(liveDb, TARGET_DEVICE)
            liveHolder.storeRemoteEvent(shared, sourcePeerId = null)
            val liveDocument = export(liveHolder)

            target.deleteEvent(shared.eventId, NOW + 2)
            assertEquals(HistoryImportResult(0, 1, 0), import(target, liveDocument))
            assertTrue(target.searchHistory().isEmpty())
        } finally {
            liveDb.close()
        }
    }

    @Test
    fun conflictingIdentitiesAreCountedAndLeaveTheRepositoryUntouched() = runBlocking {
        source.storeRemoteEvent(remoteEvent(1, content = "their version"), sourcePeerId = null)
        source.storeRemoteEvent(remoteEvent(2, content = "clean"), sourcePeerId = null)
        // The target holds a different event under the same (origin, seq) key.
        target.storeRemoteEvent(
            remoteEvent(1, content = "our version", eventIdSuffix = 9_999),
            sourcePeerId = null,
        )

        val result = import(target, export(source))

        assertEquals(HistoryImportResult(1, 0, 1), result)
        val contents = target.searchHistory().map { it.content }
        assertTrue("our version" in contents)
        assertTrue("clean" in contents)
        assertFalse("their version" in contents)
    }

    @Test
    fun importNeverEnqueuesOutboxRows() = runBlocking {
        source.storeRemoteEvent(remoteEvent(1), sourcePeerId = null)
        val document = export(source)

        import(target, document)

        assertEquals(0, target.totalPendingOutboxCount())
    }

    // ---- Whole-file validation ----

    @Test
    fun tamperedContentFailsTheWholeFileBeforeAnyWrite() = runBlocking {
        source.storeLocalEvent(draft("intact"), emptyList())
        source.storeLocalEvent(draft("tampered"), emptyList())
        val document = export(source).replace("tampered", "attacker")

        assertTransferFails(document, HistoryTransferErrorCodes.HASH_MISMATCH)
        assertTrue(target.searchHistory().isEmpty())
    }

    @Test
    fun unsupportedVersionsAndForeignFilesAreRejectedWithStableCodes() = runBlocking {
        val wrongVersion =
            """{"type":"header","format":"clipsync-history","format_version":2,""" +
                """"exported_at_ms":1,"exporting_device_id":"x","platform":"android","event_count":0}""" + "\n"
        assertTransferFails(wrongVersion, HistoryTransferErrorCodes.UNSUPPORTED_VERSION)
        assertTransferFails("{\"hello\":\"world\"}\n", HistoryTransferErrorCodes.BAD_HEADER)
        assertTransferFails("", HistoryTransferErrorCodes.BAD_HEADER)
    }

    @Test
    fun truncatedFilesFailTheEventCountCheck() = runBlocking {
        source.storeLocalEvent(draft("kept"), emptyList())
        source.storeLocalEvent(draft("lost in truncation"), emptyList())
        val lines = export(source).trimEnd('\n').split('\n')
        val truncated = lines.dropLast(1).joinToString("\n") + "\n"

        assertTransferFails(truncated, HistoryTransferErrorCodes.COUNT_MISMATCH)
        assertTrue(target.searchHistory().isEmpty())
    }

    @Test
    fun blankLinesAreToleratedAroundRecords() = runBlocking {
        source.storeLocalEvent(draft("padded"), emptyList())
        val document = "\n" + export(source) + "\n\n"

        assertEquals(HistoryImportResult(1, 0, 0), import(target, document))
    }

    /** A line as the Windows exporter writes it (System.Text.Json field order) imports cleanly. */
    @Test
    fun windowsShapedDocumentImports() = runBlocking {
        val document =
            """{"type":"header","format":"clipsync-history","format_version":1,"exported_at_ms":1700000000000,""" +
                """"exporting_device_id":"11111111-1111-4111-8111-111111111111","platform":"windows","event_count":1}""" +
                "\n" +
                """{"type":"clip","event_id":"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",""" +
                """"origin_device_id":"11111111-1111-4111-8111-111111111111","origin_seq":7,"kind":"text",""" +
                """"content":"hello","content_hash":"2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",""" +
                """"source_app":"notepad","created_at_ms":1700000000000,"expires_at_ms":null,"deleted_at_ms":null,""" +
                """"terminal_reason":null}""" + "\n"

        assertEquals(HistoryImportResult(1, 0, 0), import(target, document))
        assertEquals("hello", target.searchHistory().single().content)
    }

    // ---- Helpers ----

    private suspend fun export(repository: ClipSyncRepository): String {
        val output = ByteArrayOutputStream()
        repository.exportHistory(output, EXPORTED_AT)
        return output.toString(Charsets.UTF_8)
    }

    private suspend fun import(repository: ClipSyncRepository, document: String): HistoryImportResult =
        repository.importHistory(ByteArrayInputStream(document.toByteArray(Charsets.UTF_8)))

    private suspend fun assertTransferFails(document: String, expectedCode: String) {
        try {
            import(target, document)
            fail("Expected HistoryTransferException($expectedCode)")
        } catch (exception: HistoryTransferException) {
            assertEquals(expectedCode, exception.errorCode)
        }
    }

    private fun draft(text: String) = LocalClipDraft(
        content = text,
        contentHash = Sha256ContentHasher.hash(text),
        sourceApp = "com.example.app",
        capturedAtMs = NOW,
    )

    private fun remoteEvent(seq: Long, content: String = "remote $seq", eventIdSuffix: Long = seq) =
        RemoteClipEvent(
            eventId = "00000000-0000-4000-8000-%012d".format(eventIdSuffix),
            originDeviceId = REMOTE_ORIGIN,
            originSeq = seq,
            content = content,
            contentHash = Sha256ContentHasher.hash(content),
            sourceApp = null,
            createdAtMs = NOW,
            expiresAtMs = null,
        )

    private companion object {
        const val SOURCE_DEVICE = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        const val TARGET_DEVICE = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
        const val REMOTE_ORIGIN = "22222222-2222-4222-8222-222222222222"
        const val NOW = 1_700_000_000_000L
        const val EXPORTED_AT = 1_700_000_100_000L
    }
}

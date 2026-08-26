package com.clipsync.android.ui.home

import com.clipsync.android.R
import com.clipsync.android.i18n.UiText
import com.clipsync.android.pairing.FakeKeyValueStore
import com.clipsync.android.pairing.FakeSecretProtector
import com.clipsync.android.pairing.PairingConfirmResponse
import com.clipsync.android.pairing.PairingDocumentKinds
import com.clipsync.android.pairing.PairingQrPayload
import com.clipsync.android.pairing.PairingStore
import com.clipsync.android.platform.clipboard.CapabilityState
import com.clipsync.android.platform.clipboard.ClipboardWriteCoordinator
import com.clipsync.android.platform.clipboard.ClipboardWriteResult
import com.clipsync.android.platform.clipboard.ClipboardWriter
import com.clipsync.android.storage.ClipHistoryEntry
import com.clipsync.android.storage.ClipKinds
import com.clipsync.android.storage.ClipMediaRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** In-memory history: contains-filtering like the DAO, newest first. */
private class FakeHistoryGateway : HistoryGateway {
    val clips = MutableStateFlow<List<ClipHistoryEntry>>(emptyList())
    val observedQueries = mutableListOf<String>()
    val deletes = mutableListOf<Pair<String, Long>>()

    override fun observeSearch(query: String): Flow<List<ClipHistoryEntry>> {
        observedQueries += query
        val needle = query.trim()
        return clips.map { list ->
            list
                .filter { needle.isEmpty() || it.content.contains(needle, ignoreCase = true) }
                .sortedByDescending { it.createdAtMs }
        }
    }

    override suspend fun findVisible(eventId: String): ClipHistoryEntry? = clips.value.find { it.eventId == eventId }

    override suspend fun delete(
        eventId: String,
        nowMs: Long,
    ) {
        deletes += eventId to nowMs
        clips.update { list -> list.filterNot { it.eventId == eventId } }
    }
}

private class FakeClipboardWriter(
    var result: ClipboardWriteResult = ClipboardWriteResult.Success,
) : ClipboardWriter {
    val writes = mutableListOf<Pair<String, String>>()

    override fun probe(): CapabilityState = CapabilityState.READY

    override fun writeText(
        text: String,
        originEventId: String,
    ): ClipboardWriteResult {
        writes += originEventId to text
        return result
    }
}

private const val WINDOWS_ID = "2f9f3c1a-9f5e-4d0b-b0a3-2f4f1c6d8e01"
private const val CERT = "AAAABBBBCCCCDDDDEEEEFFFF0000111122223333444455556666777788889999"

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repository = FakeHistoryGateway()
    private val writer = FakeClipboardWriter()
    private val keyValues = FakeKeyValueStore()
    private val pairingStore = PairingStore(keyValues, FakeSecretProtector())

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun model(nowMs: () -> Long = { 1_755_100_000_000 }) =
        HomeViewModel(
            history = repository,
            writeCoordinator = ClipboardWriteCoordinator(publicWriter = writer),
            pairingStore = pairingStore,
            nowMs = nowMs,
            searchDebounceMs = 250,
            noticeClearAfterMs = 2_500,
        )

    private fun clip(
        eventId: String,
        content: String,
        origin: String,
        createdAtMs: Long,
    ) = ClipHistoryEntry(
        eventId = eventId,
        originDeviceId = origin,
        originSeq = createdAtMs,
        content = content,
        contentHash = "hash-$eventId",
        sourceApp = null,
        createdAtMs = createdAtMs,
        expiresAtMs = null,
        deletedAtMs = null,
        appliedAtMs = null,
    )

    private fun imageClip(
        eventId: String,
        createdAtMs: Long,
        media: ClipMediaRef?,
    ) = ClipHistoryEntry(
        eventId = eventId,
        originDeviceId = "x",
        originSeq = createdAtMs,
        content = "",
        contentHash = "hash-$eventId",
        sourceApp = null,
        createdAtMs = createdAtMs,
        expiresAtMs = null,
        deletedAtMs = null,
        appliedAtMs = null,
        kind = ClipKinds.IMAGE,
        media = media,
    )

    private fun pairWithWindows() {
        pairingStore.savePeer(
            qr =
                PairingQrPayload(
                    kind = PairingDocumentKinds.QR,
                    version = 1,
                    hosts = listOf("192.168.1.23"),
                    port = 47654,
                    deviceId = WINDOWS_ID,
                    displayName = "PC-STUDIO",
                    certSha256 = CERT,
                    token = "token",
                    expiresAtMs = 1_755_200_000_000,
                ),
            response =
                PairingConfirmResponse(
                    kind = PairingDocumentKinds.CONFIRM_RESPONSE,
                    version = 1,
                    deviceId = WINDOWS_ID,
                    displayName = "PC-STUDIO",
                    platform = "windows",
                    pairSecret = "secret",
                    trustEpoch = 1,
                ),
            pairSecret = ByteArray(32),
            nowMs = 1_755_000_000_000,
        )
    }

    @Test
    fun `maps rows - remote clips carry the peer label and locals carry none`() =
        runTest(dispatcher) {
            pairWithWindows()
            val localId = pairingStore.localDeviceId()
            repository.clips.value =
                listOf(
                    clip("e-local", "git status", origin = localId, createdAtMs = 2_000),
                    clip("e-remote", "https://github.com", origin = WINDOWS_ID, createdAtMs = 3_000),
                    clip("e-unknown", "hello", origin = "someone-else", createdAtMs = 1_000),
                )
            val model = model()
            testScheduler.advanceUntilIdle()

            val items = model.state.value.items
            assertEquals(listOf("e-remote", "e-local", "e-unknown"), items.map { it.eventId })
            assertEquals(UiText.Raw("PC-STUDIO"), items[0].remoteSourceLabel)
            assertNull(items[1].remoteSourceLabel)
            assertEquals(UiText.Res(R.string.remote_device_fallback), items[2].remoteSourceLabel)
            // Neighbour hues follow pairing order (charter §3.4): the paired PC
            // holds slot 1; locals and unslotted remotes carry no hue.
            assertEquals(1, items[0].sourceAccentSlot)
            assertNull(items[1].sourceAccentSlot)
            assertNull(items[2].sourceAccentSlot)
            assertTrue(model.state.value.loaded)
            assertFalse(model.state.value.searchActive)
        }

    @Test
    fun `search is debounced - only the settled query reaches the repository`() =
        runTest(dispatcher) {
            repository.clips.value =
                listOf(
                    clip("e1", "git checkout", origin = "x", createdAtMs = 1_000),
                    clip("e2", "meeting notes", origin = "x", createdAtMs = 2_000),
                )
            val model = model()
            testScheduler.advanceUntilIdle()

            model.setQuery("g")
            testScheduler.advanceTimeBy(100)
            model.setQuery("gi")
            testScheduler.advanceTimeBy(100)
            model.setQuery("git")
            // Neither intermediate query may have hit the repository yet.
            assertEquals(listOf(""), repository.observedQueries)

            testScheduler.advanceTimeBy(251)
            testScheduler.runCurrent()
            assertEquals(listOf("", "git"), repository.observedQueries)
            assertEquals(
                listOf("e1"),
                model.state.value.items
                    .map { it.eventId },
            )
            assertTrue(model.state.value.searchActive)
            assertEquals("git", model.state.value.query)
        }

    @Test
    fun `clearing the query skips the debounce delay`() =
        runTest(dispatcher) {
            repository.clips.value = listOf(clip("e1", "abc", origin = "x", createdAtMs = 1_000))
            val model = model()
            testScheduler.advanceUntilIdle()
            model.setQuery("zzz")
            testScheduler.advanceUntilIdle()
            assertTrue(
                model.state.value.items
                    .isEmpty(),
            )

            model.setQuery("")
            testScheduler.runCurrent()
            assertEquals(
                listOf("e1"),
                model.state.value.items
                    .map { it.eventId },
            )
            assertFalse(model.state.value.searchActive)
        }

    @Test
    fun `copy writes through the coordinator and reports success`() =
        runTest(dispatcher) {
            repository.clips.value = listOf(clip("e1", "copy me", origin = "x", createdAtMs = 1_000))
            val model = model()
            testScheduler.advanceUntilIdle()

            model.copy("e1")
            testScheduler.runCurrent()
            assertEquals(listOf("e1" to "copy me"), writer.writes)
            assertEquals(HomeNotice.Copied, model.state.value.notice)

            // The notice is transient.
            testScheduler.advanceTimeBy(2_501)
            testScheduler.runCurrent()
            assertNull(model.state.value.notice)
        }

    @Test
    fun `copy failure surfaces the writer error code instead of faking success`() =
        runTest(dispatcher) {
            repository.clips.value = listOf(clip("e1", "copy me", origin = "x", createdAtMs = 1_000))
            writer.result = ClipboardWriteResult.Failure("public_write_denied")
            val model = model()
            testScheduler.advanceUntilIdle()

            model.copy("e1")
            testScheduler.runCurrent()
            assertEquals(HomeNotice.CopyFailed("public_write_denied"), model.state.value.notice)
        }

    @Test
    fun `copy of a vanished entry does nothing`() =
        runTest(dispatcher) {
            val model = model()
            testScheduler.advanceUntilIdle()
            model.copy("missing")
            testScheduler.runCurrent()
            assertTrue(writer.writes.isEmpty())
            assertNull(model.state.value.notice)
        }

    @Test
    fun `delete removes the local copy only and says so`() =
        runTest(dispatcher) {
            repository.clips.value =
                listOf(
                    clip("e1", "first", origin = "x", createdAtMs = 1_000),
                    clip("e2", "second", origin = "x", createdAtMs = 2_000),
                )
            val model = model(nowMs = { 42L })
            testScheduler.advanceUntilIdle()

            model.delete("e1")
            testScheduler.runCurrent()
            assertEquals(listOf("e1" to 42L), repository.deletes)
            assertEquals(
                listOf("e2"),
                model.state.value.items
                    .map { it.eventId },
            )
            assertEquals(HomeNotice.DeletedLocal, model.state.value.notice)

            // The removal notice is transient like the copy one.
            testScheduler.advanceTimeBy(2_501)
            testScheduler.runCurrent()
            assertNull(model.state.value.notice)
        }

    @Test
    fun `refreshPeer relabels remote clips after a new pairing`() =
        runTest(dispatcher) {
            repository.clips.value =
                listOf(
                    clip("e-remote", "hello", origin = WINDOWS_ID, createdAtMs = 1_000),
                )
            val model = model()
            testScheduler.advanceUntilIdle()
            assertEquals(
                UiText.Res(R.string.remote_device_fallback),
                model.state.value.items
                    .single()
                    .remoteSourceLabel,
            )
            assertNull(
                model.state.value.items
                    .single()
                    .sourceAccentSlot,
            )

            pairWithWindows()
            model.refreshPeer()
            testScheduler.advanceUntilIdle()
            assertEquals(
                UiText.Raw("PC-STUDIO"),
                model.state.value.items
                    .single()
                    .remoteSourceLabel,
            )
            assertEquals(
                1,
                model.state.value.items
                    .single()
                    .sourceAccentSlot,
            )
        }

    @Test
    fun `manual device colour overrides the pairing-order slot after refreshPeer`() =
        runTest(dispatcher) {
            pairWithWindows()
            repository.clips.value =
                listOf(
                    clip("e-remote", "hello", origin = WINDOWS_ID, createdAtMs = 1_000),
                )
            val model = model()
            testScheduler.advanceUntilIdle()
            assertEquals(
                1,
                model.state.value.items
                    .single()
                    .sourceAccentSlot,
            )

            // 设备色是设备行的属性（P1#14）：the override wins, clearing it returns
            // the row to its pairing-order default.
            pairingStore.setDeviceAccent(WINDOWS_ID, 4)
            model.refreshPeer()
            testScheduler.advanceUntilIdle()
            assertEquals(
                4,
                model.state.value.items
                    .single()
                    .sourceAccentSlot,
            )

            pairingStore.setDeviceAccent(WINDOWS_ID, null)
            model.refreshPeer()
            testScheduler.advanceUntilIdle()
            assertEquals(
                1,
                model.state.value.items
                    .single()
                    .sourceAccentSlot,
            )
        }

    @Test
    fun `format chips filter render-time without touching the repository query`() =
        runTest(dispatcher) {
            repository.clips.value =
                listOf(
                    clip("e-link", "https://github.com/clipsync", origin = "x", createdAtMs = 3_000),
                    clip("e-otp", "843921", origin = "x", createdAtMs = 2_000),
                    clip("e-plain", "meeting notes", origin = "x", createdAtMs = 1_000),
                )
            val model = model()
            testScheduler.advanceUntilIdle()
            assertEquals(
                listOf(ClipContentFormat.LINK, ClipContentFormat.OTP, ClipContentFormat.PLAIN),
                model.state.value.items
                    .map { it.format },
            )

            model.setFormatFilter(ClipContentFormat.LINK)
            testScheduler.advanceUntilIdle()
            assertEquals(
                listOf("e-link"),
                model.state.value.items
                    .map { it.eventId },
            )
            assertEquals(ClipContentFormat.LINK, model.state.value.formatFilter)
            // The repository only ever saw the blank search — the format never
            // reaches the DB (ADR 0003: render-time only, no persisted tag).
            assertEquals(listOf(""), repository.observedQueries)

            model.setFormatFilter(null)
            testScheduler.advanceUntilIdle()
            assertEquals(
                listOf("e-link", "e-otp", "e-plain"),
                model.state.value.items
                    .map { it.eventId },
            )
            assertNull(model.state.value.formatFilter)
        }

    @Test
    fun `image rows carry quiet metadata pill labels from the joined blob index`() =
        runTest(dispatcher) {
            repository.clips.value =
                listOf(
                    imageClip(
                        "e-img",
                        createdAtMs = 2_000,
                        media =
                            ClipMediaRef(
                                contentHash = "hash-e-img",
                                mimeType = "image/png",
                                encodedBytes = 2_048,
                                pixelWidth = 320,
                                pixelHeight = 200,
                            ),
                    ),
                    // Blob index gone: the pills hide, the row stays an honest image row.
                    imageClip("e-img-bare", createdAtMs = 1_000, media = null),
                )
            val model = model()
            testScheduler.advanceUntilIdle()

            val items = model.state.value.items
            assertEquals(listOf("e-img", "e-img-bare"), items.map { it.eventId })
            assertTrue(items[0].isImage)
            assertEquals("PNG", items[0].imageFormatLabel)
            assertEquals("320×200", items[0].imageDimensionsLabel)
            assertEquals("2 KiB", items[0].imageByteSizeLabel)
            assertEquals("", items[1].imageFormatLabel)
            assertEquals("", items[1].imageDimensionsLabel)
            assertEquals("", items[1].imageByteSizeLabel)
        }

    @Test
    fun `preview text collapses whitespace and caps length`() {
        assertEquals("a b c", previewText(" a\n b\t\tc "))
        val long = "x".repeat(500)
        val preview = previewText(long)
        assertEquals(161, preview.length)
        assertTrue(preview.endsWith("…"))
    }

    @Test
    fun `time labels - today by clock yesterday by name older by date`() {
        val zone = ZoneId.of("Asia/Shanghai")
        val now = ZonedDateTime.of(2026, 8, 24, 10, 48, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals("10:48", clipTimeLabel(now, now, zone))
        assertEquals("昨天", clipTimeLabel(now - 24 * 3_600_000, now, zone))
        assertEquals("8月20日", clipTimeLabel(now - 4 * 24 * 3_600_000, now, zone))
        assertEquals("2025年8月24日", clipTimeLabel(now - 365L * 24 * 3_600_000, now, zone))
    }
}

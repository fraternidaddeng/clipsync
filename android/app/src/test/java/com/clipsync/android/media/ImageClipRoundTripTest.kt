package com.clipsync.android.media

import com.clipsync.android.protocol.ProtocolJson
import com.clipsync.android.sync.ClipPayloadBeginBody
import com.clipsync.android.sync.ClipPayloadChunkBody
import com.clipsync.android.sync.ClipPayloadEndBody
import com.clipsync.android.sync.SyncMessageTypes
import com.clipsync.android.sync.SyncWire
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlin.io.path.createTempDirectory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cross-platform contract for the v2 image clip round-trip, driven by the small binary
 * fixtures in protocol/v2/fixtures/media. The Windows suite runs the same assertions
 * (Media/ImageClipRoundTripTests.cs) against the same files and manifest, so both stacks
 * must agree on bytes, hashes, chunk boundaries, and wire frames.
 */
class ImageClipRoundTripTest {
    private val fixturesRoot = File(requireNotNull(System.getProperty("protocol.v2.fixtures.dir")))
    private val mediaRoot = File(fixturesRoot, "media")
    private val manifest = Json.parseToJsonElement(File(mediaRoot, "manifest.json").readText()).jsonObject

    private data class MediaFixture(
        val fileName: String,
        val manifestPrefix: String,
        val mime: String,
        val width: Int,
        val height: Int,
    )

    private val fixtures = listOf(
        MediaFixture("png-1x1-transparent.png", "png_1x1", MediaLimits.MIME_PNG, 1, 1),
        MediaFixture("png-2x2-quadrant.png", "png_2x2", MediaLimits.MIME_PNG, 2, 2),
        MediaFixture("png-8x8.png", "png_8x8", MediaLimits.MIME_PNG, 8, 8),
        MediaFixture("jpeg-1x1.jpg", "jpeg_1x1", MediaLimits.MIME_JPEG, 1, 1),
    )

    @Test
    fun `every media fixture inspects to its manifest entry`() {
        fixtures.forEach { fixture ->
            val encoded = File(mediaRoot, fixture.fileName).readBytes()
            assertEquals(fixture.fileName, manifestInt("${fixture.manifestPrefix}_bytes"), encoded.size)

            val (error, image) = ImageCodec.tryInspect(encoded)
            assertEquals(fixture.fileName, ImageCodecError.OK, error)
            assertNotNull(fixture.fileName, image)
            assertEquals(fixture.fileName, fixture.mime, image!!.mimeType)
            assertEquals(fixture.fileName, fixture.width, image.pixelWidth)
            assertEquals(fixture.fileName, fixture.height, image.pixelHeight)
            assertEquals(fixture.fileName, encoded.size, image.encodedBytes)
            assertEquals(fixture.fileName, manifestString("${fixture.manifestPrefix}_sha256"), image.contentHash)
        }
    }

    @Test
    fun `every media fixture survives the chunked wire round-trip into the blob store`() {
        fixtures.forEach { fixture ->
            val encoded = File(mediaRoot, fixture.fileName).readBytes()
            val contentHash = ImageCodec.hashBytes(encoded)
            val transferId = UUID.randomUUID().toString()
            val eventId = UUID.randomUUID().toString()

            // Sender: split and serialize begin -> chunk* -> end exactly as the sync engine does.
            val chunks = ImageChunks.split(encoded)
            val frames = buildList {
                add(
                    SyncWire.encode(
                        SyncMessageTypes.CLIP_PAYLOAD_BEGIN,
                        SyncWire.newRequestId(),
                        ClipPayloadBeginBody(
                            transferId = transferId,
                            eventId = eventId,
                            chunkCount = chunks.size,
                            encodedBytes = encoded.size.toLong(),
                            contentHash = contentHash,
                            mimeType = fixture.mime,
                        ),
                        version = ProtocolJson.PROTOCOL_V2,
                    ),
                )
                chunks.forEach { chunk ->
                    add(
                        SyncWire.encode(
                            SyncMessageTypes.CLIP_PAYLOAD_CHUNK,
                            SyncWire.newRequestId(),
                            ClipPayloadChunkBody(
                                transferId = transferId,
                                eventId = eventId,
                                chunkIndex = chunk.index,
                                chunkCount = chunk.count,
                                chunkBytes = chunk.byteCount,
                                data = chunk.data,
                            ),
                            version = ProtocolJson.PROTOCOL_V2,
                        ),
                    )
                }
                add(
                    SyncWire.encode(
                        SyncMessageTypes.CLIP_PAYLOAD_END,
                        SyncWire.newRequestId(),
                        ClipPayloadEndBody(transferId = transferId, eventId = eventId, contentHash = contentHash),
                        version = ProtocolJson.PROTOCOL_V2,
                    ),
                )
            }

            // Receiver: every frame must pass the strict v2 validator, then reassemble and commit.
            val begin = SyncWire.decode(frames.first(), ProtocolJson.PROTOCOL_V2).body as ClipPayloadBeginBody
            assertEquals(fixture.fileName, contentHash, begin.contentHash)
            assertEquals(fixture.fileName, encoded.size.toLong(), begin.encodedBytes)
            assertEquals(fixture.fileName, fixture.mime, begin.mimeType)

            val reassembly = ByteArrayOutputStream()
            chunks.indices.forEach { index ->
                val chunk = SyncWire.decode(frames[1 + index], ProtocolJson.PROTOCOL_V2).body as ClipPayloadChunkBody
                assertEquals(fixture.fileName, begin.transferId, chunk.transferId)
                assertEquals(fixture.fileName, index, chunk.chunkIndex)
                val bytes = ImageChunks.tryDecodeChunk(chunk.data, chunk.chunkBytes)
                assertNotNull(fixture.fileName, bytes)
                reassembly.write(bytes!!)
            }

            val end = SyncWire.decode(frames.last(), ProtocolJson.PROTOCOL_V2).body as ClipPayloadEndBody
            assertEquals(fixture.fileName, begin.contentHash, end.contentHash)

            val reassembled = reassembly.toByteArray()
            assertArrayEquals(fixture.fileName, encoded, reassembled)
            assertEquals(fixture.fileName, contentHash, ImageCodec.hashBytes(reassembled))

            val store = MediaBlobStore(createTempDirectory("clipsync-roundtrip").toFile())
            val committed = store.commitBytes(reassembled, contentHash)
            assertEquals(fixture.fileName, contentHash, committed.contentHash)
            assertTrue(fixture.fileName, store.exists(contentHash))
            assertArrayEquals(fixture.fileName, encoded, store.readAllBytes(contentHash))
        }
    }

    @Test
    fun `wire fixtures bind the shared png-8x8 sample exactly`() {
        val encoded = File(mediaRoot, "png-8x8.png").readBytes()
        val expectedHash = manifestString("png_8x8_sha256")
        val chunk0Bytes = manifestInt("png_8x8_chunk0_bytes")
        val chunk1Bytes = manifestInt("png_8x8_chunk1_bytes")
        assertEquals(encoded.size, chunk0Bytes + chunk1Bytes)
        assertEquals(expectedHash, ImageCodec.hashBytes(encoded))

        val begin = decodeValidFixture("clip_payload_begin").body as ClipPayloadBeginBody
        assertEquals(expectedHash, begin.contentHash)
        assertEquals(encoded.size.toLong(), begin.encodedBytes)
        assertEquals(2, begin.chunkCount)
        assertEquals(MediaLimits.MIME_PNG, begin.mimeType)

        val chunk0 = decodeValidFixture("clip_payload_chunk").body as ClipPayloadChunkBody
        assertEquals(begin.transferId, chunk0.transferId)
        assertEquals(0, chunk0.chunkIndex)
        assertEquals(chunk0Bytes, chunk0.chunkBytes)
        assertEquals(ImageChunks.encodeBase64Url(encoded.copyOfRange(0, chunk0Bytes)), chunk0.data)

        // Reassemble the fixture-declared split: fixture chunk 0 + locally derived chunk 1.
        val first = ImageChunks.tryDecodeChunk(chunk0.data, chunk0Bytes)
        assertNotNull(first)
        assertArrayEquals(encoded, first!! + encoded.copyOfRange(chunk0Bytes, encoded.size))

        val end = decodeValidFixture("clip_payload_end").body as ClipPayloadEndBody
        assertEquals(begin.transferId, end.transferId)
        assertEquals(expectedHash, end.contentHash)
    }

    @Test
    fun `the v1 parser rejects every v2 image frame so text-only peers stay clean`() {
        listOf("clip_payload_begin", "clip_payload_chunk", "clip_payload_end").forEach { name ->
            val frame = File(fixturesRoot, "valid/$name.json").readText()
            val outcome = runCatching { ProtocolJson.parseEnvelope(frame, ProtocolJson.PROTOCOL_V1) }
            assertTrue("v1 parser must reject $name", outcome.isFailure)
        }
    }

    private fun decodeValidFixture(name: String) =
        SyncWire.decode(File(fixturesRoot, "valid/$name.json").readText(), ProtocolJson.PROTOCOL_V2)

    private fun manifestInt(key: String): Int = requireNotNull(manifest[key]).jsonPrimitive.int

    private fun manifestString(key: String): String = requireNotNull(manifest[key]).jsonPrimitive.content
}

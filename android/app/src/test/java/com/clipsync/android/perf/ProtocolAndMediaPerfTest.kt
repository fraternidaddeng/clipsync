package com.clipsync.android.perf

import com.clipsync.android.media.ImageChunks
import com.clipsync.android.media.ImageCodec
import com.clipsync.android.media.MediaBlobStore
import com.clipsync.android.media.MediaLimits
import com.clipsync.android.protocol.ProtocolJson
import com.clipsync.android.protocol.ProtocolStrictJson
import com.clipsync.android.sync.ClipPayloadBody
import com.clipsync.android.sync.ClipPayloadChunkBody
import com.clipsync.android.sync.ClipPayloadItemDto
import com.clipsync.android.sync.SyncMessageTypes
import com.clipsync.android.sync.SyncWire
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Opt-in measurement (`CLIPSYNC_PERF=1 ./gradlew testDebugUnitTest --tests '*PerfTest'`):
 * pure-JVM timings of the frame codec and image path with `System.nanoTime()`, median of N.
 * JVM numbers are indicative for ART; the point is the before/after ratio on the same box.
 */
class ProtocolAndMediaPerfTest {
    @Before
    fun onlyWhenRequested() {
        assumeTrue("set CLIPSYNC_PERF=1 to run", System.getenv(PerfProbe.ENVIRONMENT_VARIABLE) == "1")
    }

    @Test
    fun chunkFrameRoundTrip() {
        val chunk = PerfProbe.noise(MediaLimits.MAX_CHUNK_BYTES, seed = 11)
        val data = ImageChunks.encodeBase64Url(chunk)
        val body = ClipPayloadChunkBody(
            transferId = TRANSFER_ID,
            eventId = EVENT_ID,
            chunkIndex = 3,
            chunkCount = 8,
            chunkBytes = chunk.size,
            data = data,
        )
        val frame = SyncWire.encode(SyncMessageTypes.CLIP_PAYLOAD_CHUNK, SyncWire.newRequestId(), body, ProtocolJson.PROTOCOL_V2)
        println("chunk frame ${frame.length} chars")

        PerfProbe.measure("encodeBase64Url 256 KiB", 20) { ImageChunks.encodeBase64Url(chunk) }
        PerfProbe.measure("tryDecodeChunk 256 KiB", 20) { ImageChunks.tryDecodeChunk(data, chunk.size) }
        PerfProbe.measure("SyncWire.encode chunk frame", 20) {
            SyncWire.encode(SyncMessageTypes.CLIP_PAYLOAD_CHUNK, SyncWire.newRequestId(), body, ProtocolJson.PROTOCOL_V2)
        }
        PerfProbe.measure("ProtocolStrictJson.scan chunk frame", 20) { ProtocolStrictJson.scan(frame) }
        PerfProbe.measure("ProtocolJson.parseEnvelope chunk frame", 20) { ProtocolJson.parseEnvelope(frame, ProtocolJson.PROTOCOL_V2) }
        PerfProbe.measure("SyncWire.decode chunk frame", 20) { SyncWire.decode(frame, ProtocolJson.PROTOCOL_V2) }
        PerfProbe.measure("decode + tryDecodeChunk (receive path per chunk)", 20) {
            val parsed = SyncWire.decode(frame, ProtocolJson.PROTOCOL_V2).body as ClipPayloadChunkBody
            checkNotNull(ImageChunks.tryDecodeChunk(parsed.data, parsed.chunkBytes))
        }
        val image = PerfProbe.noise(4 * 1024 * 1024, seed = 5)
        PerfProbe.measure("ImageChunks.split 4 MiB", 5) { ImageChunks.split(image) }
    }

    @Test
    fun textPayloadFrame() {
        val content = "字".repeat(100_000) + "a".repeat(300_000)
        val utf8 = content.toByteArray(Charsets.UTF_8)
        val body = ClipPayloadBody(
            clips = listOf(
                ClipPayloadItemDto(
                    eventId = EVENT_ID,
                    originDeviceId = TRANSFER_ID,
                    originSeq = 42,
                    kind = "text",
                    content = content,
                    contentHash = MessageDigest.getInstance("SHA-256").digest(utf8).joinToString("") { "%02x".format(it) },
                    utf8Bytes = utf8.size.toLong(),
                    createdAtMs = 1_700_000_000_000,
                ),
            ),
        )
        val frame = SyncWire.encode(SyncMessageTypes.CLIP_PAYLOAD, SyncWire.newRequestId(), body, ProtocolJson.PROTOCOL_V2)
        println("text payload frame ${frame.length} chars")

        PerfProbe.measure("SyncWire.encode 600 KiB text payload", 10) {
            SyncWire.encode(SyncMessageTypes.CLIP_PAYLOAD, SyncWire.newRequestId(), body, ProtocolJson.PROTOCOL_V2)
        }
        PerfProbe.measure("ProtocolStrictJson.scan 600 KiB text payload", 10) { ProtocolStrictJson.scan(frame) }
        PerfProbe.measure("SyncWire.decode 600 KiB text payload", 10) { SyncWire.decode(frame, ProtocolJson.PROTOCOL_V2) }
    }

    @Test
    fun imagePath() {
        val png = PerfProbe.noise(4 * 1024 * 1024, seed = 9).also { PerfProbe.stampPngHeader(it, 2048, 512) }
        val hash = ImageCodec.hashBytes(png)
        PerfProbe.measure("ImageCodec.hashBytes 4 MiB", 10) { ImageCodec.hashBytes(png) }
        PerfProbe.measure("ImageCodec.tryInspect 4 MiB", 10) { ImageCodec.tryInspect(png) }

        val root = Files.createTempDirectory("clipsync-perf").toFile()
        try {
            val store = MediaBlobStore(root)
            PerfProbe.measure("MediaBlobStore.commitBytes 4 MiB (fresh)", 5) {
                store.deleteBlob(hash)
                store.commitBytes(png, hash)
            }
            PerfProbe.measure("MediaBlobStore begin/append/commit 4 MiB (sync ingress)", 5) {
                store.deleteBlob(hash)
                val pending = store.beginWrite()
                var offset = 0
                while (offset < png.size) {
                    val length = minOf(MediaLimits.MAX_CHUNK_BYTES, png.size - offset)
                    store.append(pending, png.copyOfRange(offset, offset + length))
                    offset += length
                }
                store.commit(pending, hash, MediaLimits.MIME_PNG)
            }
            val path = store.requirePath(hash)
            PerfProbe.measure("ImageCodec.tryInspectFile 4 MiB", 10) { ImageCodec.tryInspectFile(path) }
        } finally {
            root.deleteRecursively()
        }
    }

    private companion object {
        const val TRANSFER_ID = "6f1d2c3b-4a5e-4f60-8b9c-0d1e2f3a4b5c"
        const val EVENT_ID = "7a2e3d4c-5b6f-4a71-9c0d-1e2f3a4b5c6d"
    }
}

internal object PerfProbe {
    const val ENVIRONMENT_VARIABLE = "CLIPSYNC_PERF"

    fun measure(label: String, iterations: Int, body: () -> Any?) {
        body()
        val samples = DoubleArray(iterations)
        for (index in 0 until iterations) {
            System.gc()
            val start = System.nanoTime()
            body()
            samples[index] = (System.nanoTime() - start) / 1_000_000.0
        }
        samples.sort()
        val median = if (iterations % 2 == 0) {
            (samples[iterations / 2 - 1] + samples[iterations / 2]) / 2
        } else {
            samples[iterations / 2]
        }
        println(
            String.format(
                java.util.Locale.ROOT,
                "%-56s median %9.3f ms  (min %9.3f / max %9.3f)",
                label,
                median,
                samples.first(),
                samples.last(),
            ),
        )
    }

    fun noise(length: Int, seed: Int): ByteArray {
        val bytes = ByteArray(length)
        var state = seed.toUInt()
        for (index in bytes.indices) {
            state = state * 1664525u + 1013904223u
            bytes[index] = (state shr 24).toByte()
        }
        return bytes
    }

    /** Makes a noise buffer pass the PNG header gate (magic + IHDR) so inspect/commit exercise the hash path. */
    fun stampPngHeader(bytes: ByteArray, width: Int, height: Int) {
        val magic = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        magic.copyInto(bytes)
        writeInt32Be(bytes, 8, 13)
        "IHDR".toByteArray(Charsets.US_ASCII).copyInto(bytes, 12)
        writeInt32Be(bytes, 16, width)
        writeInt32Be(bytes, 20, height)
    }

    private fun writeInt32Be(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 24).toByte()
        bytes[offset + 1] = (value ushr 16).toByte()
        bytes[offset + 2] = (value ushr 8).toByte()
        bytes[offset + 3] = value.toByte()
    }
}

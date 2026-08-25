package com.clipsync.android.sync

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/**
 * Blocking frame I/O for a bt1 stream (docs/protocol-bt1.md section 2): every byte belongs
 * to UINT32_BE(payload_length) || payload. Pure java.io so unit tests drive it over
 * in-memory pipes; the RFCOMM connector supplies socket streams and owns timeouts by
 * closing the socket, which surfaces here as [IOException]. Declared-length caps are
 * enforced before any payload allocation.
 */
object Bt1StreamFraming {
    /** Writes one plaintext handshake frame around the UTF-8 encoding of [payloadText]. */
    fun writeHandshakeFrame(
        output: OutputStream,
        payloadText: String,
    ) {
        val payload = payloadText.toByteArray(StandardCharsets.UTF_8)
        require(Bt1Frames.isAcceptableHandshakePayloadLength(payload.size.toLong())) {
            "Handshake payloads must be 2 to 4096 bytes."
        }
        val frame =
            ByteBuffer
                .allocate(Bt1Frames.LENGTH_PREFIX_LENGTH + payload.size)
                .putInt(payload.size)
                .put(payload)
                .array()
        output.write(frame)
        output.flush()
    }

    /** Writes one already-encrypted complete frame (length prefix included). */
    fun writeEncryptedFrame(
        output: OutputStream,
        frame: ByteArray,
    ) {
        output.write(frame)
        output.flush()
    }

    /**
     * Reads one plaintext handshake payload. Throws [Bt1HandshakeException] with
     * BT1_SCHEMA_VIOLATION for an empty/short declared length and BT1_FRAME_TOO_LARGE above
     * the 4096-byte cap; [IOException] when the peer closed or the stream broke.
     */
    fun readHandshakePayload(input: InputStream): String {
        val declared = readDeclaredLength(input) ?: throw EOFException("bt1 peer closed during the handshake")
        val violation =
            when {
                declared > Bt1Frames.MAX_HANDSHAKE_PAYLOAD_LENGTH ->
                    Bt1HandshakeException(Bt1ErrorCodes.FRAME_TOO_LARGE, "handshake frame length exceeds 4096 bytes")
                !Bt1Frames.isAcceptableHandshakePayloadLength(declared) ->
                    Bt1HandshakeException(Bt1ErrorCodes.SCHEMA_VIOLATION, "handshake frame length is invalid")
                else -> null
            }
        if (violation != null) {
            throw violation
        }
        return String(readFully(input, declared.toInt()), StandardCharsets.UTF_8)
    }

    /**
     * Reads one encrypted frame payload (the bytes after the length prefix). Returns null on
     * a clean close at a frame boundary; throws [IOException] on a declared length outside
     * the post-handshake window (the connection must close without attempting decryption)
     * or on a truncated frame.
     */
    fun readEncryptedPayload(input: InputStream): ByteArray? {
        val declared = readDeclaredLength(input) ?: return null
        if (!Bt1Frames.isAcceptableEncryptedPayloadLength(declared)) {
            throw IOException("bt1 encrypted frame length is outside the accepted window")
        }
        return readFully(input, declared.toInt())
    }

    /** Null on EOF before the first prefix byte; throws when the prefix itself is cut off. */
    private fun readDeclaredLength(input: InputStream): Long? {
        val prefix = ByteArray(Bt1Frames.LENGTH_PREFIX_LENGTH)
        val first = input.read()
        if (first < 0) {
            return null
        }
        prefix[0] = first.toByte()
        var offset = 1
        while (offset < prefix.size) {
            val read = input.read(prefix, offset, prefix.size - offset)
            if (read < 0) {
                throw EOFException("bt1 stream ended inside a frame length prefix")
            }
            offset += read
        }
        return Bt1Frames.readDeclaredPayloadLength(prefix)
    }

    private fun readFully(
        input: InputStream,
        length: Int,
    ): ByteArray {
        val payload = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(payload, offset, length - offset)
            if (read < 0) {
                throw EOFException("bt1 stream ended inside a frame payload")
            }
            offset += read
        }
        return payload
    }
}

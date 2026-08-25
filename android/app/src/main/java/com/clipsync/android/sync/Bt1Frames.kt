package com.clipsync.android.sync

import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * bt1 frame-layer constants and length-prefix rules from docs/protocol-bt1.md sections 2
 * and 5. Every byte on the stream belongs to a frame: UINT32_BE(payload_length) || payload.
 * Must stay byte-identical with the Windows Bt1Frames over the shared vectors in
 * protocol/bt1/fixtures/frames/vectors.json.
 */
object Bt1Frames {
    const val LENGTH_PREFIX_LENGTH = 4
    const val NONCE_LENGTH = 12
    const val TAG_LENGTH = 16
    const val TAG_LENGTH_BITS = TAG_LENGTH * 8

    /** 7 MiB, matching the protocol v1 WebSocket text-message limit. */
    const val MAX_PLAINTEXT_LENGTH = 7 * 1024 * 1024

    /** Zero-length plaintext is invalid, so the smallest payload is 1 + tag. */
    const val MIN_ENCRYPTED_PAYLOAD_LENGTH = 1 + TAG_LENGTH
    const val MAX_ENCRYPTED_PAYLOAD_LENGTH = MAX_PLAINTEXT_LENGTH + TAG_LENGTH

    /** Plaintext handshake JSON payloads are 2..4096 bytes. */
    const val MIN_HANDSHAKE_PAYLOAD_LENGTH = 2
    const val MAX_HANDSHAKE_PAYLOAD_LENGTH = 4096

    private const val UINT32_MASK = 0xFFFF_FFFFL

    /** Reads the declared payload length from a 4-byte big-endian prefix. */
    fun readDeclaredPayloadLength(lengthPrefix: ByteArray): Long {
        require(lengthPrefix.size >= LENGTH_PREFIX_LENGTH) { "The length prefix must be 4 bytes." }
        return ByteBuffer.wrap(lengthPrefix, 0, LENGTH_PREFIX_LENGTH).int.toLong() and UINT32_MASK
    }

    fun isAcceptableHandshakePayloadLength(declaredLength: Long): Boolean =
        declaredLength in MIN_HANDSHAKE_PAYLOAD_LENGTH.toLong()..MAX_HANDSHAKE_PAYLOAD_LENGTH.toLong()

    fun isAcceptableEncryptedPayloadLength(declaredLength: Long): Boolean =
        declaredLength in MIN_ENCRYPTED_PAYLOAD_LENGTH.toLong()..MAX_ENCRYPTED_PAYLOAD_LENGTH.toLong()

    /** 12-byte GCM nonce: 4 zero bytes followed by the big-endian direction counter. */
    internal fun counterNonce(sequence: ULong): ByteArray =
        ByteBuffer
            .allocate(NONCE_LENGTH)
            .putInt(0)
            .putLong(sequence.toLong())
            .array()
}

/**
 * Sending half of the bt1 frame layer (docs/protocol-bt1.md section 5): AES-256-GCM with a
 * 12-byte counter nonce starting at 0 and advancing by exactly 1 per frame for this
 * direction. Not thread-safe; one instance per direction per connection.
 */
class Bt1FrameEncryptor(
    key: ByteArray,
    startSequence: ULong = 0uL,
) {
    private val keySpec: SecretKeySpec
    private var sequence: ULong = startSequence
    private var exhausted = false

    init {
        require(key.size == Bt1KeySchedule.KEY_LENGTH) { "The direction key must be exactly 32 bytes." }
        keySpec = SecretKeySpec(key, "AES")
    }

    /** Encrypts one plaintext into a complete frame including the 4-byte length prefix. */
    fun encryptFrame(plaintext: ByteArray): ByteArray {
        require(plaintext.size in 1..Bt1Frames.MAX_PLAINTEXT_LENGTH) {
            "Frame plaintext must be 1 byte to 7 MiB."
        }
        check(!exhausted) { "The bt1 send counter is exhausted; the session must close." }

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            keySpec,
            GCMParameterSpec(Bt1Frames.TAG_LENGTH_BITS, Bt1Frames.counterNonce(sequence)),
        )
        val ciphertext = cipher.doFinal(plaintext)

        if (sequence == ULong.MAX_VALUE) {
            exhausted = true
        } else {
            sequence++
        }

        return ByteBuffer
            .allocate(Bt1Frames.LENGTH_PREFIX_LENGTH + ciphertext.size)
            .putInt(ciphertext.size)
            .put(ciphertext)
            .array()
    }
}

/**
 * Receiving half of the bt1 frame layer. The sequence is never transmitted: the receiver
 * decrypts with its own expected counter, so a replayed, reordered, dropped, truncated, or
 * tampered frame fails tag verification. Any failure is fatal and permanently poisons this
 * instance — the caller must close the connection. Not thread-safe; one instance per
 * direction per connection.
 */
class Bt1FrameDecryptor(
    key: ByteArray,
    startSequence: ULong = 0uL,
) {
    private val keySpec: SecretKeySpec
    private var sequence: ULong = startSequence
    private var exhausted = false

    /** True once any payload failed; the connection must be closed. */
    var hasFailed: Boolean = false
        private set

    init {
        require(key.size == Bt1KeySchedule.KEY_LENGTH) { "The direction key must be exactly 32 bytes." }
        keySpec = SecretKeySpec(key, "AES")
    }

    /**
     * Decrypts one frame payload (the bytes after the length prefix). Returns null — and
     * permanently fails this decryptor — on any length violation or tag mismatch.
     */
    fun tryDecryptPayload(payload: ByteArray): ByteArray? {
        val acceptable =
            !hasFailed &&
                !exhausted &&
                Bt1Frames.isAcceptableEncryptedPayloadLength(payload.size.toLong())
        val plaintext =
            if (acceptable) {
                runCatching {
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(
                        Cipher.DECRYPT_MODE,
                        keySpec,
                        GCMParameterSpec(Bt1Frames.TAG_LENGTH_BITS, Bt1Frames.counterNonce(sequence)),
                    )
                    cipher.doFinal(payload)
                }.getOrNull()
            } else {
                null
            }

        if (plaintext == null) {
            hasFailed = true
        } else if (sequence == ULong.MAX_VALUE) {
            exhausted = true
        } else {
            sequence++
        }
        return plaintext
    }
}

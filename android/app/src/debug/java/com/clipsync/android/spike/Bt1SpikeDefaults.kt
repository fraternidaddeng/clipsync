package com.clipsync.android.spike

import java.security.MessageDigest
import java.util.UUID

/**
 * PHASE 0 SPIKE ONLY (docs/bluetooth-phase0-spike.md): fixed spike parameters shared with
 * the Windows spike listener (scripts/spike-bt1-windows/SpikeOptions.cs). Both sides
 * default to the same values so the handshake works with zero typing on the phone.
 */
object Bt1SpikeDefaults {
    /** Logcat tag; capture with `adb logcat -s ClipSyncSpike`. */
    const val LOG_TAG = "ClipSyncSpike"

    /**
     * The frozen ClipSync SDP service UUID. Must stay byte-identical to
     * windows/ClipSync.Peer.Bluetooth/RfcommContract.cs (RfcommContract.ServiceUuid).
     */
    val SERVICE_UUID: UUID = UUID.fromString("5f7f1d9c-2d6b-4e8d-9f1b-ef9ed49b0bec")

    /**
     * Spike-only shared secret (32 bytes as 64 hex chars). PUBLIC BY DESIGN: it proves
     * interoperability of the bt1 math, not confidentiality. Never reuse it as, or derive
     * it from, a real ClipSync pair secret.
     */
    const val DEFAULT_SECRET_HEX =
        "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"

    /** Fixed spike device identity of this Android (client) side. */
    const val CLIENT_DEVICE_ID = "11111111-1111-4111-8111-111111111111"

    /** Fixed spike device identity of the Windows (listener) side. */
    const val LISTENER_DEVICE_ID = "22222222-2222-4222-8222-222222222222"

    const val TRUST_EPOCH = 1L

    /** Decodes 64 hex chars into 32 bytes, or null when malformed. */
    fun decodeSecretHex(hex: String): ByteArray? {
        val trimmed = hex.trim()
        if (trimmed.length != 64 || !trimmed.all { it in "0123456789abcdefABCDEF" }) {
            return null
        }
        return ByteArray(32) { index ->
            trimmed.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    /**
     * First 4 bytes of SHA-256(secret) as hex: both spikes print it so a typed-secret
     * mismatch is diagnosable without ever logging the secret itself.
     */
    fun secretFingerprint(secret: ByteArray): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(secret)
            .take(4)
            .joinToString("") { "%02x".format(it) }
}

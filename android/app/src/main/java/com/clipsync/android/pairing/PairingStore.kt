package com.clipsync.android.pairing

import java.util.UUID

/** Protects the 32-byte pair secret at rest. The Android implementation uses Keystore AES-GCM. */
interface SecretProtector {
    fun protect(plain: ByteArray): ByteArray

    fun unprotect(protected: ByteArray): ByteArray
}

/** Minimal atomic key-value persistence; the Android implementation wraps SharedPreferences. */
interface KeyValueStore {
    fun read(key: String): String?

    /** Applies every entry in one commit; a null value removes the key. */
    fun write(values: Map<String, String?>)
}

/**
 * Neighbour-hue slot arithmetic shared with Windows `DeviceAccent` (charter §3.4):
 * slots follow pairing order and wrap past five; the order is only the default —
 * a manual per-device override (P1#14) may replace it.
 */
object DeviceAccents {
    const val SLOTS = 5

    /** Maps a zero-based pairing position to the default slot 1..5, cycling. */
    fun defaultSlot(pairingPosition: Int): Int = (pairingPosition % SLOTS) + 1

    fun isValidSlot(slot: Int): Boolean = slot in 1..SLOTS
}

/** The Windows peer this phone trusts, as saved after an approved confirm exchange. */
data class PairedPeer(
    val deviceId: String,
    val displayName: String,
    val platform: String,
    val certSha256: String,
    val trustEpoch: Long,
    val hosts: List<String>,
    val port: Int,
    val pairedAtMs: Long,
)

/**
 * Persists the local device identity and the single paired Windows peer. The pair secret is
 * stored only in protected form; certificate trust is exactly the pinned fingerprint, so a
 * changed peer certificate can never be accepted silently (a new pairing must replace it).
 */
class PairingStore(
    private val keyValues: KeyValueStore,
    private val protector: SecretProtector,
) {
    fun localDeviceId(): String {
        keyValues.read(KEY_LOCAL_DEVICE_ID)?.let { existing ->
            if (PairingJson.isCanonicalUuid(existing)) {
                return existing
            }
        }
        val created = UUID.randomUUID().toString()
        keyValues.write(mapOf(KEY_LOCAL_DEVICE_ID to created))
        return created
    }

    fun localDisplayName(fallback: String): String =
        keyValues.read(KEY_LOCAL_DISPLAY_NAME)?.takeIf { it.isNotBlank() }
            ?: fallback.trim().take(64).ifBlank { "Android phone" }

    fun setLocalDisplayName(name: String) {
        val trimmed = name.trim().take(64)
        if (trimmed.isNotEmpty()) {
            keyValues.write(mapOf(KEY_LOCAL_DISPLAY_NAME to trimmed))
        }
    }

    /**
     * Every trusted peer in pairing order — the order that assigns each device
     * its neighbour hue (charter §3.4: slot N takes dev-N, never a hash).
     * Stage 4 pairs a single Windows peer, so the list holds at most one; the
     * ordering contract is what multi-device stages must preserve.
     */
    fun pairedPeers(): List<PairedPeer> = listOfNotNull(peer())

    fun peer(): PairedPeer? {
        val deviceId = keyValues.read(KEY_PEER_DEVICE_ID) ?: return null
        val hosts = keyValues.read(KEY_PEER_HOSTS)?.split('\n')?.filter { it.isNotEmpty() } ?: return null
        return PairedPeer(
            deviceId = deviceId,
            displayName = keyValues.read(KEY_PEER_DISPLAY_NAME) ?: return null,
            platform = keyValues.read(KEY_PEER_PLATFORM) ?: return null,
            certSha256 = keyValues.read(KEY_PEER_CERT) ?: return null,
            trustEpoch = keyValues.read(KEY_PEER_EPOCH)?.toLongOrNull() ?: return null,
            hosts = hosts,
            port = keyValues.read(KEY_PEER_PORT)?.toIntOrNull() ?: return null,
            pairedAtMs = keyValues.read(KEY_PEER_PAIRED_AT)?.toLongOrNull() ?: return null,
        )
    }

    /**
     * Saves an approved pairing in one commit and zeroes the plaintext secret. The pinned
     * fingerprint comes from the QR payload the user just verified, not from the network.
     */
    fun savePeer(
        qr: PairingQrPayload,
        response: PairingConfirmResponse,
        pairSecret: ByteArray,
        nowMs: Long,
    ) {
        require(pairSecret.size == 32) { "pair secret must be 32 bytes" }
        val protected = protector.protect(pairSecret)
        pairSecret.fill(0)
        keyValues.write(
            mapOf(
                KEY_PEER_DEVICE_ID to response.deviceId,
                KEY_PEER_DISPLAY_NAME to response.displayName,
                KEY_PEER_PLATFORM to response.platform,
                KEY_PEER_CERT to qr.certSha256,
                KEY_PEER_EPOCH to response.trustEpoch.toString(),
                KEY_PEER_HOSTS to qr.hosts.joinToString(separator = "\n"),
                KEY_PEER_PORT to qr.port.toString(),
                KEY_PEER_PAIRED_AT to nowMs.toString(),
                KEY_PEER_SECRET to encodeBase64(protected),
            ),
        )
    }

    /** The raw 32-byte pair secret, or null when unpaired or the protector cannot recover it. */
    fun pairSecret(): ByteArray? {
        val stored = keyValues.read(KEY_PEER_SECRET) ?: return null
        return runCatching { protector.unprotect(decodeBase64(stored)) }.getOrNull()
    }

    /**
     * True when the presented certificate fingerprint matches the pinned one. A `false` from
     * here must block the connection and tell the user; only a fresh QR pairing may replace
     * the pin.
     */
    fun matchesPinnedCertificate(presentedSha256: String): Boolean {
        val pinned = peer()?.certSha256 ?: return false
        return pinned.equals(presentedSha256, ignoreCase = true)
    }

    /**
     * Manual neighbour-hue override for one device row (P1#14), or null when the
     * device follows its pairing-order default. Keyed by device id so re-pairing
     * the same machine keeps the chosen colour; an out-of-range stored value
     * reads as null rather than surfacing a broken slot.
     */
    fun deviceAccent(deviceId: String): Int? =
        keyValues
            .read(accentKey(deviceId))
            ?.toIntOrNull()
            ?.takeIf(DeviceAccents::isValidSlot)

    /** Persists a manual device colour; null returns the row to its pairing-order default. */
    fun setDeviceAccent(
        deviceId: String,
        slot: Int?,
    ) {
        require(slot == null || DeviceAccents.isValidSlot(slot)) {
            "accent slot must be 1..${DeviceAccents.SLOTS}"
        }
        keyValues.write(mapOf(accentKey(deviceId) to slot?.toString()))
    }

    /**
     * Removes the pairing and its protected secret; local history is untouched.
     * Manual device colours survive on purpose: they are display facts about a
     * device identity, and re-pairing the same machine should look the same.
     */
    fun forgetPeer() {
        keyValues.write(
            mapOf(
                KEY_PEER_DEVICE_ID to null,
                KEY_PEER_DISPLAY_NAME to null,
                KEY_PEER_PLATFORM to null,
                KEY_PEER_CERT to null,
                KEY_PEER_EPOCH to null,
                KEY_PEER_HOSTS to null,
                KEY_PEER_PORT to null,
                KEY_PEER_PAIRED_AT to null,
                KEY_PEER_SECRET to null,
            ),
        )
    }

    private fun encodeBase64(bytes: ByteArray): String =
        java.util.Base64
            .getEncoder()
            .encodeToString(bytes)

    private fun decodeBase64(value: String): ByteArray =
        java.util.Base64
            .getDecoder()
            .decode(value)

    private fun accentKey(deviceId: String): String = KEY_DEVICE_ACCENT_PREFIX + deviceId

    private companion object {
        const val KEY_LOCAL_DEVICE_ID = "local.device_id"
        const val KEY_LOCAL_DISPLAY_NAME = "local.display_name"
        const val KEY_PEER_DEVICE_ID = "peer.device_id"
        const val KEY_PEER_DISPLAY_NAME = "peer.display_name"
        const val KEY_PEER_PLATFORM = "peer.platform"
        const val KEY_PEER_CERT = "peer.cert_sha256"
        const val KEY_PEER_EPOCH = "peer.trust_epoch"
        const val KEY_PEER_HOSTS = "peer.hosts"
        const val KEY_PEER_PORT = "peer.port"
        const val KEY_PEER_PAIRED_AT = "peer.paired_at_ms"
        const val KEY_PEER_SECRET = "peer.secret_protected"
        const val KEY_DEVICE_ACCENT_PREFIX = "device.accent."
    }
}

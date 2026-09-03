package com.clipsync.android.pairing

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The UDP announcement the Windows listener broadcasts on the LAN: identity only (device id,
 * certificate fingerprint, TCP port), never a name or any content. Receiving one that matches
 * the scanned QR proves the PC is on and shares the phone's broadcast domain.
 */
@Serializable
data class DiscoveryBeacon(
    @SerialName("v") val version: Int,
    @SerialName("kind") val kind: String,
    @SerialName("device_id") val deviceId: String,
    @SerialName("port") val port: Int,
    @SerialName("cert_sha256") val certSha256: String,
)

/** The identity a beacon must carry to count as the PC in the QR code that is being paired. */
data class BeaconExpectation(
    val deviceId: String,
    val certSha256: String,
)

/**
 * What the pairing screen knows about the PC's LAN beacon while a QR code is in hand. Only
 * [Seen] is evidence; the others describe the listener, never the PC (a phone that hears
 * nothing may simply be on a guest network or a ROM that drops broadcasts).
 */
sealed interface BeaconStatus {
    /** Not listening: no QR in hand, or the pairing page is not on screen. */
    data object Off : BeaconStatus

    /** The UDP socket is bound and nothing matching has arrived yet. */
    data object Listening : BeaconStatus

    /** The socket could not be bound (port taken, no Wi-Fi manager…); stated, never guessed around. */
    data object Unavailable : BeaconStatus

    /** A matching beacon arrived from [sourceHost], the PC's address as it actually reaches this phone. */
    data class Seen(
        val sourceHost: String,
        val port: Int,
    ) : BeaconStatus
}

/**
 * Where the ViewModel turns beacon listening on and off; implemented on Android with a UDP
 * socket and a multicast lock, faked in unit tests. [onStatus] may be called from any thread.
 */
interface BeaconListener {
    fun start(
        expectation: BeaconExpectation,
        onStatus: (BeaconStatus) -> Unit,
    )

    fun stop()
}

object DiscoveryBeacons {
    const val UDP_PORT = 47653
    const val KIND = "clipsync_discovery"

    /** Beacons are tiny; anything longer is not one. */
    const val MAX_DATAGRAM_BYTES = 2048

    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = false
            coerceInputValues = false
        }

    /** Null for anything that is not a well-formed discovery beacon; never throws. */
    fun parse(text: String): DiscoveryBeacon? =
        runCatching { json.decodeFromString(DiscoveryBeacon.serializer(), text) }
            .getOrNull()
            ?.takeIf { it.kind == KIND && it.port in 1..MAX_PORT }

    /**
     * Same device id (exact, canonical UUIDs) and same fingerprint (case-insensitive, like the
     * pin comparison) — anything less is some other PC and says nothing about this pairing.
     */
    fun matches(
        beacon: DiscoveryBeacon,
        expectation: BeaconExpectation,
    ): Boolean =
        beacon.kind == KIND &&
            beacon.deviceId == expectation.deviceId &&
            beacon.certSha256.equals(expectation.certSha256, ignoreCase = true)

    private const val MAX_PORT = 65535
}

/**
 * The QR hosts reordered by what the beacon proved: the address the PC actually broadcast
 * from goes first. When it is not in the QR list at all (another adapter), it is prepended
 * as an extra candidate — the certificate pin keeps a wrong address from learning anything,
 * and the one-time token keeps it from pairing twice. Without a sighting the list is untouched.
 */
fun hostsPreferringBeacon(
    hosts: List<String>,
    beacon: BeaconStatus,
): List<String> {
    val source = (beacon as? BeaconStatus.Seen)?.sourceHost ?: return hosts
    return listOf(source) + hosts.filterNot { it == source }
}

package com.clipsync.android.pairing

/** The phone's current IPv4 address with its prefix length, as read from the active network. */
data class LocalIpv4(
    val address: String,
    val prefixLength: Int,
)

/** Where the ViewModel asks for the phone's IPv4; null means "unknown" and is never guessed. */
fun interface LocalNetworkSource {
    fun currentIpv4(): LocalIpv4?
}

private const val IPV4_BITS = 32
private const val IPV4_MASK = 0xFFFF_FFFFL
private const val OCTET_COUNT = 4
private const val OCTET_BITS = 8
private const val OCTET_MAX = 255
private const val OCTET_MAX_DIGITS = 3

/**
 * True when at least one of [hosts] is an IPv4 literal inside the same /[prefixLength]
 * network as [localAddress]. Hostnames, IPv6 literals and malformed input never match.
 */
fun anyHostOnSameSubnet(
    hosts: List<String>,
    localAddress: String,
    prefixLength: Int,
): Boolean {
    val local = parseIpv4(localAddress)
    if (local == null || prefixLength !in 0..IPV4_BITS) {
        return false
    }
    val mask = networkMask(prefixLength)
    return hosts.any { host -> parseIpv4(host)?.let { (it and mask) == (local and mask) } ?: false }
}

private fun networkMask(prefixLength: Int): Long =
    if (prefixLength == 0) {
        0L
    } else {
        (IPV4_MASK shl (IPV4_BITS - prefixLength)) and IPV4_MASK
    }

/** Dotted-quad IPv4 as an unsigned 32-bit value in a Long; null for anything else. */
internal fun parseIpv4(text: String): Long? {
    val parts = text.trim().split('.')
    val octets = parts.mapNotNull(::parseOctet)
    if (parts.size != OCTET_COUNT || octets.size != OCTET_COUNT) {
        return null
    }
    return octets.fold(0L) { acc, octet -> (acc shl OCTET_BITS) or octet.toLong() }
}

private fun parseOctet(part: String): Int? =
    part
        .takeIf { it.length in 1..OCTET_MAX_DIGITS && it.all(Char::isDigit) }
        ?.toInt()
        ?.takeIf { it <= OCTET_MAX }

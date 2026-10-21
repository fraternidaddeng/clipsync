package com.clipsync.android.protocol

import okhttp3.HttpUrl
import java.net.IDN

/** Host-only peer addresses, validated without DNS or interpreting any URL authority syntax. */
internal object PeerEndpoint {
    private const val MAX_HOST_LENGTH = 253
    private const val IPV4_OCTET_COUNT = 4
    private const val MAX_IPV4_OCTET = 255
    private val DNS_LABEL = Regex("^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$")

    fun isValidHost(host: String): Boolean = canonicalHostOrNull(host) != null

    /** OkHttp supplies the brackets for IPv6; [path] is a fixed, application-owned path. */
    fun httpsUrl(
        host: String,
        port: Int,
        path: String,
    ): HttpUrl {
        val canonicalHost = requireNotNull(canonicalHostOrNull(host)) { "peer host is invalid" }
        return HttpUrl
            .Builder()
            .scheme("https")
            .host(canonicalHost)
            .port(port)
            .addPathSegments(path)
            .build()
    }

    private fun canonicalHostOrNull(host: String): String? {
        if (host.length !in 1..MAX_HOST_LENGTH || host.any(::isForbiddenHostCharacter)) {
            return null
        }
        return if (':' in host) canonicalIpv6OrNull(host) else canonicalDnsHostOrNull(host)
    }

    private fun isForbiddenHostCharacter(character: Char): Boolean =
        character.isWhitespace() ||
            character.isISOControl() ||
            character.category == CharCategory.FORMAT ||
            character in "/\\?#@%"

    private fun canonicalIpv6OrNull(host: String): String? {
        // Accept a bare literal or one matching bracket pair, but never a zone ID or port.
        val literal = if (host.startsWith('[') && host.endsWith(']')) host.substring(1, host.lastIndex) else host
        if (literal.any { it !in "0123456789abcdefABCDEF:." }) {
            return null
        }
        return try {
            HttpUrl
                .Builder()
                .scheme("https")
                .host(literal)
                .build()
                .host
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun canonicalDnsHostOrNull(host: String): String? {
        val ascii =
            try {
                IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).lowercase()
            } catch (_: IllegalArgumentException) {
                return null
            }
        val labels = ascii.removeSuffix(".").split('.')
        // InetAddress accepts legacy abbreviated/decimal IPv4 spellings. Only dotted quads
        // are peer IPv4 addresses, so validation and the eventual socket agree on the target.
        val invalidNumericAddress = ascii.all { it in '0'..'9' || it == '.' } && !isDottedQuad(ascii, labels)
        return if (
            ascii.length !in 1..MAX_HOST_LENGTH ||
            labels.any { !DNS_LABEL.matches(it) } ||
            invalidNumericAddress
        ) {
            null
        } else {
            ascii
        }
    }

    private fun isDottedQuad(
        host: String,
        labels: List<String>,
    ): Boolean =
        labels.size == IPV4_OCTET_COUNT &&
            !host.endsWith('.') &&
            labels.all {
                (it.length == 1 || !it.startsWith('0')) &&
                    it.toIntOrNull()?.let { octet -> octet in 0..MAX_IPV4_OCTET } == true
            }
}

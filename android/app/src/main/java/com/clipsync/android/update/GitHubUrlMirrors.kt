package com.clipsync.android.update

import java.net.URI

/**
 * GitHub first, then China-reachable URL-prefix proxies. Only
 * `github.com` / `api.github.com` / `*.githubusercontent.com` are rewritten;
 * injected test hosts stay as-is so scripted clients keep a single GET.
 *
 * A proxy can see that this device asked for a ClipSync release (IP and
 * User-Agent). SHA-256 of the payload is still checked against the release
 * metadata before anything is installed.
 */
object GitHubUrlMirrors {
    val prefixes: List<String> =
        listOf(
            "https://ghproxy.net/",
            "https://ghfast.top/",
            "https://mirror.ghproxy.com/",
        )

    fun candidates(url: String): List<String> {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) {
            return emptyList()
        }
        val skipMirrors = prefixes.any { trimmed.startsWith(it) } || !isMirrorable(trimmed)
        return if (skipMirrors) {
            listOf(trimmed)
        } else {
            listOf(trimmed) + prefixes.map { prefix -> prefix + trimmed }
        }
    }

    fun isMirrorable(url: String): Boolean {
        val host =
            runCatching { URI(url).host?.lowercase() }.getOrNull()
                ?: return false
        return host == "github.com" ||
            host == "api.github.com" ||
            host.endsWith(".githubusercontent.com")
    }
}

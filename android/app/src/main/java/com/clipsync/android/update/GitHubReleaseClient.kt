package com.clipsync.android.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

/**
 * Fetches `/repos/{owner}/{repo}/releases/latest` and downloads a named asset.
 * Applying the APK is a host concern (PackageInstaller / FileProvider).
 */
class GitHubReleaseClient(
    private val currentVersion: String = "0.0.0",
    private val latestUrl: String = DEFAULT_LATEST_URL,
    private val http: OkHttpClient = defaultClient(),
    private val ioContext: CoroutineContext = Dispatchers.IO,
) {
    suspend fun fetchLatest(): GitHubLatestRelease =
        withContext(ioContext) {
            getText(latestUrl, acceptGithubJson = true) { body ->
                GitHubReleaseParser.parse(body)
            }
        }

    /**
     * Prefer the API `digest`; otherwise download the `.sha256` sidecar. Missing
     * both is a hard failure — the host must not install an unverified file.
     */
    suspend fun resolveSha256(
        release: GitHubLatestRelease,
        payload: ReleaseAsset,
    ): String =
        withContext(ioContext) {
            if (GitHubReleaseParser.isSha256Hex(payload.sha256Hex)) {
                return@withContext payload.sha256Hex!!
            }
            val sidecar =
                release.findSidecar(payload)
                    ?: throw IOException("Release ${release.tagName} has no SHA-256 for '${payload.name}'.")
            getText(sidecar.browserDownloadUrl, acceptGithubJson = false) { body ->
                GitHubReleaseParser.parseSha256Sidecar(body)
                    ?: throw IOException("Could not parse SHA-256 sidecar for '${payload.name}'.")
            }
        }

    suspend fun download(
        asset: ReleaseAsset,
        destination: File,
        onProgress: (received: Long, total: Long) -> Unit = { _, _ -> },
    ) = withContext(ioContext) {
        getSuccessful(asset.browserDownloadUrl, acceptGithubJson = false) { response ->
            val body = response.body ?: throw IOException("Download had no body.")
            val total = body.contentLength().takeIf { it > 0 } ?: asset.sizeBytes
            destination.parentFile?.mkdirs()
            if (destination.exists()) {
                destination.delete()
            }
            destination.outputStream().use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    var received = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) {
                            break
                        }
                        output.write(buffer, 0, read)
                        received += read
                        onProgress(received, total)
                    }
                }
            }
        }
    }

    private fun <T> getText(
        url: String,
        acceptGithubJson: Boolean,
        parse: (String) -> T,
    ): T =
        getSuccessful(url, acceptGithubJson) { response ->
            parse(response.body?.string().orEmpty())
        }

    private fun <T> getSuccessful(
        url: String,
        acceptGithubJson: Boolean,
        handle: (Response) -> T,
    ): T {
        var lastError: IOException? = null
        for (candidate in GitHubUrlMirrors.candidates(url)) {
            try {
                return executeCandidate(candidate, acceptGithubJson, handle)
            } catch (exception: IOException) {
                lastError = exception
            }
        }
        throw lastError ?: IOException("No URL candidates.")
    }

    private fun <T> executeCandidate(
        candidate: String,
        acceptGithubJson: Boolean,
        handle: (Response) -> T,
    ): T {
        val builder =
            Request
                .Builder()
                .url(candidate)
                .header("User-Agent", userAgent(currentVersion))
                .get()
        if (acceptGithubJson) {
            builder.header("Accept", "application/vnd.github+json")
        }
        return http.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body?.string().orEmpty()
                throw IOException(
                    "GitHub request returned ${response.code}: ${errorBody.trim().take(ERROR_BODY_CHARS)}",
                )
            }
            handle(response)
        }
    }

    companion object {
        const val DEFAULT_OWNER = "fraternidaddeng"
        const val DEFAULT_REPO = "clipsync"
        const val DEFAULT_LATEST_URL =
            "https://api.github.com/repos/$DEFAULT_OWNER/$DEFAULT_REPO/releases/latest"
        private const val COPY_BUFFER_BYTES = 81_920
        private const val ERROR_BODY_CHARS = 180
        private const val CONNECT_TIMEOUT_SECONDS = 12L
        private const val TRANSFER_TIMEOUT_MINUTES = 15L
        private const val BYTE_MASK = 0xFF
        private const val NIBBLE_MASK = 0x0F
        private const val NIBBLE_BITS = 4
        private val HEX_DIGITS = "0123456789abcdef".toCharArray()

        fun userAgent(version: String): String = "ClipSync/$version (+https://github.com/$DEFAULT_OWNER/$DEFAULT_REPO)"

        fun defaultClient(): OkHttpClient =
            OkHttpClient
                .Builder()
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(TRANSFER_TIMEOUT_MINUTES, TimeUnit.MINUTES)
                .writeTimeout(TRANSFER_TIMEOUT_MINUTES, TimeUnit.MINUTES)
                .build()

        fun sha256Hex(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) {
                        break
                    }
                    digest.update(buffer, 0, read)
                }
            }
            return toHexLower(digest.digest())
        }

        /**
         * Lowercase hex without going through `String.format("%02x", byte)`.
         * A signed Kotlin/Java `Byte` of `0x80` would otherwise become
         * `ffffff80` and every real APK hash would fail verification.
         */
        internal fun toHexLower(bytes: ByteArray): String {
            val hex = CharArray(bytes.size * 2)
            var index = 0
            for (byte in bytes) {
                val value = byte.toInt() and BYTE_MASK
                hex[index++] = HEX_DIGITS[value ushr NIBBLE_BITS]
                hex[index++] = HEX_DIGITS[value and NIBBLE_MASK]
            }
            return String(hex)
        }

        fun verifySha256(
            file: File,
            expectedHex: String,
        ) {
            val actual = sha256Hex(file)
            if (!actual.equals(expectedHex, ignoreCase = true)) {
                file.delete()
                throw IOException("SHA-256 mismatch (expected $expectedHex, got $actual).")
            }
        }
    }
}

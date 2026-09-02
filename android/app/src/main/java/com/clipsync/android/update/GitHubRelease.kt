package com.clipsync.android.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

enum class UpdatePlatform {
    WINDOWS,
    ANDROID,
}

data class ReleaseAsset(
    val name: String,
    val browserDownloadUrl: String,
    val sizeBytes: Long,
    val sha256Hex: String?,
)

data class GitHubLatestRelease(
    val tagName: String,
    val htmlUrl: String,
    val assets: List<ReleaseAsset>,
) {
    val versionLabel: String
        get() {
            val tag = tagName.trim()
            return if (tag.startsWith("v") || tag.startsWith("V")) tag.substring(1) else tag
        }

    fun findPayload(platform: UpdatePlatform): ReleaseAsset? {
        val expected =
            when (platform) {
                UpdatePlatform.WINDOWS -> "ClipSync-windows-x64.zip"
                UpdatePlatform.ANDROID -> "ClipSync-android.apk"
            }
        return assets.firstOrNull { it.name.equals(expected, ignoreCase = true) }
    }

    fun findSidecar(payload: ReleaseAsset): ReleaseAsset? =
        assets.firstOrNull { it.name.equals(payload.name + ".sha256", ignoreCase = true) }
}

object GitHubReleaseParser {
    private const val SHA256_HEX_LENGTH = 64
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(body: String): GitHubLatestRelease {
        val root = json.parseToJsonElement(body).jsonObject
        val tag = requiredString(root, "tag_name")
        val htmlUrl = optionalString(root, "html_url").orEmpty()
        val assets =
            root["assets"]
                ?.jsonArray
                ?.mapNotNull { element ->
                    val obj = element as? JsonObject ?: return@mapNotNull null
                    val name = optionalString(obj, "name") ?: return@mapNotNull null
                    val url = optionalString(obj, "browser_download_url") ?: return@mapNotNull null
                    if (name.isBlank() || url.isBlank()) {
                        return@mapNotNull null
                    }
                    val size =
                        obj["size"]
                            ?.jsonPrimitive
                            ?.longOrNull
                            ?: obj["size"]
                                ?.jsonPrimitive
                                ?.intOrNull
                                ?.toLong()
                            ?: 0L
                    ReleaseAsset(name, url, size.coerceAtLeast(0), readSha256(obj))
                }.orEmpty()
        return GitHubLatestRelease(tag, htmlUrl, assets)
    }

    fun parseSha256Sidecar(text: String): String? {
        val hex =
            text
                .trim()
                .split(Regex("\\s+"))
                .firstOrNull()
                ?.trimStart('*')
                ?: return null
        return if (isSha256Hex(hex)) hex.lowercase() else null
    }

    fun isSha256Hex(hex: String?): Boolean =
        hex != null &&
            hex.length == SHA256_HEX_LENGTH &&
            hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }

    private fun readSha256(asset: JsonObject): String? {
        val value = optionalString(asset, "digest") ?: return null
        val hex =
            if (value.startsWith("sha256:", ignoreCase = true)) {
                value.substring("sha256:".length).trim()
            } else {
                value.trim()
            }
        return if (isSha256Hex(hex)) hex.lowercase() else null
    }

    private fun requiredString(
        obj: JsonObject,
        name: String,
    ): String =
        optionalString(obj, name)?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("GitHub latest release is missing '$name'.")

    private fun optionalString(
        obj: JsonObject,
        name: String,
    ): String? = obj[name]?.jsonPrimitive?.contentOrNull
}

data class UpdateCheckResult(
    val currentVersion: String,
    val latest: GitHubLatestRelease,
    val payload: ReleaseAsset?,
    val updateAvailable: Boolean,
) {
    companion object {
        fun from(
            currentVersion: String,
            latest: GitHubLatestRelease,
            platform: UpdatePlatform,
        ): UpdateCheckResult {
            val payload = latest.findPayload(platform)
            val available = payload != null && AppVersion.compare(currentVersion, latest.versionLabel) < 0
            return UpdateCheckResult(currentVersion, latest, payload, available)
        }
    }
}

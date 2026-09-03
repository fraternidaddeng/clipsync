package com.clipsync.android.docs

import com.clipsync.android.update.GitHubReleaseClient

/** Where the user-facing documents live: the same repository the in-app updater trusts. */
const val DOCS_BASE_URL =
    "https://github.com/${GitHubReleaseClient.DEFAULT_OWNER}/${GitHubReleaseClient.DEFAULT_REPO}/blob/main/docs"

/**
 * The 使用前必读 (privacy and risks) page for [languageTag] (BCP-47): Chinese in any script or
 * region reads the zh-CN edition, Japanese the ja edition, everything else the English default.
 */
fun privacyDocUrl(languageTag: String): String {
    val suffix =
        when (languageTag.split('-', '_').first().lowercase()) {
            "zh" -> ".zh-CN.md"
            "ja" -> ".ja.md"
            else -> ".md"
        }
    return "$DOCS_BASE_URL/privacy-and-risks$suffix"
}

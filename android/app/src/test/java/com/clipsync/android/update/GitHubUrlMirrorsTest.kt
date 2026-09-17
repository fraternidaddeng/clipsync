package com.clipsync.android.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubUrlMirrorsTest {
    @Test
    fun githubApiUrlTriesOfficialThenEachPrefix() {
        val official = GitHubReleaseClient.DEFAULT_LATEST_URL
        val candidates = GitHubUrlMirrors.candidates(official)
        assertEquals(official, candidates.first())
        assertEquals(
            listOf(official) + GitHubUrlMirrors.prefixes.map { it + official },
            candidates,
        )
    }

    @Test
    fun releaseDownloadUrlIsMirrorable() {
        val url =
            "https://github.com/fraternidaddeng/clipsync/releases/download/v0.4.0/ClipSync-android.apk"
        assertTrue(GitHubUrlMirrors.isMirrorable(url))
        assertEquals(1 + GitHubUrlMirrors.prefixes.size, GitHubUrlMirrors.candidates(url).size)
    }

    @Test
    fun injectedTestHostsStaySingleCandidate() {
        assertEquals(
            listOf("https://example.test/latest"),
            GitHubUrlMirrors.candidates("https://example.test/latest"),
        )
        assertFalse(GitHubUrlMirrors.isMirrorable("https://example.test/latest"))
    }

    @Test
    fun alreadyPrefixedUrlIsNotWrappedAgain() {
        val wrapped = GitHubUrlMirrors.prefixes.first() + GitHubReleaseClient.DEFAULT_LATEST_URL
        assertEquals(listOf(wrapped), GitHubUrlMirrors.candidates(wrapped))
    }
}

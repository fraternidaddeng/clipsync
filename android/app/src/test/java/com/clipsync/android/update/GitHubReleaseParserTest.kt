package com.clipsync.android.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubReleaseParserTest {
    private val latestJson =
        """
        {
          "tag_name": "v0.2.0",
          "html_url": "https://github.com/fraternidaddeng/clipsync/releases/tag/v0.2.0",
          "prerelease": false,
          "assets": [
            {
              "name": "ClipSync-android.apk",
              "browser_download_url": "https://github.com/fraternidaddeng/clipsync/releases/download/v0.2.0/ClipSync-android.apk",
              "size": 74724855,
              "digest": "sha256:9f59ff17b2cfee9b623df2a3f8a3bc636efa173a36d7f412221f221cc24913b5"
            },
            {
              "name": "ClipSync-android.apk.sha256",
              "browser_download_url": "https://github.com/fraternidaddeng/clipsync/releases/download/v0.2.0/ClipSync-android.apk.sha256",
              "size": 87
            },
            {
              "name": "ClipSync-windows-x64.zip",
              "browser_download_url": "https://github.com/fraternidaddeng/clipsync/releases/download/v0.2.0/ClipSync-windows-x64.zip",
              "size": 117596025,
              "digest": "sha256:1dbd5e336069839096200e9304a436bde1a727bd2fefe269cb00cff71a802981"
            }
          ]
        }
        """.trimIndent()

    @Test
    fun parsesTagAssetsAndDigestsAndIgnoresUnknownFields() {
        val release = GitHubReleaseParser.parse(latestJson)
        assertEquals("v0.2.0", release.tagName)
        assertEquals("0.2.0", release.versionLabel)
        val apk = release.findPayload(UpdatePlatform.ANDROID)
        assertNotNull(apk)
        assertEquals("ClipSync-android.apk", apk!!.name)
        assertEquals(74_724_855L, apk.sizeBytes)
        assertEquals("9f59ff17b2cfee9b623df2a3f8a3bc636efa173a36d7f412221f221cc24913b5", apk.sha256Hex)
        assertNotNull(release.findSidecar(apk))
    }

    @Test
    fun doesNotTreatTheUnsignedApkAsTheAndroidPayload() {
        val release =
            GitHubReleaseParser.parse(
                """
                {"tag_name":"v0.1.0-rc.1","assets":[
                  {"name":"ClipSync-android-unsigned.apk",
                   "browser_download_url":"https://example.test/unsigned.apk","size":1}]}
                """.trimIndent(),
            )
        assertNull(release.findPayload(UpdatePlatform.ANDROID))
    }

    @Test
    fun checkResultIsAvailableOnlyWhenLatestRanksHigher() {
        val latest = GitHubReleaseParser.parse(latestJson)
        assertFalse(UpdateCheckResult.from("0.2.0", latest, UpdatePlatform.ANDROID).updateAvailable)
        assertFalse(UpdateCheckResult.from("0.3.0", latest, UpdatePlatform.ANDROID).updateAvailable)
        assertTrue(UpdateCheckResult.from("0.1.0-rc.2", latest, UpdatePlatform.ANDROID).updateAvailable)
        assertTrue(UpdateCheckResult.from("0.1.0", latest, UpdatePlatform.WINDOWS).updateAvailable)
    }

    @Test
    fun parsesGnuAndStarSidecarBodies() {
        assertEquals(
            "9f59ff17b2cfee9b623df2a3f8a3bc636efa173a36d7f412221f221cc24913b5",
            GitHubReleaseParser.parseSha256Sidecar(
                "9f59ff17b2cfee9b623df2a3f8a3bc636efa173a36d7f412221f221cc24913b5 *ClipSync-android.apk\n",
            ),
        )
        assertEquals(
            "9f59ff17b2cfee9b623df2a3f8a3bc636efa173a36d7f412221f221cc24913b5",
            GitHubReleaseParser.parseSha256Sidecar(
                "9F59FF17B2CFEE9B623DF2A3F8A3BC636EFA173A36D7F412221F221CC24913B5  ClipSync-android.apk",
            ),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun missingTagIsAParseFailure() {
        GitHubReleaseParser.parse("""{"assets":[]}""")
    }
}

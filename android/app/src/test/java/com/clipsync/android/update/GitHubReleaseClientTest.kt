package com.clipsync.android.update

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException

class GitHubReleaseClientTest {
    @Test
    fun toHexLowerDoesNotSignExtendHighBytes() {
        val high = byteArrayOf(0x00, 0x80.toByte(), 0xFF.toByte())
        assertEquals("0080ff", GitHubReleaseClient.toHexLower(high))
    }

    @Test
    fun sha256HexMatchesKnownVectorsIncludingHighBytes() {
        val empty = File.createTempFile("clipsync-sha-empty", ".bin")
        val abc = File.createTempFile("clipsync-sha-abc", ".bin")
        val high = File.createTempFile("clipsync-sha-high", ".bin")
        try {
            empty.writeBytes(ByteArray(0))
            abc.writeBytes("abc".toByteArray())
            high.writeBytes(byteArrayOf(0x00, 0x80.toByte(), 0xFF.toByte()))
            assertEquals(
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                GitHubReleaseClient.sha256Hex(empty),
            )
            assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                GitHubReleaseClient.sha256Hex(abc),
            )
            val hex = GitHubReleaseClient.sha256Hex(high)
            assertEquals(64, hex.length)
            assertEquals(
                "5240672d7b51756b829ad0ef8d9468b7a078afa2f410484fd3892dab47becb72",
                hex,
            )
        } finally {
            empty.delete()
            abc.delete()
            high.delete()
        }
    }

    @Test
    fun verifySha256DeletesTheFileOnMismatch() {
        val file = File.createTempFile("clipsync-sha-bad", ".bin")
        file.writeBytes("hello".toByteArray())
        try {
            GitHubReleaseClient.verifySha256(file, "ab" + "cd".repeat(31))
            fail("expected IOException")
        } catch (_: IOException) {
            assertFalse(file.exists())
        }
    }

    @Test
    fun fetchLatestFallsBackToTheFirstPrefixWhenOfficialReturnsForbidden() {
        val official = GitHubReleaseClient.DEFAULT_LATEST_URL
        val mirror = GitHubUrlMirrors.prefixes.first() + official
        val json =
            """
            {"tag_name":"v0.4.0",
             "html_url":"https://github.com/fraternidaddeng/clipsync/releases/tag/v0.4.0",
             "assets":[]}
            """.trimIndent()
        val seen = mutableListOf<String>()
        val http =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    val url = request.url.toString()
                    seen += url
                    when (url) {
                        official -> fakeResponse(request, 403, "rate limited")
                        mirror -> fakeResponse(request, 200, json)
                        else -> error("unexpected $url")
                    }
                }.build()
        val client = GitHubReleaseClient(currentVersion = "0.3.0", http = http)
        val release = runBlocking { client.fetchLatest() }
        assertEquals("0.4.0", release.versionLabel)
        assertEquals(listOf(official, mirror), seen)
    }

    private fun fakeResponse(
        request: Request,
        code: Int,
        body: String,
    ): Response =
        Response
            .Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code == 200) "OK" else "ERR")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()
}

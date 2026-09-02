package com.clipsync.android.update

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
}

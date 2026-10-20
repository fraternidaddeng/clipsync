package com.clipsync.android.pairing

import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Android endpoint policy is deliberately stricter than the shared schema's string length. */
class PairingQrHostTest {
    private fun payload(host: String) = PairingQrPayload(
        kind = PairingDocumentKinds.QR,
        version = 1,
        hosts = listOf(host),
        port = 47_654,
        deviceId = "11111111-1111-4111-8111-111111111111",
        displayName = "DESKTOP-WIN",
        certSha256 = "ab".repeat(32),
        token = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8",
        expiresAtMs = 2_000,
    )

    @Test
    fun `QR hosts accept IP literals and DNS hostnames without changing the document`() {
        val longestHostname = listOf("a".repeat(63), "b".repeat(63), "c".repeat(63), "d".repeat(61)).joinToString(".")
        val hosts = listOf(
            "192.168.1.23", "100.64.0.1", "0.0.0.0", "255.255.255.255",
            "::1", "2001:db8::1", "2001:db8:0:0:0:0:0:1", "::ffff:192.0.2.128", "[2001:db8::1]",
            "localhost", "DESKTOP-WIN", "desktop.local", "desktop.example.",
            "xn--bcher-kva.example", "bücher.example", longestHostname,
        )
        hosts.forEach { host ->
            val original = payload(host)
            assertEquals(original, PairingJson.parseQrPayload(PairingJson.serialize(original)))
        }
    }

    @Test
    fun `QR hosts reject URL syntax control characters and malformed addresses`() {
        val hosts = listOf(
            "", " ", " localhost", "localhost ", "local host", "local\thost", "local\nhost", "local\rhost",
            "local\u0000host", "local\u001fhost", "local\u007fhost", "local\u0085host", "local\u00a0host", "local\u200bhost",
            "https://localhost", "//localhost", "localhost:443", "user@localhost", "localhost/path", "localhost\\path",
            "localhost?query", "localhost#fragment", "localhost%2fpath", "%31%32%37.0.0.1",
            ".", ".localhost", "localhost..example", "-desktop.local", "desktop-.local", "desktop_name.local", "*.local",
            "127.0.0.256", "127.1", "2130706433", "127.000.0.1",
            "[::1]:443", "[::1", "::1]", "[localhost]", "[127.0.0.1]", "[[::1]]", "2001:db8:::1", "::1::2",
            "1:2:3:4:5:6:7", "1:2:3:4:5:6:7:8:9", "12345::", "gggg::1", "fe80::1%wlan0", "[fe80::1%25wlan0]",
            "a".repeat(64) + ".local",
            listOf("a".repeat(63), "b".repeat(63), "c".repeat(63), "d".repeat(62)).joinToString("."),
        )
        hosts.forEachIndexed { index, host ->
            val failure = runCatching { PairingJson.parseQrPayload(PairingJson.serialize(payload(host))) }.exceptionOrNull()
            assertTrue("Invalid host case #$index was accepted: $failure", failure is SerializationException)
        }
    }
}

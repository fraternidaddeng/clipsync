package com.clipsync.android.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class PeerEndpointTest {
    @Test
    fun `IPv6 endpoint URLs bracket and canonicalize only the host`() {
        listOf("2001:db8::1", "[2001:db8::1]", "2001:0DB8:0:0:0:0:0:1").forEach { host ->
            val url = PeerEndpoint.httpsUrl(host, 47_654, "v1/pair/confirm")
            assertEquals("https://[2001:db8::1]:47654/v1/pair/confirm", url.toString())
            assertEquals("2001:db8::1", url.host)
            assertEquals(47_654, url.port)
            assertEquals("", url.username)
            assertEquals(null, url.query)
            assertEquals(null, url.fragment)
        }
    }

    @Test
    fun `IPv4 and hostname endpoints keep the intended port and sync path`() {
        mapOf(
            "192.168.1.23" to "192.168.1.23",
            "DESKTOP-WIN.local" to "desktop-win.local",
            "desktop.example." to "desktop.example.",
            "bücher.example" to "xn--bcher-kva.example",
        ).forEach { (host, canonical) ->
            assertEquals(
                "https://$canonical:47654/v2/peer/sync",
                PeerEndpoint.httpsUrl(host, 47_654, "v2/peer/sync").toString(),
            )
        }
    }
}

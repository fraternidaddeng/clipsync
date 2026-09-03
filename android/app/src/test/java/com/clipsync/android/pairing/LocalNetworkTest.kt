package com.clipsync.android.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalNetworkTest {
    @Test
    fun `a host inside the phone's slash 24 matches, one outside does not`() {
        assertTrue(anyHostOnSameSubnet(listOf("192.168.1.23"), "192.168.1.5", 24))
        assertFalse(anyHostOnSameSubnet(listOf("192.168.2.23"), "192.168.1.5", 24))
    }

    @Test
    fun `any one matching host is enough, in any position`() {
        assertTrue(anyHostOnSameSubnet(listOf("10.0.0.9", "172.16.4.2", "192.168.1.200"), "192.168.1.5", 24))
        assertFalse(anyHostOnSameSubnet(listOf("10.0.0.9", "172.16.4.2"), "192.168.1.5", 24))
        assertFalse(anyHostOnSameSubnet(emptyList(), "192.168.1.5", 24))
    }

    @Test
    fun `the prefix length decides how wide the subnet is`() {
        // Different third octet: outside a /24, inside a /16.
        assertFalse(anyHostOnSameSubnet(listOf("192.168.2.23"), "192.168.1.5", 24))
        assertTrue(anyHostOnSameSubnet(listOf("192.168.2.23"), "192.168.1.5", 16))
        // /32 is the single address itself; /0 is everything.
        assertFalse(anyHostOnSameSubnet(listOf("192.168.1.6"), "192.168.1.5", 32))
        assertTrue(anyHostOnSameSubnet(listOf("192.168.1.5"), "192.168.1.5", 32))
        assertTrue(anyHostOnSameSubnet(listOf("8.8.8.8"), "192.168.1.5", 0))
    }

    @Test
    fun `non-IPv4 hosts and malformed input never match`() {
        assertFalse(anyHostOnSameSubnet(listOf("fe80::1", "desktop-win.local", "192.168.1"), "192.168.1.5", 24))
        assertFalse(anyHostOnSameSubnet(listOf("192.168.1.23"), "fe80::1", 64))
        assertFalse(anyHostOnSameSubnet(listOf("192.168.1.23"), "", 24))
        assertFalse(anyHostOnSameSubnet(listOf("192.168.1.23"), "192.168.1.5", -1))
        assertFalse(anyHostOnSameSubnet(listOf("192.168.1.23"), "192.168.1.5", 33))
    }

    @Test
    fun `IPv4 parsing accepts dotted quads only`() {
        assertEquals(0xC0A80117L, parseIpv4("192.168.1.23"))
        assertEquals(0L, parseIpv4("0.0.0.0"))
        assertEquals(0xFFFFFFFFL, parseIpv4(" 255.255.255.255 "))
        assertNull(parseIpv4("256.1.1.1"))
        assertNull(parseIpv4("1.2.3"))
        assertNull(parseIpv4("1.2.3.4.5"))
        assertNull(parseIpv4("1.2..4"))
        assertNull(parseIpv4("a.b.c.d"))
        assertNull(parseIpv4("1.2.3.0004"))
        assertNull(parseIpv4("-1.2.3.4"))
    }
}

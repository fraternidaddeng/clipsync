package com.clipsync.android.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryBeaconTest {
    private val expectation = BeaconExpectation(WINDOWS_ID, CERT)

    private fun beaconJson(
        kind: String = DiscoveryBeacons.KIND,
        deviceId: String = WINDOWS_ID,
        cert: String = CERT,
        port: Int = 47654,
    ): String = """{"v":1,"kind":"$kind","device_id":"$deviceId","port":$port,"cert_sha256":"$cert"}"""

    @Test
    fun `the windows beacon parses into its identity fields`() {
        val beacon = requireNotNull(DiscoveryBeacons.parse(beaconJson()))
        assertEquals(1, beacon.version)
        assertEquals(DiscoveryBeacons.KIND, beacon.kind)
        assertEquals(WINDOWS_ID, beacon.deviceId)
        assertEquals(47654, beacon.port)
        assertEquals(CERT, beacon.certSha256)
    }

    @Test
    fun `unknown fields are tolerated so a newer windows build still counts`() {
        val text = beaconJson().dropLast(1) + ""","name":"x","extra":{"nested":true}}"""
        assertEquals(WINDOWS_ID, DiscoveryBeacons.parse(text)?.deviceId)
    }

    @Test
    fun `other kinds, missing fields, bad ports and malformed json are not beacons`() {
        assertNull(DiscoveryBeacons.parse(beaconJson(kind = "clipsync_hello")))
        assertNull(DiscoveryBeacons.parse("""{"v":1,"kind":"clipsync_discovery","device_id":"$WINDOWS_ID"}"""))
        assertNull(DiscoveryBeacons.parse(beaconJson(port = 0)))
        assertNull(DiscoveryBeacons.parse(beaconJson(port = 70000)))
        assertNull(DiscoveryBeacons.parse("""{"v":1,"kind":"clipsync_discovery","""))
        assertNull(DiscoveryBeacons.parse("not json"))
        assertNull(DiscoveryBeacons.parse(""))
        assertNull(DiscoveryBeacons.parse("[1,2,3]"))
    }

    @Test
    fun `a beacon matches only the exact device id and fingerprint from the QR`() {
        val beacon = requireNotNull(DiscoveryBeacons.parse(beaconJson()))
        assertTrue(DiscoveryBeacons.matches(beacon, expectation))
        assertFalse(DiscoveryBeacons.matches(beacon, expectation.copy(deviceId = OTHER_ID)))
        assertFalse(DiscoveryBeacons.matches(beacon, expectation.copy(certSha256 = "ab".repeat(32))))
    }

    @Test
    fun `fingerprint comparison ignores case like the pin, device id does not`() {
        val upper = requireNotNull(DiscoveryBeacons.parse(beaconJson(cert = CERT.uppercase())))
        assertTrue(DiscoveryBeacons.matches(upper, expectation))
        val upperId = requireNotNull(DiscoveryBeacons.parse(beaconJson(deviceId = WINDOWS_ID.uppercase())))
        assertFalse(DiscoveryBeacons.matches(upperId, expectation))
    }

    @Test
    fun `a sighting from a listed host moves that host to the front`() {
        val hosts = listOf("10.0.0.5", "192.168.1.23", "100.64.0.9")
        assertEquals(
            listOf("192.168.1.23", "10.0.0.5", "100.64.0.9"),
            hostsPreferringBeacon(hosts, BeaconStatus.Seen("192.168.1.23", 47654)),
        )
    }

    @Test
    fun `a sighting from an unlisted address is prepended as an extra candidate`() {
        val hosts = listOf("10.0.0.5", "192.168.1.23")
        assertEquals(
            listOf("192.168.1.77", "10.0.0.5", "192.168.1.23"),
            hostsPreferringBeacon(hosts, BeaconStatus.Seen("192.168.1.77", 47654)),
        )
    }

    @Test
    fun `without a sighting the QR order is untouched`() {
        val hosts = listOf("10.0.0.5", "192.168.1.23")
        assertEquals(hosts, hostsPreferringBeacon(hosts, BeaconStatus.Off))
        assertEquals(hosts, hostsPreferringBeacon(hosts, BeaconStatus.Listening))
        assertEquals(hosts, hostsPreferringBeacon(hosts, BeaconStatus.Unavailable))
    }

    @Test
    fun `a sighting from the host already in front changes nothing`() {
        val hosts = listOf("192.168.1.23", "10.0.0.5")
        assertEquals(hosts, hostsPreferringBeacon(hosts, BeaconStatus.Seen("192.168.1.23", 47654)))
    }

    private companion object {
        const val WINDOWS_ID = "1a1b1c1d-1111-4111-8111-111111111abc"
        const val OTHER_ID = "33333333-3333-4333-8333-333333333333"
        const val CERT = "0f9a54e310154f2f4d6c2a01377549272117572a83a4d64d99a1d501bcda9c25"
    }
}

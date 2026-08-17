package com.clipsync.android.pairing

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-memory stand-ins for SharedPreferences and the Keystore protector. */
class FakeKeyValueStore : KeyValueStore {
    val map = HashMap<String, String>()

    override fun read(key: String): String? = map[key]

    override fun write(values: Map<String, String?>) {
        for ((key, value) in values) {
            if (value == null) map.remove(key) else map[key] = value
        }
    }
}

/** Reversible obfuscation, so tests can verify plaintext never lands in storage. */
class FakeSecretProtector : SecretProtector {
    override fun protect(plain: ByteArray): ByteArray =
        byteArrayOf(0x5A) + plain.map { (it.toInt() xor 0x2F).toByte() }

    override fun unprotect(protected: ByteArray): ByteArray {
        require(protected.isNotEmpty() && protected[0] == 0x5A.toByte()) { "not protected by this fake" }
        return protected.drop(1).map { (it.toInt() xor 0x2F).toByte() }.toByteArray()
    }
}

class PairingStoreTest {
    private val keyValues = FakeKeyValueStore()
    private val store = PairingStore(keyValues, FakeSecretProtector())

    private fun qr(cert: String = CERT_A) = PairingQrPayload(
        kind = PairingDocumentKinds.QR,
        version = 1,
        hosts = listOf("192.168.1.23", "10.0.11.7"),
        port = 47654,
        deviceId = WINDOWS_ID,
        displayName = "DESKTOP-WIN",
        certSha256 = cert,
        token = TOKEN,
        expiresAtMs = 1_755_064_500_000,
    )

    private fun response(epoch: Long = 1) = PairingConfirmResponse(
        kind = PairingDocumentKinds.CONFIRM_RESPONSE,
        version = 1,
        deviceId = WINDOWS_ID,
        displayName = "DESKTOP-WIN",
        platform = "windows",
        pairSecret = TOKEN,
        trustEpoch = epoch,
    )

    @Test
    fun `local device id is generated once and reused`() {
        val first = store.localDeviceId()
        assertTrue(PairingJson.isCanonicalUuid(first))
        assertEquals(first, store.localDeviceId())
    }

    @Test
    fun `corrupt local device id is replaced with a canonical one`() {
        keyValues.map["local.device_id"] = "not-a-uuid"
        val repaired = store.localDeviceId()
        assertTrue(PairingJson.isCanonicalUuid(repaired))
        assertEquals(repaired, keyValues.map["local.device_id"])
    }

    @Test
    fun `savePeer persists everything and zeroes the plaintext secret`() {
        val secret = ByteArray(32) { it.toByte() }
        val original = secret.copyOf()
        store.savePeer(qr(), response(epoch = 3), secret, nowMs = 1_755_000_000_000)

        // The caller's buffer is wiped after protection.
        assertTrue(secret.all { it == 0.toByte() })

        val peer = requireNotNull(store.peer())
        assertEquals(WINDOWS_ID, peer.deviceId)
        assertEquals("DESKTOP-WIN", peer.displayName)
        assertEquals("windows", peer.platform)
        assertEquals(CERT_A, peer.certSha256)
        assertEquals(3, peer.trustEpoch)
        assertEquals(listOf("192.168.1.23", "10.0.11.7"), peer.hosts)
        assertEquals(47654, peer.port)
        assertEquals(1_755_000_000_000, peer.pairedAtMs)

        // The secret round-trips through the protector...
        assertArrayEquals(original, store.pairSecret())
        // ...and its plaintext (any encoding) never appears in the raw key-value store.
        val storedValues = keyValues.map.values.joinToString()
        assertFalse(storedValues.contains(PairingJson.encodeBase64Url(original)))
    }

    @Test
    fun `secret must be exactly 32 bytes`() {
        val result = runCatching {
            store.savePeer(qr(), response(), ByteArray(16), nowMs = 0)
        }
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun `pinned certificate match is case-insensitive and unpaired never matches`() {
        assertFalse(store.matchesPinnedCertificate(CERT_A))
        store.savePeer(qr(), response(), ByteArray(32), nowMs = 0)
        assertTrue(store.matchesPinnedCertificate(CERT_A))
        assertTrue(store.matchesPinnedCertificate(CERT_A.uppercase()))
        assertFalse(store.matchesPinnedCertificate(CERT_B))
    }

    @Test
    fun `forgetPeer removes the pairing and the protected secret`() {
        store.savePeer(qr(), response(), ByteArray(32), nowMs = 0)
        store.forgetPeer()
        assertNull(store.peer())
        assertNull(store.pairSecret())
        assertTrue(keyValues.map.keys.none { it.startsWith("peer.") })
    }

    @Test
    fun `partial persistence is treated as unpaired`() {
        store.savePeer(qr(), response(), ByteArray(32), nowMs = 0)
        keyValues.map.remove("peer.trust_epoch")
        assertNull(store.peer())
    }

    @Test
    fun `display name falls back trimmed and capped`() {
        assertEquals("Pixel 8", store.localDisplayName("  Pixel 8  "))
        assertEquals("Android phone", store.localDisplayName("   "))
        assertEquals(64, store.localDisplayName("x".repeat(200)).length)

        store.setLocalDisplayName("  My Phone  ")
        assertEquals("My Phone", store.localDisplayName("ignored"))
    }

    private companion object {
        const val WINDOWS_ID = "11111111-1111-4111-8111-111111111111"
        const val CERT_A = "0f9a54e310154f2f4d6c2a01377549272117572a83a4d64d99a1d501bcda9c25"
        const val CERT_B = "aa9a54e310154f2f4d6c2a01377549272117572a83a4d64d99a1d501bcda9c25"
        const val TOKEN = "vJ8kAqhFRWDdiWvUuJ9lPCS0jBSJ73dP9-b1JzW5Qk4"
    }
}

package com.clipsync.android.platform

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.clipsync.android.pairing.SecretProtector
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Protects the pair secret with an AES-256-GCM key that lives in the Android Keystore and
 * never leaves secure hardware/TEE. Output layout: 12-byte IV followed by ciphertext+tag.
 */
class KeystoreSecretProtector : SecretProtector {
    override fun protect(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, obtainKey())
        val iv = cipher.iv
        check(iv.size == IV_BYTES) { "unexpected GCM IV length" }
        val encrypted = cipher.doFinal(plain)
        return iv + encrypted
    }

    override fun unprotect(protected: ByteArray): ByteArray {
        require(protected.size > IV_BYTES) { "ciphertext is too short" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val spec = GCMParameterSpec(TAG_BITS, protected, 0, IV_BYTES)
        cipher.init(Cipher.DECRYPT_MODE, obtainKey(), spec)
        return cipher.doFinal(protected, IV_BYTES, protected.size - IV_BYTES)
    }

    private fun obtainKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "clipsync.pair-secret"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}

package com.clipsync.android.platform.clipboard

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

fun interface ContentHasher {
    fun hash(text: String): String
}

object Sha256ContentHasher : ContentHasher {
    override fun hash(text: String): String = hashBytes(text.toByteArray(StandardCharsets.UTF_8))

    fun hashBytes(bytes: ByteArray): String = MessageDigest
        .getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
}

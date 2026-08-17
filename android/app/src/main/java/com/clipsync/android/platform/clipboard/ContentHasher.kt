package com.clipsync.android.platform.clipboard

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

fun interface ContentHasher {
    fun hash(text: String): String
}

object Sha256ContentHasher : ContentHasher {
    override fun hash(text: String): String = MessageDigest
        .getInstance("SHA-256")
        .digest(text.toByteArray(StandardCharsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
}

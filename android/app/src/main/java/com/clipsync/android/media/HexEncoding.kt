package com.clipsync.android.media

private const val HEX_DIGITS = "0123456789abcdef"
private const val BYTE_MASK = 0xFF
private const val NIBBLE_MASK = 0x0F
private const val NIBBLE_BITS = 4

/**
 * Lowercase hex of a digest. Replaces the `joinToString { "%02x".format(it) }` idiom, whose
 * 32 `String.format` calls per SHA-256 dominated hashing small inputs on ART.
 */
internal fun ByteArray.toLowerHex(): String {
    val chars = CharArray(size * 2)
    for (index in indices) {
        val value = this[index].toInt() and BYTE_MASK
        chars[index * 2] = HEX_DIGITS[value ushr NIBBLE_BITS]
        chars[index * 2 + 1] = HEX_DIGITS[value and NIBBLE_MASK]
    }
    return String(chars)
}

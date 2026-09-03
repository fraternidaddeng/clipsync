package com.clipsync.android.protocol

/**
 * Shape checks for unpadded base64url that cost O(n) character tests instead of a regex
 * pass plus a throwaway decode. Frame validation only needs to know that a chunk *would*
 * decode to `chunk_bytes`; the single real decode happens once the frame is accepted.
 */
object Base64Url {
    private const val QUANTUM_CHARS = 4
    private const val QUANTUM_BYTES = 3

    /** Same acceptance as `^[A-Za-z0-9_-]+$`. */
    fun isAlphabet(value: String): Boolean =
        value.isNotEmpty() && value.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' }

    /**
     * Byte count `java.util.Base64.getUrlDecoder()` produces for an alphabet-only, unpadded
     * string, or null where the decoder would throw (length 1 mod 4 leaves a dangling sextet).
     * The decoder does not check trailing bits, so neither does this.
     */
    fun decodedLength(value: String): Int? {
        val remainder = value.length % QUANTUM_CHARS
        if (remainder == 1) {
            return null
        }
        return value.length / QUANTUM_CHARS * QUANTUM_BYTES + if (remainder == 0) 0 else remainder - 1
    }
}

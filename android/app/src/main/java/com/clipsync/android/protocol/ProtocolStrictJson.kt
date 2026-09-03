package com.clipsync.android.protocol

import kotlinx.serialization.SerializationException

/**
 * Stable error codes assigned while parsing a frame, before a typed body exists.
 * A subset of the full protocol error-code table (see `SyncErrorCodes` and the
 * Windows `ProtocolErrorCodes`); the strings must stay byte-identical to
 * `protocol/v1/fixtures/expected_errors.json`.
 */
object ProtocolErrorCodes {
    const val MALFORMED_JSON = "MALFORMED_JSON"
    const val SCHEMA_VIOLATION = "SCHEMA_VIOLATION"
    const val UNSUPPORTED_VERSION = "UNSUPPORTED_VERSION"
}

/**
 * Token-level JSON scan run before kotlinx sees the text. Rejects duplicate
 * object names, null values, nesting above [MAX_JSON_DEPTH], oversized
 * documents, unescaped controls, and lone surrogates — matching the Windows
 * `ProtocolReader.ScanStrictJson` pass over the same wire frames.
 */
object ProtocolStrictJson {
    /** Must match Windows `ProtocolLimits.MaxJsonDepth`. */
    const val MAX_JSON_DEPTH = 16

    /** Must match `SyncLimits.MAX_WEBSOCKET_TEXT_MESSAGE_BYTES` and Windows `ProtocolLimits.MaxWebSocketTextMessageBytes`. */
    const val MAX_TEXT_MESSAGE_BYTES = 7 * 1_048_576

    fun scan(source: String, maxBytes: Int = MAX_TEXT_MESSAGE_BYTES) {
        if (utf8ByteCountExceeds(source, maxBytes)) {
            throw ProtocolParseException(ProtocolErrorCodes.MALFORMED_JSON, "document exceeds size limit")
        }
        StrictScanner(source, maxBytes).scanDocument()
    }

    /**
     * `source.toByteArray(UTF_8).size > maxBytes` without materializing the encoding (a 350K-char
     * image chunk frame used to allocate a second copy of itself here). An unpaired surrogate
     * counts as one byte, exactly like the encoder's replacement `?`.
     */
    internal fun utf8ByteCountExceeds(text: String, maxBytes: Int): Boolean {
        var bytes = 0L
        var index = 0
        while (index < text.length) {
            val character = text[index]
            bytes += when {
                character.code < UTF8_ONE_BYTE_LIMIT -> 1
                character.code < UTF8_TWO_BYTE_LIMIT -> 2
                character.isHighSurrogate() && index + 1 < text.length && text[index + 1].isLowSurrogate() -> {
                    index++
                    UTF8_SURROGATE_PAIR_BYTES
                }
                character.isSurrogate() -> 1
                else -> UTF8_THREE_BYTES
            }
            if (bytes > maxBytes) {
                return true
            }
            index++
        }
        return false
    }

    private const val UTF8_ONE_BYTE_LIMIT = 0x80
    private const val UTF8_TWO_BYTE_LIMIT = 0x800
    private const val UTF8_THREE_BYTES = 3
    private const val UTF8_SURROGATE_PAIR_BYTES = 4
    private const val CONTROL_CHARACTER_LIMIT = 0x20
    private const val UNICODE_ESCAPE_DIGITS = 4
    private const val HEX_RADIX = 16

    private class StrictScanner(
        private val source: String,
        private val maxBytes: Int,
    ) {
        private var index = 0

        fun scanDocument() {
            skipWhitespace()
            scanValue(depth = 0)
            skipWhitespace()
            require(index == source.length) { "trailing content" }
        }

        private fun scanValue(depth: Int) {
            when (peek()) {
                '{' -> scanObject(depth + 1)
                '[' -> scanArray(depth + 1)
                '"' -> scanString(builder = null)
                't' -> literal("true")
                'f' -> literal("false")
                'n' -> throw ProtocolParseException(
                    ProtocolErrorCodes.SCHEMA_VIOLATION,
                    "null values are not allowed",
                )
                else -> scanNumber()
            }
        }

        private fun scanObject(depth: Int) {
            require(depth <= MAX_JSON_DEPTH) { "maximum depth exceeded" }
            expect('{')
            skipWhitespace()
            if (peek() == '}') {
                index++
                return
            }
            val names = HashSet<String>()
            val nameBuilder = StringBuilder()
            while (true) {
                skipWhitespace()
                nameBuilder.setLength(0)
                scanString(nameBuilder)
                require(names.add(nameBuilder.toString())) { "duplicate object property" }
                skipWhitespace()
                expect(':')
                skipWhitespace()
                scanValue(depth)
                skipWhitespace()
                when (next()) {
                    ',' -> continue
                    '}' -> return
                    else -> require(false) { "expected ',' or '}'" }
                }
            }
        }

        private fun scanArray(depth: Int) {
            require(depth <= MAX_JSON_DEPTH) { "maximum depth exceeded" }
            expect('[')
            skipWhitespace()
            if (peek() == ']') {
                index++
                return
            }
            while (true) {
                skipWhitespace()
                scanValue(depth)
                skipWhitespace()
                when (next()) {
                    ',' -> continue
                    ']' -> return
                    else -> require(false) { "expected ',' or ']'" }
                }
            }
        }

        /**
         * Validates one JSON string. Property names are collected into [builder] for the
         * duplicate check; values pass `null` and are validated in place — a 350K-char image
         * chunk is never copied into a StringBuilder just to be thrown away. Lone-surrogate
         * detection runs over the decoded character stream (raw and escape-produced alike),
         * which is the same sequence the old post-hoc check walked.
         */
        private fun scanString(builder: StringBuilder?) {
            expect('"')
            val start = index
            var expectingLowSurrogate = false
            while (true) {
                require(index < source.length) { "unterminated string" }
                val character = source[index]
                val decoded = when {
                    character == '"' -> {
                        index++
                        require(!expectingLowSurrogate) { "lone surrogate" }
                        return
                    }
                    character == '\\' -> {
                        index++
                        scanEscape()
                    }
                    character.code < CONTROL_CHARACTER_LIMIT -> throw ProtocolParseException(
                        ProtocolErrorCodes.MALFORMED_JSON,
                        "unescaped control character",
                    )
                    else -> {
                        index++
                        character
                    }
                }
                if (expectingLowSurrogate) {
                    require(decoded.isLowSurrogate()) { "lone surrogate" }
                    expectingLowSurrogate = false
                } else if (decoded.isHighSurrogate()) {
                    expectingLowSurrogate = true
                } else {
                    require(!decoded.isLowSurrogate()) { "lone surrogate" }
                }
                builder?.append(decoded)
                require(index - start < maxBytes) { "string too long" }
            }
        }

        private fun scanEscape(): Char {
            require(index < source.length) { "unterminated escape" }
            return when (val escape = source[index++]) {
                '"' -> '"'
                '\\' -> '\\'
                '/' -> '/'
                'b' -> '\b'
                'f' -> '\u000C'
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'u' -> {
                    require(index + UNICODE_ESCAPE_DIGITS <= source.length) { "truncated unicode escape" }
                    val hex = source.substring(index, index + UNICODE_ESCAPE_DIGITS)
                    require(hex.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
                        "invalid unicode escape"
                    }
                    index += UNICODE_ESCAPE_DIGITS
                    hex.toInt(HEX_RADIX).toChar()
                }
                else -> throw ProtocolParseException(
                    ProtocolErrorCodes.MALFORMED_JSON,
                    "invalid escape '\\$escape'",
                )
            }
        }

        private fun scanNumber() {
            val start = index
            if (peek() == '-') {
                index++
            }
            require(index < source.length && source[index].isDigit()) { "invalid number" }
            if (source[index] == '0') {
                index++
            } else {
                while (index < source.length && source[index].isDigit()) {
                    index++
                }
            }
            if (index < source.length && source[index] == '.') {
                index++
                require(index < source.length && source[index].isDigit()) { "invalid number" }
                while (index < source.length && source[index].isDigit()) {
                    index++
                }
            }
            if (index < source.length && (source[index] == 'e' || source[index] == 'E')) {
                index++
                if (index < source.length && (source[index] == '+' || source[index] == '-')) {
                    index++
                }
                require(index < source.length && source[index].isDigit()) { "invalid number" }
                while (index < source.length && source[index].isDigit()) {
                    index++
                }
            }
            require(index > start) { "invalid number" }
        }

        private fun literal(expected: String) {
            require(source.startsWith(expected, index)) { "invalid literal" }
            index += expected.length
        }

        private fun peek(): Char {
            require(index < source.length) { "unexpected end of document" }
            return source[index]
        }

        private fun next(): Char {
            require(index < source.length) { "unexpected end of document" }
            return source[index++]
        }

        private fun expect(character: Char) {
            require(next() == character) { "expected '$character'" }
        }

        private fun skipWhitespace() {
            while (index < source.length && source[index] in " \t\r\n") {
                index++
            }
        }

        private inline fun require(condition: Boolean, reason: () -> String) {
            if (!condition) {
                throw ProtocolParseException(ProtocolErrorCodes.MALFORMED_JSON, reason())
            }
        }
    }
}

/**
 * A frame rejection with its stable protocol error code. Extends
 * [SerializationException] so every existing rejection path keeps working.
 */
class ProtocolParseException(
    val errorCode: String,
    message: String,
) : SerializationException(message)

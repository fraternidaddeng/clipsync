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
        val size = source.toByteArray(Charsets.UTF_8).size
        if (size > maxBytes) {
            throw ProtocolParseException(ProtocolErrorCodes.MALFORMED_JSON, "document exceeds size limit")
        }
        StrictScanner(source, maxBytes).scanDocument()
    }

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
                '"' -> scanString()
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
            while (true) {
                skipWhitespace()
                require(names.add(scanString())) { "duplicate object property" }
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

        private fun scanString(): String {
            expect('"')
            val start = index
            val builder = StringBuilder()
            while (true) {
                require(index < source.length) { "unterminated string" }
                val character = source[index]
                when {
                    character == '"' -> {
                        index++
                        val value = builder.toString()
                        requireNoLoneSurrogates(value)
                        return value
                    }
                    character == '\\' -> {
                        index++
                        builder.append(scanEscape())
                    }
                    character.code < 0x20 -> require(false) { "unescaped control character" }
                    else -> {
                        builder.append(character)
                        index++
                    }
                }
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
                    require(index + 4 <= source.length) { "truncated unicode escape" }
                    val hex = source.substring(index, index + 4)
                    require(hex.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
                        "invalid unicode escape"
                    }
                    index += 4
                    hex.toInt(16).toChar()
                }
                else -> throw ProtocolParseException(
                    ProtocolErrorCodes.MALFORMED_JSON,
                    "invalid escape '\\$escape'",
                )
            }
        }

        private fun requireNoLoneSurrogates(value: String) {
            var position = 0
            while (position < value.length) {
                val character = value[position]
                if (character.isHighSurrogate()) {
                    require(position + 1 < value.length && value[position + 1].isLowSurrogate()) {
                        "lone surrogate"
                    }
                    position += 2
                    continue
                }
                require(!character.isLowSurrogate()) { "lone surrogate" }
                position++
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

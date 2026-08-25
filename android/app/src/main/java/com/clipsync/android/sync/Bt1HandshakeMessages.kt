package com.clipsync.android.sync

import com.clipsync.android.protocol.ProtocolStrictJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Stable bt1 error codes from docs/protocol-bt1.md section 6. The strings must stay
 * byte-identical to protocol/bt1/handshake.schema.json and the Windows Bt1ErrorCodes.
 */
object Bt1ErrorCodes {
    const val SCHEMA_VIOLATION = "BT1_SCHEMA_VIOLATION"
    const val VERSION_UNSUPPORTED = "BT1_VERSION_UNSUPPORTED"
    const val AUTH_FAILED = "BT1_AUTH_FAILED"
    const val RATE_LIMITED = "BT1_RATE_LIMITED"
    const val FRAME_TOO_LARGE = "BT1_FRAME_TOO_LARGE"

    /** Local diagnostics only; never valid on the wire. */
    const val DECRYPT_FAILED = "BT1_DECRYPT_FAILED"

    val WIRE_CODES =
        setOf(
            SCHEMA_VIOLATION,
            VERSION_UNSUPPORTED,
            AUTH_FAILED,
            RATE_LIMITED,
            FRAME_TOO_LARGE,
        )
}

/** One parsed bt1 handshake message (docs/protocol-bt1.md section 3). */
sealed class Bt1HandshakeMessage {
    class Hello(
        val senderRole: Bt1Role,
        val deviceId: String,
        val trustEpoch: Long,
        val nonce: ByteArray,
    ) : Bt1HandshakeMessage()

    class Auth(
        val senderRole: Bt1Role,
        val proof: ByteArray,
    ) : Bt1HandshakeMessage()

    class ChannelError(
        val code: String,
    ) : Bt1HandshakeMessage()
}

/** A handshake rejection with its stable bt1 error code; the message is diagnostic only. */
class Bt1HandshakeException(
    val errorCode: String,
    message: String,
) : Exception(message)

/**
 * Strict codec for the five bt1 handshake messages. Parsing enforces the protocol v1
 * section 2 JSON rules (strict UTF-8, duplicate properties rejected, no null, no unknown
 * fields) plus the bt1 shapes in protocol/bt1/handshake.schema.json; the shared message
 * fixtures live in protocol/bt1/fixtures/handshake/valid and invalid. Must accept and
 * reject exactly like the Windows Bt1HandshakeCodec.
 */
object Bt1HandshakeCodec {
    private const val CLIENT_HELLO_KIND = "bt1_client_hello"
    private const val LISTENER_HELLO_KIND = "bt1_listener_hello"
    private const val CLIENT_AUTH_KIND = "bt1_client_auth"
    private const val LISTENER_AUTH_KIND = "bt1_listener_auth"
    private const val ERROR_KIND = "bt1_error"
    private const val CHANNEL_VERSION = 1L

    private val KNOWN_KINDS =
        setOf(
            CLIENT_HELLO_KIND,
            LISTENER_HELLO_KIND,
            CLIENT_AUTH_KIND,
            LISTENER_AUTH_KIND,
            ERROR_KIND,
        )
    private val HELLO_FIELDS = setOf("kind", "version", "device_id", "trust_epoch", "nonce")
    private val AUTH_FIELDS = setOf("kind", "version", "proof")
    private val ERROR_FIELDS = setOf("kind", "version", "code")
    private val CANONICAL_UUID =
        Regex(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
        )

    fun parse(text: String): Bt1HandshakeMessage {
        val byteLength = text.toByteArray(Charsets.UTF_8).size
        requireHandshake(byteLength >= Bt1Frames.MIN_HANDSHAKE_PAYLOAD_LENGTH) {
            "handshake payload is shorter than 2 bytes"
        }
        if (byteLength > Bt1Frames.MAX_HANDSHAKE_PAYLOAD_LENGTH) {
            failHandshake(Bt1ErrorCodes.FRAME_TOO_LARGE, "handshake payload exceeds 4096 bytes")
        }

        runCatching { ProtocolStrictJson.scan(text, Bt1Frames.MAX_HANDSHAKE_PAYLOAD_LENGTH) }
            .onFailure { requireHandshake(false) { "handshake JSON is not strict" } }
        val root =
            runCatching { Json.parseToJsonElement(text) }
                .getOrElse { null }
        val fields =
            root as? JsonObject
                ?: failHandshake(Bt1ErrorCodes.SCHEMA_VIOLATION, "handshake message must be a JSON object")

        val kind = stringField(fields, "kind")
        requireHandshake(kind in KNOWN_KINDS) { "kind is unknown" }
        val version = integerField(fields, "version")
        if (version != CHANNEL_VERSION) {
            failHandshake(Bt1ErrorCodes.VERSION_UNSUPPORTED, "bt1 channel version is unsupported")
        }

        return when (kind) {
            CLIENT_HELLO_KIND -> parseHello(fields, Bt1Role.CLIENT)
            LISTENER_HELLO_KIND -> parseHello(fields, Bt1Role.LISTENER)
            CLIENT_AUTH_KIND -> parseAuth(fields, Bt1Role.CLIENT)
            LISTENER_AUTH_KIND -> parseAuth(fields, Bt1Role.LISTENER)
            else -> parseError(fields)
        }
    }

    private fun parseHello(
        fields: JsonObject,
        senderRole: Bt1Role,
    ): Bt1HandshakeMessage.Hello {
        requireHandshake(fields.keys == HELLO_FIELDS) {
            "hello must carry exactly kind, version, device_id, trust_epoch, nonce"
        }
        val deviceId = stringField(fields, "device_id")
        requireHandshake(CANONICAL_UUID.matches(deviceId)) { "device_id is not a canonical UUID" }
        val trustEpoch = integerField(fields, "trust_epoch")
        requireHandshake(trustEpoch >= 1L) { "trust_epoch must be a positive 64-bit integer" }
        val nonce = canonicalBase64Url256(stringField(fields, "nonce"), "nonce")
        return Bt1HandshakeMessage.Hello(senderRole, deviceId, trustEpoch, nonce)
    }

    private fun parseAuth(
        fields: JsonObject,
        senderRole: Bt1Role,
    ): Bt1HandshakeMessage.Auth {
        requireHandshake(fields.keys == AUTH_FIELDS) { "auth must carry exactly kind, version, proof" }
        val proof = canonicalBase64Url256(stringField(fields, "proof"), "proof")
        return Bt1HandshakeMessage.Auth(senderRole, proof)
    }

    private fun parseError(fields: JsonObject): Bt1HandshakeMessage.ChannelError {
        requireHandshake(fields.keys == ERROR_FIELDS) { "error must carry exactly kind, version, code" }
        val code = stringField(fields, "code")
        requireHandshake(code in Bt1ErrorCodes.WIRE_CODES) { "error code is unknown" }
        return Bt1HandshakeMessage.ChannelError(code)
    }

    fun serializeHello(
        senderRole: Bt1Role,
        deviceId: String,
        trustEpoch: Long,
        nonce: ByteArray,
    ): String {
        require(trustEpoch >= 1L) { "trust_epoch must be at least 1." }
        require(CANONICAL_UUID.matches(deviceId)) { "device_id must be a canonical lowercase UUID." }
        require(nonce.size == Bt1AuthProof.NONCE_LENGTH) { "The nonce must be exactly 32 bytes." }
        return buildJsonObject {
            put("kind", if (senderRole == Bt1Role.CLIENT) CLIENT_HELLO_KIND else LISTENER_HELLO_KIND)
            put("version", CHANNEL_VERSION)
            put("device_id", deviceId)
            put("trust_epoch", trustEpoch)
            put("nonce", Base64Url.encode(nonce))
        }.toString()
    }

    fun serializeAuth(
        senderRole: Bt1Role,
        proof: ByteArray,
    ): String {
        require(proof.size == Bt1AuthProof.PROOF_LENGTH) { "The proof must be exactly 32 bytes." }
        return buildJsonObject {
            put("kind", if (senderRole == Bt1Role.CLIENT) CLIENT_AUTH_KIND else LISTENER_AUTH_KIND)
            put("version", CHANNEL_VERSION)
            put("proof", Base64Url.encode(proof))
        }.toString()
    }

    fun serializeError(code: String): String {
        require(code in Bt1ErrorCodes.WIRE_CODES) { "Only wire-legal bt1 error codes may be serialized." }
        return buildJsonObject {
            put("kind", ERROR_KIND)
            put("version", CHANNEL_VERSION)
            put("code", code)
        }.toString()
    }

    private fun stringField(
        fields: JsonObject,
        name: String,
    ): String {
        val primitive = fields[name] as? JsonPrimitive
        requireHandshake(primitive != null && primitive.isString) { "$name is missing or not a string" }
        return primitive!!.content
    }

    private fun integerField(
        fields: JsonObject,
        name: String,
    ): Long {
        val primitive = fields[name] as? JsonPrimitive
        val value = if (primitive != null && !primitive.isString) primitive.content.toLongOrNull() else null
        requireHandshake(value != null) { "$name is missing or not an integer" }
        return value!!
    }

    /** Exactly 32 bytes of unpadded, canonical base64url (re-encoding must reproduce the input). */
    private fun canonicalBase64Url256(
        value: String,
        label: String,
    ): ByteArray {
        val decoded = Base64Url.decodeExact(value, Bt1AuthProof.NONCE_LENGTH)
        requireHandshake(decoded != null && Base64Url.encode(decoded) == value) {
            "$label is not canonical base64url for 32 bytes"
        }
        return decoded!!
    }
}

private fun failHandshake(
    errorCode: String,
    reason: String,
): Nothing = throw Bt1HandshakeException(errorCode, reason)

private inline fun requireHandshake(
    condition: Boolean,
    reason: () -> String,
) {
    if (!condition) {
        failHandshake(Bt1ErrorCodes.SCHEMA_VIOLATION, reason())
    }
}

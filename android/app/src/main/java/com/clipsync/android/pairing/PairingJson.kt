package com.clipsync.android.pairing

import java.util.Base64
import java.util.UUID
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Strict reader for the pairing documents: the same token-level rules as the Windows
 * implementation (duplicate properties, null values, depth 16, unknown fields, 8 KiB cap)
 * plus the semantic checks from pairing.schema.json, so both clients fail identically.
 * Parsers throw [SerializationException]; callers map that to a stable "invalid document"
 * state without echoing the raw payload anywhere.
 */
object PairingJson {
    const val MAX_DOCUMENT_BYTES = 8 * 1024

    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        coerceInputValues = false
        allowSpecialFloatingPointValues = false
        explicitNulls = true
    }

    fun parseQrPayload(text: String): PairingQrPayload {
        scanStrict(text)
        val payload = decode<PairingQrPayload>(text)
        requireDocument(payload.kind == PairingDocumentKinds.QR) { "kind discriminator does not match" }
        validateCommon(payload.version, payload.deviceId, payload.displayName)
        requireDocument(
            payload.hosts.size in 1..8 &&
                payload.hosts.all { it.length in 1..253 } &&
                payload.hosts.toSet().size == payload.hosts.size,
        ) { "hosts must be 1..8 unique entries" }
        requireDocument(payload.port in 1..65535) { "port is out of range" }
        requireDocument(isLowercaseSha256(payload.certSha256)) { "cert_sha256 is not lowercase SHA-256 hex" }
        requireDocument(decodeBase64Url256(payload.token) != null) { "token is not 32 bytes of unpadded base64url" }
        requireDocument(payload.expiresAtMs >= 1) { "expires_at_ms must be positive" }
        return payload
    }

    fun parseConfirmResponse(text: String): PairingConfirmResponse {
        scanStrict(text)
        val response = decode<PairingConfirmResponse>(text)
        requireDocument(response.kind == PairingDocumentKinds.CONFIRM_RESPONSE) { "kind discriminator does not match" }
        validateCommon(response.version, response.deviceId, response.displayName)
        requireDocument(response.platform in KNOWN_PLATFORMS) { "platform is unsupported" }
        requireDocument(decodeBase64Url256(response.pairSecret) != null) { "pair_secret is not 32 bytes of unpadded base64url" }
        requireDocument(response.trustEpoch >= 1) { "trust_epoch must be at least 1" }
        return response
    }

    fun parseConfirmRequest(text: String): PairingConfirmRequest {
        scanStrict(text)
        val request = decode<PairingConfirmRequest>(text)
        requireDocument(request.kind == PairingDocumentKinds.CONFIRM_REQUEST) { "kind discriminator does not match" }
        validateCommon(request.version, request.deviceId, request.displayName)
        requireDocument(decodeBase64Url256(request.token) != null) { "token is not 32 bytes of unpadded base64url" }
        requireDocument(request.platform in KNOWN_PLATFORMS) { "platform is unsupported" }
        return request
    }

    fun parseError(text: String): PairingErrorBody {
        scanStrict(text)
        val body = decode<PairingErrorBody>(text)
        requireDocument(body.kind == PairingDocumentKinds.ERROR) { "kind discriminator does not match" }
        requireDocument(body.version == PROTOCOL_VERSION) { "version is unsupported" }
        requireDocument(body.error in PairingErrorCodes.ALL) { "error code is unknown" }
        return body
    }

    /** Lenient probe of the kind discriminator, used to dispatch fixtures and responses. */
    fun peekKind(text: String): String? = runCatching {
        json.parseToJsonElement(text).jsonObject["kind"]?.jsonPrimitive?.takeIf { it.isString }?.content
    }.getOrNull()

    fun serialize(request: PairingConfirmRequest): String = json.encodeToString(PairingConfirmRequest.serializer(), request)

    fun serialize(response: PairingConfirmResponse): String = json.encodeToString(PairingConfirmResponse.serializer(), response)

    fun serialize(payload: PairingQrPayload): String = json.encodeToString(PairingQrPayload.serializer(), payload)

    fun serialize(error: PairingErrorBody): String = json.encodeToString(PairingErrorBody.serializer(), error)

    fun isCanonicalUuid(value: String): Boolean = CANONICAL_UUID.matches(value)

    /** Decodes unpadded base64url for exactly 32 bytes with zeroed trailing bits, else null. */
    fun decodeBase64Url256(value: String): ByteArray? {
        if (!BASE64_URL_256.matches(value)) {
            return null
        }
        val bytes = runCatching { Base64.getUrlDecoder().decode(value) }.getOrNull() ?: return null
        if (bytes.size != 32 || encodeBase64Url(bytes) != value) {
            return null
        }
        return bytes
    }

    fun encodeBase64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun isLowercaseSha256(value: String): Boolean = LOWERCASE_SHA256.matches(value)

    private inline fun <reified T> decode(text: String): T = try {
        json.decodeFromString<T>(text)
    } catch (exception: SerializationException) {
        throw SerializationException("document shape is invalid", exception)
    } catch (exception: IllegalArgumentException) {
        throw SerializationException("document shape is invalid", exception)
    }

    private fun validateCommon(version: Int, deviceId: String, displayName: String) {
        requireDocument(version == PROTOCOL_VERSION) { "version is unsupported" }
        requireDocument(isCanonicalUuid(deviceId)) { "device_id is not a canonical UUID" }
        requireDocument(displayName.trim().length in 1..64) { "display_name must be 1..64 characters" }
    }

    private inline fun requireDocument(condition: Boolean, reason: () -> String) {
        if (!condition) {
            throw SerializationException(reason())
        }
    }

    /**
     * Token-level pass rejecting malformed JSON, nesting above 16, duplicate object
     * properties, null values, unescaped control characters, lone surrogates, and
     * documents above the size cap — mirroring the Windows ScanStrictJson pass.
     */
    private fun scanStrict(text: String) {
        requireDocument(text.toByteArray(Charsets.UTF_8).size <= MAX_DOCUMENT_BYTES) {
            "document exceeds the pairing size limit"
        }
        com.clipsync.android.protocol.ProtocolStrictJson.scan(text, MAX_DOCUMENT_BYTES)
    }


    private const val PROTOCOL_VERSION = 1
    private val KNOWN_PLATFORMS = setOf("windows", "android")
    private val CANONICAL_UUID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
    private val BASE64_URL_256 = Regex("^[A-Za-z0-9_-]{43}$")
    private val LOWERCASE_SHA256 = Regex("^[0-9a-f]{64}$")
}

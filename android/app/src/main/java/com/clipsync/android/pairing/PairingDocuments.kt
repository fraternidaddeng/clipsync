package com.clipsync.android.pairing

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Discriminator values for the pairing documents frozen in pairing.schema.json. */
object PairingDocumentKinds {
    const val QR = "pairing_qr"
    const val CONFIRM_REQUEST = "pairing_confirm_request"
    const val CONFIRM_RESPONSE = "pairing_confirm_response"
    const val ERROR = "pairing_error"
}

object PairingErrorCodes {
    const val SCHEMA_VIOLATION = "SCHEMA_VIOLATION"
    const val TOKEN_INVALID = "PAIRING_TOKEN_INVALID"
    const val TOKEN_EXPIRED = "PAIRING_TOKEN_EXPIRED"
    const val REJECTED = "PAIRING_REJECTED"
    const val TIMEOUT = "PAIRING_TIMEOUT"

    val ALL = setOf(SCHEMA_VIOLATION, TOKEN_INVALID, TOKEN_EXPIRED, REJECTED, TIMEOUT)
}

/** The JSON object rendered inside the pairing QR code. Never contains the pair secret. */
@Serializable
data class PairingQrPayload(
    @SerialName("kind") val kind: String,
    @SerialName("version") val version: Int,
    @SerialName("hosts") val hosts: List<String>,
    @SerialName("port") val port: Int,
    @SerialName("device_id") val deviceId: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("cert_sha256") val certSha256: String,
    @SerialName("token") val token: String,
    @SerialName("expires_at_ms") val expiresAtMs: Long,
)

@Serializable
data class PairingConfirmRequest(
    @SerialName("kind") val kind: String,
    @SerialName("version") val version: Int,
    @SerialName("token") val token: String,
    @SerialName("device_id") val deviceId: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("platform") val platform: String,
)

@Serializable
data class PairingConfirmResponse(
    @SerialName("kind") val kind: String,
    @SerialName("version") val version: Int,
    @SerialName("device_id") val deviceId: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("platform") val platform: String,
    @SerialName("pair_secret") val pairSecret: String,
    @SerialName("trust_epoch") val trustEpoch: Long,
)

@Serializable
data class PairingErrorBody(
    @SerialName("kind") val kind: String,
    @SerialName("version") val version: Int,
    @SerialName("error") val error: String,
)

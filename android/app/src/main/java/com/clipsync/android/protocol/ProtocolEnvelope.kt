package com.clipsync.android.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class ProtocolEnvelope(
    val version: Int,
    val type: String,
    @SerialName("request_id") val requestId: String,
    val body: JsonObject,
)

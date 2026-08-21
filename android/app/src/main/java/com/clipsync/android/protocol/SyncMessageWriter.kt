package com.clipsync.android.protocol

import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

/**
 * Encodes protocol v1 envelopes. [SyncMessages] only parses; this writer is the
 * complementary encode path. Optional fields are omitted, never null.
 */
object SyncMessageWriter {
    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        coerceInputValues = false
        explicitNulls = false
        encodeDefaults = true
    }

    fun newRequestId(): String = UUID.randomUUID().toString()

    fun encode(
        type: String,
        requestId: String,
        body: SyncMessageBody,
        version: Int = ProtocolLimits.PROTOCOL_VERSION,
    ): String {
        val envelope = buildJsonObject {
            put("version", version)
            put("type", type)
            put("request_id", requestId)
            put("body", encodeBody(type, body))
        }
        return json.encodeToString(JsonObject.serializer(), envelope)
    }

    fun encode(
        requestId: String,
        body: SyncMessageBody,
        version: Int = ProtocolLimits.PROTOCOL_VERSION,
    ): String = encode(typeOf(body), requestId, body, version)

    fun encode(
        body: SyncMessageBody,
        version: Int = ProtocolLimits.PROTOCOL_VERSION,
    ): String = encode(newRequestId(), body, version)

    private fun typeOf(body: SyncMessageBody): String = when (body) {
        is HelloBody -> ProtocolMessageTypes.HELLO
        is ChallengeBody -> ProtocolMessageTypes.CHALLENGE
        is AuthBody -> ProtocolMessageTypes.AUTH
        is SyncStateDto -> ProtocolMessageTypes.KNOWN_VECTOR
        is WantRangesBody -> ProtocolMessageTypes.WANT_RANGES
        is ClipAnnounceBody -> ProtocolMessageTypes.CLIP_ANNOUNCE
        is ClipFetchBody -> ProtocolMessageTypes.CLIP_FETCH
        is ClipPayloadBody -> ProtocolMessageTypes.CLIP_PAYLOAD
        is ClipPayloadBeginBody -> ProtocolMessageTypes.CLIP_PAYLOAD_BEGIN
        is ClipPayloadChunkBody -> ProtocolMessageTypes.CLIP_PAYLOAD_CHUNK
        is ClipPayloadEndBody -> ProtocolMessageTypes.CLIP_PAYLOAD_END
        is AckRangesBody -> ProtocolMessageTypes.ACK_RANGES
        is ErrorBody -> ProtocolMessageTypes.ERROR
        is PingBody -> ProtocolMessageTypes.PING
        is PongBody -> ProtocolMessageTypes.PONG
    }

    private fun encodeBody(type: String, body: SyncMessageBody) = when (type) {
        ProtocolMessageTypes.HELLO -> json.encodeToJsonElement(HelloBody.serializer(), body as HelloBody)
        ProtocolMessageTypes.CHALLENGE ->
            json.encodeToJsonElement(ChallengeBody.serializer(), body as ChallengeBody)
        ProtocolMessageTypes.AUTH -> json.encodeToJsonElement(AuthBody.serializer(), body as AuthBody)
        ProtocolMessageTypes.KNOWN_VECTOR ->
            json.encodeToJsonElement(SyncStateDto.serializer(), body as SyncStateDto)
        ProtocolMessageTypes.WANT_RANGES ->
            json.encodeToJsonElement(WantRangesBody.serializer(), body as WantRangesBody)
        ProtocolMessageTypes.CLIP_ANNOUNCE ->
            json.encodeToJsonElement(ClipAnnounceBody.serializer(), body as ClipAnnounceBody)
        ProtocolMessageTypes.CLIP_FETCH ->
            json.encodeToJsonElement(ClipFetchBody.serializer(), body as ClipFetchBody)
        ProtocolMessageTypes.CLIP_PAYLOAD ->
            json.encodeToJsonElement(ClipPayloadBody.serializer(), body as ClipPayloadBody)
        ProtocolMessageTypes.CLIP_PAYLOAD_BEGIN ->
            json.encodeToJsonElement(ClipPayloadBeginBody.serializer(), body as ClipPayloadBeginBody)
        ProtocolMessageTypes.CLIP_PAYLOAD_CHUNK ->
            json.encodeToJsonElement(ClipPayloadChunkBody.serializer(), body as ClipPayloadChunkBody)
        ProtocolMessageTypes.CLIP_PAYLOAD_END ->
            json.encodeToJsonElement(ClipPayloadEndBody.serializer(), body as ClipPayloadEndBody)
        ProtocolMessageTypes.ACK_RANGES ->
            json.encodeToJsonElement(AckRangesBody.serializer(), body as AckRangesBody)
        ProtocolMessageTypes.ERROR -> json.encodeToJsonElement(ErrorBody.serializer(), body as ErrorBody)
        ProtocolMessageTypes.PING -> json.encodeToJsonElement(PingBody.serializer(), body as PingBody)
        ProtocolMessageTypes.PONG -> json.encodeToJsonElement(PongBody.serializer(), body as PongBody)
        else -> error("Unsupported message type: $type")
    }
}

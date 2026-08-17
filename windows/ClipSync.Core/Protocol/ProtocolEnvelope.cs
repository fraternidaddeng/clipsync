using System.Text.Json;
using System.Text.Json.Serialization;

namespace ClipSync.Core.Protocol;

public sealed record ProtocolEnvelope(
    [property: JsonPropertyName("version")] int Version,
    [property: JsonPropertyName("type")] string Type,
    [property: JsonPropertyName("request_id")] Guid RequestId,
    [property: JsonPropertyName("body")] JsonElement Body);

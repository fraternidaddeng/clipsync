using System.Text.Json;
using System.Text.Json.Serialization;

namespace ClipSync.Core.Protocol;

public static class ProtocolJson
{
    public static JsonSerializerOptions Options { get; } = new()
    {
        PropertyNameCaseInsensitive = false,
        UnmappedMemberHandling = JsonUnmappedMemberHandling.Disallow,
        NumberHandling = JsonNumberHandling.Strict,
        MaxDepth = ProtocolLimits.MaxJsonDepth
    };

    public static ProtocolEnvelope ParseEnvelope(string json) =>
        JsonSerializer.Deserialize<ProtocolEnvelope>(json, Options)
        ?? throw new JsonException("Protocol envelope cannot be null.");
}

namespace ClipSync.Core.Health;

public sealed record ApplicationHealth(string Status, int ProtocolVersion, string Platform)
{
    public static ApplicationHealth Create() => new("ready", 1, "windows");
}

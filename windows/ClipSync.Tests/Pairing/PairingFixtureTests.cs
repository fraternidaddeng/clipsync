using System.Text.Json;
using ClipSync.Peer.Pairing;

namespace ClipSync.Tests.Pairing;

/// <summary>
/// Consumes the shared pairing fixtures exactly like the protocol fixtures: every valid
/// document parses, every invalid one is rejected. The Kotlin client must run the same files.
/// </summary>
public sealed class PairingFixtureTests
{
    private static readonly string FixtureRoot = Path.Combine(
        AppContext.BaseDirectory,
        "protocol-fixtures",
        "pairing");

    public static TheoryData<string> ValidFixtures() => Enumerate("valid");

    public static TheoryData<string> InvalidFixtures() => Enumerate("invalid");

    [Theory]
    [MemberData(nameof(ValidFixtures))]
    public void ValidFixtureParses(string fileName)
    {
        var text = File.ReadAllText(Path.Combine(FixtureRoot, "valid", fileName));
        var (document, error) = ParseByKind(text);
        Assert.Null(error);
        Assert.NotNull(document);
    }

    [Theory]
    [MemberData(nameof(InvalidFixtures))]
    public void InvalidFixtureIsRejected(string fileName)
    {
        var text = File.ReadAllText(Path.Combine(FixtureRoot, "invalid", fileName));
        var (document, error) = ParseByKind(text);
        Assert.Null(document);
        Assert.NotNull(error);
    }

    /// <summary>Dispatches on the kind discriminator with a lenient pre-read, then parses strictly.</summary>
    private static (object? Document, string? Error) ParseByKind(string text)
    {
        string? kind;
        try
        {
            using var probe = JsonDocument.Parse(text, new JsonDocumentOptions { AllowTrailingCommas = false });
            kind = probe.RootElement.TryGetProperty("kind", out var kindElement) ? kindElement.GetString() : null;
        }
        catch (JsonException)
        {
            return (null, "malformed JSON");
        }

        return kind switch
        {
            PairingDocumentKinds.Qr => Wrap(PairingJson.ParseQrPayload(text, out var error), error),
            PairingDocumentKinds.ConfirmRequest => Wrap(
                PairingJson.ParseConfirmRequest(System.Text.Encoding.UTF8.GetBytes(text), out var error), error),
            PairingDocumentKinds.ConfirmResponse => Wrap(PairingJson.ParseConfirmResponse(text, out var error), error),
            PairingDocumentKinds.Error => Wrap(PairingJson.ParseError(text, out var error), error),
            _ => (null, "unknown kind")
        };

        static (object?, string?) Wrap(object? document, string? error) => (document, error);
    }

    private static TheoryData<string> Enumerate(string subset)
    {
        var data = new TheoryData<string>();
        foreach (var path in Directory.EnumerateFiles(Path.Combine(FixtureRoot, subset), "*.json"))
        {
            data.Add(Path.GetFileName(path));
        }

        return data;
    }
}

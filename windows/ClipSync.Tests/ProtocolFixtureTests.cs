using ClipSync.Core.Protocol;

namespace ClipSync.Tests;

public sealed class ProtocolFixtureTests
{
    public static IEnumerable<object[]> ValidEnvelopeFixtures()
    {
        var fixtureRoot = Path.Combine(AppContext.BaseDirectory, "protocol-fixtures", "valid");
        Assert.True(Directory.Exists(fixtureRoot), $"Protocol fixture directory is missing: {fixtureRoot}");

        foreach (var path in Directory.EnumerateFiles(fixtureRoot, "*.json", SearchOption.AllDirectories))
        {
            yield return new object[] { path };
        }
    }

    [Theory]
    [MemberData(nameof(ValidEnvelopeFixtures))]
    public void ValidEnvelopeFixtureParsesStrictly(string path)
    {
        var envelope = ProtocolJson.ParseEnvelope(File.ReadAllText(path));

        Assert.Equal(1, envelope.Version);
        Assert.False(string.IsNullOrWhiteSpace(envelope.Type));
        Assert.NotEqual(Guid.Empty, envelope.RequestId);
        Assert.Equal(System.Text.Json.JsonValueKind.Object, envelope.Body.ValueKind);
    }

    [Fact]
    public void ValidFixtureSetIsNotEmpty()
    {
        Assert.NotEmpty(ValidEnvelopeFixtures());
    }
}

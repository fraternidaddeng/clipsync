using ClipSync.Core.Protocol;

namespace ClipSync.Tests.Protocol;

public sealed class ProtocolReaderV2Tests
{
    private static string FixtureRoot(string subset) =>
        Path.Combine(AppContext.BaseDirectory, "protocol-fixtures-v2", subset);

    public static IEnumerable<object[]> ValidFixtures() =>
        Directory.EnumerateFiles(FixtureRoot("valid"), "*.json").Select(path => new object[] { path });

    public static IEnumerable<object[]> InvalidFixtures() =>
        Directory.EnumerateFiles(FixtureRoot("invalid"), "*.json").Select(path => new object[] { path });

    [Theory]
    [MemberData(nameof(ValidFixtures))]
    public void ValidV2FixturePassesFullPipeline(string path)
    {
        var outcome = ProtocolReaderV2.Parse(File.ReadAllText(path));

        var success = Assert.IsType<ProtocolParseOutcome.Success>(outcome);
        Assert.Equal(2, success.Version);
        Assert.Equal(Path.GetFileNameWithoutExtension(path), success.Type);
        Assert.NotEqual(Guid.Empty, success.RequestId);
    }

    [Theory]
    [MemberData(nameof(InvalidFixtures))]
    public void InvalidV2FixtureIsRejected(string path)
    {
        var outcome = ProtocolReaderV2.Parse(File.ReadAllText(path));

        var failure = Assert.IsType<ProtocolParseOutcome.Failure>(outcome);
        Assert.Contains(failure.ErrorCode, ProtocolErrorCodes.All);
    }

    [Theory]
    [MemberData(nameof(ValidFixtures))]
    public void ValidV2FixtureSurvivesWriterRoundTrip(string path)
    {
        var first = Assert.IsType<ProtocolParseOutcome.Success>(ProtocolReaderV2.Parse(File.ReadAllText(path)));

        var rewritten = ProtocolWriter.Serialize(2, first.Type, first.RequestId, first.Body);
        var second = Assert.IsType<ProtocolParseOutcome.Success>(ProtocolReaderV2.Parse(rewritten));

        Assert.Equal(first.Type, second.Type);
        Assert.Equal(first.RequestId, second.RequestId);
        Assert.Equal(rewritten, ProtocolWriter.Serialize(2, second.Type, second.RequestId, second.Body));
    }

    [Fact]
    public void V1ReaderRejectsV2Hello()
    {
        var hello = File.ReadAllText(Path.Combine(FixtureRoot("valid"), "hello.json"));
        var outcome = ProtocolReader.Parse(hello);
        var failure = Assert.IsType<ProtocolParseOutcome.Failure>(outcome);
        Assert.Equal(ProtocolErrorCodes.UnsupportedVersion, failure.ErrorCode);
    }

    [Fact]
    public void ValidFixtureSetIsNotEmpty()
    {
        Assert.NotEmpty(ValidFixtures());
        Assert.NotEmpty(InvalidFixtures());
    }
}

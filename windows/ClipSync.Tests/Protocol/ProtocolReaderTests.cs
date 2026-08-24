using System.Text.Json;
using ClipSync.Core.Protocol;

namespace ClipSync.Tests.Protocol;

public sealed class ProtocolReaderTests
{
    private static string FixtureRoot(string subset) =>
        Path.Combine(AppContext.BaseDirectory, "protocol-fixtures", subset);

    public static IEnumerable<object[]> ValidFixtures() =>
        Directory.EnumerateFiles(FixtureRoot("valid"), "*.json").Select(path => new object[] { path });

    public static IEnumerable<object[]> InvalidFixtures() =>
        Directory.EnumerateFiles(FixtureRoot("invalid"), "*.json").Select(path => new object[] { path });

    [Theory]
    [MemberData(nameof(ValidFixtures))]
    public void ValidFixturePassesFullPipeline(string path)
    {
        var outcome = ProtocolReader.Parse(File.ReadAllText(path));

        var success = Assert.IsType<ProtocolParseOutcome.Success>(outcome);
        Assert.Equal(1, success.Version);
        Assert.Equal(Path.GetFileNameWithoutExtension(path), success.Type);
        Assert.NotEqual(Guid.Empty, success.RequestId);
    }

    [Theory]
    [MemberData(nameof(InvalidFixtures))]
    public void InvalidFixtureIsRejected(string path)
    {
        var outcome = ProtocolReader.Parse(File.ReadAllText(path));

        var failure = Assert.IsType<ProtocolParseOutcome.Failure>(outcome);
        var expected = ExpectedErrors()[Path.GetFileName(path)];
        Assert.Equal(expected, failure.ErrorCode);
    }

    [Theory]
    [MemberData(nameof(ValidFixtures))]
    public void ValidFixtureSurvivesWriterRoundTrip(string path)
    {
        var first = Assert.IsType<ProtocolParseOutcome.Success>(ProtocolReader.Parse(File.ReadAllText(path)));

        var rewritten = ProtocolWriter.Serialize(first.Type, first.RequestId, first.Body);
        var second = Assert.IsType<ProtocolParseOutcome.Success>(ProtocolReader.Parse(rewritten));

        Assert.Equal(first.Type, second.Type);
        Assert.Equal(first.RequestId, second.RequestId);
        Assert.Equal(rewritten, ProtocolWriter.Serialize(second.Type, second.RequestId, second.Body));
    }

    [Fact]
    public void DuplicateEnvelopePropertyIsMalformed()
    {
        var outcome = ProtocolReader.Parse("""
            {"version":1,"version":1,"type":"ping","request_id":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa","body":{"sent_at_ms":1}}
            """);

        var failure = Assert.IsType<ProtocolParseOutcome.Failure>(outcome);
        Assert.Equal(ProtocolErrorCodes.MalformedJson, failure.ErrorCode);
    }

    [Fact]
    public void DuplicateNestedPropertyIsMalformed()
    {
        var outcome = ProtocolReader.Parse("""
            {"version":1,"type":"ping","request_id":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa","body":{"sent_at_ms":1,"sent_at_ms":2}}
            """);

        Assert.Equal(
            ProtocolErrorCodes.MalformedJson,
            Assert.IsType<ProtocolParseOutcome.Failure>(outcome).ErrorCode);
    }

    [Fact]
    public void NullValueAnywhereIsRejected()
    {
        var outcome = ProtocolReader.Parse("""
            {"version":1,"type":"clip_fetch","request_id":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa","body":{"event_ids":[null]}}
            """);

        Assert.Equal(
            ProtocolErrorCodes.SchemaViolation,
            Assert.IsType<ProtocolParseOutcome.Failure>(outcome).ErrorCode);
    }

    [Fact]
    public void UppercaseRequestIdIsRejected()
    {
        var outcome = ProtocolReader.Parse("""
            {"version":1,"type":"ping","request_id":"AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA","body":{"sent_at_ms":1}}
            """);

        Assert.Equal(
            ProtocolErrorCodes.SchemaViolation,
            Assert.IsType<ProtocolParseOutcome.Failure>(outcome).ErrorCode);
    }

    [Fact]
    public void NilRequestIdIsRejected()
    {
        var outcome = ProtocolReader.Parse("""
            {"version":1,"type":"ping","request_id":"00000000-0000-0000-0000-000000000000","body":{"sent_at_ms":1}}
            """);

        Assert.Equal(
            ProtocolErrorCodes.SchemaViolation,
            Assert.IsType<ProtocolParseOutcome.Failure>(outcome).ErrorCode);
    }

    [Fact]
    public void FractionalVersionIsRejected()
    {
        var outcome = ProtocolReader.Parse("""
            {"version":1.0,"type":"ping","request_id":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa","body":{"sent_at_ms":1}}
            """);

        Assert.Equal(
            ProtocolErrorCodes.SchemaViolation,
            Assert.IsType<ProtocolParseOutcome.Failure>(outcome).ErrorCode);
    }

    [Fact]
    public void UnknownMessageTypeIsRejected()
    {
        var outcome = ProtocolReader.Parse("""
            {"version":1,"type":"settings_write","request_id":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa","body":{}}
            """);

        Assert.Equal(
            ProtocolErrorCodes.SchemaViolation,
            Assert.IsType<ProtocolParseOutcome.Failure>(outcome).ErrorCode);
    }

    [Fact]
    public void MissingRequiredBodyFieldIsRejected()
    {
        var outcome = ProtocolReader.Parse("""
            {"version":1,"type":"ping","request_id":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa","body":{}}
            """);

        Assert.Equal(
            ProtocolErrorCodes.SchemaViolation,
            Assert.IsType<ProtocolParseOutcome.Failure>(outcome).ErrorCode);
    }

    [Fact]
    public void NestingDeeperThanSixteenIsMalformed()
    {
        var open = string.Concat(Enumerable.Repeat("""{"a":""", 17));
        var close = new string('}', 17);
        var outcome = ProtocolReader.Parse(open + "1" + close);

        Assert.Equal(
            ProtocolErrorCodes.MalformedJson,
            Assert.IsType<ProtocolParseOutcome.Failure>(outcome).ErrorCode);
    }

    [Fact]
    public void ChallengeNonceMustDecodeToThirtyTwoBytes()
    {
        // 43 base64url characters whose trailing bits are not zero decode ambiguously and are rejected.
        var invalidTail = new string('A', 42) + "B";
        var outcome = ProtocolReader.Parse($$"""
            {"version":1,"type":"challenge","request_id":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa","body":{
              "algorithm":"hmac-sha256",
              "nonce":"{{invalidTail}}",
              "challenger_device_id":"11111111-1111-4111-8111-111111111111",
              "responder_device_id":"22222222-2222-4222-8222-222222222222",
              "trust_epoch":1,
              "expires_at_ms":1
            }
            }
            """);

        Assert.Equal(
            ProtocolErrorCodes.SchemaViolation,
            Assert.IsType<ProtocolParseOutcome.Failure>(outcome).ErrorCode);
    }

    [Fact]
    public void AnnounceAboveLimitIsRejected()
    {
        var body = new ClipAnnounceBody
        {
            Clips = Enumerable.Range(1, 257).Select(seq => new ClipHeaderDto
            {
                EventId = $"{seq:x8}-1111-4111-8111-111111111111",
                OriginDeviceId = "11111111-1111-4111-8111-111111111111",
                OriginSeq = seq,
                Availability = ClipAvailability.Unavailable,
                Reason = ClipUnavailableReasons.Deleted
            }).ToArray()
        };

        var frame = ProtocolWriter.Serialize(ProtocolMessageTypes.ClipAnnounce, Guid.NewGuid(), body);
        var outcome = ProtocolReader.Parse(frame);

        Assert.Equal(
            ProtocolErrorCodes.SchemaViolation,
            Assert.IsType<ProtocolParseOutcome.Failure>(outcome).ErrorCode);
    }

    [Fact]
    public void WriterOmitsOptionalNullFields()
    {
        var frame = ProtocolWriter.Serialize(
            ProtocolMessageTypes.Error,
            Guid.NewGuid(),
            new ErrorBody { Code = ProtocolErrorCodes.RateLimited, Retryable = true });

        Assert.DoesNotContain("failed_type", frame, StringComparison.Ordinal);
        Assert.DoesNotContain("null", frame, StringComparison.Ordinal);
        Assert.IsType<ProtocolParseOutcome.Success>(ProtocolReader.Parse(frame));
    }

    [Fact]
    public void PayloadBatchOverOneMebibyteIsRejected()
    {
        var chunk = new string('x', 600_000);
        var body = new ClipPayloadBody
        {
            Clips = Enumerable.Range(1, 2).Select(seq => new ClipPayloadItemDto
            {
                EventId = $"{seq:x8}-1111-4111-8111-111111111111",
                OriginDeviceId = "11111111-1111-4111-8111-111111111111",
                OriginSeq = seq,
                Kind = "text",
                Content = chunk,
                ContentHash = ProtocolValidation.ComputeContentHash(chunk),
                Utf8Bytes = 600_000,
                CreatedAtMs = 1
            }).ToArray()
        };

        var outcome = ProtocolReader.Parse(ProtocolWriter.Serialize(ProtocolMessageTypes.ClipPayload, Guid.NewGuid(), body));

        Assert.Equal(
            ProtocolErrorCodes.SchemaViolation,
            Assert.IsType<ProtocolParseOutcome.Failure>(outcome).ErrorCode);
    }

    [Fact]
    public void AnnounceUtf8BytesAboveOneMebibyteIsRejected()
    {
        var outcome = ProtocolReader.Parse(ProtocolWriter.Serialize(
            ProtocolMessageTypes.ClipAnnounce,
            Guid.NewGuid(),
            new ClipAnnounceBody { Clips = [AvailableHeader(ProtocolLimits.MaxContentUtf8Bytes + 1)] }));

        Assert.Equal(
            ProtocolErrorCodes.SchemaViolation,
            Assert.IsType<ProtocolParseOutcome.Failure>(outcome).ErrorCode);
    }

    [Fact]
    public void AnnounceUtf8BytesAtOneMebibyteIsAccepted()
    {
        var outcome = ProtocolReader.Parse(ProtocolWriter.Serialize(
            ProtocolMessageTypes.ClipAnnounce,
            Guid.NewGuid(),
            new ClipAnnounceBody { Clips = [AvailableHeader(ProtocolLimits.MaxContentUtf8Bytes)] }));

        Assert.IsType<ProtocolParseOutcome.Success>(outcome);
    }

    [Fact]
    public void SinglePayloadClipAboveOneMebibyteIsRejected()
    {
        var oversized = new string('x', ProtocolLimits.MaxContentUtf8Bytes + 1);
        var body = new ClipPayloadBody
        {
            Clips =
            [
                new ClipPayloadItemDto
                {
                    EventId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                    OriginDeviceId = "11111111-1111-4111-8111-111111111111",
                    OriginSeq = 1,
                    Kind = "text",
                    Content = oversized,
                    ContentHash = ProtocolValidation.ComputeContentHash(oversized),
                    Utf8Bytes = oversized.Length,
                    CreatedAtMs = 1
                }
            ]
        };

        var outcome = ProtocolReader.Parse(ProtocolWriter.Serialize(ProtocolMessageTypes.ClipPayload, Guid.NewGuid(), body));

        Assert.Equal(
            ProtocolErrorCodes.SchemaViolation,
            Assert.IsType<ProtocolParseOutcome.Failure>(outcome).ErrorCode);
    }

    private static Dictionary<string, string> ExpectedErrors()
    {
        var path = Path.Combine(AppContext.BaseDirectory, "protocol-fixtures", "expected_errors.json");
        using var document = JsonDocument.Parse(File.ReadAllText(path));
        return document.RootElement.EnumerateObject()
            .ToDictionary(property => property.Name, property => property.Value.GetString()!, StringComparer.Ordinal);
    }

    private static ClipHeaderDto AvailableHeader(long utf8Bytes) => new()
    {
        EventId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
        OriginDeviceId = "11111111-1111-4111-8111-111111111111",
        OriginSeq = 1,
        Availability = ClipAvailability.Available,
        Kind = "text",
        ContentHash = new string('a', 64),
        Utf8Bytes = utf8Bytes,
        CreatedAtMs = 1
    };
}

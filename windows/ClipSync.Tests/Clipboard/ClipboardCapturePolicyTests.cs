using ClipSync.Core.Clipboard;
using ClipSync.Core.Media;

namespace ClipSync.Tests.Clipboard;

public sealed class ClipboardCapturePolicyTests
{
    private static readonly DateTimeOffset BaseTime = DateTimeOffset.FromUnixTimeMilliseconds(1_700_000_000_000);
    private static readonly string[] BlockedProcesses = ["KeePass", "bank-*"];

    [Fact]
    public void EvaluatePreservesUnicodeAndLineEndings()
    {
        var text = "第一行\r\nsecond line\nemoji 😀";
        var policy = new ClipboardCapturePolicy();

        var result = policy.Evaluate(new ClipboardCandidate(text, "notepad", BaseTime));

        var accepted = Assert.IsType<CaptureDecision.Accept>(result);
        Assert.Equal(text, accepted.Content.Text);
        Assert.Equal(System.Text.Encoding.UTF8.GetByteCount(text), accepted.Content.Utf8Bytes);
    }

    [Theory]
    [InlineData(null)]
    [InlineData("")]
    public void EvaluateRejectsMissingOrEmptyText(string? text)
    {
        var result = new ClipboardCapturePolicy().Evaluate(new ClipboardCandidate(text, null, BaseTime));

        Assert.Equal(CaptureRejectionReason.EmptyText, Assert.IsType<CaptureDecision.Reject>(result).Reason);
    }

    [Fact]
    public void EvaluateAcceptsExactlyOneMebibyteOfUtf8()
    {
        var text = new string('a', ClipboardCapturePolicy.MaximumUtf8Bytes);

        var result = new ClipboardCapturePolicy().Evaluate(new ClipboardCandidate(text, null, BaseTime));

        Assert.IsType<CaptureDecision.Accept>(result);
    }

    [Fact]
    public void EvaluateRejectsMoreThanOneMebibyteOfUtf8WithoutTruncating()
    {
        var text = new string('a', ClipboardCapturePolicy.MaximumUtf8Bytes + 1);

        var result = new ClipboardCapturePolicy().Evaluate(new ClipboardCandidate(text, null, BaseTime));

        Assert.Equal(CaptureRejectionReason.TooLarge, Assert.IsType<CaptureDecision.Reject>(result).Reason);
    }

    [Fact]
    public void EvaluateDeduplicatesSameContentInsideTwoSecondWindow()
    {
        var policy = new ClipboardCapturePolicy();

        Assert.IsType<CaptureDecision.Accept>(policy.Evaluate(new ClipboardCandidate("same", null, BaseTime)));
        var duplicate = policy.Evaluate(new ClipboardCandidate("same", null, BaseTime.AddMilliseconds(1_999)));
        var outsideWindow = policy.Evaluate(new ClipboardCandidate("same", null, BaseTime.AddSeconds(2)));

        Assert.Equal(CaptureRejectionReason.Duplicate, Assert.IsType<CaptureDecision.Reject>(duplicate).Reason);
        Assert.IsType<CaptureDecision.Accept>(outsideWindow);
    }

    [Fact]
    public void EvaluateRejectsPausedPrivateAndBlacklistedSources()
    {
        var paused = new ClipboardCapturePolicy(new CaptureSettings(IsPaused: true));
        var privateMode = new ClipboardCapturePolicy(new CaptureSettings(IsPrivateMode: true));
        var blocked = new ClipboardCapturePolicy(new CaptureSettings(BlockedSourceProcesses: BlockedProcesses));

        Assert.Equal(CaptureRejectionReason.Paused, Assert.IsType<CaptureDecision.Reject>(paused.Evaluate(new("x", null, BaseTime))).Reason);
        Assert.Equal(CaptureRejectionReason.PrivateMode, Assert.IsType<CaptureDecision.Reject>(privateMode.Evaluate(new("x", null, BaseTime))).Reason);
        Assert.Equal(CaptureRejectionReason.SourceBlocked, Assert.IsType<CaptureDecision.Reject>(blocked.Evaluate(new("x", "keepass.exe", BaseTime))).Reason);
        Assert.Equal(CaptureRejectionReason.SourceBlocked, Assert.IsType<CaptureDecision.Reject>(blocked.Evaluate(new("x", "bank-client", BaseTime))).Reason);
    }

    [Fact]
    public void SuppressionConsumesOneMatchingWriteAndDoesNotSuppressOtherText()
    {
        var policy = new ClipboardCapturePolicy();
        policy.SuppressNextWrite("remote", BaseTime);

        Assert.IsType<CaptureDecision.Accept>(policy.Evaluate(new("local", null, BaseTime.AddMilliseconds(1))));
        Assert.Equal(
            CaptureRejectionReason.SuppressedWrite,
            Assert.IsType<CaptureDecision.Reject>(policy.Evaluate(new("remote", null, BaseTime.AddMilliseconds(2)))).Reason);
        Assert.IsType<CaptureDecision.Accept>(policy.Evaluate(new("remote", null, BaseTime.AddSeconds(3))));
    }

    [Fact]
    public void DuplicateNotificationDoesNotConsumeSuppressionForDifferentText()
    {
        var policy = new ClipboardCapturePolicy();
        policy.SuppressNextWrite("remote", BaseTime);

        Assert.IsType<CaptureDecision.Accept>(policy.Evaluate(new("local", null, BaseTime.AddMilliseconds(1))));
        Assert.Equal(
            CaptureRejectionReason.Duplicate,
            Assert.IsType<CaptureDecision.Reject>(policy.Evaluate(new("local", null, BaseTime.AddMilliseconds(2)))).Reason);
        Assert.Equal(
            CaptureRejectionReason.SuppressedWrite,
            Assert.IsType<CaptureDecision.Reject>(policy.Evaluate(new("remote", null, BaseTime.AddMilliseconds(3)))).Reason);
    }

    [Fact]
    public void ImageCandidateIsRejectedWhenImageSyncIsOff()
    {
        var policy = new ClipboardCapturePolicy();
        var png = Convert.FromHexString("89504E470D0A1A0A0000000D49484452000000010000000108060000001F15C4890000000A49444154789C63000100000500010D0A2DB40000000049454E44AE426082");

        var imageOnly = policy.Evaluate(new ClipboardCandidate(null, null, BaseTime, png, "image/png"));
        Assert.Equal(CaptureRejectionReason.UnsupportedMedia, Assert.IsType<CaptureDecision.Reject>(imageOnly).Reason);

        var mixed = policy.Evaluate(new ClipboardCandidate("fallback text", null, BaseTime, png, "image/png"));
        var accepted = Assert.IsType<CaptureDecision.Accept>(mixed);
        Assert.Equal("fallback text", accepted.Content.Text);

        var enabled = new ClipboardCapturePolicy(new CaptureSettings(ImageSyncEnabled: true));
        Assert.IsType<CaptureDecision.AcceptImage>(enabled.Evaluate(new ClipboardCandidate(null, null, BaseTime, png, "image/png")));
    }

    [Fact]
    public void AppliedImageEchoIsSuppressedByPixelDigestWhenTheContentHashChanged()
    {
        // A JPEG applied to the Windows clipboard has no PNG clipboard format, so our own
        // listener reads it back as a DIB→PNG re-encode: different content hash, same
        // pixels. The apply sites (remote auto-apply, history copy) arm suppression with
        // the stored hash AND the pixel digest — the digest is what recognizes that echo.
        var policy = new ClipboardCapturePolicy(new CaptureSettings(ImageSyncEnabled: true));
        byte[] bgra = [255, 0, 0, 255, 0, 255, 0, 255];
        var echoPng = ImageCodec.EncodePngBgra(2, 1, bgra);
        var pixelDigest = ImageCodec.HashBytes(bgra);
        var storedJpegHash = new string('a', 64);
        Assert.NotEqual(storedJpegHash, ImageCodec.HashBytes(echoPng));

        policy.SuppressNextImage(storedJpegHash, BaseTime, pixelDigest);
        var echo = policy.Evaluate(new ClipboardCandidate(null, null, BaseTime.AddMilliseconds(1), echoPng, "image/png", pixelDigest));
        Assert.Equal(CaptureRejectionReason.SuppressedWrite, Assert.IsType<CaptureDecision.Reject>(echo).Reason);

        // One-shot: the same pixels copied again later are a genuine new capture.
        var later = policy.Evaluate(new ClipboardCandidate(null, null, BaseTime.AddSeconds(3), echoPng, "image/png", pixelDigest));
        Assert.IsType<CaptureDecision.AcceptImage>(later);
    }

    [Fact]
    public void HashOnlyImageSuppressionMissesTheReEncodedEcho()
    {
        // Documents why SuppressNextImage must be armed with the pixel digest: hash-only
        // arming (the pre-fix apply sites) lets the DIB→PNG echo through as a brand-new
        // local capture that would sync straight back to the phone.
        var policy = new ClipboardCapturePolicy(new CaptureSettings(ImageSyncEnabled: true));
        byte[] bgra = [255, 0, 0, 255, 0, 255, 0, 255];
        var echoPng = ImageCodec.EncodePngBgra(2, 1, bgra);
        var pixelDigest = ImageCodec.HashBytes(bgra);
        var storedJpegHash = new string('a', 64);

        policy.SuppressNextImage(storedJpegHash, BaseTime);
        var echo = policy.Evaluate(new ClipboardCandidate(null, null, BaseTime.AddMilliseconds(1), echoPng, "image/png", pixelDigest));

        Assert.IsType<CaptureDecision.AcceptImage>(echo);
    }

    [Fact]
    public void OneHundredRemoteWritesDoNotLoopBack()
    {
        var policy = new ClipboardCapturePolicy();

        for (var index = 0; index < 100; index++)
        {
            var text = $"remote-{index}";
            var at = BaseTime.AddSeconds(index * 3);
            policy.SuppressNextWrite(text, at);

            var result = policy.Evaluate(new(text, null, at.AddMilliseconds(1)));

            Assert.Equal(CaptureRejectionReason.SuppressedWrite, Assert.IsType<CaptureDecision.Reject>(result).Reason);
        }
    }
}

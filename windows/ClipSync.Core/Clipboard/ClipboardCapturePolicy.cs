using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;
using ClipSync.Core.Media;

namespace ClipSync.Core.Clipboard;

public sealed class ClipboardCapturePolicy
{
    public const int MaximumUtf8Bytes = 1024 * 1024;

    private static readonly TimeSpan DuplicateWindow = TimeSpan.FromSeconds(2);
    private static readonly TimeSpan SuppressionWindow = TimeSpan.FromSeconds(5);
    private readonly List<Suppression> suppressions = new();
    private CaptureSettings settings;
    private string? lastAcceptedHash;
    private DateTimeOffset lastAcceptedAt;

    public ClipboardCapturePolicy(CaptureSettings? settings = null)
    {
        this.settings = settings ?? new CaptureSettings();
    }

    public CaptureSettings Settings => settings;

    public void UpdateSettings(CaptureSettings value) => settings = value;

    public void SuppressNextWrite(string text, DateTimeOffset at)
    {
        ArgumentNullException.ThrowIfNull(text);
        PurgeSuppressions(at);
        suppressions.Add(new Suppression(Hash(text), at + SuppressionWindow));
    }

    public void SuppressNextImage(string contentHash, DateTimeOffset at, string? pixelDigest = null)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(contentHash);
        PurgeSuppressions(at);
        suppressions.Add(new Suppression(contentHash, at + SuppressionWindow, pixelDigest));
    }

    public CaptureDecision Evaluate(ClipboardCandidate candidate)
    {
        if (settings.IsPaused)
        {
            return new CaptureDecision.Reject(CaptureRejectionReason.Paused);
        }

        if (settings.IsPrivateMode)
        {
            return new CaptureDecision.Reject(CaptureRejectionReason.PrivateMode);
        }

        if (IsSourceBlocked(candidate.SourceProcess))
        {
            return new CaptureDecision.Reject(CaptureRejectionReason.SourceBlocked);
        }

        PurgeSuppressions(candidate.CapturedAt);

        if (candidate.ImageBytes is { Length: > 0 })
        {
            if (!settings.ImageSyncEnabled)
            {
                if (string.IsNullOrEmpty(candidate.Text))
                {
                    return new CaptureDecision.Reject(CaptureRejectionReason.UnsupportedMedia);
                }
            }
            else
            {
                var imageDecision = EvaluateImage(candidate);
                if (imageDecision is CaptureDecision.AcceptImage or CaptureDecision.Reject
                    {
                        Reason: CaptureRejectionReason.Duplicate
                        or CaptureRejectionReason.SuppressedWrite
                        or CaptureRejectionReason.TooLarge
                    })
                {
                    return imageDecision;
                }

                if (imageDecision is CaptureDecision.Reject
                    && string.IsNullOrEmpty(candidate.Text))
                {
                    return imageDecision;
                }
            }
        }

        if (string.IsNullOrEmpty(candidate.Text))
        {
            return new CaptureDecision.Reject(CaptureRejectionReason.EmptyText);
        }

        var bytes = Encoding.UTF8.GetByteCount(candidate.Text);
        if (bytes > MaximumUtf8Bytes)
        {
            return new CaptureDecision.Reject(CaptureRejectionReason.TooLarge);
        }

        var hash = Hash(candidate.Text);
        var suppressionIndex = suppressions.FindIndex(item => item.Hash == hash);
        if (suppressionIndex >= 0)
        {
            suppressions.RemoveAt(suppressionIndex);
            return new CaptureDecision.Reject(CaptureRejectionReason.SuppressedWrite);
        }

        if (hash == lastAcceptedHash && candidate.CapturedAt - lastAcceptedAt < DuplicateWindow)
        {
            return new CaptureDecision.Reject(CaptureRejectionReason.Duplicate);
        }

        lastAcceptedHash = hash;
        lastAcceptedAt = candidate.CapturedAt;
        return new CaptureDecision.Accept(new AcceptedClipboardContent(
            candidate.Text,
            hash,
            bytes,
            NormalizeSource(candidate.SourceProcess),
            candidate.CapturedAt));
    }

    private CaptureDecision EvaluateImage(ClipboardCandidate candidate)
    {
        var inspect = ImageCodec.TryInspect(candidate.ImageBytes.AsSpan(), out var image);
        if (inspect == ImageCodecError.TooLarge)
        {
            return new CaptureDecision.Reject(CaptureRejectionReason.TooLarge);
        }

        if (inspect != ImageCodecError.Ok || image is null)
        {
            return new CaptureDecision.Reject(
                inspect == ImageCodecError.UnsupportedMedia
                    ? CaptureRejectionReason.UnsupportedMedia
                    : CaptureRejectionReason.DecodeFailed);
        }

        if (!string.IsNullOrEmpty(candidate.ImageMimeType)
            && !string.Equals(candidate.ImageMimeType, image.MimeType, StringComparison.Ordinal))
        {
            return new CaptureDecision.Reject(CaptureRejectionReason.UnsupportedMedia);
        }

        var suppressionIndex = suppressions.FindIndex(item =>
            item.Hash == image.ContentHash
            || (candidate.PixelDigest is not null && item.PixelDigest == candidate.PixelDigest));
        if (suppressionIndex >= 0)
        {
            suppressions.RemoveAt(suppressionIndex);
            return new CaptureDecision.Reject(CaptureRejectionReason.SuppressedWrite);
        }

        if ((image.ContentHash == lastAcceptedHash
                || (candidate.PixelDigest is not null && candidate.PixelDigest == lastAcceptedHash))
            && candidate.CapturedAt - lastAcceptedAt < DuplicateWindow)
        {
            return new CaptureDecision.Reject(CaptureRejectionReason.Duplicate);
        }

        lastAcceptedHash = candidate.PixelDigest ?? image.ContentHash;
        lastAcceptedAt = candidate.CapturedAt;
        return new CaptureDecision.AcceptImage(new AcceptedImageContent(
            candidate.ImageBytes!,
            image.ContentHash,
            image.MimeType,
            image.PixelWidth,
            image.PixelHeight,
            NormalizeSource(candidate.SourceProcess),
            candidate.CapturedAt,
            candidate.PixelDigest));
    }

    private bool IsSourceBlocked(string? sourceProcess)
    {
        var normalized = NormalizeSource(sourceProcess);
        if (normalized is null || settings.BlockedSourceProcesses is null)
        {
            return false;
        }

        return settings.BlockedSourceProcesses.Any(pattern => WildcardMatches(normalized, NormalizeSource(pattern) ?? string.Empty));
    }

    private static bool WildcardMatches(string value, string pattern)
    {
        var expression = "^" + Regex.Escape(pattern).Replace("\\*", ".*", StringComparison.Ordinal) + "$";
        return Regex.IsMatch(value, expression, RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);
    }

    private static string? NormalizeSource(string? value)
    {
        if (string.IsNullOrWhiteSpace(value))
        {
            return null;
        }

        var trimmed = value.Trim();
        return trimmed.EndsWith(".exe", StringComparison.OrdinalIgnoreCase) ? trimmed[..^4] : trimmed;
    }

    private void PurgeSuppressions(DateTimeOffset now) => suppressions.RemoveAll(item => item.ExpiresAt <= now);

    private static string Hash(string text) => Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(text))).ToLowerInvariant();

    private sealed record Suppression(string Hash, DateTimeOffset ExpiresAt, string? PixelDigest = null);
}

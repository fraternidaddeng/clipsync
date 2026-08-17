using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;

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
        PurgeSuppressions(candidate.CapturedAt);
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

    private sealed record Suppression(string Hash, DateTimeOffset ExpiresAt);
}

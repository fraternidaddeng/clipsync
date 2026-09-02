using System.Globalization;
using System.Text.RegularExpressions;

namespace ClipSync.Core.Update;

/// <summary>
/// Product version used by the in-app updater. Matches the release-tag scheme
/// documented in <c>scripts/package-android.ps1</c>: <c>X.Y.Z</c> or
/// <c>X.Y.Z-rc.N</c>, with an optional leading <c>v</c> and optional
/// <c>+metadata</c> suffix (assembly informational versions).
/// </summary>
/// <remarks>
/// Rank is <c>major×1_000_000 + minor×10_000 + patch×100 + (N for rc.N, 99 for final)</c>
/// so every rc sorts below its final release and above the previous patch. The
/// same numbers are what a tagged APK gets as <c>versionCode</c>.
/// </remarks>
public readonly record struct AppVersion(int Major, int Minor, int Patch, int PreReleaseOffset)
{
    public const int FinalOffset = 99;

    private static readonly Regex Pattern = new(
        @"^v?(\d+)\.(\d+)\.(\d+)(?:-rc\.(\d+))?(?:\+.*)?$",
        RegexOptions.CultureInvariant | RegexOptions.IgnoreCase);

    /// <summary>True when this is a final (non-rc) version.</summary>
    public bool IsFinal => PreReleaseOffset == FinalOffset;

    /// <summary>Monotonic rank used for upgrade decisions. Higher is newer.</summary>
    public int Rank =>
        checked(Major * 1_000_000 + Minor * 10_000 + Patch * 100 + PreReleaseOffset);

    public string ToDisplayString() =>
        IsFinal
            ? string.Create(CultureInfo.InvariantCulture, $"{Major}.{Minor}.{Patch}")
            : string.Create(CultureInfo.InvariantCulture, $"{Major}.{Minor}.{Patch}-rc.{PreReleaseOffset}");

    public static bool TryParse(string? raw, out AppVersion version)
    {
        version = default;
        if (string.IsNullOrWhiteSpace(raw))
        {
            return false;
        }

        var match = Pattern.Match(raw.Trim());
        if (!match.Success)
        {
            return false;
        }

        if (!int.TryParse(match.Groups[1].Value, NumberStyles.None, CultureInfo.InvariantCulture, out var major)
            || !int.TryParse(match.Groups[2].Value, NumberStyles.None, CultureInfo.InvariantCulture, out var minor)
            || !int.TryParse(match.Groups[3].Value, NumberStyles.None, CultureInfo.InvariantCulture, out var patch))
        {
            return false;
        }

        if (major > 2099 || minor > 99 || patch > 99)
        {
            return false;
        }

        var offset = FinalOffset;
        if (match.Groups[4].Success)
        {
            if (!int.TryParse(match.Groups[4].Value, NumberStyles.None, CultureInfo.InvariantCulture, out var rc)
                || rc is < 1 or > 98)
            {
                return false;
            }

            offset = rc;
        }

        version = new AppVersion(major, minor, patch, offset);
        return true;
    }

    public static AppVersion Parse(string raw)
    {
        if (!TryParse(raw, out var version))
        {
            throw new FormatException($"Unsupported app version '{raw}'. Expected X.Y.Z or X.Y.Z-rc.N.");
        }

        return version;
    }

    /// <summary>
    /// Compares two display/tag strings. Unparseable values lose to parseable ones
    /// so a broken local stamp still offers a real GitHub release.
    /// </summary>
    public static int Compare(string? left, string? right)
    {
        var leftOk = TryParse(left, out var leftVersion);
        var rightOk = TryParse(right, out var rightVersion);
        return (leftOk, rightOk) switch
        {
            (false, false) => string.Compare(left, right, StringComparison.OrdinalIgnoreCase),
            (false, true) => -1,
            (true, false) => 1,
            (true, true) => leftVersion.Rank.CompareTo(rightVersion.Rank),
        };
    }
}

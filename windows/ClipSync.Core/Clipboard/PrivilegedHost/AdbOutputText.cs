namespace ClipSync.Core.Clipboard.PrivilegedHost;

/// <summary>Tiny shared helpers for reading adb's captured output as honest facts.</summary>
internal static class AdbOutputText
{
    /// <summary>The first non-blank line of a captured stream, trimmed; null when there is none.</summary>
    internal static string? FirstNonEmptyLine(string? text)
    {
        if (string.IsNullOrWhiteSpace(text))
        {
            return null;
        }

        foreach (var line in text.Replace("\r\n", "\n", StringComparison.Ordinal).Split('\n'))
        {
            var trimmed = line.Trim();
            if (trimmed.Length > 0)
            {
                return trimmed;
            }
        }

        return null;
    }
}

namespace ClipSync.App.Tray;

/// <summary>
/// The four charter tray states. All four render the same polyline mark; only the
/// stroke treatment changes (fill/dash/dot), never the shape (charter rule 12.7).
/// </summary>
internal enum TrayState
{
    /// <summary>Normal operation: the solid polyline — content is flowing.</summary>
    Flow,

    /// <summary>Needs the user: same polyline with an ochre dot lit on the peak vertex.</summary>
    Attention,

    /// <summary>Capture paused: same stroke with amplitude zero — a flat line, not an error.</summary>
    Paused,

    /// <summary>Private mode: same polyline dashed — the trace is not recorded.</summary>
    Private,
}

/// <summary>
/// Maps application state to a tray state. Priority order:
/// private &gt; paused &gt; attention &gt; flow. Private beats paused per the charter
/// ("private is the stronger promise"); both beat attention because they are
/// deliberate user choices, and the attention dot is designed to sit on the peak
/// of the flowing polyline, which the flat/paused stroke does not have.
/// </summary>
internal static class TrayStateMapper
{
    public static TrayState Map(bool isPrivateMode, bool isPaused, bool needsAttention)
    {
        if (isPrivateMode)
        {
            return TrayState.Private;
        }

        if (isPaused)
        {
            return TrayState.Paused;
        }

        return needsAttention ? TrayState.Attention : TrayState.Flow;
    }
}

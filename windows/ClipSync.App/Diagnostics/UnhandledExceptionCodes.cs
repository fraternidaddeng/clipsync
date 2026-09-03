namespace ClipSync.App.Diagnostics;

/// <summary>
/// Maps the three last-resort exception sinks to diagnostics codes. Only the exception
/// type name ever leaves here: a message or stack trace could carry clipboard text, and
/// the diagnostics log promises codes and timestamps only.
/// </summary>
internal static class UnhandledExceptionCodes
{
    private const string UnknownTypeName = "Unknown";

    /// <summary>An exception that escaped a WPF dispatcher frame (UI thread).</summary>
    public static string ForDispatcher(Exception exception) =>
        $"unhandled_ui_{TypeNameOf(exception)}";

    /// <summary>
    /// The AppDomain-level sink; the runtime hands over an <see cref="object"/> because
    /// non-CLS code may throw values that are not exceptions.
    /// </summary>
    public static string ForDomain(object? exceptionObject) =>
        $"unhandled_fatal_{TypeNameOf(exceptionObject as Exception)}";

    /// <summary>A faulted task nobody awaited; the innermost cause names the code.</summary>
    public static string ForUnobservedTask(AggregateException? exception) =>
        $"unobserved_task_{TypeNameOf(Innermost(exception))}";

    private static Exception? Innermost(AggregateException? exception)
    {
        if (exception is null)
        {
            return null;
        }

        // Flatten() collapses nested aggregates, so the first leaf is a real cause.
        var leaf = exception.Flatten().InnerExceptions.FirstOrDefault();
        return leaf ?? exception;
    }

    private static string TypeNameOf(Exception? exception) =>
        exception?.GetType().Name ?? UnknownTypeName;
}

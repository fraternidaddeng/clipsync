namespace ClipSync.App.Clipboard;

public sealed class ClipboardTextChangedEventArgs(
    string text,
    string? sourceProcess,
    DateTimeOffset capturedAt) : EventArgs
{
    public string Text { get; } = text;

    public string? SourceProcess { get; } = sourceProcess;

    public DateTimeOffset CapturedAt { get; } = capturedAt;
}

public enum ClipboardAdapterOperation
{
    Read,
    Write,
    NotifySubscriber
}

public sealed class ClipboardAdapterFaultEventArgs(
    ClipboardAdapterOperation operation,
    Exception exception) : EventArgs
{
    public ClipboardAdapterOperation Operation { get; } = operation;

    public string ErrorType { get; } = exception.GetType().Name;

    // Kept for existing diagnostics consumers, but deliberately does not expose
    // the original exception message or captured clipboard content.
    public Exception Exception { get; } = new ClipboardAdapterException(exception.GetType().Name);
}

public sealed class ClipboardAdapterException(string errorType)
    : Exception($"Clipboard adapter operation failed ({errorType}).");

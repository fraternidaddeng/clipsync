using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Logging.Abstractions;

namespace ClipSync.App.Diagnostics;

/// <summary>
/// The <see cref="ILoggerFactory"/> handed to the Peer components (<c>PeerServer</c>,
/// <c>PairingService</c>). Every admitted event becomes one <see cref="LocalDiagnostics"/>
/// code via <see cref="DiagnosticsLogCodes"/>; categories outside <c>ClipSync.*</c> get a
/// null logger so framework chatter never reaches the ring buffer.
/// </summary>
internal sealed class DiagnosticsLoggerFactory : ILoggerFactory
{
    public static DiagnosticsLoggerFactory Instance { get; } = new();

    private DiagnosticsLoggerFactory()
    {
    }

    public ILogger CreateLogger(string categoryName) =>
        DiagnosticsLogCodes.IsAdmittedCategory(categoryName)
            ? new DiagnosticsLogger(categoryName)
            : NullLogger.Instance;

    /// <summary>The diagnostics ring buffer is the one fixed sink; nothing composes on top of it.</summary>
    public void AddProvider(ILoggerProvider provider)
    {
    }

    public void Dispose()
    {
    }
}

/// <summary>
/// Records Information-and-above events as diagnostics codes. Debug events (per-frame
/// message traces) stay out: the 200-entry ring buffer must keep the pairing and session
/// evidence, not be flushed by chatter.
/// </summary>
internal sealed class DiagnosticsLogger(string category) : ILogger
{
    private const LogLevel MinimumLevel = LogLevel.Information;

    public IDisposable? BeginScope<TState>(TState state) where TState : notnull => null;

    public bool IsEnabled(LogLevel logLevel) => logLevel >= MinimumLevel && logLevel != LogLevel.None;

    public void Log<TState>(
        LogLevel logLevel,
        EventId eventId,
        TState state,
        Exception? exception,
        Func<TState, Exception?, string> formatter)
    {
        if (!IsEnabled(logLevel))
        {
            return;
        }

        var code = DiagnosticsLogCodes.For(
            category,
            eventId,
            state as IReadOnlyList<KeyValuePair<string, object?>>,
            exception);
        if (code is not null)
        {
            LocalDiagnostics.Write(code);
        }
    }
}

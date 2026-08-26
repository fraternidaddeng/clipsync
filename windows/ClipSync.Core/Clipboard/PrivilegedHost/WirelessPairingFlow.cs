namespace ClipSync.Core.Clipboard.PrivilegedHost;

/// <summary>
/// Where the wireless-pairing assistant stands right now. One linear happy path —
/// show QR (or type a code) → pair → connect — with a single honest failure stage;
/// after <see cref="Connected"/> the existing USB-path probe/start takes over.
/// </summary>
public enum WirelessPairingStage
{
    /// <summary>Nothing in flight; the section only explains.</summary>
    Idle,

    /// <summary>A QR is on screen and the PC is watching mDNS for the phone's pairing announcement.</summary>
    AwaitingScan,

    /// <summary><c>adb pair</c> is in flight.</summary>
    Pairing,

    /// <summary><c>adb connect</c> is in flight (or its target is being discovered).</summary>
    Connecting,

    /// <summary><c>adb connect</c> succeeded; the device now shows up in the normal probe.</summary>
    Connected,

    /// <summary>A step failed; the status line states why and every entry action is available again.</summary>
    Failed,
}

/// <summary>What just happened; the only inputs that may move the stage.</summary>
public enum WirelessPairingEvent
{
    /// <summary>The user asked to show the pairing QR.</summary>
    QrShown,

    /// <summary>mDNS produced the phone's pairing endpoint for our QR's service name.</summary>
    PairingServiceDiscovered,

    /// <summary>The user submitted a typed endpoint + 6-digit code.</summary>
    ManualPairSubmitted,

    /// <summary><c>adb pair</c> reported success.</summary>
    PairSucceeded,

    /// <summary><c>adb pair</c> reported rejection or failure.</summary>
    PairFailed,

    /// <summary>The user asked to <c>adb connect</c> directly (already-paired phone).</summary>
    ConnectRequested,

    /// <summary><c>adb connect</c> reported success.</summary>
    ConnectSucceeded,

    /// <summary><c>adb connect</c> (or its endpoint discovery) failed.</summary>
    ConnectFailed,

    /// <summary>The user cancelled, the QR wait timed out, or consent was withdrawn.</summary>
    Cancelled,
}

/// <summary>
/// The wireless-pairing state machine, pure and synchronous so every legal and illegal
/// transition is unit-testable without adb. The view-model owns one instance and only
/// mutates its stage through <see cref="TryApply"/>; an event that is not legal for the
/// current stage is refused (returns false) instead of guessing — a stale async completion
/// can therefore never yank the UI to a stage the user already left.
/// </summary>
public sealed class WirelessPairingFlow
{
    public WirelessPairingStage Stage { get; private set; } = WirelessPairingStage.Idle;

    /// <summary>Applies one event; false (stage unchanged) when it is not legal right now.</summary>
    public bool TryApply(WirelessPairingEvent trigger)
    {
        if (!TryAdvance(Stage, trigger, out var next))
        {
            return false;
        }

        Stage = next;
        return true;
    }

    /// <summary>The pure transition table; the instance above is just this plus memory.</summary>
    public static bool TryAdvance(WirelessPairingStage current, WirelessPairingEvent trigger, out WirelessPairingStage next)
    {
        next = current;

        // Cancel always lands back at Idle — including from Idle itself, harmlessly.
        if (trigger == WirelessPairingEvent.Cancelled)
        {
            next = WirelessPairingStage.Idle;
            return true;
        }

        // Entry actions are legal from every settled stage (never while pair/connect is in
        // flight): starting over after success or failure must not require a hidden reset.
        var settled = current is WirelessPairingStage.Idle
            or WirelessPairingStage.AwaitingScan
            or WirelessPairingStage.Connected
            or WirelessPairingStage.Failed;

        switch (trigger)
        {
            case WirelessPairingEvent.QrShown when settled:
                next = WirelessPairingStage.AwaitingScan;
                return true;
            case WirelessPairingEvent.ManualPairSubmitted when settled:
                next = WirelessPairingStage.Pairing;
                return true;
            case WirelessPairingEvent.ConnectRequested when settled:
                next = WirelessPairingStage.Connecting;
                return true;
            case WirelessPairingEvent.PairingServiceDiscovered when current == WirelessPairingStage.AwaitingScan:
                next = WirelessPairingStage.Pairing;
                return true;
            case WirelessPairingEvent.PairSucceeded when current == WirelessPairingStage.Pairing:
                next = WirelessPairingStage.Connecting;
                return true;
            case WirelessPairingEvent.PairFailed when current == WirelessPairingStage.Pairing:
                next = WirelessPairingStage.Failed;
                return true;
            case WirelessPairingEvent.ConnectSucceeded when current == WirelessPairingStage.Connecting:
                next = WirelessPairingStage.Connected;
                return true;
            case WirelessPairingEvent.ConnectFailed when current == WirelessPairingStage.Connecting:
                next = WirelessPairingStage.Failed;
                return true;
            default:
                return false;
        }
    }
}

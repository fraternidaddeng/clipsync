namespace ClipSync.Core.Clipboard.PrivilegedHost;

/// <summary>
/// The single most-relevant thing the PC can say about helping a phone open 特权直读 right
/// now. Ordered from "nothing attached" up to "ready to start", mirroring the honest
/// next-step wording the card shows.
/// </summary>
public enum PrivilegedHostAvailability
{
    /// <summary>No adb executable was located, so nothing can be attempted (bundle it or install platform-tools).</summary>
    AdbUnavailable,

    /// <summary>adb runs, but no phone is attached over USB or wireless debugging.</summary>
    NoDevice,

    /// <summary>A phone is attached but its on-screen "允许 USB 调试" RSA prompt has not been accepted yet.</summary>
    DeviceUnauthorized,

    /// <summary>A phone is attached but adb reports it offline; usually a transient (re)connect state.</summary>
    DeviceOffline,

    /// <summary>An authorized phone is attached — the start script can run.</summary>
    DeviceReady,
}

/// <summary>
/// A point-in-time snapshot of the adb side. Pure data so the view-model mapping and its
/// tests stay free of any process launching.
/// </summary>
public sealed record PrivilegedHostProbe(
    PrivilegedHostAvailability Availability,
    IReadOnlyList<AndroidAdbDevice> Devices,
    AndroidAdbDevice? Target,
    bool? HostRunning = null,
    string? Detail = null);

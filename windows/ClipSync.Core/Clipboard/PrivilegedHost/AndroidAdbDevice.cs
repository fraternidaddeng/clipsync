namespace ClipSync.Core.Clipboard.PrivilegedHost;

/// <summary>
/// The connection state adb reports for one attached transport. Only <see cref="Ready"/>
/// devices can run the start script; the others each need a different, honest next step.
/// </summary>
public enum AdbDeviceState
{
    /// <summary>adb printed a state token this side does not model yet.</summary>
    Unknown,

    /// <summary>Authorized and usable (<c>device</c>).</summary>
    Ready,

    /// <summary>Attached but the on-phone RSA "允许 USB 调试" prompt has not been accepted (<c>unauthorized</c>).</summary>
    Unauthorized,

    /// <summary>Seen by adb but not responding yet (<c>offline</c>); usually transient after (re)connect.</summary>
    Offline,
}

/// <summary>
/// One line of parsed <c>adb devices -l</c> output. The description carries adb's own
/// <c>model:</c>/<c>device:</c> hints when present so the UI can name the phone without
/// touching clipboard content or any secret.
/// </summary>
public sealed record AndroidAdbDevice(string Serial, AdbDeviceState State, string? Model = null)
{
    /// <summary>A short human label: the adb model when known, otherwise the raw serial.</summary>
    public string DisplayName => string.IsNullOrWhiteSpace(Model) ? Serial : Model!;
}

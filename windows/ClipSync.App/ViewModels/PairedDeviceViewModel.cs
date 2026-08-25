using ClipSync.App.Ui;
using ClipSync.Core.Storage;

namespace ClipSync.App.ViewModels;

public sealed record PairedDeviceViewModel(
    string DeviceId,
    string DisplayName,
    string Platform,
    string LastSeenText,
    string StateText,
    bool IsRevoked,
    int AccentIndex,
    int PendingCount = 0,
    string? StaleReason = null)
{
    /// <summary>True when this entry is flagged as a leftover (duplicate re-pair ghost or long unseen).</summary>
    public bool IsStale => StaleReason is not null;

    /// <summary>
    /// <paramref name="pairingPosition"/> is the device's zero-based position in the
    /// created_at-ordered device list; the charter assigns neighbour hues by pairing
    /// order (first device hue 195, second 215, …), cycling after five.
    /// <paramref name="pendingCount"/> is this peer's outbox backlog; <paramref name="staleReason"/>
    /// is the badge text when the device is flagged stale (null for healthy devices).
    /// </summary>
    public static PairedDeviceViewModel FromDevice(
        PairedDevice device,
        int pairingPosition,
        int pendingCount = 0,
        string? staleReason = null) => new(
        device.DeviceId,
        device.DisplayName,
        device.Platform switch { "android" => "Android", "windows" => "Windows", _ => device.Platform },
        device.LastSeenAt is { } seen
            ? $"Last seen {seen.ToLocalTime().ToString("g", System.Globalization.CultureInfo.CurrentCulture)}"
            : "Never connected",
        device.IsRevoked ? "Revoked — scan a new QR code to re-pair" : "Paired",
        device.IsRevoked,
        DeviceAccent.ForPairingPosition(pairingPosition),
        pendingCount,
        staleReason);
}

using ClipSync.Core.Storage;

namespace ClipSync.App.ViewModels;

public sealed record PairedDeviceViewModel(
    string DeviceId,
    string DisplayName,
    string Platform,
    string LastSeenText,
    string StateText,
    bool IsRevoked)
{
    public static PairedDeviceViewModel FromDevice(PairedDevice device) => new(
        device.DeviceId,
        device.DisplayName,
        device.Platform switch { "android" => "Android", "windows" => "Windows", _ => device.Platform },
        device.LastSeenAt is { } seen
            ? $"Last seen {seen.ToLocalTime().ToString("g", System.Globalization.CultureInfo.CurrentCulture)}"
            : "Never connected",
        device.IsRevoked ? "Revoked — scan a new QR code to re-pair" : "Paired",
        device.IsRevoked);
}

using ClipSync.App;
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
        Strings.PlatformLabel(device.Platform),
        device.LastSeenAt is { } seen
            ? Strings.FormatLastSeen(seen.ToLocalTime().ToString("g", System.Globalization.CultureInfo.CurrentCulture))
            : Strings.NeverConnected,
        device.IsRevoked ? Strings.DeviceRevokedState : Strings.DevicePairedState,
        device.IsRevoked);
}

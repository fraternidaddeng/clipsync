using ClipSync.App.Ui;
using ClipSync.Core.Storage;

namespace ClipSync.App.ViewModels;

/// <summary>
/// One selectable colour dot in a device row's 设备色 picker (P1#14). Selecting
/// the pairing-order default clears the stored override, so persisted state stays
/// minimal and "跟随配对顺位" remains the honest description.
/// </summary>
public sealed record DeviceAccentSwatch(string DeviceId, int Slot, bool IsSelected, bool IsDefault)
{
    /// <summary>What choosing this dot means for the stored override: null = follow pairing order.</summary>
    public int? OverrideToStore => IsDefault ? null : Slot;
}

public sealed record PairedDeviceViewModel(
    string DeviceId,
    string DisplayName,
    string Platform,
    string LastSeenText,
    string StateText,
    bool IsRevoked,
    int AccentIndex,
    int DefaultAccentIndex,
    int PendingCount = 0,
    string? StaleReason = null)
{
    /// <summary>True when this entry is flagged as a leftover (duplicate re-pair ghost or long unseen).</summary>
    public bool IsStale => StaleReason is not null;

    /// <summary>True when the user pinned a colour (设备色手动改) instead of the pairing-order default.</summary>
    public bool HasCustomAccent => AccentIndex != DefaultAccentIndex;

    /// <summary>"跟随配对顺位" or "手动指定" — the same fact wording as the Android conduit row.</summary>
    public string AccentSourceText => HasCustomAccent ? "手动指定" : "跟随配对顺位";

    /// <summary>The five charter neighbour hues as picker dots; the effective one is marked selected.</summary>
    public IReadOnlyList<DeviceAccentSwatch> AccentSwatches =>
        Enumerable.Range(1, DeviceAccent.PaletteSize)
            .Select(slot => new DeviceAccentSwatch(
                DeviceId,
                slot,
                IsSelected: slot == AccentIndex,
                IsDefault: slot == DefaultAccentIndex))
            .ToArray();

    /// <summary>
    /// <paramref name="pairingPosition"/> is the device's zero-based position in the
    /// created_at-ordered device list; the charter assigns neighbour hues by pairing
    /// order (first device hue 195, second 215, …), cycling after five — unless the
    /// user pinned a colour (<see cref="PairedDevice.AccentOverride"/>, P1#14).
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
        device.AccentOverride ?? DeviceAccent.ForPairingPosition(pairingPosition),
        DeviceAccent.ForPairingPosition(pairingPosition),
        pendingCount,
        staleReason);
}

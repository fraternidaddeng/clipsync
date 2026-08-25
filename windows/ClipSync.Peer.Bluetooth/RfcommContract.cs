namespace ClipSync.Peer.Bluetooth;

/// <summary>
/// Cross-platform constants of the ClipSync RFCOMM deployment (ADR 0005 phase 2/3).
/// The Android client must use the exact same service UUID in
/// <c>createRfcommSocketToServiceRecord</c>, so treat this value as frozen once a
/// release ships: changing it strands every already-installed peer.
/// </summary>
public static class RfcommContract
{
    /// <summary>ClipSync's own SDP service UUID, published by the Windows listener.</summary>
    public static readonly Guid ServiceUuid = new("5f7f1d9c-2d6b-4e8d-9f1b-ef9ed49b0bec");

    /// <summary>Human-readable SDP service name attribute.</summary>
    public const string ServiceName = "ClipSync bt1 sync";
}

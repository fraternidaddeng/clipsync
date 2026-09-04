using ClipSync.App.Diagnostics;
using ClipSync.Core.Security.Bt1;
using ClipSync.Peer.Bluetooth;
using ClipSync.Peer.Diagnostics;
using ClipSync.Peer.Pairing;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Logging.Abstractions;

namespace ClipSync.App.Tests.Diagnostics;

/// <summary>
/// The Peer-layer log events reach the tray diagnostics as codes only. These tests pin the
/// hygiene contract: event names become snake_case codes under a category-chosen prefix, a
/// state value follows the code solely when it belongs to a constant allow list, and messages,
/// device ids, names, MACs, and free text never leak — whatever the state carries.
/// </summary>
public sealed class DiagnosticsLogCodesTests
{
    private const string PairingCategory = "ClipSync.Peer.Pairing";
    private const string ServerCategory = "ClipSync.Peer.Server";
    private const string BluetoothCategory = "ClipSync.Peer.Bluetooth";

    [Theory]
    [InlineData("PairingConfirmReceived", "peer_pairing_confirm_received")]
    [InlineData("PairingTicketIssued", "peer_pairing_ticket_issued")]
    [InlineData("PairingConfirmed", "peer_pairing_confirmed")]
    [InlineData("V2RefusedImageSyncDisabled", "peer_v2_refused_image_sync_disabled")]
    [InlineData("FetchMissingIds", "peer_fetch_missing_ids")]
    [InlineData("FrameRateLimited", "peer_frame_rate_limited")]
    public void EventNameBecomesSnakeCaseCode(string eventName, string expected)
    {
        Assert.Equal(expected, DiagnosticsLogCodes.For(PairingCategory, new EventId(1, eventName), null));
    }

    [Fact]
    public void NamelessEventFallsBackToItsId()
    {
        Assert.Equal("peer_event_7", DiagnosticsLogCodes.For(ServerCategory, new EventId(7), null));
        Assert.Equal("peer_event_7", DiagnosticsLogCodes.For(ServerCategory, new EventId(7, string.Empty), null));
    }

    [Fact]
    public void ForeignCategoriesProduceNoCode()
    {
        Assert.Null(DiagnosticsLogCodes.For(
            "Microsoft.AspNetCore.Hosting.Diagnostics",
            new EventId(1, "RequestStarting"),
            State(("Path", "/v1/pair/confirm"))));
        Assert.False(DiagnosticsLogCodes.IsAdmittedCategory("Microsoft.Hosting.Lifetime"));
        Assert.True(DiagnosticsLogCodes.IsAdmittedCategory(ServerCategory));
    }

    [Fact]
    public void EveryPairingErrorCodeIsAppendedLowercased()
    {
        Assert.All(PairingErrorCodes.All, code =>
        {
            var result = DiagnosticsLogCodes.For(
                PairingCategory,
                new EventId(14, "PairingConfirmFailed"),
                State(("Code", code)));
            Assert.Equal("peer_pairing_confirm_failed_" + code.ToLowerInvariant(), result);
        });
    }

    [Fact]
    public void PairingConfirmFailedWithTimeoutReadsAsTheProtocolConstant()
    {
        Assert.Equal(
            "peer_pairing_confirm_failed_pairing_timeout",
            DiagnosticsLogCodes.For(PairingCategory, new EventId(14, "PairingConfirmFailed"), State(("Code", PairingErrorCodes.Timeout))));
    }

    [Fact]
    public void PairingConfirmFailedWithPeerAbortReadsAsTheLogOnlyConstant()
    {
        Assert.Equal(
            "peer_pairing_confirm_failed_pairing_aborted",
            DiagnosticsLogCodes.For(PairingCategory, new EventId(14, "PairingConfirmFailed"), State(("Code", PairingErrorCodes.PeerAborted))));
    }

    [Theory]
    [InlineData("SECRET clipboard text")]
    [InlineData("pairing_timeout")] // case matters: only the exact constant passes
    [InlineData("AUTH_FAILED")] // a protocol code is not a pairing code
    [InlineData("")]
    public void UnlistedErrorCodesReadUnknown(string code)
    {
        var result = DiagnosticsLogCodes.For(
            PairingCategory,
            new EventId(14, "PairingConfirmFailed"),
            State(("Code", code)));

        Assert.Equal("peer_pairing_confirm_failed_unknown", result);
        Assert.DoesNotContain("SECRET", result, StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public void MissingOrNonStringErrorCodeReadsUnknown()
    {
        var eventId = new EventId(14, "PairingConfirmFailed");

        Assert.Equal("peer_pairing_confirm_failed_unknown", DiagnosticsLogCodes.For(PairingCategory, eventId, null));
        Assert.Equal("peer_pairing_confirm_failed_unknown", DiagnosticsLogCodes.For(PairingCategory, eventId, State(("Other", "x"))));
        Assert.Equal("peer_pairing_confirm_failed_unknown", DiagnosticsLogCodes.For(PairingCategory, eventId, State(("Code", 42))));
    }

    [Fact]
    public void RateLimitKindIsAppendedOnlyFromTheKnownSet()
    {
        var eventId = new EventId(17, "ConnectionRateLimited");

        Assert.Equal(
            "peer_connection_rate_limited_pairing_confirm",
            DiagnosticsLogCodes.For(ServerCategory, eventId, State(("Kind", "pairing_confirm"))));
        Assert.Equal(
            "peer_connection_rate_limited_sync_accept",
            DiagnosticsLogCodes.For(ServerCategory, eventId, State(("Kind", "sync_accept"))));
        Assert.Equal(
            "peer_connection_rate_limited_unknown",
            DiagnosticsLogCodes.For(ServerCategory, eventId, State(("Kind", "SECRET"))));
    }

    [Fact]
    public void ServerListeningCarriesThePortAndNothingElse()
    {
        var eventId = new EventId(11, "ServerListening");

        Assert.Equal(
            "peer_server_listening_port_47654",
            DiagnosticsLogCodes.For(ServerCategory, eventId, State(("Port", 47654), ("AddressCount", 3))));
        Assert.Equal("peer_server_listening_port_unknown", DiagnosticsLogCodes.For(ServerCategory, eventId, State(("Port", 70000))));
        Assert.Equal("peer_server_listening_port_unknown", DiagnosticsLogCodes.For(ServerCategory, eventId, State(("Port", "SECRET"))));
        Assert.Equal("peer_server_listening_port_unknown", DiagnosticsLogCodes.For(ServerCategory, eventId, null));
    }

    [Fact]
    public void SessionEventsKeepOnlyTheProtocolCode()
    {
        var ended = DiagnosticsLogCodes.For(
            "ClipSync.Peer.Session",
            new EventId(1, "SessionEnded"),
            State(
                ("Role", "Listener"),
                ("Peer", "SECRET-device-id"),
                ("Authenticated", true),
                ("Code", "AUTH_FAILED"),
                ("Detail", "SECRET detail with clipboard text")));

        Assert.Equal("peer_session_ended_auth_failed", ended);
        Assert.DoesNotContain("SECRET", ended, StringComparison.OrdinalIgnoreCase);

        var closed = DiagnosticsLogCodes.For("ClipSync.Peer.Session", new EventId(1, "SessionEnded"), State(("Code", "none")));
        Assert.Equal("peer_session_ended_none", closed);

        var media = DiagnosticsLogCodes.For("ClipSync.Peer.Session", new EventId(2, "FrameRejected"), State(("Code", "MEDIA_TOO_LARGE"), ("Reason", "SECRET")));
        Assert.Equal("peer_frame_rejected_media_too_large", media);
    }

    [Fact]
    public void IdentifyingStateNeverReachesTheCode()
    {
        var confirmed = DiagnosticsLogCodes.For(
            PairingCategory,
            new EventId(15, "PairingConfirmed"),
            State(("DeviceId", "SECRET-device"), ("IsRepair", true), ("TrustEpoch", 3L)));
        Assert.Equal("peer_pairing_confirmed", confirmed);

        var throttled = DiagnosticsLogCodes.For(
            ServerCategory,
            new EventId(16, "AuthRateLimited"),
            State(("DeviceId", "SECRET-device")));
        Assert.Equal("peer_auth_rate_limited", throttled);

        var superseded = DiagnosticsLogCodes.For(
            PairingCategory,
            new EventId(19, "PairingSupersededGhost"),
            State(("GhostDeviceId", "SECRET-ghost"), ("NewDeviceId", "SECRET-new")));
        Assert.Equal("peer_pairing_superseded_ghost", superseded);
    }

    [Fact]
    public void ExceptionContributesItsTypeNameOnly()
    {
        var code = DiagnosticsLogCodes.For(
            ServerCategory,
            new EventId(10, "BackgroundLoopStopped"),
            State(("Loop", "outbox"), ("ExceptionKind", "SECRET")),
            new InvalidOperationException("SECRET message"));

        Assert.Equal("peer_background_loop_stopped_outbox_InvalidOperationException", code);
    }

    [Fact]
    public void LoggerRecordsSourceGeneratedEventsWithoutTouchingTheMessage()
    {
        var logger = DiagnosticsLoggerFactory.Instance.CreateLogger(PairingCategory);

        PeerLog.PairingConfirmFailed(logger, PairingErrorCodes.TokenExpired);
        PeerLog.PairingConfirmReceived(logger);

        var codes = LocalDiagnostics.Snapshot().Select(entry => entry.Code).ToList();
        Assert.Contains("peer_pairing_confirm_failed_pairing_token_expired", codes);
        Assert.Contains("peer_pairing_confirm_received", codes);
        Assert.All(codes, code => Assert.DoesNotContain("pairing confirm failed", code, StringComparison.Ordinal));
    }

    [Fact]
    public void LoggerDropsDebugEventsAndNeverCallsTheFormatter()
    {
        var logger = DiagnosticsLoggerFactory.Instance.CreateLogger("ClipSync.Peer.Session");

        Assert.False(logger.IsEnabled(LogLevel.Debug));
        Assert.False(logger.IsEnabled(LogLevel.Trace));
        Assert.False(logger.IsEnabled(LogLevel.None));
        Assert.True(logger.IsEnabled(LogLevel.Information));
        Assert.True(logger.IsEnabled(LogLevel.Warning));

        PeerLog.MessageReceived(logger, "SECRET-DEBUG-TYPE");
        logger.Log(
            LogLevel.Warning,
            new EventId(2, "FrameRejected"),
            State(("Code", "SCHEMA_VIOLATION"), ("Reason", "SECRET reason")),
            null,
            (_, _) => throw new InvalidOperationException("the formatter must never run"));

        var codes = LocalDiagnostics.Snapshot().Select(entry => entry.Code).ToList();
        Assert.Contains("peer_frame_rejected_schema_violation", codes);
        Assert.DoesNotContain(codes, code => code.Contains("SECRET", StringComparison.OrdinalIgnoreCase));
        Assert.DoesNotContain(codes, code => code.Contains("message_received", StringComparison.Ordinal));
    }

    [Fact]
    public void FactoryHandsForeignCategoriesANullLogger()
    {
        Assert.Same(NullLogger.Instance, DiagnosticsLoggerFactory.Instance.CreateLogger("Microsoft.AspNetCore.Server.Kestrel"));
        Assert.IsType<DiagnosticsLogger>(DiagnosticsLoggerFactory.Instance.CreateLogger(ServerCategory));
        Assert.IsType<DiagnosticsLogger>(DiagnosticsLoggerFactory.Instance.CreateLogger(BluetoothCategory));
    }

    [Theory]
    [InlineData("ClipSync.Peer", "peer_")]
    [InlineData("ClipSync.Peer.Server", "peer_")]
    [InlineData("ClipSync.Peer.Session", "peer_")]
    [InlineData("ClipSync.Peer.Pairing", "peer_")]
    [InlineData("ClipSync.Peer.Bluetooth", "bt_")]
    [InlineData("ClipSync.Peer.Bluetooth.Session", "bt_")]
    [InlineData("ClipSync.App.Startup", "app_")]
    [InlineData("ClipSync.Core.Storage", "app_")]
    [InlineData("ClipSync.PeerX", "app_")] // a lookalike category is not the peer namespace
    public void PrefixFollowsTheLoggerCategory(string category, string expectedPrefix)
    {
        Assert.Equal(expectedPrefix, DiagnosticsLogCodes.PrefixFor(category));
        Assert.StartsWith(expectedPrefix, DiagnosticsLogCodes.For(category, new EventId(1, "ListenerStarted"), null), StringComparison.Ordinal);
    }

    [Fact]
    public void ForeignCategoriesHaveNoPrefix()
    {
        Assert.Null(DiagnosticsLogCodes.PrefixFor("Microsoft.Hosting.Lifetime"));
        Assert.Null(DiagnosticsLogCodes.PrefixFor("ClipSyncOther"));
    }

    [Theory]
    [InlineData("ListenerStarted", "bt_listener_started")]
    [InlineData("ListenerStopped", "bt_listener_stopped")]
    [InlineData("AcceptRateLimited", "bt_accept_rate_limited")]
    [InlineData("SessionStarted", "bt_session_started")]
    public void BluetoothEventNamesBecomeBtCodes(string eventName, string expected)
    {
        Assert.Equal(expected, DiagnosticsLogCodes.For(BluetoothCategory, new EventId(1, eventName), null));
    }

    [Fact]
    public void BluetoothSessionEndedKeepsProtocolCodesAndTheDeclaredOutcomesOnly()
    {
        var eventId = new EventId(6, "SessionEnded");

        Assert.Equal(
            "bt_session_ended_clean",
            DiagnosticsLogCodes.For(BluetoothCategory, eventId, State(("DeviceId", "SECRET-device"), ("Code", BluetoothLog.SessionEndClean))));
        Assert.Equal(
            "bt_session_ended_cancelled",
            DiagnosticsLogCodes.For(BluetoothCategory, eventId, State(("DeviceId", "SECRET-device"), ("Code", BluetoothLog.SessionEndCancelled))));
        Assert.Equal(
            "bt_session_ended_auth_failed",
            DiagnosticsLogCodes.For(BluetoothCategory, eventId, State(("DeviceId", "SECRET-device"), ("Code", "AUTH_FAILED"))));
        // Transport exception names are not a declared set: they read unknown.
        Assert.Equal(
            "bt_session_ended_unknown",
            DiagnosticsLogCodes.For(BluetoothCategory, eventId, State(("DeviceId", "SECRET-device"), ("Code", "IOException"))));
        Assert.Equal(
            "bt_session_ended_unknown",
            DiagnosticsLogCodes.For(BluetoothCategory, eventId, State(("DeviceId", "SECRET-device"), ("Code", "SECRET"))));
    }

    [Fact]
    public void BluetoothOutcomesDoNotLeakIntoTheIpSessionAllowList()
    {
        Assert.Equal(
            "peer_session_ended_unknown",
            DiagnosticsLogCodes.For("ClipSync.Peer.Session", new EventId(1, "SessionEnded"), State(("Code", BluetoothLog.SessionEndClean))));
    }

    [Fact]
    public void BluetoothHandshakeRefusedCarriesOnlyWireErrorCodes()
    {
        var eventId = new EventId(4, "HandshakeRefused");

        Assert.All(Bt1ErrorCodes.WireCodes, code =>
            Assert.Equal(
                "bt_handshake_refused_" + code.ToLowerInvariant(),
                DiagnosticsLogCodes.For(BluetoothCategory, eventId, State(("Code", code), ("Reason", "SECRET reason")))));
        // DecryptFailed is local-only and never on the wire; the reason text is never a code.
        Assert.Equal(
            "bt_handshake_refused_unknown",
            DiagnosticsLogCodes.For(BluetoothCategory, eventId, State(("Code", Bt1ErrorCodes.DecryptFailed), ("Reason", "SECRET"))));
        Assert.Equal(
            "bt_handshake_refused_unknown",
            DiagnosticsLogCodes.For(BluetoothCategory, eventId, State(("Code", "SECRET"), ("Reason", "unknown_device"))));
    }

    [Fact]
    public void BluetoothIdentifyingAndFreeTextStateNeverReachesTheCode()
    {
        var started = DiagnosticsLogCodes.For(
            BluetoothCategory,
            new EventId(5, "SessionStarted"),
            State(("DeviceId", "SECRET-device"), ("RemoteAddress", "SECRET-MAC")));
        Assert.Equal("bt_session_started", started);

        var failed = DiagnosticsLogCodes.For(
            BluetoothCategory,
            new EventId(3, "ListenerFailed"),
            State(("ExceptionKind", "SECRET-kind")));
        Assert.Equal("bt_listener_failed", failed);
    }

    [Fact]
    public void BluetoothLoggerRecordsSourceGeneratedEventsAndDropsTheDebugAbort()
    {
        var logger = DiagnosticsLoggerFactory.Instance.CreateLogger(BluetoothCategory);

        BluetoothLog.ListenerStarted(logger);
        BluetoothLog.HandshakeRefused(logger, Bt1ErrorCodes.AuthFailed, "SECRET reason");
        BluetoothLog.SessionStarted(logger, "SECRET-device");
        BluetoothLog.SessionEnded(logger, "SECRET-device", BluetoothLog.SessionEndClean);
        BluetoothLog.HandshakeAborted(logger, "SECRET-DEBUG-KIND");

        var codes = LocalDiagnostics.Snapshot().Select(entry => entry.Code).ToList();
        Assert.Contains("bt_listener_started", codes);
        Assert.Contains("bt_handshake_refused_bt1_auth_failed", codes);
        Assert.Contains("bt_session_started", codes);
        Assert.Contains("bt_session_ended_clean", codes);
        Assert.DoesNotContain(codes, code => code.Contains("SECRET", StringComparison.OrdinalIgnoreCase));
        Assert.DoesNotContain(codes, code => code.Contains("handshake_aborted", StringComparison.Ordinal));
    }

    private static List<KeyValuePair<string, object?>> State(params (string Key, object? Value)[] pairs) =>
        pairs.Select(pair => new KeyValuePair<string, object?>(pair.Key, pair.Value)).ToList();
}

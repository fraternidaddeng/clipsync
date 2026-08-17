using ClipSync.Core.Protocol;
using ClipSync.Core.Storage;
using ClipSync.Peer.Pairing;
using ClipSync.Tests.Peer;
using Microsoft.Data.Sqlite;

namespace ClipSync.Tests.Pairing;

public sealed class PairingServiceTests : IAsyncDisposable
{
    private const string WindowsDeviceId = "11111111-1111-4111-8111-111111111111";
    private const string AndroidDeviceId = "22222222-2222-4222-8222-222222222222";

    private readonly string directory;
    private readonly SqliteClipboardEventStore store;
    private readonly FakeSecretProtector protector = new();
    private readonly ManualClock clock = new();

    public PairingServiceTests()
    {
        directory = Path.Combine(Path.GetTempPath(), "clipsync-pairing-tests", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(directory);
        store = new SqliteClipboardEventStore(Path.Combine(directory, "pairing.db"), WindowsDeviceId);
    }

    [Fact]
    public void QrPayloadCarriesEverythingExceptSecrets()
    {
        var service = CreateService(AutoApprove());
        var ticket = service.IssueTicket();
        var payload = service.BuildQrPayload(
            ticket,
            ["192.168.1.23", "10.0.11.7"],
            47654,
            new string('a', 64));

        Assert.Equal("pairing_qr", payload.Kind);
        Assert.Equal(WindowsDeviceId, payload.DeviceId);
        Assert.Equal("DESKTOP-WIN", payload.DisplayName);
        Assert.Equal(ticket.Token, payload.Token);
        Assert.Equal(ticket.ExpiresAt.ToUnixTimeMilliseconds(), payload.ExpiresAtMs);

        // The serialized payload parses under the frozen contract and has no secret field.
        var json = PairingJson.Serialize(payload);
        Assert.NotNull(PairingJson.ParseQrPayload(json, out var error));
        Assert.Null(error);
        Assert.DoesNotContain("pair_secret", json, StringComparison.Ordinal);
    }

    [Fact]
    public async Task ApprovedConfirmStoresDeviceAndReturnsWorkingSecret()
    {
        var service = CreateService(AutoApprove());
        PairedDevice? completed = null;
        service.PairingCompleted += device => completed = device;
        var ticket = service.IssueTicket();

        var outcome = await service.ConfirmAsync(Request(ticket.Token), CancellationToken.None);

        var approved = Assert.IsType<PairingConfirmOutcome.Approved>(outcome);
        Assert.Equal(WindowsDeviceId, approved.Response.DeviceId);
        Assert.Equal("windows", approved.Response.Platform);
        Assert.Equal(1, approved.Response.TrustEpoch);

        // The secret in the response and the protected secret in the store are the same bytes.
        Assert.True(ProtocolValidation.TryDecodeBase64Url256(approved.Response.PairSecret, out var responseSecret));
        var stored = await store.GetDeviceAsync(AndroidDeviceId);
        Assert.NotNull(stored);
        Assert.Equal("Pixel 8", stored.DisplayName);
        Assert.Equal(responseSecret, protector.Unprotect(Convert.FromBase64String(stored.PairSecretProtected)));
        Assert.NotNull(completed);
        Assert.Equal(AndroidDeviceId, completed.DeviceId);
    }

    [Fact]
    public async Task TokenIsSingleUse()
    {
        var service = CreateService(AutoApprove());
        var ticket = service.IssueTicket();
        Assert.IsType<PairingConfirmOutcome.Approved>(await service.ConfirmAsync(Request(ticket.Token), CancellationToken.None));

        var replay = Assert.IsType<PairingConfirmOutcome.Failed>(
            await service.ConfirmAsync(Request(ticket.Token), CancellationToken.None));
        Assert.Equal(403, replay.HttpStatus);
        Assert.Equal(PairingErrorCodes.TokenInvalid, replay.ErrorCode);
    }

    [Fact]
    public async Task ExpiredTokenIsRejectedWithGone()
    {
        var service = CreateService(AutoApprove());
        var ticket = service.IssueTicket();
        clock.Advance(TimeSpan.FromMinutes(6));

        var expired = Assert.IsType<PairingConfirmOutcome.Failed>(
            await service.ConfirmAsync(Request(ticket.Token), CancellationToken.None));
        Assert.Equal(410, expired.HttpStatus);
        Assert.Equal(PairingErrorCodes.TokenExpired, expired.ErrorCode);
    }

    [Fact]
    public async Task WrongGuessDoesNotBurnTheOutstandingTicket()
    {
        var service = CreateService(AutoApprove());
        var ticket = service.IssueTicket();
        var guess = ProtocolValidation.EncodeBase64Url(new byte[32]);

        var wrong = Assert.IsType<PairingConfirmOutcome.Failed>(
            await service.ConfirmAsync(Request(guess), CancellationToken.None));
        Assert.Equal(PairingErrorCodes.TokenInvalid, wrong.ErrorCode);

        Assert.IsType<PairingConfirmOutcome.Approved>(
            await service.ConfirmAsync(Request(ticket.Token), CancellationToken.None));
    }

    [Fact]
    public async Task ReissueAndCancelInvalidateOutstandingTokens()
    {
        var service = CreateService(AutoApprove());
        var first = service.IssueTicket();
        var second = service.IssueTicket();

        var stale = Assert.IsType<PairingConfirmOutcome.Failed>(
            await service.ConfirmAsync(Request(first.Token), CancellationToken.None));
        Assert.Equal(PairingErrorCodes.TokenInvalid, stale.ErrorCode);

        service.CancelTicket();
        var cancelled = Assert.IsType<PairingConfirmOutcome.Failed>(
            await service.ConfirmAsync(Request(second.Token), CancellationToken.None));
        Assert.Equal(PairingErrorCodes.TokenInvalid, cancelled.ErrorCode);
    }

    [Fact]
    public async Task RejectionLeavesNoDeviceBehind()
    {
        var service = CreateService(new DelegateApprover((_, _) => Task.FromResult(false)));
        var ticket = service.IssueTicket();

        var rejected = Assert.IsType<PairingConfirmOutcome.Failed>(
            await service.ConfirmAsync(Request(ticket.Token), CancellationToken.None));
        Assert.Equal(403, rejected.HttpStatus);
        Assert.Equal(PairingErrorCodes.Rejected, rejected.ErrorCode);
        Assert.Null(await store.GetDeviceAsync(AndroidDeviceId));
    }

    [Fact]
    public async Task ApprovalTimeoutFailsWithoutSavingAnything()
    {
        var service = CreateService(
            new DelegateApprover(async (_, ct) =>
            {
                await Task.Delay(Timeout.InfiniteTimeSpan, ct);
                return true;
            }),
            approvalTimeout: TimeSpan.FromMilliseconds(120));
        var ticket = service.IssueTicket();

        var timedOut = Assert.IsType<PairingConfirmOutcome.Failed>(
            await service.ConfirmAsync(Request(ticket.Token), CancellationToken.None));
        Assert.Equal(PairingErrorCodes.Timeout, timedOut.ErrorCode);
        Assert.Null(await store.GetDeviceAsync(AndroidDeviceId));
    }

    [Fact]
    public async Task RepairBumpsEpochClearsRevocationAndTellsTheApprover()
    {
        PairingCandidate? seen = null;
        var service = CreateService(new DelegateApprover((candidate, _) =>
        {
            seen = candidate;
            return Task.FromResult(true);
        }));

        // First pairing, then revoke: epoch 1 -> 2.
        Assert.IsType<PairingConfirmOutcome.Approved>(
            await service.ConfirmAsync(Request(service.IssueTicket().Token), CancellationToken.None));
        Assert.True(await store.RevokeDeviceAsync(AndroidDeviceId, clock.GetUtcNow()));

        var outcome = Assert.IsType<PairingConfirmOutcome.Approved>(
            await service.ConfirmAsync(Request(service.IssueTicket().Token), CancellationToken.None));

        Assert.NotNull(seen);
        Assert.True(seen.IsRepair);
        Assert.Equal(3, outcome.Response.TrustEpoch);
        var device = await store.GetDeviceAsync(AndroidDeviceId);
        Assert.NotNull(device);
        Assert.False(device.IsRevoked);
        Assert.Equal(3, device.TrustEpoch);
    }

    [Fact]
    public async Task ConfirmForOwnDeviceIdIsSchemaViolation()
    {
        var service = CreateService(AutoApprove());
        var ticket = service.IssueTicket();

        var outcome = Assert.IsType<PairingConfirmOutcome.Failed>(
            await service.ConfirmAsync(Request(ticket.Token) with { DeviceId = WindowsDeviceId }, CancellationToken.None));
        Assert.Equal(400, outcome.HttpStatus);
        Assert.Equal(PairingErrorCodes.SchemaViolation, outcome.ErrorCode);
    }

    private PairingService CreateService(IPairingApprover approver, TimeSpan? approvalTimeout = null) =>
        new(
            store,
            protector,
            approver,
            new PairingServiceOptions
            {
                LocalDisplayName = "DESKTOP-WIN",
                TimeProvider = clock,
                ApprovalTimeout = approvalTimeout ?? TimeSpan.FromSeconds(5)
            });

    private static DelegateApprover AutoApprove() => new((_, _) => Task.FromResult(true));

    private static PairingConfirmRequest Request(string token) => new()
    {
        Kind = PairingDocumentKinds.ConfirmRequest,
        Version = 1,
        Token = token,
        DeviceId = AndroidDeviceId,
        DisplayName = "Pixel 8",
        Platform = "android"
    };

    public async ValueTask DisposeAsync()
    {
        await store.DisposeAsync();
        SqliteConnection.ClearAllPools();
        Directory.Delete(directory, recursive: true);
    }

    private sealed class ManualClock : TimeProvider
    {
        private DateTimeOffset now = DateTimeOffset.FromUnixTimeMilliseconds(1_700_000_000_000);

        public override DateTimeOffset GetUtcNow() => now;

        public void Advance(TimeSpan by) => now += by;
    }
}

public sealed class DelegateApprover(Func<PairingCandidate, CancellationToken, Task<bool>> handler) : IPairingApprover
{
    public Task<bool> ApproveAsync(PairingCandidate candidate, CancellationToken cancellationToken) =>
        handler(candidate, cancellationToken);
}

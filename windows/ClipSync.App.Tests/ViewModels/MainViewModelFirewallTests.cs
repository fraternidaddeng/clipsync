using System.IO;
using ClipSync.App.Clipboard;
using ClipSync.App.Firewall;
using ClipSync.App.ViewModels;
using ClipSync.Core.Clipboard;
using ClipSync.Core.Storage;

namespace ClipSync.App.Tests.ViewModels;

/// <summary>
/// The conduit page's 防火墙 line and 放行/移除 flow (ADR 0006) against fakes: the view model
/// states the inspector's verdict as facts, never runs netsh without a confirmed command, and
/// re-checks after every elevated run so the result line matches what the firewall now holds.
/// </summary>
public sealed class MainViewModelFirewallTests : IAsyncDisposable
{
    private const string LocalDeviceId = "11111111-1111-4111-8111-111111111111";

    private readonly string directory;
    private readonly SqliteClipboardEventStore store;
    private readonly Win32ClipboardAdapter adapter = new();
    private readonly FakeInspector inspector = new();
    private readonly FakeElevator elevator = new();
    private FirewallRuleCommand? promptAnswer;
    private FirewallRulePromptRequest? lastPrompt;
    private readonly MainViewModel viewModel;

    public MainViewModelFirewallTests()
    {
        directory = Path.Combine(Path.GetTempPath(), "clipsync-firewall-tests", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(directory);
        store = new SqliteClipboardEventStore(Path.Combine(directory, "firewall.db"), LocalDeviceId);
        viewModel = new MainViewModel(
            store,
            new ClipboardCapturePolicy(),
            adapter,
            firewallInspector: inspector,
            firewallElevator: elevator,
            firewallRulePrompt: request =>
            {
                lastPrompt = request;
                return promptAnswer;
            });
    }

    [Fact]
    public async Task NoRuleFoundIsStatedInActColourWithPortMismatchAndBlockFacts()
    {
        viewModel.UpdatePeerStatus(online: true, port: 50123, connectedCount: 0);
        inspector.Report = Report(FirewallVerdict.NoRuleFound, actualPort: 50123, blocking: ["Blocked by user"]);

        await viewModel.RefreshFirewallStatusCommand.ExecuteAsync(null);

        Assert.Equal((47654, 50123), inspector.LastPorts);
        Assert.True(viewModel.FirewallRuleMissing);
        Assert.False(viewModel.FirewallAllowed);
        Assert.Contains("未发现 TCP 47654", viewModel.FirewallStatus, StringComparison.Ordinal);
        Assert.Contains("监听在端口 50123", viewModel.FirewallDetail, StringComparison.Ordinal);
        Assert.Contains("Blocked by user", viewModel.FirewallDetail, StringComparison.Ordinal);
        Assert.False(viewModel.FirewallBusy);
    }

    [Fact]
    public async Task AllowedNamesTheCoveringRuleAndKeepsTheReachabilityCaveat()
    {
        inspector.Report = Report(FirewallVerdict.Allowed, allowing: [FirewallRuleCommand.RuleName], namedRuleExists: true);

        await viewModel.RefreshFirewallStatusCommand.ExecuteAsync(null);

        Assert.True(viewModel.FirewallAllowed);
        Assert.False(viewModel.FirewallRuleMissing);
        Assert.True(viewModel.FirewallManagedRuleExists);
        Assert.True(viewModel.RemoveFirewallRuleCommand.CanExecute(null));
        Assert.Contains("ClipSync TCP 47654", viewModel.FirewallStatus, StringComparison.Ordinal);
        Assert.Contains("AP 隔离", viewModel.FirewallDetail, StringComparison.Ordinal);
    }

    [Fact]
    public async Task AllowedByASwitchedOffFirewallNamesTheProfilesInstead()
    {
        inspector.Report = Report(FirewallVerdict.Allowed, active: FirewallProfiles.Private | FirewallProfiles.Public);

        await viewModel.RefreshFirewallStatusCommand.ExecuteAsync(null);

        Assert.Contains("专用网络 / 公用网络", viewModel.FirewallStatus, StringComparison.Ordinal);
    }

    [Fact]
    public async Task UndeterminedIsNeitherAllowedNorMissingAndHintsPublicNetwork()
    {
        inspector.Report = Report(FirewallVerdict.Undetermined, active: FirewallProfiles.Public);

        await viewModel.RefreshFirewallStatusCommand.ExecuteAsync(null);

        Assert.False(viewModel.FirewallAllowed);
        Assert.False(viewModel.FirewallRuleMissing);
        Assert.True(viewModel.FirewallPublicHintNeeded);
        Assert.Contains("无法判断", viewModel.FirewallStatus, StringComparison.Ordinal);
        Assert.False(viewModel.RemoveFirewallRuleCommand.CanExecute(null));
    }

    [Fact]
    public async Task CancellingThePromptRunsNothing()
    {
        promptAnswer = null;

        await viewModel.AllowFirewallPortCommand.ExecuteAsync(null);

        Assert.NotNull(lastPrompt);
        Assert.False(lastPrompt!.IsRemoval);
        Assert.Empty(elevator.Commands);
        Assert.Equal(string.Empty, viewModel.FirewallActionResult);
    }

    [Fact]
    public async Task ConfirmedAllowRunsTheChosenCommandThenRechecks()
    {
        inspector.Report = Report(FirewallVerdict.NoRuleFound, active: FirewallProfiles.Public);
        await viewModel.RefreshFirewallStatusCommand.ExecuteAsync(null);
        promptAnswer = FirewallRuleCommand.Allow(FirewallProfiles.Private | FirewallProfiles.Public);
        elevator.Outcome = ElevationOutcome.AppliedOutcome;
        inspector.Report = Report(FirewallVerdict.Allowed, allowing: [FirewallRuleCommand.RuleName], namedRuleExists: true);

        await viewModel.AllowFirewallPortCommand.ExecuteAsync(null);

        Assert.Equal(FirewallProfiles.Public, lastPrompt!.ActiveProfiles);
        var ran = Assert.Single(elevator.Commands);
        Assert.Equal("profile=private,public", ran.Arguments[^1]);
        Assert.Equal(2, inspector.Calls);
        Assert.True(viewModel.FirewallAllowed);
        Assert.True(viewModel.FirewallManagedRuleExists);
        Assert.Contains("规则已创建", viewModel.FirewallActionResult, StringComparison.Ordinal);
    }

    [Fact]
    public async Task DeclinedUacIsStatedAsCancelledNotSuccess()
    {
        promptAnswer = FirewallRuleCommand.Allow(FirewallProfiles.Private);
        elevator.Outcome = ElevationOutcome.CancelledOutcome;
        inspector.Report = Report(FirewallVerdict.NoRuleFound);

        await viewModel.AllowFirewallPortCommand.ExecuteAsync(null);

        Assert.Contains("未获得管理员授权", viewModel.FirewallActionResult, StringComparison.Ordinal);
        Assert.True(viewModel.FirewallRuleMissing);
    }

    [Fact]
    public async Task NetshFailureReportsTheExitCode()
    {
        promptAnswer = FirewallRuleCommand.Allow(FirewallProfiles.Private);
        elevator.Outcome = new ElevationOutcome(ElevationStatus.Failed, 1);
        inspector.Report = Report(FirewallVerdict.NoRuleFound);

        await viewModel.AllowFirewallPortCommand.ExecuteAsync(null);

        Assert.Contains("退出码 1", viewModel.FirewallActionResult, StringComparison.Ordinal);
    }

    [Fact]
    public async Task RemoveIsOnlyOfferedForTheManagedRuleAndStatesRemoval()
    {
        Assert.False(viewModel.RemoveFirewallRuleCommand.CanExecute(null));
        inspector.Report = Report(FirewallVerdict.Allowed, allowing: [FirewallRuleCommand.RuleName], namedRuleExists: true);
        await viewModel.RefreshFirewallStatusCommand.ExecuteAsync(null);
        Assert.True(viewModel.RemoveFirewallRuleCommand.CanExecute(null));

        promptAnswer = FirewallRuleCommand.Remove();
        elevator.Outcome = ElevationOutcome.AppliedOutcome;
        inspector.Report = Report(FirewallVerdict.NoRuleFound);
        await viewModel.RemoveFirewallRuleCommand.ExecuteAsync(null);

        Assert.True(lastPrompt!.IsRemoval);
        Assert.True(Assert.Single(elevator.Commands).IsRemoval);
        Assert.Contains("规则已移除", viewModel.FirewallActionResult, StringComparison.Ordinal);
        Assert.False(viewModel.FirewallManagedRuleExists);
        Assert.False(viewModel.RemoveFirewallRuleCommand.CanExecute(null));
    }

    [Fact]
    public async Task ExistingManagedRuleDisablesAllowUntilItIsRemoved()
    {
        Assert.True(viewModel.AllowFirewallPortCommand.CanExecute(null));

        // 规则存在但被禁用/被 Block 压过：仍不许再「放行」——netsh 会在同名下再建一条。
        inspector.Report = Report(FirewallVerdict.NoRuleFound, namedRuleExists: true);
        await viewModel.RefreshFirewallStatusCommand.ExecuteAsync(null);

        Assert.True(viewModel.FirewallManagedRuleExists);
        Assert.False(viewModel.AllowFirewallPortCommand.CanExecute(null));
        Assert.True(viewModel.RemoveFirewallRuleCommand.CanExecute(null));

        promptAnswer = FirewallRuleCommand.Remove();
        elevator.Outcome = ElevationOutcome.AppliedOutcome;
        inspector.Report = Report(FirewallVerdict.NoRuleFound);
        await viewModel.RemoveFirewallRuleCommand.ExecuteAsync(null);

        Assert.False(viewModel.FirewallManagedRuleExists);
        Assert.True(viewModel.AllowFirewallPortCommand.CanExecute(null));
        Assert.False(viewModel.RemoveFirewallRuleCommand.CanExecute(null));
    }

    [Fact]
    public void CommandTextsShowTheDefaultPrivateOnlyRule()
    {
        Assert.Equal(
            "netsh advfirewall firewall add rule name=\"ClipSync TCP 47654\" dir=in action=allow protocol=TCP localport=47654 profile=private",
            viewModel.FirewallAllowCommandText);
        Assert.StartsWith("New-NetFirewallRule ", viewModel.FirewallAllowPowerShellText, StringComparison.Ordinal);
    }

    public async ValueTask DisposeAsync()
    {
        adapter.Dispose();
        await store.DisposeAsync();
        Microsoft.Data.Sqlite.SqliteConnection.ClearAllPools();
        Directory.Delete(directory, recursive: true);
    }

    private static FirewallReport Report(
        FirewallVerdict verdict,
        FirewallProfiles active = FirewallProfiles.Private,
        int actualPort = 47654,
        string[]? allowing = null,
        string[]? blocking = null,
        bool namedRuleExists = false) =>
        new(
            verdict,
            active,
            allowing ?? [],
            blocking ?? [],
            verdict == FirewallVerdict.Allowed ? active : FirewallProfiles.None,
            FirewallProfiles.None,
            FirewallProfiles.None,
            ThirdPartyProductPresent: verdict == FirewallVerdict.Undetermined,
            GroupPolicyOverride: false,
            NamedRuleExists: namedRuleExists,
            47654,
            actualPort);

    private sealed class FakeInspector : IFirewallInspector
    {
        public FirewallReport Report { get; set; } = FirewallReport.Unavailable(47654, 0);

        public int Calls { get; private set; }

        public (int Expected, int Actual) LastPorts { get; private set; }

        public Task<FirewallReport> InspectAsync(int expectedPort, int actualPort, string exePath, CancellationToken cancellationToken)
        {
            Calls++;
            LastPorts = (expectedPort, actualPort);
            return Task.FromResult(Report);
        }
    }

    private sealed class FakeElevator : IFirewallRuleElevator
    {
        public ElevationOutcome Outcome { get; set; } = ElevationOutcome.AppliedOutcome;

        public List<FirewallRuleCommand> Commands { get; } = [];

        public Task<ElevationOutcome> RunAsync(FirewallRuleCommand command, CancellationToken cancellationToken)
        {
            Commands.Add(command);
            return Task.FromResult(Outcome);
        }
    }
}

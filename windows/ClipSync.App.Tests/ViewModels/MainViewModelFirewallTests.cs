using System.IO;
using ClipSync.App.Clipboard;
using ClipSync.App.Firewall;
using ClipSync.App.ViewModels;
using ClipSync.Core.Clipboard;
using ClipSync.Core.Storage;

namespace ClipSync.App.Tests.ViewModels;

/// <summary>
/// The conduit page's 防火墙 line and 放行/移除 flow (ADR 0006) against fakes, driven through
/// <see cref="MainViewModel.Firewall"/> so the wiring (live listener port, copy path, the
/// forwarded members the QR window and app layer bind) is covered too: the child states the
/// inspector's verdict as facts, never runs netsh without a confirmed command, and re-checks
/// after every elevated run so the result line matches what the firewall now holds.
/// </summary>
public sealed class MainViewModelFirewallTests : IAsyncDisposable
{
    private const string LocalDeviceId = "11111111-1111-4111-8111-111111111111";
    private const string StoredExePath = @"d:\apps\clipsync\clipsync.app.exe";

    /// <summary>The pair the security alert writes after 取消, as the check reports it (one entry for TCP + UDP).</summary>
    private static readonly FirewallProgramBlockRule AlertBlockRule = new("ClipSync.App", StoredExePath);

    private readonly string directory;
    private readonly SqliteClipboardEventStore store;
    private readonly Win32ClipboardAdapter adapter = new();
    private readonly FakeInspector inspector = new();
    private readonly FakeElevator elevator = new();
    private FirewallRuleCommand? promptAnswer;
    private FirewallRulePromptRequest? lastPrompt;
    private readonly MainViewModel viewModel;
    private readonly FirewallViewModel firewall;

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
        firewall = viewModel.Firewall;
    }

    [Fact]
    public async Task NoRuleFoundIsStatedInActColourWithPortMismatchAndBlockFacts()
    {
        viewModel.UpdatePeerStatus(online: true, port: 50123, connectedCount: 0);
        inspector.Report = Report(FirewallVerdict.NoRuleFound, actualPort: 50123, blocking: ["Blocked by user"]);

        await firewall.RefreshStatusCommand.ExecuteAsync(null);

        // The child reads the live listener port from the parent at check time.
        Assert.Equal((47654, 50123), inspector.LastPorts);
        Assert.True(firewall.RuleMissing);
        Assert.False(firewall.Allowed);
        Assert.Contains("未发现 TCP 47654", firewall.Status, StringComparison.Ordinal);
        Assert.Contains("监听在端口 50123", firewall.Detail, StringComparison.Ordinal);
        Assert.Contains("Blocked by user", firewall.Detail, StringComparison.Ordinal);
        Assert.False(firewall.Busy);
    }

    [Fact]
    public async Task ForwardedMembersMirrorTheChildForTheQrWindowAndAppLayer()
    {
        var raised = new List<string?>();
        viewModel.PropertyChanged += (_, e) => raised.Add(e.PropertyName);
        inspector.Report = Report(FirewallVerdict.NoRuleFound);

        // App.xaml.cs drives re-checks through this forwarded command.
        Assert.Same(firewall.RefreshStatusCommand, viewModel.RefreshFirewallStatusCommand);
        await viewModel.RefreshFirewallStatusCommand.ExecuteAsync(null);

        // PairingQrWindow binds nameof(MainViewModel.FirewallRuleMissing) on the main view model.
        Assert.True(viewModel.FirewallRuleMissing);
        Assert.Contains(nameof(MainViewModel.FirewallRuleMissing), raised);
        // And the flyout's second line picks the firewall fact up from the same change.
        Assert.Equal(SettingStatusTone.Attention, viewModel.TrayDetailStatus.Tone);
        Assert.Contains("TCP 47654", viewModel.TrayDetailStatus.Text, StringComparison.Ordinal);
    }

    [Fact]
    public async Task AllowedNamesTheCoveringRuleAndKeepsTheReachabilityCaveat()
    {
        inspector.Report = Report(FirewallVerdict.Allowed, allowing: [FirewallRuleCommand.RuleName], namedRuleExists: true);

        await firewall.RefreshStatusCommand.ExecuteAsync(null);

        Assert.True(firewall.Allowed);
        Assert.False(firewall.RuleMissing);
        Assert.True(firewall.ManagedRuleExists);
        Assert.True(firewall.RemoveRuleCommand.CanExecute(null));
        Assert.Contains("ClipSync TCP 47654", firewall.Status, StringComparison.Ordinal);
        Assert.Contains("AP 隔离", firewall.Detail, StringComparison.Ordinal);
    }

    [Fact]
    public async Task AllowedByASwitchedOffFirewallNamesTheProfilesInstead()
    {
        inspector.Report = Report(FirewallVerdict.Allowed, active: FirewallProfiles.Private | FirewallProfiles.Public);

        await firewall.RefreshStatusCommand.ExecuteAsync(null);

        Assert.Contains("专用网络 / 公用网络", firewall.Status, StringComparison.Ordinal);
    }

    [Fact]
    public async Task UndeterminedIsNeitherAllowedNorMissingAndHintsPublicNetwork()
    {
        inspector.Report = Report(FirewallVerdict.Undetermined, active: FirewallProfiles.Public);

        await firewall.RefreshStatusCommand.ExecuteAsync(null);

        Assert.False(firewall.Allowed);
        Assert.False(firewall.RuleMissing);
        Assert.True(firewall.PublicHintNeeded);
        Assert.Contains("无法判断", firewall.Status, StringComparison.Ordinal);
        Assert.False(firewall.RemoveRuleCommand.CanExecute(null));
    }

    [Fact]
    public async Task CancellingThePromptRunsNothing()
    {
        promptAnswer = null;

        await firewall.AllowPortCommand.ExecuteAsync(null);

        Assert.NotNull(lastPrompt);
        Assert.False(lastPrompt!.IsRemoval);
        Assert.Empty(elevator.Commands);
        Assert.Equal(string.Empty, firewall.ActionResult);
    }

    [Fact]
    public async Task ConfirmedAllowRunsTheChosenCommandThenRechecks()
    {
        inspector.Report = Report(FirewallVerdict.NoRuleFound, active: FirewallProfiles.Public);
        await firewall.RefreshStatusCommand.ExecuteAsync(null);
        promptAnswer = FirewallRuleCommand.Allow(FirewallProfiles.Private | FirewallProfiles.Public);
        elevator.Outcome = ElevationOutcome.AppliedOutcome;
        inspector.Report = Report(FirewallVerdict.Allowed, allowing: [FirewallRuleCommand.RuleName], namedRuleExists: true);

        await firewall.AllowPortCommand.ExecuteAsync(null);

        Assert.Equal(FirewallProfiles.Public, lastPrompt!.ActiveProfiles);
        var ran = Assert.Single(elevator.Commands);
        Assert.Equal("profile=private,public", ran.Arguments[^1]);
        Assert.Equal(2, inspector.Calls);
        Assert.True(firewall.Allowed);
        Assert.True(firewall.ManagedRuleExists);
        Assert.Contains("规则已创建", firewall.ActionResult, StringComparison.Ordinal);
    }

    [Fact]
    public async Task DeclinedUacIsStatedAsCancelledNotSuccess()
    {
        promptAnswer = FirewallRuleCommand.Allow(FirewallProfiles.Private);
        elevator.Outcome = ElevationOutcome.CancelledOutcome;
        inspector.Report = Report(FirewallVerdict.NoRuleFound);

        await firewall.AllowPortCommand.ExecuteAsync(null);

        Assert.Contains("未获得管理员授权", firewall.ActionResult, StringComparison.Ordinal);
        Assert.True(firewall.RuleMissing);
    }

    [Fact]
    public async Task NetshFailureReportsTheExitCode()
    {
        promptAnswer = FirewallRuleCommand.Allow(FirewallProfiles.Private);
        elevator.Outcome = new ElevationOutcome(ElevationStatus.Failed, 1);
        inspector.Report = Report(FirewallVerdict.NoRuleFound);

        await firewall.AllowPortCommand.ExecuteAsync(null);

        Assert.Contains("退出码 1", firewall.ActionResult, StringComparison.Ordinal);
    }

    [Fact]
    public async Task RemoveIsOnlyOfferedForTheManagedRuleAndStatesRemoval()
    {
        Assert.False(firewall.RemoveRuleCommand.CanExecute(null));
        inspector.Report = Report(FirewallVerdict.Allowed, allowing: [FirewallRuleCommand.RuleName], namedRuleExists: true);
        await firewall.RefreshStatusCommand.ExecuteAsync(null);
        Assert.True(firewall.RemoveRuleCommand.CanExecute(null));

        promptAnswer = FirewallRuleCommand.Remove();
        elevator.Outcome = ElevationOutcome.AppliedOutcome;
        inspector.Report = Report(FirewallVerdict.NoRuleFound);
        await firewall.RemoveRuleCommand.ExecuteAsync(null);

        Assert.True(lastPrompt!.IsRemoval);
        Assert.True(Assert.Single(elevator.Commands).IsRemoval);
        Assert.Contains("规则已移除", firewall.ActionResult, StringComparison.Ordinal);
        Assert.False(firewall.ManagedRuleExists);
        Assert.False(firewall.RemoveRuleCommand.CanExecute(null));
    }

    [Fact]
    public async Task ExistingManagedRuleDisablesAllowUntilItIsRemoved()
    {
        Assert.True(firewall.AllowPortCommand.CanExecute(null));

        // 规则存在但被禁用/被 Block 压过：仍不许再「放行」——netsh 会在同名下再建一条。
        inspector.Report = Report(FirewallVerdict.NoRuleFound, namedRuleExists: true);
        await firewall.RefreshStatusCommand.ExecuteAsync(null);

        Assert.True(firewall.ManagedRuleExists);
        Assert.False(firewall.AllowPortCommand.CanExecute(null));
        Assert.True(firewall.RemoveRuleCommand.CanExecute(null));

        promptAnswer = FirewallRuleCommand.Remove();
        elevator.Outcome = ElevationOutcome.AppliedOutcome;
        inspector.Report = Report(FirewallVerdict.NoRuleFound);
        await firewall.RemoveRuleCommand.ExecuteAsync(null);

        Assert.False(firewall.ManagedRuleExists);
        Assert.True(firewall.AllowPortCommand.CanExecute(null));
        Assert.False(firewall.RemoveRuleCommand.CanExecute(null));
    }

    [Fact]
    public async Task ManagedRuleShadowedByProgramBlocksKeepsAllowReachableAndReplacesItself()
    {
        // 真机 09-04：放行规则已在，但安全警报留下的程序级 Block 仍压着它——此时「放行」必须还能按，
        // 且请求要带 ReplaceExisting，脚本先删本应用的旧规则再建，不留两条同名。
        var block = new FirewallProgramBlockRule("ClipSync.App", @"d:\apps\clipsync.app.exe");
        inspector.Report = Report(FirewallVerdict.NoRuleFound, namedRuleExists: true, programBlocks: [block]);
        await firewall.RefreshStatusCommand.ExecuteAsync(null);

        Assert.True(firewall.ManagedRuleExists);
        Assert.True(firewall.AllowPortCommand.CanExecute(null));
        Assert.True(firewall.RemoveRuleCommand.CanExecute(null));

        promptAnswer = FirewallRuleCommand.Allow(FirewallProfiles.Private | FirewallProfiles.Public, [block], replaceExisting: true);
        elevator.Outcome = ElevationOutcome.AppliedOutcome;
        inspector.Report = Report(FirewallVerdict.Allowed, allowing: [FirewallRuleCommand.RuleName], namedRuleExists: true);
        await firewall.AllowPortCommand.ExecuteAsync(null);

        Assert.NotNull(lastPrompt);
        Assert.False(lastPrompt!.IsRemoval);
        Assert.True(lastPrompt.ReplaceExisting);
        Assert.Equal([block], lastPrompt.BlockRules);
        Assert.True(firewall.Allowed);
        // Blocks gone, own rule in place: nothing left for 放行 to do until the state changes again.
        Assert.False(firewall.AllowPortCommand.CanExecute(null));
    }

    [Fact]
    public void CommandTextsShowTheDefaultPrivateOnlyRule()
    {
        Assert.Equal(
            "netsh advfirewall firewall add rule name=\"ClipSync TCP 47654\" dir=in action=allow protocol=TCP localport=47654 profile=private",
            firewall.AllowCommandText);
        Assert.StartsWith("New-NetFirewallRule ", firewall.AllowPowerShellText, StringComparison.Ordinal);
    }

    [Fact]
    public async Task BlockRulesFromTheCheckLeadThePreviewAndRideIntoThePrompt()
    {
        var raised = new List<string?>();
        firewall.PropertyChanged += (_, e) => raised.Add(e.PropertyName);
        inspector.Report = Report(
            FirewallVerdict.NoRuleFound,
            active: FirewallProfiles.Public,
            blocking: ["ClipSync.App"],
            programBlocks: [AlertBlockRule]);

        await firewall.RefreshStatusCommand.ExecuteAsync(null);

        Assert.Contains(nameof(FirewallViewModel.AllowCommandText), raised);
        Assert.Contains(nameof(FirewallViewModel.AllowPowerShellText), raised);
        Assert.Equal(
            "netsh advfirewall firewall delete rule name=ClipSync.App dir=in program=" + StoredExePath + Environment.NewLine
            + "netsh advfirewall firewall add rule name=\"ClipSync TCP 47654\" dir=in action=allow protocol=TCP localport=47654 profile=private",
            firewall.AllowCommandText);
        Assert.StartsWith("Remove-NetFirewallRule -DisplayName \"ClipSync.App\"" + Environment.NewLine + "New-NetFirewallRule ", firewall.AllowPowerShellText, StringComparison.Ordinal);

        promptAnswer = FirewallRuleCommand.Allow(FirewallProfiles.Private | FirewallProfiles.Public, [AlertBlockRule]);
        elevator.Outcome = ElevationOutcome.AppliedOutcome;
        inspector.Report = Report(FirewallVerdict.Allowed, active: FirewallProfiles.Public, allowing: [FirewallRuleCommand.RuleName], namedRuleExists: true);

        await firewall.AllowPortCommand.ExecuteAsync(null);

        Assert.Equal(AlertBlockRule, Assert.Single(lastPrompt!.BlockRules));
        var ran = Assert.Single(elevator.Commands);
        Assert.True(ran.RequiresScript);
        Assert.Equal("ClipSync.App", Assert.Single(ran.BlockRuleNames));
        Assert.Contains("规则已创建", firewall.ActionResult, StringComparison.Ordinal);
        Assert.True(firewall.Allowed);
        // The re-check found no Block rule left, so the preview is back to the single add.
        Assert.StartsWith("netsh advfirewall firewall add rule", firewall.AllowCommandText, StringComparison.Ordinal);
    }

    [Fact]
    public async Task RemovePromptCarriesNoBlockRules()
    {
        inspector.Report = Report(FirewallVerdict.Allowed, allowing: [FirewallRuleCommand.RuleName], namedRuleExists: true, programBlocks: [AlertBlockRule]);
        await firewall.RefreshStatusCommand.ExecuteAsync(null);
        promptAnswer = null;

        await firewall.RemoveRuleCommand.ExecuteAsync(null);

        Assert.True(lastPrompt!.IsRemoval);
        Assert.Empty(lastPrompt.BlockRules);
    }

    [Fact]
    public async Task SkippedBlockRuleNamesAreStatedInTheResultAfterARun()
    {
        promptAnswer = FirewallRuleCommand.Allow(
            FirewallProfiles.Private,
            [new FirewallProgramBlockRule("Say \"no\"", StoredExePath), AlertBlockRule]);
        elevator.Outcome = ElevationOutcome.AppliedOutcome;
        inspector.Report = Report(FirewallVerdict.Allowed, allowing: [FirewallRuleCommand.RuleName], namedRuleExists: true);

        await firewall.AllowPortCommand.ExecuteAsync(null);

        Assert.Contains("规则已创建", firewall.ActionResult, StringComparison.Ordinal);
        Assert.Contains("未处理名称含引号的阻止规则：Say \"no\"", firewall.ActionResult, StringComparison.Ordinal);
    }

    [Fact]
    public async Task SkippedBlockRuleNamesAreNotMentionedWhenNothingRan()
    {
        promptAnswer = FirewallRuleCommand.Allow(
            FirewallProfiles.Private,
            [new FirewallProgramBlockRule("Say \"no\"", StoredExePath)]);
        elevator.Outcome = ElevationOutcome.CancelledOutcome;
        inspector.Report = Report(FirewallVerdict.NoRuleFound);

        await firewall.AllowPortCommand.ExecuteAsync(null);

        Assert.Contains("未获得管理员授权", firewall.ActionResult, StringComparison.Ordinal);
        Assert.DoesNotContain("Say", firewall.ActionResult, StringComparison.Ordinal);
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
        bool namedRuleExists = false,
        FirewallProgramBlockRule[]? programBlocks = null) =>
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
            actualPort)
        {
            ProgramBlockRules = programBlocks ?? [],
        };

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

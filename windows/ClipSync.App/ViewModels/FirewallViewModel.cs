using ClipSync.App.Firewall;
using ClipSync.App.Localization;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;

namespace ClipSync.App.ViewModels;

/// <summary>
/// 防火墙（ADR 0006）: read-only detection, the command shown verbatim, and 放行/移除 through
/// UAC. Detection runs at standard user rights; a rule is only ever written after the user
/// clicked, saw the full command and confirmed, and then Windows' own netsh.exe runs elevated.
/// The three verdicts are stated as facts — "a rule exists" is never rounded up to "reachable".
/// Exposed by <see cref="MainViewModel.Firewall"/>; XAML binds <c>Firewall.*</c>.
/// </summary>
/// <param name="listeningPort">The port the peer listener holds right now, 0 while it is down.</param>
/// <param name="copyText">Copies through the capture-suppression path so the write is not re-captured.</param>
public partial class FirewallViewModel(
    IFirewallInspector inspector,
    IFirewallRuleElevator elevator,
    Func<FirewallRulePromptRequest, FirewallRuleCommand?>? rulePrompt,
    Func<int> listeningPort,
    Action<string> copyText) : ObservableObject
{
    /// <summary>
    /// The exact netsh lines the default 放行 (private profile only) runs — the deletes of this
    /// program's Block rules the last check found, then the add; shown read-only and offered for copying.
    /// </summary>
    public string AllowCommandText => FirewallRuleCommand.Allow(FirewallProfiles.Private, programBlockRules).ToDisplayString();

    /// <summary>The same sequence as NetSecurity cmdlets (what docs/install.md quotes), for people who prefer PowerShell.</summary>
    public string AllowPowerShellText => FirewallRuleCommand.Allow(FirewallProfiles.Private, programBlockRules).ToPowerShellString();

    /// <summary>One line for the conduit network segment: 正在检测… / 已放行 / 未发现规则 / 无法判断.</summary>
    [ObservableProperty]
    private string status = string.Empty;

    /// <summary>Facts under the status line (port mismatch, block rules aimed at this exe, the reachability caveat); empty = hidden.</summary>
    [ObservableProperty]
    private string detail = string.Empty;

    /// <summary>True when the last check found no covering rule: act-coloured status, hint in the QR window.</summary>
    [ObservableProperty]
    private bool ruleMissing;

    /// <summary>True when the last check found the port allowed on every active profile.</summary>
    [ObservableProperty]
    private bool allowed;

    /// <summary>
    /// True when a rule named ClipSync TCP 47654 exists (whatever its state): the 移除 button shows
    /// and 放行 is disabled — netsh would otherwise add a second rule under the same name.
    /// </summary>
    [ObservableProperty]
    [NotifyCanExecuteChangedFor(nameof(AllowPortCommand))]
    [NotifyCanExecuteChangedFor(nameof(RemoveRuleCommand))]
    private bool managedRuleExists;

    /// <summary>True when Windows classes the current network as Public and the port is not allowed there: the profile hint shows.</summary>
    [ObservableProperty]
    private bool publicHintNeeded;

    /// <summary>Result line of the last 放行/移除/复制; empty until one runs.</summary>
    [ObservableProperty]
    private string actionResult = string.Empty;

    /// <summary>True while a check or an elevated run is in flight; freezes the firewall buttons.</summary>
    [ObservableProperty]
    [NotifyCanExecuteChangedFor(nameof(RefreshStatusCommand))]
    [NotifyCanExecuteChangedFor(nameof(AllowPortCommand))]
    [NotifyCanExecuteChangedFor(nameof(RemoveRuleCommand))]
    private bool busy;

    /// <summary>Active profiles from the last check, handed to the confirmation window for its Public-network hint.</summary>
    private FirewallProfiles activeProfiles = FirewallProfiles.Private;

    /// <summary>This program's Block rules from the last check (the dismissed alert's artefacts); 放行 deletes them in the same elevated run.</summary>
    private IReadOnlyList<FirewallProgramBlockRule> programBlockRules = [];

    private bool CanRefresh() => !Busy;

    /// <summary>
    /// Re-reads the Windows Firewall (read-only, standard user rights) and restates the network
    /// segment's 防火墙 line. The app layer runs it after the listener starts and after every
    /// network-change recovery pass; 重新检测 and each 放行/移除 run it again.
    /// </summary>
    [RelayCommand(CanExecute = nameof(CanRefresh))]
    private async Task RefreshStatusAsync()
    {
        Busy = true;
        try
        {
            await InspectAsync();
        }
        finally
        {
            Busy = false;
        }
    }

    private async Task InspectAsync()
    {
        Status = Strings.Conduit_Firewall_Checking;
        var report = await inspector.InspectAsync(
            FirewallRuleCommand.Port,
            listeningPort(),
            Environment.ProcessPath ?? string.Empty,
            CancellationToken.None);
        ApplyReport(report);
    }

    /// <summary>Maps a report onto the observable state; every sentence is a fact the check established.</summary>
    private void ApplyReport(FirewallReport report)
    {
        Allowed = report.Verdict == FirewallVerdict.Allowed;
        RuleMissing = report.Verdict == FirewallVerdict.NoRuleFound;
        ManagedRuleExists = report.NamedRuleExists;
        activeProfiles = report.ActiveProfiles;
        programBlockRules = report.ProgramBlockRules;
        OnPropertyChanged(nameof(AllowCommandText));
        OnPropertyChanged(nameof(AllowPowerShellText));
        AllowPortCommand.NotifyCanExecuteChanged();
        PublicHintNeeded = report.Verdict != FirewallVerdict.Allowed
            && (report.ActiveProfiles & FirewallProfiles.Public) != 0;
        Status = report.Verdict switch
        {
            FirewallVerdict.Allowed => Strings.Format(
                nameof(Strings.Conduit_Firewall_AllowedFormat), DescribeAllowSource(report)),
            FirewallVerdict.NoRuleFound => Strings.Conduit_Firewall_NoRule,
            _ => Strings.Conduit_Firewall_Undetermined,
        };

        var details = new List<string>(3);
        if (report.PortMismatch && report.Verdict != FirewallVerdict.Allowed)
        {
            details.Add(Strings.Format(nameof(Strings.Conduit_Firewall_PortMismatchFormat), report.ActualPort));
        }

        if (report.BlockingRuleNames.Count > 0)
        {
            details.Add(Strings.Format(
                nameof(Strings.Conduit_Firewall_BlockRuleFormat), string.Join(", ", report.BlockingRuleNames)));
        }

        if (report.Verdict == FirewallVerdict.Allowed)
        {
            details.Add(Strings.Conduit_Firewall_Caveat);
        }

        Detail = string.Join('\n', details);
    }

    /// <summary>What let the port through: the covering rule names, else the active profiles (firewall off or default-allow there).</summary>
    private static string DescribeAllowSource(FirewallReport report) =>
        report.MatchingAllowRuleNames.Count > 0
            ? string.Join(", ", report.MatchingAllowRuleNames)
            : string.Join(" / ", ProfileNames(report.ActiveProfiles));

    private static IEnumerable<string> ProfileNames(FirewallProfiles profiles)
    {
        if ((profiles & FirewallProfiles.Domain) != 0)
        {
            yield return Strings.Firewall_Profile_Domain;
        }

        if ((profiles & FirewallProfiles.Private) != 0)
        {
            yield return Strings.Firewall_Profile_Private;
        }

        if ((profiles & FirewallProfiles.Public) != 0)
        {
            yield return Strings.Firewall_Profile_Public;
        }
    }

    /// <summary>Copies the netsh line through the same suppression path as history copies, so the capture loop ignores the write.</summary>
    [RelayCommand]
    private void CopyRuleCommandText()
    {
        copyText(AllowCommandText);
        ActionResult = Strings.Conduit_FirewallRule_Copied;
    }

    // Re-running 放行 stays possible while Block rules aimed at this exe remain: they defeat the
    // app's own rule no matter how many times it is added, so the cleanup run must be reachable.
    private bool CanAllowPort() => !Busy && (!ManagedRuleExists || programBlockRules.Count > 0);

    /// <summary>
    /// 放行: the confirmation window shows the exact commands and the profile choice first
    /// (ADR 0006 §2), then Windows' own netsh runs through the system UAC prompt. When the last
    /// check found Block rules aimed at this exe, the same run deletes them before adding — an
    /// Allow rule alone would change nothing while they exist. A declined prompt is stated as
    /// such — never as success — and every outcome is followed by a fresh read-only check so the
    /// status line states what the firewall now holds.
    /// </summary>
    [RelayCommand(CanExecute = nameof(CanAllowPort))]
    private Task AllowPortAsync() =>
        RunRuleAsync(new FirewallRulePromptRequest(IsRemoval: false, activeProfiles, programBlockRules, ReplaceExisting: ManagedRuleExists));

    private bool CanRemoveRule() => !Busy && ManagedRuleExists;

    /// <summary>移除: same confirmation and UAC path; deletes only the rule this app named (ADR 0006 §3).</summary>
    [RelayCommand(CanExecute = nameof(CanRemoveRule))]
    private Task RemoveRuleAsync() =>
        RunRuleAsync(new FirewallRulePromptRequest(IsRemoval: true, activeProfiles, []));

    private async Task RunRuleAsync(FirewallRulePromptRequest request)
    {
        var command = (rulePrompt ?? PromptRule)(request);
        if (command is null)
        {
            return;
        }

        Busy = true;
        try
        {
            var outcome = await elevator.RunAsync(command, CancellationToken.None);
            await InspectAsync();
            var result = outcome.Status switch
            {
                ElevationStatus.Applied => command.IsRemoval
                    ? Strings.FirewallRule_Result_Removed
                    : Strings.FirewallRule_Result_Applied,
                ElevationStatus.Cancelled => Strings.FirewallRule_Result_Cancelled,
                _ => Strings.Format(
                    nameof(Strings.FirewallRule_Result_FailedFormat),
                    outcome.ExitCode?.ToString(System.Globalization.CultureInfo.InvariantCulture)
                        ?? outcome.FailureType
                        ?? "?"),
            };
            if (outcome.Status != ElevationStatus.Cancelled && command.SkippedBlockRuleNames.Count > 0)
            {
                result += "\n" + Strings.Format(
                    nameof(Strings.FirewallRule_Result_BlockSkippedFormat),
                    string.Join(", ", command.SkippedBlockRuleNames));
            }

            ActionResult = result;
        }
        finally
        {
            Busy = false;
        }
    }

    /// <summary>The default prompt: the modal confirmation window; null when the user cancelled it.</summary>
    private static FirewallRuleCommand? PromptRule(FirewallRulePromptRequest request)
    {
        var owner = System.Windows.Application.Current?.MainWindow;
        var window = new FirewallRuleWindow(request)
        {
            Owner = owner is { IsVisible: true } ? owner : null,
        };
        window.ShowDialog();
        return window.ConfirmedCommand;
    }
}

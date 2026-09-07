using System.ComponentModel;
using System.IO;
using System.Text;
using System.Text.RegularExpressions;
using ClipSync.App.Diagnostics;
using ClipSync.App.Firewall;

namespace ClipSync.App.Tests.Firewall;

/// <summary>
/// How the elevator hands a command to netsh (ADR 0006 §2) with the launcher faked: a plain
/// add or delete goes as argv tokens; a 放行 with Block-rule cleanup goes as one temporary
/// <c>netsh -f</c> script (UTF-8, no BOM — the only form netsh's script reader decodes), written
/// before the launch and deleted after it whatever the outcome, so one UAC consent covers the
/// whole sequence. Exit codes and a declined UAC map to the same three outcomes as before.
/// </summary>
public sealed class FirewallRuleElevatorTests
{
    private static readonly Regex ScriptName = new(@"^clipsync-firewall-[0-9a-f]{32}\.txt$", RegexOptions.CultureInvariant);

    private static readonly FirewallProgramBlockRule[] AlertBlockRules =
        [new FirewallProgramBlockRule("ClipSync.App", @"d:\apps\clipsync\clipsync.app.exe")];

    private readonly FakeLauncher launcher = new();
    private readonly FirewallRuleElevator elevator;

    public FirewallRuleElevatorTests()
    {
        elevator = new FirewallRuleElevator(launcher);
    }

    [Fact]
    public async Task SingleStepCommandGoesToSystem32NetshAsArgvWithoutAScript()
    {
        var command = FirewallRuleCommand.Allow(FirewallProfiles.Private);

        var outcome = await elevator.RunAsync(command, CancellationToken.None);

        Assert.Equal(ElevationOutcome.AppliedOutcome, outcome);
        Assert.Equal(Path.Combine(Environment.SystemDirectory, "netsh.exe"), launcher.FileName);
        Assert.Equal(command.Arguments, launcher.Arguments);
        Assert.Null(launcher.ScriptPath);
        Assert.Contains(LocalDiagnostics.Snapshot(), entry => entry.Code == "firewall_rule_add_applied");
    }

    [Fact]
    public async Task BlockCleanupRunsAsOneScriptFileThatIsRemovedAfterwards()
    {
        var command = FirewallRuleCommand.Allow(FirewallProfiles.Private | FirewallProfiles.Public, AlertBlockRules);

        var outcome = await elevator.RunAsync(command, CancellationToken.None);

        Assert.Equal(ElevationOutcome.AppliedOutcome, outcome);
        Assert.Equal(Path.Combine(Environment.SystemDirectory, "netsh.exe"), launcher.FileName);
        Assert.Equal(2, launcher.Arguments!.Count);
        Assert.Equal("-f", launcher.Arguments[0]);

        var scriptPath = launcher.ScriptPath!;
        Assert.Equal(scriptPath, launcher.Arguments[1]);
        Assert.Equal(Path.GetTempPath().TrimEnd(Path.DirectorySeparatorChar), Path.GetDirectoryName(scriptPath));
        Assert.Matches(ScriptName, Path.GetFileName(scriptPath));
        Assert.Equal(
            "advfirewall firewall delete rule name=ClipSync.App dir=in program=d:\\apps\\clipsync\\clipsync.app.exe\r\n"
            + "advfirewall firewall add rule name=\"ClipSync TCP 47654\" dir=in action=allow protocol=TCP localport=47654 profile=private,public\r\n",
            Encoding.UTF8.GetString(launcher.ScriptBytes!));
        Assert.Equal((byte)'a', launcher.ScriptBytes![0]);
        Assert.False(File.Exists(scriptPath));
        Assert.Contains(LocalDiagnostics.Snapshot(), entry => entry.Code == "firewall_rule_add_with_block_cleanup_applied");
    }

    [Fact]
    public async Task NonAsciiRuleNamesAreWrittenAsUtf8WithoutABom()
    {
        var command = FirewallRuleCommand.Allow(
            FirewallProfiles.Private,
            [new FirewallProgramBlockRule("阻止 剪剪相传", @"d:\apps\clipsync\clipsync.app.exe")]);

        await elevator.RunAsync(command, CancellationToken.None);

        var expected = new UTF8Encoding(encoderShouldEmitUTF8Identifier: false).GetBytes(command.ToScriptText());
        Assert.Equal(expected, launcher.ScriptBytes);
        Assert.StartsWith("advfirewall firewall delete rule name=\"阻止 剪剪相传\"", Encoding.UTF8.GetString(launcher.ScriptBytes!), StringComparison.Ordinal);
    }

    [Fact]
    public async Task DeclinedUacDuringAScriptRunIsCancelledAndTheScriptIsStillRemoved()
    {
        launcher.Failure = new Win32Exception(1223);
        var command = FirewallRuleCommand.Allow(FirewallProfiles.Private, AlertBlockRules);

        var outcome = await elevator.RunAsync(command, CancellationToken.None);

        Assert.Equal(ElevationOutcome.CancelledOutcome, outcome);
        Assert.NotNull(launcher.ScriptPath);
        Assert.False(File.Exists(launcher.ScriptPath));
        Assert.Contains(LocalDiagnostics.Snapshot(), entry => entry.Code == "firewall_rule_add_with_block_cleanup_cancelled");
    }

    [Fact]
    public async Task NonZeroScriptExitIsFailedWithNetshsCodeAndTheScriptIsRemoved()
    {
        launcher.ExitCode = 1;
        var command = FirewallRuleCommand.Allow(FirewallProfiles.Private, AlertBlockRules);

        var outcome = await elevator.RunAsync(command, CancellationToken.None);

        Assert.Equal(new ElevationOutcome(ElevationStatus.Failed, 1), outcome);
        Assert.False(File.Exists(launcher.ScriptPath));
        Assert.Contains(LocalDiagnostics.Snapshot(), entry => entry.Code == "firewall_rule_add_with_block_cleanup_failed_1");
    }

    [Fact]
    public async Task LaunchFailureNamesOnlyTheExceptionType()
    {
        launcher.Failure = new InvalidOperationException("secret details");

        var outcome = await elevator.RunAsync(FirewallRuleCommand.Remove(), CancellationToken.None);

        Assert.Equal(new ElevationOutcome(ElevationStatus.Failed, FailureType: "InvalidOperationException"), outcome);
        Assert.Contains(LocalDiagnostics.Snapshot(), entry => entry.Code == "firewall_rule_remove_failed_InvalidOperationException");
    }

    [Fact]
    public async Task NoProcessIsFailed()
    {
        launcher.ExitCode = null;

        var outcome = await elevator.RunAsync(FirewallRuleCommand.Allow(FirewallProfiles.Private), CancellationToken.None);

        Assert.Equal(new ElevationOutcome(ElevationStatus.Failed, FailureType: "NoProcess"), outcome);
    }

    private sealed class FakeLauncher : IElevatedProcessLauncher
    {
        public int? ExitCode { get; set; } = 0;

        public Exception? Failure { get; set; }

        public string? FileName { get; private set; }

        public IReadOnlyList<string>? Arguments { get; private set; }

        /// <summary>The script path netsh would have read, captured while the file still exists.</summary>
        public string? ScriptPath { get; private set; }

        public byte[]? ScriptBytes { get; private set; }

        public Task<int?> RunAsync(string fileName, IReadOnlyList<string> arguments, CancellationToken cancellationToken)
        {
            FileName = fileName;
            Arguments = arguments;
            if (arguments.Count == 2 && arguments[0] == "-f")
            {
                ScriptPath = arguments[1];
                ScriptBytes = File.ReadAllBytes(ScriptPath);
            }

            if (Failure is not null)
            {
                throw Failure;
            }

            return Task.FromResult(ExitCode);
        }
    }
}

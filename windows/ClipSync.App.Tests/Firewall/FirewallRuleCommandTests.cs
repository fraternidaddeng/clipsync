using ClipSync.App.Firewall;

namespace ClipSync.App.Tests.Firewall;

/// <summary>
/// The exact netsh invocation behind 放行/移除 (ADR 0006 §2–3): fixed rule name, by port not
/// by path, profile keyword from the user's choice, and a display string identical to what a
/// person would type — shown before the UAC prompt and offered for copying. When the check found
/// Block rules Windows wrote for this exe (the dismissed security alert), 放行 becomes a netsh
/// script: one delete per distinct rule, by name and stored program path, then the same add.
/// </summary>
public sealed class FirewallRuleCommandTests
{
    private const string StoredExePath = @"d:\paste\windows\clipsync.app\bin\debug\net8.0-windows10.0.19041.0\clipsync.app.exe";
    private const string AddPrivate = "advfirewall firewall add rule name=\"ClipSync TCP 47654\" dir=in action=allow protocol=TCP localport=47654 profile=private";

    [Fact]
    public void AllowPrivateBuildsTheDocumentedArguments()
    {
        var command = FirewallRuleCommand.Allow(FirewallProfiles.Private);

        Assert.False(command.IsRemoval);
        Assert.Equal(
            "advfirewall|firewall|add|rule|name=ClipSync TCP 47654|dir=in|action=allow|protocol=TCP|localport=47654|profile=private",
            string.Join('|', command.Arguments));
        Assert.Equal(
            "netsh advfirewall firewall add rule name=\"ClipSync TCP 47654\" dir=in action=allow protocol=TCP localport=47654 profile=private",
            command.ToDisplayString());
    }

    [Fact]
    public void AllowWithoutBlockRulesStaysASingleArgvCommand()
    {
        var command = FirewallRuleCommand.Allow(FirewallProfiles.Private, []);

        Assert.False(command.RequiresScript);
        Assert.Equal(AddPrivate, Assert.Single(command.ScriptLines));
        Assert.Same(command.Arguments, Assert.Single(command.Steps));
        Assert.Empty(command.BlockRuleNames);
        Assert.Empty(command.SkippedBlockRuleNames);
    }

    [Fact]
    public void OneBlockRuleIsDeletedByNameAndStoredProgramBeforeTheAdd()
    {
        var command = FirewallRuleCommand.Allow(
            FirewallProfiles.Private,
            [new FirewallProgramBlockRule("ClipSync.App", StoredExePath)]);

        Assert.True(command.RequiresScript);
        Assert.Equal(2, command.Steps.Count);
        Assert.Equal(
            [
                "advfirewall firewall delete rule name=ClipSync.App dir=in program=" + StoredExePath,
                AddPrivate,
            ],
            command.ScriptLines);
        Assert.Equal(
            "advfirewall|firewall|delete|rule|name=ClipSync.App|dir=in|program=" + StoredExePath,
            string.Join('|', command.Steps[0]));
        // The add itself is unchanged and still what Arguments names.
        Assert.Equal("profile=private", command.Arguments[^1]);
        Assert.Equal("add", command.Arguments[2]);
        Assert.Equal("ClipSync.App", Assert.Single(command.BlockRuleNames));
        Assert.Empty(command.SkippedBlockRuleNames);
    }

    [Fact]
    public void ReplaceExistingDeletesTheAppsOwnRuleFirstThenTheBlocksThenAdds()
    {
        var command = FirewallRuleCommand.Allow(
            FirewallProfiles.Private,
            [new FirewallProgramBlockRule("ClipSync.App", StoredExePath)],
            replaceExisting: true);

        Assert.True(command.RequiresScript);
        Assert.Equal(
            [
                "advfirewall firewall delete rule name=\"ClipSync TCP 47654\"",
                "advfirewall firewall delete rule name=ClipSync.App dir=in program=" + StoredExePath,
                AddPrivate,
            ],
            command.ScriptLines);
        Assert.Equal("add", command.Arguments[2]);
    }

    [Fact]
    public void ReplaceExistingWithoutBlocksIsStillOneScriptedDeleteAndAdd()
    {
        var command = FirewallRuleCommand.Allow(FirewallProfiles.Private, [], replaceExisting: true);

        Assert.True(command.RequiresScript);
        Assert.Equal(2, command.Steps.Count);
        Assert.Equal("advfirewall firewall delete rule name=\"ClipSync TCP 47654\"", command.ScriptLines[0]);
        Assert.Equal(AddPrivate, command.ScriptLines[1]);
    }

    [Fact]
    public void TheAlertsTcpAndUdpPairCollapsesIntoOneDelete()
    {
        var command = FirewallRuleCommand.Allow(
            FirewallProfiles.Private | FirewallProfiles.Public,
            [
                new FirewallProgramBlockRule("ClipSync.App", StoredExePath),
                new FirewallProgramBlockRule("ClipSync.App", StoredExePath),
            ]);

        Assert.Equal(
            [
                "advfirewall firewall delete rule name=ClipSync.App dir=in program=" + StoredExePath,
                "advfirewall firewall add rule name=\"ClipSync TCP 47654\" dir=in action=allow protocol=TCP localport=47654 profile=private,public",
            ],
            command.ScriptLines);
        Assert.Equal("ClipSync.App", Assert.Single(command.BlockRuleNames));
    }

    [Fact]
    public void DistinctBlockRulesGetOneDeleteEachInDetectionOrderWithQuotesWhereNeeded()
    {
        const string spacedPath = @"C:\Program Files (x86)\ClipSync\ClipSync.App.exe";
        var command = FirewallRuleCommand.Allow(
            FirewallProfiles.Private,
            [
                new FirewallProgramBlockRule("ClipSync.App", StoredExePath),
                new FirewallProgramBlockRule("Blocked ClipSync", spacedPath),
                new FirewallProgramBlockRule("ClipSync.App", spacedPath),
            ]);

        Assert.Equal(
            [
                "advfirewall firewall delete rule name=ClipSync.App dir=in program=" + StoredExePath,
                "advfirewall firewall delete rule name=\"Blocked ClipSync\" dir=in program=\"" + spacedPath + "\"",
                "advfirewall firewall delete rule name=ClipSync.App dir=in program=\"" + spacedPath + "\"",
                AddPrivate,
            ],
            command.ScriptLines);
        Assert.Equal(["ClipSync.App", "Blocked ClipSync"], command.BlockRuleNames);
        Assert.Equal(
            "netsh advfirewall firewall delete rule name=ClipSync.App dir=in program=" + StoredExePath + Environment.NewLine
            + "netsh advfirewall firewall delete rule name=\"Blocked ClipSync\" dir=in program=\"" + spacedPath + "\"" + Environment.NewLine
            + "netsh advfirewall firewall delete rule name=ClipSync.App dir=in program=\"" + spacedPath + "\"" + Environment.NewLine
            + "netsh " + AddPrivate,
            command.ToDisplayString());
    }

    [Fact]
    public void NamesANetshLineCannotCarryAreSkippedAndReported()
    {
        var command = FirewallRuleCommand.Allow(
            FirewallProfiles.Private,
            [
                new FirewallProgramBlockRule("Say \"no\"", StoredExePath),
                new FirewallProgramBlockRule("Two\r\nlines", StoredExePath),
                new FirewallProgramBlockRule("ClipSync.App", StoredExePath),
                new FirewallProgramBlockRule("Say \"no\"", StoredExePath),
            ]);

        Assert.Equal(
            [
                "advfirewall firewall delete rule name=ClipSync.App dir=in program=" + StoredExePath,
                AddPrivate,
            ],
            command.ScriptLines);
        Assert.Equal(["Say \"no\"", "Two\r\nlines"], command.SkippedBlockRuleNames);
        Assert.Equal("ClipSync.App", Assert.Single(command.BlockRuleNames));
        Assert.DoesNotContain("Say", command.ToDisplayString(), StringComparison.Ordinal);
        Assert.DoesNotContain("Say", command.ToPowerShellString(), StringComparison.Ordinal);
    }

    [Fact]
    public void ScriptTextIsOneCrlfTerminatedLinePerStep()
    {
        var command = FirewallRuleCommand.Allow(
            FirewallProfiles.Private,
            [new FirewallProgramBlockRule("ClipSync.App", StoredExePath)]);

        Assert.Equal(
            "advfirewall firewall delete rule name=ClipSync.App dir=in program=" + StoredExePath + "\r\n" + AddPrivate + "\r\n",
            command.ToScriptText());
        Assert.Equal(AddPrivate + "\r\n", FirewallRuleCommand.Allow(FirewallProfiles.Private).ToScriptText());
    }

    [Fact]
    public void PowerShellEquivalentRemovesTheBlockRulesByDisplayNameFirst()
    {
        var command = FirewallRuleCommand.Allow(
            FirewallProfiles.Private | FirewallProfiles.Public,
            [
                new FirewallProgramBlockRule("ClipSync.App", StoredExePath),
                new FirewallProgramBlockRule("ClipSync.App", StoredExePath),
                new FirewallProgramBlockRule("Blocked ClipSync", StoredExePath),
            ]);

        Assert.Equal(
            "Remove-NetFirewallRule -DisplayName \"ClipSync.App\"" + Environment.NewLine
            + "Remove-NetFirewallRule -DisplayName \"Blocked ClipSync\"" + Environment.NewLine
            + "New-NetFirewallRule -DisplayName \"ClipSync TCP 47654\" -Direction Inbound -Action Allow -Protocol TCP -LocalPort 47654 -Profile Private, Public",
            command.ToPowerShellString());
    }

    [Fact]
    public void AllowPrivateAndPublicJoinsTheProfilesInNetshOrder()
    {
        var command = FirewallRuleCommand.Allow(FirewallProfiles.Private | FirewallProfiles.Public);

        Assert.Equal("profile=private,public", command.Arguments[^1]);
        Assert.EndsWith(" profile=private,public", command.ToDisplayString(), StringComparison.Ordinal);
    }

    [Fact]
    public void AllowPublicOnlyIsPossibleButExplicit()
    {
        var command = FirewallRuleCommand.Allow(FirewallProfiles.Public);

        Assert.Equal("profile=public", command.Arguments[^1]);
    }

    [Fact]
    public void AllowWithNoProfileIsRefused()
    {
        Assert.Throws<ArgumentException>(() => FirewallRuleCommand.Allow(FirewallProfiles.None));
    }

    [Fact]
    public void RemoveDeletesOnlyTheManagedRuleByName()
    {
        var command = FirewallRuleCommand.Remove();

        Assert.True(command.IsRemoval);
        Assert.Equal(
            "advfirewall|firewall|delete|rule|name=ClipSync TCP 47654",
            string.Join('|', command.Arguments));
        Assert.Equal(
            "netsh advfirewall firewall delete rule name=\"ClipSync TCP 47654\"",
            command.ToDisplayString());
    }

    [Fact]
    public void RuleNameAndPortAreTheDocumentedConstants()
    {
        Assert.Equal("ClipSync TCP 47654", FirewallRuleCommand.RuleName);
        Assert.Equal(47654, FirewallRuleCommand.Port);
        Assert.Equal(ClipSync.App.Sync.PeerSyncHost.DefaultPort, FirewallRuleCommand.Port);
    }

    [Fact]
    public void PowerShellEquivalentMatchesTheInstallDocs()
    {
        Assert.Equal(
            "New-NetFirewallRule -DisplayName \"ClipSync TCP 47654\" -Direction Inbound -Action Allow -Protocol TCP -LocalPort 47654 -Profile Private",
            FirewallRuleCommand.Allow(FirewallProfiles.Private).ToPowerShellString());
        Assert.Equal(
            "New-NetFirewallRule -DisplayName \"ClipSync TCP 47654\" -Direction Inbound -Action Allow -Protocol TCP -LocalPort 47654 -Profile Private, Public",
            FirewallRuleCommand.Allow(FirewallProfiles.Private | FirewallProfiles.Public).ToPowerShellString());
        Assert.Equal(
            "Remove-NetFirewallRule -DisplayName \"ClipSync TCP 47654\"",
            FirewallRuleCommand.Remove().ToPowerShellString());
    }

    [Fact]
    public void ArgumentsContainNoShellQuotesTheProcessLauncherWouldDouble()
    {
        var allow = FirewallRuleCommand.Allow(FirewallProfiles.Private | FirewallProfiles.Public);

        Assert.All(allow.Arguments, argument => Assert.DoesNotContain('"', argument));
        Assert.All(FirewallRuleCommand.Remove().Arguments, argument => Assert.DoesNotContain('"', argument));
    }
}

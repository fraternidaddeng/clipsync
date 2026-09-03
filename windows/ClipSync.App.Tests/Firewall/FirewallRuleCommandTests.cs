using ClipSync.App.Firewall;

namespace ClipSync.App.Tests.Firewall;

/// <summary>
/// The exact netsh invocation behind 放行/移除 (ADR 0006 §2–3): fixed rule name, by port not
/// by path, profile keyword from the user's choice, and a display string identical to what a
/// person would type — shown before the UAC prompt and offered for copying.
/// </summary>
public sealed class FirewallRuleCommandTests
{
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

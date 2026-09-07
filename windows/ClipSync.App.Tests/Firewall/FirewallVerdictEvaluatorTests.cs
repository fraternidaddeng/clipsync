using ClipSync.App.Firewall;

namespace ClipSync.App.Tests.Firewall;

/// <summary>
/// The pure firewall verdict (ADR 0006 §1): per active profile, an enabled inbound TCP Allow
/// rule covering the port for this exe (or no program) means allowed; a Block rule aimed at
/// this exe wins; BlockAllInbound or a missing rule means not allowed; a third-party product
/// makes the answer undetermined; a switched-off firewall needs no rule.
/// </summary>
public sealed class FirewallVerdictEvaluatorTests
{
    private const int Port = 47654;
    private const string ExePath = @"C:\Apps\ClipSync\ClipSync.App.exe";

    [Theory]
    [InlineData("47654")]
    [InlineData("47000-48000")]
    [InlineData("80,443,47654")]
    [InlineData("*")]
    [InlineData(null)]
    public void AllowedWhenAnEnabledInboundTcpAllowRuleCoversThePort(string? localPorts)
    {
        var report = Evaluate(Rule("Custom allow", localPorts: localPorts));

        Assert.Equal(FirewallVerdict.Allowed, report.Verdict);
        Assert.Equal("Custom allow", Assert.Single(report.MatchingAllowRuleNames));
        Assert.Empty(report.BlockingRuleNames);
        Assert.Equal(FirewallProfiles.Private, report.AllowedProfiles);
    }

    [Theory]
    [InlineData(null)]
    [InlineData("")]
    [InlineData(ExePath)]
    [InlineData(@"c:\apps\clipsync\CLIPSYNC.APP.EXE")]
    public void AllowedWhenTheRuleNamesNoProgramOrThisExeInAnyCase(string? applicationName)
    {
        var report = Evaluate(Rule("Program rule", applicationName: applicationName));

        Assert.Equal(FirewallVerdict.Allowed, report.Verdict);
    }

    // 真机 09-04：Store/打包应用的规则（协议 Any、无端口、无程序、三配置文件全允许）挂着用户 SID 或包 ID，
    // 只对该包的流量生效；评估器曾把它们当成全局放行而误报"已放行"，手机实测仍被拦。
    [Theory]
    [InlineData("S-1-5-21-4261912661-680185022-1787628989-1002", null)]
    [InlineData(null, "S-1-15-2-1234567890-1-2-3-4-5-6")]
    [InlineData("S-1-5-21-1-2-3-1002", "S-1-15-2-1234567890-1-2-3-4-5-6")]
    public void StorePackageRulesScopedToAUserOrPackageNeverCountAsAllowing(string? userOwner, string? packageId)
    {
        var storeRule = Rule(
            "ChatGPT",
            protocol: FirewallComValues.ProtocolAny,
            localPorts: null,
            applicationName: null) with
        {
            LocalUserOwner = userOwner,
            LocalAppPackageId = packageId,
        };

        var report = Evaluate(storeRule);

        Assert.Equal(FirewallVerdict.NoRuleFound, report.Verdict);
        Assert.Empty(report.MatchingAllowRuleNames);
    }

    [Fact]
    public void StorePackageBlockRulesDoNotCountAgainstTheListenerEither()
    {
        var storeBlock = Rule(
            "Some package",
            protocol: FirewallComValues.ProtocolAny,
            localPorts: null,
            applicationName: null,
            action: FirewallComValues.ActionBlock) with
        {
            LocalAppPackageId = "S-1-15-2-1-2-3-4-5-6-7",
        };

        var report = Evaluate(storeBlock, Rule("Custom allow"));

        Assert.Equal(FirewallVerdict.Allowed, report.Verdict);
        Assert.Empty(report.BlockingRuleNames);
    }

    [Fact]
    public void AllowedRuleForAnotherProgramDoesNotCount()
    {
        var report = Evaluate(Rule("Other app", applicationName: @"C:\Other\other.exe"));

        Assert.Equal(FirewallVerdict.NoRuleFound, report.Verdict);
        Assert.Empty(report.MatchingAllowRuleNames);
    }

    [Fact]
    public void NoRuleFoundWhenThereAreNoRulesAtAll()
    {
        var report = Evaluate();

        Assert.Equal(FirewallVerdict.NoRuleFound, report.Verdict);
        Assert.False(report.NamedRuleExists);
        Assert.Equal(FirewallProfiles.None, report.AllowedProfiles);
    }

    [Fact]
    public void DisabledRuleDoesNotAllow()
    {
        var report = Evaluate(Rule("Off", enabled: false));

        Assert.Equal(FirewallVerdict.NoRuleFound, report.Verdict);
    }

    [Fact]
    public void OutboundRuleDoesNotAllow()
    {
        var report = Evaluate(Rule("Out", direction: FirewallComValues.DirectionOut));

        Assert.Equal(FirewallVerdict.NoRuleFound, report.Verdict);
    }

    [Fact]
    public void UdpRuleDoesNotAllow()
    {
        var report = Evaluate(Rule("Udp", protocol: FirewallComValues.ProtocolUdp));

        Assert.Equal(FirewallVerdict.NoRuleFound, report.Verdict);
    }

    [Fact]
    public void AnyProtocolRuleAllows()
    {
        var report = Evaluate(Rule("Any", protocol: FirewallComValues.ProtocolAny));

        Assert.Equal(FirewallVerdict.Allowed, report.Verdict);
    }

    [Fact]
    public void RuleScopedToAnotherProfileDoesNotAllow()
    {
        var report = Evaluate(Rule("Public only", profiles: (int)FirewallProfiles.Public));

        Assert.Equal(FirewallVerdict.NoRuleFound, report.Verdict);
    }

    [Fact]
    public void RuleForAllProfilesAllowsOnTheActiveOne()
    {
        var report = Evaluate(Rule("Everywhere", profiles: FirewallComValues.ProfilesAll));

        Assert.Equal(FirewallVerdict.Allowed, report.Verdict);
    }

    [Fact]
    public void BlockRuleAimedAtThisExeWinsOverAnAllowRule()
    {
        var report = Evaluate(
            Rule("Allow port"),
            Rule("Blocked by user", action: FirewallComValues.ActionBlock, applicationName: ExePath, localPorts: "*"));

        Assert.Equal(FirewallVerdict.NoRuleFound, report.Verdict);
        Assert.Equal("Blocked by user", Assert.Single(report.BlockingRuleNames));
        Assert.Empty(report.MatchingAllowRuleNames);
    }

    [Fact]
    public void BlockRuleForAnotherProgramIsIgnored()
    {
        var report = Evaluate(
            Rule("Allow port"),
            Rule("Block other", action: FirewallComValues.ActionBlock, applicationName: @"C:\Other\other.exe"));

        Assert.Equal(FirewallVerdict.Allowed, report.Verdict);
        Assert.Empty(report.BlockingRuleNames);
        Assert.Empty(report.ProgramBlockRules);
    }

    [Fact]
    public void ProgramBlockRulesListTheDismissedAlertsPairOnceWithTheStoredPath()
    {
        // What the security alert writes after 取消: TCP + UDP, Public only, path lower-cased, any port.
        const string storedPath = @"c:\apps\clipsync\clipsync.app.exe";
        var report = Evaluate(
            Rule("Allow port"),
            Rule("ClipSync.App", protocol: FirewallComValues.ProtocolTcp, localPorts: null, applicationName: storedPath, action: FirewallComValues.ActionBlock, profiles: (int)FirewallProfiles.Public),
            Rule("ClipSync.App", protocol: FirewallComValues.ProtocolUdp, localPorts: null, applicationName: storedPath, action: FirewallComValues.ActionBlock, profiles: (int)FirewallProfiles.Public));

        // On the active Private profile nothing blocks — yet the pair is reported for the cleanup,
        // since it would defeat the Allow rule the moment the network is classed Public.
        Assert.Equal(FirewallVerdict.Allowed, report.Verdict);
        Assert.Empty(report.BlockingRuleNames);
        Assert.Equal(new FirewallProgramBlockRule("ClipSync.App", storedPath), Assert.Single(report.ProgramBlockRules));
    }

    [Fact]
    public void ProgramBlockRulesSkipWhatTheDeleteMustNotTouch()
    {
        var report = Evaluate(
            Rule("Disabled", enabled: false, applicationName: ExePath, action: FirewallComValues.ActionBlock),
            Rule("Outbound", direction: FirewallComValues.DirectionOut, applicationName: ExePath, action: FirewallComValues.ActionBlock),
            Rule("Other program", applicationName: @"C:\Other\other.exe", action: FirewallComValues.ActionBlock),
            Rule("Port block without program", localPorts: "47654", action: FirewallComValues.ActionBlock),
            Rule("Allow for this exe", applicationName: ExePath));

        // The program-less port block still counts against the verdict but is the user's own rule.
        Assert.Equal(FirewallVerdict.NoRuleFound, report.Verdict);
        Assert.Equal("Port block without program", Assert.Single(report.BlockingRuleNames));
        Assert.Empty(report.ProgramBlockRules);
    }

    [Fact]
    public void BlockAllInboundTrafficOnTheActiveProfileMeansNoRuleFound()
    {
        var snapshot = Snapshot(
            FirewallProfiles.Private,
            [ProfileState(FirewallProfiles.Private, blockAllInbound: true)],
            Rule("Allow port"));

        var report = FirewallVerdictEvaluator.Evaluate(snapshot, ExePath, Port, Port);

        Assert.Equal(FirewallVerdict.NoRuleFound, report.Verdict);
        Assert.Equal(FirewallProfiles.Private, report.BlockAllInboundProfiles);
    }

    [Fact]
    public void EveryActiveProfileMustBeAllowed()
    {
        var snapshot = Snapshot(
            FirewallProfiles.Private | FirewallProfiles.Public,
            [ProfileState(FirewallProfiles.Private), ProfileState(FirewallProfiles.Public)],
            Rule("Private only", profiles: (int)FirewallProfiles.Private));

        var report = FirewallVerdictEvaluator.Evaluate(snapshot, ExePath, Port, Port);

        Assert.Equal(FirewallVerdict.NoRuleFound, report.Verdict);
        Assert.Equal(FirewallProfiles.Private, report.AllowedProfiles);
        Assert.Equal(FirewallProfiles.Private | FirewallProfiles.Public, report.ActiveProfiles);
    }

    [Fact]
    public void ThirdPartyProductMakesTheVerdictUndetermined()
    {
        var snapshot = Snapshot(
            FirewallProfiles.Private,
            [ProfileState(FirewallProfiles.Private)],
            thirdPartyProducts: 1,
            Rule("Allow port"));

        var report = FirewallVerdictEvaluator.Evaluate(snapshot, ExePath, Port, Port);

        Assert.Equal(FirewallVerdict.Undetermined, report.Verdict);
        Assert.True(report.ThirdPartyProductPresent);
        // What was readable still rides along for the UI.
        Assert.Equal("Allow port", Assert.Single(report.MatchingAllowRuleNames));
    }

    [Fact]
    public void SwitchedOffFirewallNeedsNoRule()
    {
        var snapshot = Snapshot(
            FirewallProfiles.Private,
            [ProfileState(FirewallProfiles.Private, firewallEnabled: false)]);

        var report = FirewallVerdictEvaluator.Evaluate(snapshot, ExePath, Port, Port);

        Assert.Equal(FirewallVerdict.Allowed, report.Verdict);
        Assert.Equal(FirewallProfiles.Private, report.FirewallDisabledProfiles);
        Assert.True(report.AllowedOnlyByDisabledFirewall);
    }

    [Fact]
    public void DefaultInboundAllowNeedsNoRule()
    {
        var snapshot = Snapshot(
            FirewallProfiles.Private,
            [ProfileState(FirewallProfiles.Private, defaultInboundAction: FirewallComValues.ActionAllow)]);

        var report = FirewallVerdictEvaluator.Evaluate(snapshot, ExePath, Port, Port);

        Assert.Equal(FirewallVerdict.Allowed, report.Verdict);
        Assert.False(report.AllowedOnlyByDisabledFirewall);
    }

    [Theory]
    [InlineData("RPC")]
    [InlineData("RPC-EPMap")]
    [InlineData("IPHTTPS")]
    [InlineData("Teredo")]
    [InlineData("Ply2Disc")]
    public void KeywordPortsNeverCoverAPlainPort(string keyword)
    {
        Assert.False(FirewallVerdictEvaluator.LocalPortsCover(keyword, Port));
        Assert.Equal(FirewallVerdict.NoRuleFound, Evaluate(Rule("Keyword", localPorts: keyword)).Verdict);
    }

    [Theory]
    [InlineData("47653", false)]
    [InlineData("47655-48000", false)]
    [InlineData(" 47654 ", true)]
    [InlineData("1-65535", true)]
    [InlineData("47654-", false)]
    [InlineData("-47654", false)]
    public void LocalPortsCoverParsesSinglesRangesAndGarbage(string localPorts, bool expected)
    {
        Assert.Equal(expected, FirewallVerdictEvaluator.LocalPortsCover(localPorts, Port));
    }

    [Fact]
    public void NoActiveProfileIsJudgedAsPublic()
    {
        var snapshot = Snapshot(
            FirewallProfiles.None,
            [ProfileState(FirewallProfiles.Public)],
            Rule("Private only", profiles: (int)FirewallProfiles.Private));

        var report = FirewallVerdictEvaluator.Evaluate(snapshot, ExePath, Port, Port);

        Assert.Equal(FirewallProfiles.Public, report.ActiveProfiles);
        Assert.Equal(FirewallVerdict.NoRuleFound, report.Verdict);
    }

    [Fact]
    public void ManagedRuleIsReportedByNameWhateverItsState()
    {
        var report = Evaluate(Rule(FirewallRuleCommand.RuleName, enabled: false));

        Assert.True(report.NamedRuleExists);
        Assert.Equal(FirewallVerdict.NoRuleFound, report.Verdict);
    }

    [Fact]
    public void ListeningOnAnotherPortIsJudgedAgainstThatPortAndFlagged()
    {
        var snapshot = Snapshot(FirewallProfiles.Private, [ProfileState(FirewallProfiles.Private)], Rule("Port rule"));

        var report = FirewallVerdictEvaluator.Evaluate(snapshot, ExePath, Port, actualPort: 50123);

        Assert.Equal(FirewallVerdict.NoRuleFound, report.Verdict);
        Assert.True(report.PortMismatch);
        Assert.Equal(Port, report.TargetPort);
        Assert.Equal(50123, report.ActualPort);
    }

    [Fact]
    public void WildcardRuleStillCoversAFallbackPort()
    {
        var snapshot = Snapshot(FirewallProfiles.Private, [ProfileState(FirewallProfiles.Private)], Rule("Any port", localPorts: "*"));

        var report = FirewallVerdictEvaluator.Evaluate(snapshot, ExePath, Port, actualPort: 50123);

        Assert.Equal(FirewallVerdict.Allowed, report.Verdict);
        Assert.True(report.PortMismatch);
    }

    [Fact]
    public void OfflineProcessIsJudgedAgainstTheTargetPort()
    {
        var snapshot = Snapshot(FirewallProfiles.Private, [ProfileState(FirewallProfiles.Private)], Rule("Port rule"));

        var report = FirewallVerdictEvaluator.Evaluate(snapshot, ExePath, Port, actualPort: 0);

        Assert.Equal(FirewallVerdict.Allowed, report.Verdict);
        Assert.False(report.PortMismatch);
    }

    [Fact]
    public void UnavailableReportIsUndeterminedWithEmptyLists()
    {
        var report = FirewallReport.Unavailable(Port, Port);

        Assert.Equal(FirewallVerdict.Undetermined, report.Verdict);
        Assert.Empty(report.MatchingAllowRuleNames);
        Assert.Empty(report.BlockingRuleNames);
        Assert.False(report.NamedRuleExists);
    }

    private static FirewallReport Evaluate(params FirewallRuleInfo[] rules) =>
        FirewallVerdictEvaluator.Evaluate(
            Snapshot(FirewallProfiles.Private, [ProfileState(FirewallProfiles.Private)], rules),
            ExePath,
            Port,
            Port);

    private static FirewallSnapshot Snapshot(
        FirewallProfiles active,
        IReadOnlyList<FirewallProfileState> profiles,
        params FirewallRuleInfo[] rules) =>
        Snapshot(active, profiles, thirdPartyProducts: 0, rules);

    private static FirewallSnapshot Snapshot(
        FirewallProfiles active,
        IReadOnlyList<FirewallProfileState> profiles,
        int thirdPartyProducts,
        params FirewallRuleInfo[] rules) =>
        new(active, profiles, rules, thirdPartyProducts, FirewallComValues.ModifyStateOk);

    private static FirewallProfileState ProfileState(
        FirewallProfiles profile,
        bool firewallEnabled = true,
        int defaultInboundAction = FirewallComValues.ActionBlock,
        bool blockAllInbound = false) =>
        new(profile, firewallEnabled, defaultInboundAction, blockAllInbound);

    private static FirewallRuleInfo Rule(
        string name,
        bool enabled = true,
        int direction = FirewallComValues.DirectionIn,
        int protocol = FirewallComValues.ProtocolTcp,
        string? localPorts = "47654",
        string? applicationName = null,
        int action = FirewallComValues.ActionAllow,
        int profiles = FirewallComValues.ProfilesAll) =>
        new(name, enabled, direction, protocol, localPorts, applicationName, action, profiles);
}

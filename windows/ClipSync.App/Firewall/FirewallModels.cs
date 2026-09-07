namespace ClipSync.App.Firewall;

/// <summary>
/// The three honest answers the conduit page can give about the Windows Firewall (ADR 0006):
/// an enabled inbound Allow rule covers the port on every active profile; no such rule (or the
/// rule is disabled / off-profile / the exe is blocked / all inbound is blocked); or nothing
/// can be said because a third-party product or a failed COM query took the firewall out of view.
/// </summary>
public enum FirewallVerdict
{
    Allowed,
    NoRuleFound,
    Undetermined,
}

/// <summary>Windows Firewall profile bitmask (NET_FW_PROFILE_TYPE2); several bits may be active at once.</summary>
[Flags]
public enum FirewallProfiles
{
    None = 0,
    Domain = 1,
    Private = 2,
    Public = 4,
}

/// <summary>Raw NET_FW constants as the COM API reports them; kept numeric so the DTOs mirror the wire values.</summary>
public static class FirewallComValues
{
    public const int DirectionIn = 1;
    public const int DirectionOut = 2;
    public const int ProtocolTcp = 6;
    public const int ProtocolUdp = 17;
    public const int ProtocolAny = 256;
    public const int ActionBlock = 0;
    public const int ActionAllow = 1;
    public const int ProfilesAll = 0x7FFFFFFF;
    public const int ModifyStateOk = 0;
    public const int ModifyStateGroupPolicyOverride = 1;
    public const int ModifyStateInboundBlocked = 2;
}

/// <summary>
/// One firewall rule as read from INetFwRule (plus the INetFwRule3 scoping members); nullable
/// strings mirror the COM nulls. A rule that names a user owner or an app package applies only
/// to that user's or that package's traffic — never to this desktop process.
/// </summary>
public sealed record FirewallRuleInfo(
    string Name,
    bool Enabled,
    int Direction,
    int Protocol,
    string? LocalPorts,
    string? ApplicationName,
    int Action,
    int Profiles)
{
    public string? LocalUserOwner { get; init; }

    public string? LocalAppPackageId { get; init; }

    /// <summary>True when the rule is scoped to a user or an app package rather than to programs at large.</summary>
    public bool IsScopedToUserOrPackage =>
        !string.IsNullOrWhiteSpace(LocalUserOwner) || !string.IsNullOrWhiteSpace(LocalAppPackageId);
}

/// <summary>Per-profile switches read from INetFwPolicy2.</summary>
public sealed record FirewallProfileState(
    FirewallProfiles Profile,
    bool FirewallEnabled,
    int DefaultInboundAction,
    bool BlockAllInboundTraffic);

/// <summary>Everything the read-only inspection collected; the evaluator's sole input besides the query.</summary>
public sealed record FirewallSnapshot(
    FirewallProfiles ActiveProfiles,
    IReadOnlyList<FirewallProfileState> Profiles,
    IReadOnlyList<FirewallRuleInfo> Rules,
    int ThirdPartyProductCount,
    int LocalPolicyModifyState);

/// <summary>
/// An enabled inbound Block rule whose program is this exe — what Windows writes when the
/// security alert is dismissed. <see cref="Program"/> is the path exactly as the firewall
/// stored it (the alert writes it lower-cased), so the cleanup delete matches by that string
/// rather than by <c>Environment.ProcessPath</c>.
/// </summary>
public sealed record FirewallProgramBlockRule(string Name, string Program);

/// <summary>
/// What the confirmation step (ADR 0006 §2: show the command before the UAC prompt) needs to
/// know: whether the user is adding or removing the managed rule, which network profiles are
/// active right now (a Public-only network gets its hint before the profile choice), and which
/// program-scoped Block rules the 放行 run deletes first (empty for 移除).
/// </summary>
public sealed record FirewallRulePromptRequest(
    bool IsRemoval,
    FirewallProfiles ActiveProfiles,
    IReadOnlyList<FirewallProgramBlockRule> BlockRules,
    bool ReplaceExisting = false);

/// <summary>
/// The evaluator's structured answer. Every list is empty rather than null so the presenter
/// can join names without guards; <see cref="ActiveProfiles"/> is what the verdict was judged against.
/// </summary>
public sealed record FirewallReport(
    FirewallVerdict Verdict,
    FirewallProfiles ActiveProfiles,
    IReadOnlyList<string> MatchingAllowRuleNames,
    IReadOnlyList<string> BlockingRuleNames,
    FirewallProfiles AllowedProfiles,
    FirewallProfiles FirewallDisabledProfiles,
    FirewallProfiles BlockAllInboundProfiles,
    bool ThirdPartyProductPresent,
    bool GroupPolicyOverride,
    bool NamedRuleExists,
    int TargetPort,
    int ActualPort)
{
    /// <summary>
    /// Enabled inbound Block rules aimed at this exe on any profile, one entry per distinct
    /// name + stored path (the alert's TCP and UDP pair collapses to one). While any exists the
    /// firewall ignores every Allow rule for this program, so 放行 deletes them first.
    /// </summary>
    public IReadOnlyList<FirewallProgramBlockRule> ProgramBlockRules { get; init; } = [];

    /// <summary>True when the process listens somewhere other than the port the rule names.</summary>
    public bool PortMismatch => ActualPort != 0 && ActualPort != TargetPort;

    /// <summary>All active profiles are satisfied purely because the firewall is switched off on them.</summary>
    public bool AllowedOnlyByDisabledFirewall =>
        Verdict == FirewallVerdict.Allowed
        && ActiveProfiles != FirewallProfiles.None
        && (FirewallDisabledProfiles & ActiveProfiles) == ActiveProfiles;

    /// <summary>The report for an inspection that could not read the firewall at all.</summary>
    public static FirewallReport Unavailable(int targetPort, int actualPort) => new(
        FirewallVerdict.Undetermined,
        FirewallProfiles.None,
        [],
        [],
        FirewallProfiles.None,
        FirewallProfiles.None,
        FirewallProfiles.None,
        ThirdPartyProductPresent: false,
        GroupPolicyOverride: false,
        NamedRuleExists: false,
        targetPort,
        actualPort);
}

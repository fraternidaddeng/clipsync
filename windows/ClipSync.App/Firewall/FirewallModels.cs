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

/// <summary>One firewall rule as read from INetFwRule; nullable strings mirror the COM nulls.</summary>
public sealed record FirewallRuleInfo(
    string Name,
    bool Enabled,
    int Direction,
    int Protocol,
    string? LocalPorts,
    string? ApplicationName,
    int Action,
    int Profiles);

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
/// What the confirmation step (ADR 0006 §2: show the command before the UAC prompt) needs to
/// know: whether the user is adding or removing the managed rule, and which network profiles
/// are active right now, so a Public-only network gets its hint before the profile choice.
/// </summary>
public sealed record FirewallRulePromptRequest(bool IsRemoval, FirewallProfiles ActiveProfiles);

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

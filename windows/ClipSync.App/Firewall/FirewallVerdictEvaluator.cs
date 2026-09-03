using System.Globalization;

namespace ClipSync.App.Firewall;

/// <summary>
/// The pure half of the firewall check (ADR 0006 §1): given a snapshot of profiles and rules,
/// decide per active profile whether inbound TCP on the port this process listens on reaches
/// this exe, then fold the profiles into one verdict. Block rules aimed at this exe win over
/// Allow rules, exactly as the firewall itself resolves them; a profile with the firewall
/// switched off, or whose default inbound action is Allow, needs no rule. A registered
/// third-party product makes the whole answer <see cref="FirewallVerdict.Undetermined"/> — the
/// collected details still ride along so the UI can show what was readable.
/// </summary>
/// <remarks>
/// <c>targetPort</c> is the port the managed rule names (the default listening port);
/// <c>actualPort</c> is where the process listens right now (0 when offline). Rules are
/// matched against the actual port when there is one (ADR 0006 §4: after a fallback to an
/// ephemeral port the per-port rule is honestly reported as not covering this run), and
/// against the target port while offline.
/// </remarks>
internal static class FirewallVerdictEvaluator
{
    /// <summary>The rule name the one-click 放行 creates; its presence unlocks the 移除 button.</summary>
    public const string ManagedRuleName = FirewallRuleCommand.RuleName;

    private static readonly FirewallProfiles[] KnownProfiles =
        [FirewallProfiles.Domain, FirewallProfiles.Private, FirewallProfiles.Public];

    public static FirewallReport Evaluate(FirewallSnapshot snapshot, string exePath, int targetPort, int actualPort)
    {
        ArgumentNullException.ThrowIfNull(snapshot);
        ArgumentNullException.ThrowIfNull(exePath);

        var port = actualPort != 0 ? actualPort : targetPort;

        // No active network profile reads back as 0 from CurrentProfileTypes; Windows treats an
        // unidentified network as Public, so the verdict is judged against Public too.
        var active = snapshot.ActiveProfiles == FirewallProfiles.None
            ? FirewallProfiles.Public
            : snapshot.ActiveProfiles;

        var allowRuleNames = new List<string>();
        var blockRuleNames = new List<string>();
        var allowedProfiles = FirewallProfiles.None;
        var disabledProfiles = FirewallProfiles.None;
        var blockAllProfiles = FirewallProfiles.None;

        foreach (var profile in KnownProfiles)
        {
            if ((active & profile) == 0)
            {
                continue;
            }

            var state = snapshot.Profiles.FirstOrDefault(candidate => candidate.Profile == profile)
                ?? new FirewallProfileState(profile, FirewallEnabled: true, FirewallComValues.ActionBlock, BlockAllInboundTraffic: false);

            if (!state.FirewallEnabled)
            {
                disabledProfiles |= profile;
                allowedProfiles |= profile;
                continue;
            }

            if (state.BlockAllInboundTraffic)
            {
                blockAllProfiles |= profile;
                continue;
            }

            var blockers = snapshot.Rules
                .Where(rule => IsInboundTcpRuleForPort(rule, port)
                    && rule.Action == FirewallComValues.ActionBlock
                    && ProfileCovers(rule.Profiles, profile)
                    && BlocksThisListener(rule, exePath))
                .Select(rule => rule.Name)
                .ToList();
            if (blockers.Count > 0)
            {
                AddDistinct(blockRuleNames, blockers);
                continue;
            }

            var allowers = snapshot.Rules
                .Where(rule => IsInboundTcpRuleForPort(rule, port)
                    && rule.Action == FirewallComValues.ActionAllow
                    && ProfileCovers(rule.Profiles, profile)
                    && ApplicationMatches(rule.ApplicationName, exePath))
                .Select(rule => rule.Name)
                .ToList();
            if (allowers.Count > 0 || state.DefaultInboundAction == FirewallComValues.ActionAllow)
            {
                AddDistinct(allowRuleNames, allowers);
                allowedProfiles |= profile;
            }
        }

        var thirdParty = snapshot.ThirdPartyProductCount > 0;
        var verdict = thirdParty
            ? FirewallVerdict.Undetermined
            : (allowedProfiles & active) == active
                ? FirewallVerdict.Allowed
                : FirewallVerdict.NoRuleFound;

        return new FirewallReport(
            verdict,
            active,
            allowRuleNames,
            blockRuleNames,
            allowedProfiles,
            disabledProfiles,
            blockAllProfiles,
            ThirdPartyProductPresent: thirdParty,
            GroupPolicyOverride: snapshot.LocalPolicyModifyState == FirewallComValues.ModifyStateGroupPolicyOverride,
            NamedRuleExists: snapshot.Rules.Any(rule => string.Equals(rule.Name, ManagedRuleName, StringComparison.Ordinal)),
            targetPort,
            actualPort);
    }

    /// <summary>
    /// Whether a LocalPorts string covers one port. Accepts the forms the firewall writes:
    /// <c>*</c> (or empty — no restriction), a single port, a comma list, and ranges; the
    /// keyword forms (RPC, RPC-EPMap, IPHTTPS, Teredo, Ply2Disc…) never cover a plain port.
    /// </summary>
    internal static bool LocalPortsCover(string? localPorts, int port)
    {
        if (string.IsNullOrWhiteSpace(localPorts))
        {
            return true;
        }

        foreach (var rawToken in localPorts.Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries))
        {
            if (rawToken == "*")
            {
                return true;
            }

            if (int.TryParse(rawToken, NumberStyles.None, CultureInfo.InvariantCulture, out var single))
            {
                if (single == port)
                {
                    return true;
                }

                continue;
            }

            var dash = rawToken.IndexOf('-', StringComparison.Ordinal);
            if (dash > 0
                && int.TryParse(rawToken.AsSpan(0, dash), NumberStyles.None, CultureInfo.InvariantCulture, out var low)
                && int.TryParse(rawToken.AsSpan(dash + 1), NumberStyles.None, CultureInfo.InvariantCulture, out var high)
                && low <= port && port <= high)
            {
                return true;
            }
        }

        return false;
    }

    private static bool IsInboundTcpRuleForPort(FirewallRuleInfo rule, int port) =>
        rule.Enabled
        && rule.Direction == FirewallComValues.DirectionIn
        && rule.Protocol is FirewallComValues.ProtocolTcp or FirewallComValues.ProtocolAny
        && LocalPortsCover(rule.LocalPorts, port);

    private static bool ProfileCovers(int ruleProfiles, FirewallProfiles profile) =>
        (ruleProfiles & (int)profile) != 0;

    /// <summary>An Allow rule applies to this exe when it names no program at all or names exactly this one.</summary>
    private static bool ApplicationMatches(string? ruleApplication, string exePath) =>
        string.IsNullOrWhiteSpace(ruleApplication) || ApplicationEquals(ruleApplication, exePath);

    /// <summary>
    /// A Block rule counts against the listener when it names this exe (the rule Windows writes
    /// after 取消 on the security alert), or when it names no program but lists the port explicitly.
    /// A program-less <c>*</c> block would be an all-inbound block, which the profile flag already covers.
    /// </summary>
    private static bool BlocksThisListener(FirewallRuleInfo rule, string exePath) =>
        ApplicationEquals(rule.ApplicationName, exePath)
        || (string.IsNullOrWhiteSpace(rule.ApplicationName)
            && !string.IsNullOrWhiteSpace(rule.LocalPorts)
            && rule.LocalPorts.Trim() != "*");

    /// <summary>Paths in rules may carry %SystemRoot%-style variables; compare after expansion, case-insensitively.</summary>
    private static bool ApplicationEquals(string? ruleApplication, string exePath)
    {
        if (string.IsNullOrWhiteSpace(ruleApplication))
        {
            return false;
        }

        var expanded = Environment.ExpandEnvironmentVariables(ruleApplication.Trim());
        return string.Equals(expanded, exePath.Trim(), StringComparison.OrdinalIgnoreCase);
    }

    private static void AddDistinct(List<string> target, IEnumerable<string> names)
    {
        foreach (var name in names)
        {
            if (!target.Contains(name, StringComparer.Ordinal))
            {
                target.Add(name);
            }
        }
    }
}

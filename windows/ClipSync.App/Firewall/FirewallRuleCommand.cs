using System.Buffers;
using System.Globalization;
using System.Text;

namespace ClipSync.App.Firewall;

/// <summary>
/// The exact <c>netsh advfirewall</c> commands the one-click 放行 / 移除 runs (ADR 0006 §2–3).
/// <see cref="Steps"/> lists them in run order, one argv token list each (a value with spaces is
/// a single token — the process launcher or the script line quotes it). A plain add or delete is
/// one step and reaches <c>netsh.exe</c> as argv; a 放行 that first deletes this program's Block
/// rules (what the dismissed security alert left behind) is several steps and reaches it as one
/// <c>netsh -f</c> script under a single UAC consent. <see cref="ToDisplayString"/> is the same
/// sequence as a person types it, shown before the UAC prompt and offered for copying.
/// </summary>
public sealed class FirewallRuleCommand
{
    /// <summary>Fixed so users can recognise and delete it in the system UI; docs/install.md §3 uses the same name.</summary>
    public const string RuleName = "ClipSync TCP 47654";

    /// <summary>Always the default listening port (<see cref="Sync.PeerSyncHost.DefaultPort"/>): the rule is per port, not per exe.</summary>
    public const int Port = Sync.PeerSyncHost.DefaultPort;

    /// <summary>Characters a value carries unquoted both in a shell and in a netsh script line; any other character gets double quotes.</summary>
    private static readonly SearchValues<char> PlainValueChars =
        SearchValues.Create("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789._:\\/,-");

    private FirewallRuleCommand(
        IReadOnlyList<IReadOnlyList<string>> steps,
        bool isRemoval,
        IReadOnlyList<string> blockRuleNames,
        IReadOnlyList<string> skippedBlockRuleNames)
    {
        Steps = steps;
        IsRemoval = isRemoval;
        BlockRuleNames = blockRuleNames;
        SkippedBlockRuleNames = skippedBlockRuleNames;
    }

    /// <summary>Every netsh command in run order, each as argv tokens after <c>netsh</c>; the rule add/delete itself is always last.</summary>
    public IReadOnlyList<IReadOnlyList<string>> Steps { get; }

    /// <summary>argv tokens of the rule add/delete itself; pass each to <c>ProcessStartInfo.ArgumentList</c> when it is the only step.</summary>
    public IReadOnlyList<string> Arguments => Steps[^1];

    public bool IsRemoval { get; }

    /// <summary>Distinct names of this program's Block rules the run deletes before adding; empty for a plain add and for 移除.</summary>
    public IReadOnlyList<string> BlockRuleNames { get; }

    /// <summary>Block rule names a netsh line cannot carry (a double quote or a line break in the name or path); left for the user to delete by hand.</summary>
    public IReadOnlyList<string> SkippedBlockRuleNames { get; }

    /// <summary>True when more than one command runs under the same consent: the elevator hands netsh a script file instead of argv.</summary>
    public bool RequiresScript => Steps.Count > 1;

    /// <summary>The netsh script body for <c>netsh -f</c>: one command per line without the <c>netsh</c> prefix, CRLF-terminated.</summary>
    public IReadOnlyList<string> ScriptLines => Steps.Select(FormatLine).ToList();

    /// <summary>Adds the inbound TCP allow rule for the chosen profiles (default port, by port not by path — ADR 0006 §4).</summary>
    public static FirewallRuleCommand Allow(FirewallProfiles profiles) => Allow(profiles, []);

    /// <summary>
    /// The same add, preceded by one delete per distinct Block rule aimed at this exe: by name
    /// and stored program path, so a user's own rule that merely shares the name is untouched,
    /// and the alert's TCP + UDP pair (same name, same program) goes in one line. While such a
    /// rule exists the firewall lets no Allow rule for this program take effect.
    /// </summary>
    public static FirewallRuleCommand Allow(FirewallProfiles profiles, IReadOnlyList<FirewallProgramBlockRule> blockRules) =>
        Allow(profiles, blockRules, replaceExisting: false);

    /// <summary>
    /// With <paramref name="replaceExisting"/> the app's own rule is deleted first so re-running
    /// 放行 (to clear Block rules, or to widen the profiles) never leaves two rules under one name.
    /// </summary>
    public static FirewallRuleCommand Allow(FirewallProfiles profiles, IReadOnlyList<FirewallProgramBlockRule> blockRules, bool replaceExisting)
    {
        ArgumentNullException.ThrowIfNull(blockRules);
        var profileValue = ProfileArgument(profiles);

        var steps = new List<IReadOnlyList<string>>(blockRules.Count + 2);
        if (replaceExisting)
        {
            steps.Add(
            [
                "advfirewall",
                "firewall",
                "delete",
                "rule",
                "name=" + RuleName,
            ]);
        }

        var deleted = new List<FirewallProgramBlockRule>(blockRules.Count);
        var names = new List<string>();
        var skipped = new List<string>();
        foreach (var rule in blockRules)
        {
            if (!FitsOnALine(rule.Name) || !FitsOnALine(rule.Program))
            {
                AddDistinct(skipped, rule.Name);
                continue;
            }

            if (deleted.Contains(rule))
            {
                continue;
            }

            deleted.Add(rule);
            AddDistinct(names, rule.Name);
            steps.Add(
            [
                "advfirewall",
                "firewall",
                "delete",
                "rule",
                "name=" + rule.Name,
                "dir=in",
                "program=" + rule.Program,
            ]);
        }

        steps.Add(
        [
            "advfirewall",
            "firewall",
            "add",
            "rule",
            "name=" + RuleName,
            "dir=in",
            "action=allow",
            "protocol=TCP",
            "localport=" + Port.ToString(CultureInfo.InvariantCulture),
            "profile=" + profileValue,
        ]);
        return new FirewallRuleCommand(steps, isRemoval: false, names, skipped);
    }

    /// <summary>Deletes only the rule this app created, by its fixed name.</summary>
    public static FirewallRuleCommand Remove() => new(
        [
            [
                "advfirewall",
                "firewall",
                "delete",
                "rule",
                "name=" + RuleName,
            ],
        ],
        isRemoval: true,
        [],
        []);

    /// <summary>The netsh profile keyword list: <c>private</c>, <c>public</c>, <c>domain</c>, comma-joined in that order.</summary>
    public static string ProfileArgument(FirewallProfiles profiles)
    {
        var parts = new List<string>(3);
        if ((profiles & FirewallProfiles.Private) != 0)
        {
            parts.Add("private");
        }

        if ((profiles & FirewallProfiles.Public) != 0)
        {
            parts.Add("public");
        }

        if ((profiles & FirewallProfiles.Domain) != 0)
        {
            parts.Add("domain");
        }

        if (parts.Count == 0)
        {
            throw new ArgumentException("At least one firewall profile is required.", nameof(profiles));
        }

        return string.Join(',', parts);
    }

    /// <summary>The commands as typed in cmd or PowerShell, one per line: <c>netsh</c> prefix, values beyond plain path characters in double quotes.</summary>
    public string ToDisplayString() =>
        string.Join(Environment.NewLine, Steps.Select(step => "netsh " + FormatLine(step)));

    /// <summary>The file handed to <c>netsh -f</c>: <see cref="ScriptLines"/> each followed by CRLF.</summary>
    public string ToScriptText()
    {
        var builder = new StringBuilder();
        foreach (var line in ScriptLines)
        {
            builder.Append(line).Append("\r\n");
        }

        return builder.ToString();
    }

    /// <summary>The equivalent NetSecurity cmdlets (what docs/install.md quotes) for people who prefer PowerShell; display only.</summary>
    public string ToPowerShellString()
    {
        if (IsRemoval)
        {
            return $"Remove-NetFirewallRule -DisplayName \"{RuleName}\"";
        }

        var profileToken = Arguments[^1]["profile=".Length..];
        var profiles = string.Join(", ", profileToken
            .Split(',')
            .Select(part => char.ToUpperInvariant(part[0]) + part[1..]));
        var lines = BlockRuleNames
            .Select(name => $"Remove-NetFirewallRule -DisplayName \"{name}\"")
            .Append($"New-NetFirewallRule -DisplayName \"{RuleName}\" -Direction Inbound -Action Allow -Protocol TCP -LocalPort {Port.ToString(CultureInfo.InvariantCulture)} -Profile {profiles}");
        return string.Join(Environment.NewLine, lines);
    }

    /// <summary>A netsh line is one line and quotes its values with <c>"</c>; a value containing either cannot be expressed and is refused.</summary>
    private static bool FitsOnALine(string value) =>
        value.AsSpan().IndexOfAny('"', '\r', '\n') < 0;

    private static string FormatLine(IReadOnlyList<string> tokens)
    {
        var builder = new StringBuilder();
        foreach (var token in tokens)
        {
            if (builder.Length > 0)
            {
                builder.Append(' ');
            }

            var equals = token.IndexOf('=', StringComparison.Ordinal);
            if (equals > 0 && token.AsSpan(equals + 1).ContainsAnyExcept(PlainValueChars))
            {
                builder.Append(token, 0, equals + 1).Append('"').Append(token, equals + 1, token.Length - equals - 1).Append('"');
            }
            else
            {
                builder.Append(token);
            }
        }

        return builder.ToString();
    }

    private static void AddDistinct(List<string> target, string value)
    {
        if (!target.Contains(value, StringComparer.Ordinal))
        {
            target.Add(value);
        }
    }
}

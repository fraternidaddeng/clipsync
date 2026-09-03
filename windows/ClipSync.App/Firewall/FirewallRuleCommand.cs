using System.Globalization;
using System.Text;

namespace ClipSync.App.Firewall;

/// <summary>
/// The exact <c>netsh advfirewall</c> invocation the one-click 放行 / 移除 runs (ADR 0006 §2–3),
/// built from constants only. <see cref="Arguments"/> is what reaches <c>netsh.exe</c> (one
/// element per argv token — the value with spaces is a single token, quoted by the process
/// launcher); <see cref="ToDisplayString"/> is the same command as a person types it in a shell,
/// shown to the user before the UAC prompt and offered for copying.
/// </summary>
public sealed class FirewallRuleCommand
{
    /// <summary>Fixed so users can recognise and delete it in the system UI; docs/install.md §3 uses the same name.</summary>
    public const string RuleName = "ClipSync TCP 47654";

    /// <summary>Always the default listening port (<see cref="Sync.PeerSyncHost.DefaultPort"/>): the rule is per port, not per exe.</summary>
    public const int Port = Sync.PeerSyncHost.DefaultPort;

    private FirewallRuleCommand(IReadOnlyList<string> arguments, bool isRemoval)
    {
        Arguments = arguments;
        IsRemoval = isRemoval;
    }

    /// <summary>argv tokens after <c>netsh</c>; pass each to <c>ProcessStartInfo.ArgumentList</c>.</summary>
    public IReadOnlyList<string> Arguments { get; }

    public bool IsRemoval { get; }

    /// <summary>Adds the inbound TCP allow rule for the chosen profiles (default port, by port not by path — ADR 0006 §4).</summary>
    public static FirewallRuleCommand Allow(FirewallProfiles profiles)
    {
        var profileValue = ProfileArgument(profiles);
        return new FirewallRuleCommand(
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
            ],
            isRemoval: false);
    }

    /// <summary>Deletes only the rule this app created, by its fixed name.</summary>
    public static FirewallRuleCommand Remove() => new(
        [
            "advfirewall",
            "firewall",
            "delete",
            "rule",
            "name=" + RuleName,
        ],
        isRemoval: true);

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

    /// <summary>The command as typed in cmd or PowerShell: tokens with spaces get shell quotes.</summary>
    public string ToDisplayString()
    {
        var builder = new StringBuilder("netsh");
        foreach (var argument in Arguments)
        {
            builder.Append(' ');
            var equals = argument.IndexOf('=', StringComparison.Ordinal);
            if (argument.Contains(' ', StringComparison.Ordinal) && equals > 0)
            {
                builder.Append(argument, 0, equals + 1).Append('"').Append(argument, equals + 1, argument.Length - equals - 1).Append('"');
            }
            else
            {
                builder.Append(argument);
            }
        }

        return builder.ToString();
    }

    /// <summary>The equivalent NetSecurity cmdlet (what docs/install.md quotes) for people who prefer PowerShell.</summary>
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
        return $"New-NetFirewallRule -DisplayName \"{RuleName}\" -Direction Inbound -Action Allow -Protocol TCP -LocalPort {Port.ToString(CultureInfo.InvariantCulture)} -Profile {profiles}";
    }
}

using System.Runtime.InteropServices;
using ClipSync.App.Diagnostics;
using Microsoft.CSharp.RuntimeBinder;

namespace ClipSync.App.Firewall;

/// <summary>Read-only firewall state query; the interface lets the view model be tested without COM.</summary>
public interface IFirewallInspector
{
    /// <param name="expectedPort">The port the managed rule names (the default listening port).</param>
    /// <param name="actualPort">The port this process is listening on right now (0 when offline).</param>
    /// <param name="exePath">Full path of this executable, for matching program-scoped rules.</param>
    Task<FirewallReport> InspectAsync(int expectedPort, int actualPort, string exePath, CancellationToken cancellationToken);
}

/// <summary>
/// Reads the Windows Firewall through the <c>HNetCfg.FwPolicy2</c> COM automation object with
/// late binding (ADR 0006 §1). No type library import: the ProgID is resolved at run time and
/// members are called through <c>dynamic</c>, which the runtime binder services over IDispatch —
/// so the csproj stays free of COMReference and the Linux publish keeps working. Standard user
/// rights suffice for reading. Every COM call runs on a thread-pool thread (rule sets reach
/// thousands of entries); the caller awaits the finished report. Any failure to read collapses
/// to <see cref="FirewallVerdict.Undetermined"/> with a diagnostics code — never a guess.
/// </summary>
public sealed class FirewallInspector : IFirewallInspector
{
    private const string PolicyProgId = "HNetCfg.FwPolicy2";
    private const string ProductsProgId = "HNetCfg.FwProducts";

    private static readonly FirewallProfiles[] KnownProfiles =
        [FirewallProfiles.Domain, FirewallProfiles.Private, FirewallProfiles.Public];

    public Task<FirewallReport> InspectAsync(int expectedPort, int actualPort, string exePath, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(exePath);
        return Task.Run(() => Inspect(expectedPort, actualPort, exePath, cancellationToken), cancellationToken);
    }

    private static FirewallReport Inspect(int expectedPort, int actualPort, string exePath, CancellationToken cancellationToken)
    {
        FirewallSnapshot snapshot;
        try
        {
            snapshot = ReadSnapshot(cancellationToken);
        }
        catch (Exception exception) when (exception is COMException
            or RuntimeBinderException
            or InvalidCastException
            or InvalidOperationException
            or UnauthorizedAccessException
            or MissingMemberException
            or ArgumentException
            or NotSupportedException)
        {
            // COMException (service down, class not registered), RuntimeBinderException /
            // InvalidCastException / MissingMemberException (a member shaped differently than
            // expected), UnauthorizedAccessException (E_ACCESSDENIED), ArgumentException
            // (DISP_E_TYPEMISMATCH family): all mean "cannot read", never a crash of a
            // read-only status line. The type name is the whole diagnostic; no message.
            LocalDiagnostics.Write($"firewall_inspect_failed_{exception.GetType().Name}");
            return FirewallReport.Unavailable(expectedPort, actualPort);
        }

        var report = FirewallVerdictEvaluator.Evaluate(snapshot, exePath, expectedPort, actualPort);
        LocalDiagnostics.Write($"firewall_inspect_{DiagnosticCode(report.Verdict)}");
        return report;
    }

    private static string DiagnosticCode(FirewallVerdict verdict) => verdict switch
    {
        FirewallVerdict.Allowed => "allowed",
        FirewallVerdict.NoRuleFound => "no_rule",
        _ => "undetermined",
    };

    /// <summary>Everything the evaluator needs, read in one pass; COM objects are released as soon as they are copied out.</summary>
    internal static FirewallSnapshot ReadSnapshot(CancellationToken cancellationToken)
    {
        var policyType = Type.GetTypeFromProgID(PolicyProgId)
            ?? throw new InvalidOperationException($"{PolicyProgId} is not registered.");
        object policyObject = Activator.CreateInstance(policyType)
            ?? throw new InvalidOperationException($"{PolicyProgId} could not be created.");
        try
        {
            dynamic policy = policyObject;
            int activeProfiles = policy.CurrentProfileTypes;
            int modifyState = policy.LocalPolicyModifyState;

            var profiles = new List<FirewallProfileState>(KnownProfiles.Length);
            foreach (var profile in KnownProfiles)
            {
                bool enabled = policy.FirewallEnabled((int)profile);
                int defaultInbound = policy.DefaultInboundAction((int)profile);
                bool blockAll = policy.BlockAllInboundTraffic((int)profile);
                profiles.Add(new FirewallProfileState(profile, enabled, defaultInbound, blockAll));
            }

            var rules = ReadRules(policy, cancellationToken);
            var products = CountThirdPartyProducts();
            return new FirewallSnapshot(
                (FirewallProfiles)(activeProfiles & (int)(FirewallProfiles.Domain | FirewallProfiles.Private | FirewallProfiles.Public)),
                profiles,
                rules,
                products,
                modifyState);
        }
        finally
        {
            Marshal.FinalReleaseComObject(policyObject);
        }
    }

    private static List<FirewallRuleInfo> ReadRules(dynamic policy, CancellationToken cancellationToken)
    {
        object rulesObject = policy.Rules;
        var rules = new List<FirewallRuleInfo>();
        try
        {
            dynamic collection = rulesObject;
            foreach (object ruleObject in collection)
            {
                cancellationToken.ThrowIfCancellationRequested();
                try
                {
                    dynamic rule = ruleObject;
                    string? name = rule.Name;
                    bool enabled = rule.Enabled;
                    int direction = rule.Direction;
                    int protocol = rule.Protocol;
                    string? localPorts = rule.LocalPorts;
                    string? applicationName = rule.ApplicationName;
                    int action = rule.Action;
                    int ruleProfiles = rule.Profiles;
                    rules.Add(new FirewallRuleInfo(
                        name ?? string.Empty,
                        enabled,
                        direction,
                        protocol,
                        localPorts,
                        applicationName,
                        action,
                        ruleProfiles)
                    {
                        LocalUserOwner = ReadScopeMember(ruleObject, static r => (string?)r.LocalUserOwner),
                        LocalAppPackageId = ReadScopeMember(ruleObject, static r => (string?)r.LocalAppPackageId),
                    });
                }
                finally
                {
                    Marshal.FinalReleaseComObject(ruleObject);
                }
            }
        }
        finally
        {
            Marshal.FinalReleaseComObject(rulesObject);
        }

        return rules;
    }

    /// <summary>
    /// INetFwRule3 scoping members (user owner, app package) are dispatched by name; a rule object
    /// that predates that interface answers with a binder or COM error, which reads as "unscoped".
    /// </summary>
    private static string? ReadScopeMember(object rule, Func<dynamic, string?> read)
    {
        try
        {
            var value = read(rule);
            return string.IsNullOrWhiteSpace(value) ? null : value;
        }
        catch (RuntimeBinderException)
        {
            return null;
        }
        catch (COMException)
        {
            return null;
        }
        catch (InvalidCastException)
        {
            return null;
        }
        catch (MissingMemberException)
        {
            return null;
        }
    }

    /// <summary>
    /// Registered third-party firewall products (INetFwProducts). Missing ProgID reads as none:
    /// the class exists since Windows 7, and its absence says nothing about a takeover.
    /// </summary>
    private static int CountThirdPartyProducts()
    {
        var productsType = Type.GetTypeFromProgID(ProductsProgId);
        if (productsType is null)
        {
            return 0;
        }

        object? productsObject = Activator.CreateInstance(productsType);
        if (productsObject is null)
        {
            return 0;
        }

        try
        {
            dynamic products = productsObject;
            int count = products.Count;
            return count;
        }
        finally
        {
            Marshal.FinalReleaseComObject(productsObject);
        }
    }
}

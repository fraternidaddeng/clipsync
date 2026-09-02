using System.Globalization;
using System.Reflection;
using ClipSync.Core.Update;

namespace ClipSync.App.Update;

/// <summary>
/// The version stamped into this assembly (<c>Version</c> / <c>InformationalVersion</c>).
/// Release packaging overrides it from the git tag; local builds use the
/// <c>Directory.Build.props</c> default.
/// </summary>
public static class LocalAppVersion
{
    public static string Read()
    {
        var assembly = typeof(LocalAppVersion).Assembly;
        var informational = assembly.GetCustomAttribute<AssemblyInformationalVersionAttribute>()
            ?.InformationalVersion;
        if (AppVersion.TryParse(informational, out var parsed))
        {
            return parsed.ToDisplayString();
        }

        var version = assembly.GetName().Version;
        if (version is not null && version != new Version(0, 0, 0, 0) && version != new Version(1, 0, 0, 0))
        {
            return string.Create(
                CultureInfo.InvariantCulture,
                $"{version.Major}.{version.Minor}.{version.Build}");
        }

        return "0.0.0";
    }
}

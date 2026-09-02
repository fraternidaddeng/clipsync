using System.Globalization;
using System.IO.Compression;
using System.Text;

namespace ClipSync.Core.Update;

/// <summary>
/// Applies a portable Windows ZIP next to the running executable. The process
/// itself cannot overwrite its own files, so this extracts, writes a tiny
/// <c>cmd</c> helper, and returns the command the host must launch before it
/// exits. User data lives under LocalAppData and is never touched.
/// </summary>
public static class PortableUpdateApplier
{
    public const string WindowsExeName = "ClipSync.App.exe";

    public static string ExtractPayloadDirectory(string zipPath, string extractRoot)
    {
        if (Directory.Exists(extractRoot))
        {
            Directory.Delete(extractRoot, recursive: true);
        }

        Directory.CreateDirectory(extractRoot);
        ZipFile.ExtractToDirectory(zipPath, extractRoot);
        return FindPayloadDirectory(extractRoot)
            ?? throw new InvalidOperationException(
                $"The update ZIP did not contain {WindowsExeName}.");
    }

    public static string? FindPayloadDirectory(string extractRoot)
    {
        if (!Directory.Exists(extractRoot))
        {
            return null;
        }

        foreach (var exe in Directory.EnumerateFiles(extractRoot, WindowsExeName, SearchOption.AllDirectories))
        {
            return Path.GetDirectoryName(exe);
        }

        return null;
    }

    /// <summary>
    /// Writes a helper that waits for <paramref name="pid"/> to exit, copies
    /// <paramref name="payloadDirectory"/> over <paramref name="installDirectory"/>,
    /// relaunches the app, and deletes <paramref name="stagingRoot"/>.
    /// </summary>
    public static string WriteApplyScript(
        string stagingRoot,
        int pid,
        string payloadDirectory,
        string installDirectory)
    {
        Directory.CreateDirectory(stagingRoot);
        var src = NormalizeCmdPath(payloadDirectory);
        var dst = NormalizeCmdPath(installDirectory);
        var staging = NormalizeCmdPath(stagingRoot);
        // Sibling of the staging tree so `rmdir /S` can delete the payload
        // without fighting a still-running script inside that folder.
        var scriptPath = staging + "-apply.cmd";
        var exe = NormalizeCmdPath(Path.Combine(installDirectory, WindowsExeName));
        var script = new StringBuilder();
        script.AppendLine("@echo off");
        script.AppendLine("setlocal");
        script.AppendLine("set PID=" + pid.ToString(CultureInfo.InvariantCulture));
        script.AppendLine(":wait");
        script.AppendLine("timeout /t 1 /nobreak >nul");
        script.AppendLine("tasklist /FI \"PID eq %PID%\" | find \"%PID%\" >nul && goto wait");
        script.AppendLine("robocopy \"" + src + "\" \"" + dst + "\" /E /R:2 /W:1 /NFL /NDL /NJH /NJS /NP");
        // robocopy uses 0–7 for success-with-copies; 8+ is a real failure.
        script.AppendLine("if errorlevel 8 exit /b 1");
        script.AppendLine("start \"\" \"" + exe + "\"");
        script.AppendLine("rmdir /S /Q \"" + staging + "\"");
        script.AppendLine("del \"%~f0\"");
        File.WriteAllText(scriptPath, script.ToString(), Encoding.ASCII);
        return scriptPath;
    }

    private static string NormalizeCmdPath(string path) =>
        Path.GetFullPath(path).TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar);
}

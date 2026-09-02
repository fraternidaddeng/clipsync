using System.IO.Compression;
using System.Text;
using ClipSync.Core.Update;

namespace ClipSync.Tests.Update;

public sealed class PortableUpdateApplierTests : IDisposable
{
    private readonly string root = Path.Combine(
        Path.GetTempPath(),
        "clipsync-update-applier-tests",
        Guid.NewGuid().ToString("N"));

    public PortableUpdateApplierTests() => Directory.CreateDirectory(root);

    public void Dispose()
    {
        if (Directory.Exists(root))
        {
            Directory.Delete(root, recursive: true);
        }
    }

    [Fact]
    public void ExtractsTheNestedClipSyncFolderFromThePublishedZipShape()
    {
        var zip = Path.Combine(root, "ClipSync-windows-x64.zip");
        var staging = Path.Combine(root, "zip-src", "ClipSync");
        Directory.CreateDirectory(staging);
        File.WriteAllText(Path.Combine(staging, PortableUpdateApplier.WindowsExeName), "exe");
        File.WriteAllText(Path.Combine(staging, "LICENSE.txt"), "license");
        ZipFile.CreateFromDirectory(Path.Combine(root, "zip-src"), zip);

        var extracted = PortableUpdateApplier.ExtractPayloadDirectory(zip, Path.Combine(root, "out"));
        Assert.True(File.Exists(Path.Combine(extracted, PortableUpdateApplier.WindowsExeName)));
        Assert.True(File.Exists(Path.Combine(extracted, "LICENSE.txt")));
    }

    [Fact]
    public void WriteApplyScriptWaitsForThePidThenRobocopiesAndRelaunches()
    {
        var staging = Path.Combine(root, "staging");
        var payload = Path.Combine(root, "payload");
        var install = Path.Combine(root, "install");
        Directory.CreateDirectory(payload);
        var script = PortableUpdateApplier.WriteApplyScript(staging, 4242, payload, install);
        var text = File.ReadAllText(script, Encoding.ASCII);
        var stagingFull = Path.GetFullPath(staging).TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar);
        Assert.Equal(stagingFull + "-apply.cmd", script);
        Assert.False(script.StartsWith(stagingFull + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase));
        Assert.Contains("set PID=4242", text);
        Assert.Contains("robocopy", text, StringComparison.OrdinalIgnoreCase);
        Assert.Contains(PortableUpdateApplier.WindowsExeName, text);
        Assert.Contains("tasklist /FI \"PID eq %PID%\"", text);
        Assert.Contains("del \"%~f0\"", text);
        Assert.Contains("\"" + stagingFull + "\"", text);
        Assert.Contains("\"" + Path.GetFullPath(payload).TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar) + "\"", text);
    }

    [Fact]
    public void ZipWithoutTheExeIsRejected()
    {
        var zip = Path.Combine(root, "empty.zip");
        var src = Path.Combine(root, "empty-src");
        Directory.CreateDirectory(src);
        File.WriteAllText(Path.Combine(src, "readme.txt"), "no exe");
        ZipFile.CreateFromDirectory(src, zip);
        Assert.Throws<InvalidOperationException>(
            () => PortableUpdateApplier.ExtractPayloadDirectory(zip, Path.Combine(root, "bad")));
    }
}

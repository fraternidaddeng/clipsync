using System.IO;
using ClipSync.Core.Update;

namespace ClipSync.App.Update;

/// <summary>
/// Checks GitHub <c>/releases/latest</c>, downloads the Windows ZIP, verifies
/// SHA-256, extracts, and returns the helper script the host must launch before
/// exiting. User data under LocalAppData is never touched.
/// </summary>
public sealed class WindowsAppUpdater : IDisposable
{
    private readonly GitHubReleaseClient client;

    public WindowsAppUpdater(string? currentVersion = null, GitHubReleaseClient? client = null)
    {
        CurrentVersion = currentVersion ?? LocalAppVersion.Read();
        this.client = client ?? new GitHubReleaseClient(CurrentVersion);
    }

    public string CurrentVersion { get; }

    public async Task<UpdateCheckResult> CheckAsync(CancellationToken cancellationToken = default)
    {
        var latest = await client.FetchLatestAsync(cancellationToken).ConfigureAwait(false);
        return UpdateCheckResult.From(CurrentVersion, latest, UpdatePlatform.Windows);
    }

    /// <summary>
    /// Downloads, verifies, extracts, and writes the apply script. The caller
    /// must start the script and then exit this process.
    /// </summary>
    public async Task<string> PrepareApplyAsync(
        UpdateCheckResult check,
        IProgress<UpdateDownloadProgress>? progress = null,
        CancellationToken cancellationToken = default)
    {
        if (check.Payload is null)
        {
            throw new InvalidOperationException("Latest release has no Windows ZIP.");
        }

        var expectedSha = await client.ResolveSha256Async(check.Latest, check.Payload, cancellationToken)
            .ConfigureAwait(false);
        var staging = Path.Combine(
            Path.GetTempPath(),
            "clipsync-update",
            check.Latest.VersionLabel,
            Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(staging);
        try
        {
            var zipPath = Path.Combine(staging, check.Payload.Name);
            await using (var file = new FileStream(zipPath, FileMode.Create, FileAccess.ReadWrite, FileShare.None))
            {
                await client.DownloadAsync(check.Payload, file, progress, cancellationToken).ConfigureAwait(false);
                GitHubReleaseClient.VerifySha256(file, expectedSha);
            }

            var payload = PortableUpdateApplier.ExtractPayloadDirectory(
                zipPath,
                Path.Combine(staging, "extracted"));
            var installDirectory = AppContext.BaseDirectory.TrimEnd(
                Path.DirectorySeparatorChar,
                Path.AltDirectorySeparatorChar);
            return PortableUpdateApplier.WriteApplyScript(
                staging,
                Environment.ProcessId,
                payload,
                installDirectory);
        }
        catch
        {
            try
            {
                if (Directory.Exists(staging))
                {
                    Directory.Delete(staging, recursive: true);
                }
            }
            catch (IOException)
            {
            }
            catch (UnauthorizedAccessException)
            {
            }

            throw;
        }
    }

    public void Dispose() => client.Dispose();
}

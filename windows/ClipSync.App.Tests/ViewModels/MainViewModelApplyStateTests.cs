using System.IO;
using ClipSync.App.Clipboard;
using ClipSync.App.ViewModels;
using ClipSync.Core.Clipboard;
using ClipSync.Core.Storage;
using ClipSync.Peer.Server;
using Microsoft.Data.Sqlite;

namespace ClipSync.App.Tests.ViewModels;

/// <summary>
/// The two honesty seams added after manual QA 2026-08-25: the clipboard apply
/// self-report the health endpoint serves to the phone's 对端写入 segment, and the
/// local-only strip that speaks when an oversize copy is kept off the sync path.
/// </summary>
public sealed class MainViewModelApplyStateTests : IAsyncDisposable
{
    private const string LocalDeviceId = "11111111-1111-4111-8111-111111111111";

    private readonly string directory;
    private readonly SqliteClipboardEventStore store;
    private readonly Win32ClipboardAdapter adapter = new();
    private readonly MainViewModel viewModel;

    public MainViewModelApplyStateTests()
    {
        directory = Path.Combine(Path.GetTempPath(), "clipsync-applystate-tests", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(directory);
        store = new SqliteClipboardEventStore(Path.Combine(directory, "applystate.db"), LocalDeviceId);
        viewModel = new MainViewModel(store, new ClipboardCapturePolicy(), adapter);
    }

    [Fact]
    public void ApplyStatePutsPostureBeforeEvidenceAndNeverInventsSuccess()
    {
        // Fresh session, auto-apply on: nothing has actually been written yet,
        // so the report must say so instead of "the API exists so it works".
        Assert.Equal(ClipboardApplyStates.Unverified, viewModel.ClipboardApplyState);

        // A real remote apply reached the system clipboard.
        viewModel.RecordRemoteApplyOutcome(ok: true);
        Assert.Equal(ClipboardApplyStates.Applied, viewModel.ClipboardApplyState);

        // The user's posture outranks stale evidence, and 自动写入 off outranks paused.
        viewModel.IsPaused = true;
        Assert.Equal(ClipboardApplyStates.Paused, viewModel.ClipboardApplyState);
        viewModel.AutoApplyRemote = false;
        Assert.Equal(ClipboardApplyStates.Off, viewModel.ClipboardApplyState);

        // Posture back on: the session's real evidence is still the truth.
        viewModel.IsPaused = false;
        viewModel.AutoApplyRemote = true;
        Assert.Equal(ClipboardApplyStates.Applied, viewModel.ClipboardApplyState);

        // A failed apply must degrade the report immediately.
        viewModel.RecordRemoteApplyOutcome(ok: false);
        Assert.Equal(ClipboardApplyStates.Failed, viewModel.ClipboardApplyState);
    }

    [Fact]
    public void OversizeRejectionSpeaksOnceAndRetiresWithDismissOrTheNextCapture()
    {
        Assert.Equal(string.Empty, viewModel.CaptureNotice);

        // The QA gap: a >1 MiB copy left zero rows and zero words. The notice must
        // state the whole promise — kept locally, untruncated, not synced.
        viewModel.NoteCaptureRejected(CaptureRejectionReason.TooLarge);
        Assert.Contains("1 MiB", viewModel.CaptureNotice, StringComparison.Ordinal);
        Assert.Contains("未截断", viewModel.CaptureNotice, StringComparison.Ordinal);
        Assert.Contains("不同步", viewModel.CaptureNotice, StringComparison.Ordinal);

        // 「知道了」 retires the strip.
        viewModel.DismissCaptureNoticeCommand.Execute(null);
        Assert.Equal(string.Empty, viewModel.CaptureNotice);

        // The next accepted capture also supersedes the stale fact on its own.
        viewModel.NoteCaptureRejected(CaptureRejectionReason.TooLarge);
        viewModel.NoteCaptureStored();
        Assert.Equal(string.Empty, viewModel.CaptureNotice);
    }

    [Fact]
    public void ExpectedRejectionsStayQuiet()
    {
        // Paused/private/duplicate/suppressed/blocked rejections are the app doing
        // what the user asked; only the oversize surprise may take the strip.
        foreach (var reason in new[]
                 {
                     CaptureRejectionReason.EmptyText,
                     CaptureRejectionReason.Duplicate,
                     CaptureRejectionReason.SuppressedWrite,
                     CaptureRejectionReason.Paused,
                     CaptureRejectionReason.PrivateMode,
                     CaptureRejectionReason.SourceBlocked,
                     CaptureRejectionReason.UnsupportedMedia,
                     CaptureRejectionReason.DecodeFailed
                 })
        {
            viewModel.NoteCaptureRejected(reason);
            Assert.Equal(string.Empty, viewModel.CaptureNotice);
        }
    }

    public async ValueTask DisposeAsync()
    {
        adapter.Dispose();
        await store.DisposeAsync();
        SqliteConnection.ClearAllPools();
        Directory.Delete(directory, recursive: true);
    }
}

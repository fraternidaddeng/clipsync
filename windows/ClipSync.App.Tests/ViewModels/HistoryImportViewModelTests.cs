using System.IO;
using System.Security.Cryptography;
using System.Text;
using ClipSync.App;
using ClipSync.App.Clipboard;
using ClipSync.App.ViewModels;
using ClipSync.Core.Clipboard;
using ClipSync.Core.Storage;
using Microsoft.Data.Sqlite;

namespace ClipSync.App.Tests.ViewModels;

public sealed class HistoryImportViewModelTests : IAsyncDisposable
{
    private const string LocalDeviceId = "11111111-1111-4111-8111-111111111111";
    private static readonly DateTimeOffset BaseTime = DateTimeOffset.FromUnixTimeMilliseconds(1_700_000_000_000);

    private readonly string directory;
    private readonly SqliteClipboardEventStore store;
    private readonly Win32ClipboardAdapter adapter = new();
    private readonly MainViewModel viewModel;

    public HistoryImportViewModelTests()
    {
        directory = Path.Combine(Path.GetTempPath(), "clipsync-import-vm-tests", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(directory);
        store = new SqliteClipboardEventStore(Path.Combine(directory, "vm.db"), LocalDeviceId);
        viewModel = new MainViewModel(store, new ClipboardCapturePolicy(), adapter);
    }

    [Fact]
    public async Task ImportHistoryRestoresRowsAndSetsImportedStatus()
    {
        await using var source = new SqliteClipboardEventStore(Path.Combine(directory, "source.db"), "source-device");
        await source.InitializeAsync();
        var stored = await source.StoreAsync(Content("import-me", BaseTime));
        var path = Path.Combine(directory, "in.jsonl");
        await File.WriteAllTextAsync(path, await ClipboardExport.EncodeJsonLinesAsync(source));

        await viewModel.InitializeAsync();
        viewModel.PickImportPath = () => path;

        await viewModel.ImportHistoryCommand.ExecuteAsync(null);

        var item = Assert.Single(viewModel.History);
        Assert.Equal(stored.EventId, item.EventId);
        Assert.Equal("import-me", item.Text);
        Assert.Equal(Strings.FormatImportedClips(1, 0), viewModel.ExportStatus);
    }

    [Fact]
    public async Task ImportHistoryReimportSkipsAllAndKeepsStatusShape()
    {
        await using var source = new SqliteClipboardEventStore(Path.Combine(directory, "source-re.db"), "source-device");
        await source.InitializeAsync();
        await source.StoreAsync(Content("once", BaseTime));
        var path = Path.Combine(directory, "reimport.jsonl");
        await File.WriteAllTextAsync(path, await ClipboardExport.EncodeJsonLinesAsync(source));

        await viewModel.InitializeAsync();
        viewModel.PickImportPath = () => path;
        await viewModel.ImportHistoryCommand.ExecuteAsync(null);
        await viewModel.ImportHistoryCommand.ExecuteAsync(null);

        Assert.Single(viewModel.History);
        Assert.Equal(Strings.FormatImportedClips(0, 1), viewModel.ExportStatus);
    }

    [Fact]
    public async Task ImportHistoryCancelLeavesStatusUnchanged()
    {
        await viewModel.InitializeAsync();
        viewModel.PickImportPath = () => null;

        await viewModel.ImportHistoryCommand.ExecuteAsync(null);

        Assert.Equal(string.Empty, viewModel.ExportStatus);
    }

    [Fact]
    public async Task ImportHistoryInvalidPathSetsFailedStatus()
    {
        await viewModel.InitializeAsync();
        viewModel.PickImportPath = () => Path.Combine(directory, "missing-folder", "in.jsonl");

        await viewModel.ImportHistoryCommand.ExecuteAsync(null);

        Assert.Equal(Strings.ImportFailed, viewModel.ExportStatus);
    }

    private static AcceptedClipboardContent Content(string text, DateTimeOffset capturedAt)
    {
        var bytes = Encoding.UTF8.GetBytes(text);
        return new AcceptedClipboardContent(
            text,
            Convert.ToHexString(SHA256.HashData(bytes)).ToLowerInvariant(),
            bytes.Length,
            "notepad",
            capturedAt);
    }

    public async ValueTask DisposeAsync()
    {
        adapter.Dispose();
        await store.DisposeAsync();
        SqliteConnection.ClearAllPools();
        Directory.Delete(directory, recursive: true);
    }
}

using System.IO;
using System.Security.Cryptography;
using System.Text;
using ClipSync.App;
using ClipSync.App.Clipboard;
using ClipSync.App.ViewModels;
using ClipSync.Core.Clipboard;
using ClipSync.Core.Media;
using ClipSync.Core.Storage;
using Microsoft.Data.Sqlite;

namespace ClipSync.App.Tests.ViewModels;

public sealed class HistoryExportAndDetailViewModelTests : IAsyncDisposable
{
    private const string LocalDeviceId = "11111111-1111-4111-8111-111111111111";

    private readonly string directory;
    private readonly SqliteClipboardEventStore store;
    private readonly Win32ClipboardAdapter adapter = new();
    private readonly MainViewModel viewModel;

    public HistoryExportAndDetailViewModelTests()
    {
        directory = Path.Combine(Path.GetTempPath(), "clipsync-export-vm-tests", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(directory);
        store = new SqliteClipboardEventStore(Path.Combine(directory, "vm.db"), LocalDeviceId);
        viewModel = new MainViewModel(store, new ClipboardCapturePolicy(), adapter);
    }

    [Fact]
    public async Task GetSelectedDetailReturnsFullTextAndMetadata()
    {
        const string body = "line one\nline two\nline three\nline four that the list preview would trim";
        await store.InitializeAsync();
        await store.StoreAsync(Content(body, DateTimeOffset.UtcNow));
        await viewModel.InitializeAsync();

        Assert.Null(viewModel.GetSelectedDetail());

        viewModel.SelectedItem = Assert.Single(viewModel.History);
        var detail = viewModel.GetSelectedDetail();

        Assert.NotNull(detail);
        Assert.Equal(body, detail.Text);
        Assert.Equal(viewModel.SelectedItem.Source, detail.Source);
        Assert.Equal(viewModel.SelectedItem.CreatedAt, detail.CreatedAt);
        Assert.False(detail.IsImage);
        Assert.Null(detail.ThumbnailPath);
    }

    [Fact]
    public async Task GetSelectedDetailForImageIncludesThumbnailPath()
    {
        await store.InitializeAsync();
        var png = ImageCodec.EncodePngBgra(8, 8, new byte[8 * 8 * 4]);
        var hash = ImageCodec.HashBytes(png);
        await store.StoreImageAsync(new AcceptedImageContent(
            png,
            hash,
            MediaLimits.MimePng,
            8,
            8,
            "paint",
            DateTimeOffset.UtcNow));
        await viewModel.InitializeAsync();

        viewModel.SelectedItem = Assert.Single(viewModel.History);
        Assert.True(viewModel.SelectedItem.IsImage);
        Assert.False(string.IsNullOrWhiteSpace(viewModel.SelectedItem.ThumbnailPath));
        Assert.True(File.Exists(viewModel.SelectedItem.ThumbnailPath));

        var detail = viewModel.GetSelectedDetail();
        Assert.NotNull(detail);
        Assert.True(detail.IsImage);
        Assert.Equal(hash, detail.ContentHash);
        Assert.Equal(viewModel.SelectedItem.ThumbnailPath, detail.ThumbnailPath);
        Assert.Equal("image/png", detail.MimeType);
        Assert.Equal(8, detail.PixelWidth);
    }

    [Fact]
    public async Task ExportHistoryWritesJsonlMatchingStoreRows()
    {
        await store.InitializeAsync();
        await store.StoreAsync(Content("first-export-row", DateTimeOffset.UtcNow));
        await store.StoreAsync(Content("second-export-row", DateTimeOffset.UtcNow.AddSeconds(1)));
        await viewModel.InitializeAsync();

        var path = Path.Combine(directory, "clipsync-export.jsonl");
        viewModel.PickExportPath = () => path;

        await viewModel.ExportHistoryCommand.ExecuteAsync(null);

        var expected = await ClipboardExport.EncodeJsonLinesAsync(store);
        var written = await File.ReadAllTextAsync(path);
        Assert.Equal(expected, written);
        Assert.Equal(2, written.AsSpan().Count('\n'));
        Assert.Equal(Strings.FormatExportedClips(2), viewModel.ExportStatus);
    }

    [Fact]
    public async Task ExportHistoryEmptyStoreWritesEmptyFileAndSaneStatus()
    {
        await viewModel.InitializeAsync();

        var path = Path.Combine(directory, "empty-export.jsonl");
        viewModel.PickExportPath = () => path;

        await viewModel.ExportHistoryCommand.ExecuteAsync(null);

        Assert.True(File.Exists(path));
        Assert.Equal(string.Empty, await File.ReadAllTextAsync(path));
        Assert.Equal(Strings.FormatExportedClips(0), viewModel.ExportStatus);
    }

    [Fact]
    public async Task ExportHistoryCancelLeavesStatusUnchanged()
    {
        await viewModel.InitializeAsync();
        viewModel.PickExportPath = () => null;

        await viewModel.ExportHistoryCommand.ExecuteAsync(null);

        Assert.Equal(string.Empty, viewModel.ExportStatus);
    }

    [Fact]
    public async Task ExportHistoryInvalidPathSetsFailedStatus()
    {
        await viewModel.InitializeAsync();
        viewModel.PickExportPath = () => Path.Combine(directory, "missing-folder", "out.jsonl");

        await viewModel.ExportHistoryCommand.ExecuteAsync(null);

        Assert.Equal(Strings.ExportFailed, viewModel.ExportStatus);
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

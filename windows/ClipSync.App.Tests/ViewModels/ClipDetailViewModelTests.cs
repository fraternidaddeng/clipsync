using System.IO;
using System.Security.Cryptography;
using System.Text;
using ClipSync.App.Clipboard;
using ClipSync.App.ViewModels;
using ClipSync.Core.Clipboard;
using ClipSync.Core.Media;
using ClipSync.Core.Storage;
using Microsoft.Data.Sqlite;

namespace ClipSync.App.Tests.ViewModels;

/// <summary>
/// GetSelectedDetail maps the current selection into the detail-window payload
/// (the unit-test seam so payload mapping never needs WPF UI), for text and
/// image clips including the generated thumbnail path.
/// </summary>
public sealed class ClipDetailViewModelTests : IAsyncDisposable
{
    private const string LocalDeviceId = "11111111-1111-4111-8111-111111111111";

    private readonly string directory;
    private readonly SqliteClipboardEventStore store;
    private readonly Win32ClipboardAdapter adapter = new();
    private readonly MainViewModel viewModel;

    public ClipDetailViewModelTests()
    {
        directory = Path.Combine(Path.GetTempPath(), "clipsync-detail-vm-tests", Guid.NewGuid().ToString("N"));
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

using System.IO;
using ClipSync.App.Clipboard;
using ClipSync.App.ViewModels;
using ClipSync.Core.Clipboard;
using ClipSync.Core.Storage;
using Microsoft.Data.Sqlite;

namespace ClipSync.App.Tests.ViewModels;

/// <summary>
/// 偏好 · 数据: the 导出历史/导入历史 commands with injected path pickers, so no
/// dialogs open. The store-level merge semantics live in ClipSync.Tests; these tests
/// cover the command wiring — status text, refresh, and the cancel path.
/// </summary>
public sealed class HistoryTransferViewModelTests : IAsyncDisposable
{
    private const string LocalDeviceId = "11111111-1111-4111-8111-111111111111";
    private const string PhoneDeviceId = "22222222-2222-4222-8222-222222222222";

    private readonly string directory;
    private readonly SqliteClipboardEventStore store;
    private readonly Win32ClipboardAdapter adapter = new();
    private string? exportPath;
    private string? importPath;
    private readonly MainViewModel viewModel;

    public HistoryTransferViewModelTests()
    {
        directory = Path.Combine(Path.GetTempPath(), "clipsync-transfer-vm-tests", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(directory);
        store = new SqliteClipboardEventStore(Path.Combine(directory, "vm.db"), LocalDeviceId);
        viewModel = new MainViewModel(
            store,
            new ClipboardCapturePolicy(),
            adapter,
            exportPathPicker: () => exportPath,
            importPathPicker: () => importPath);
    }

    [Fact]
    public async Task ExportCommandWritesTheFileAndReportsTheCount()
    {
        await viewModel.InitializeAsync();
        await store.StoreAsync(Content("clip one"));
        await store.StoreAsync(Content("clip two"));
        exportPath = Path.Combine(directory, "backup.jsonl");

        await viewModel.ExportHistoryCommand.ExecuteAsync(null);

        Assert.Contains("已导出 2 条", viewModel.HistoryTransferStatus);
        var lines = (await File.ReadAllTextAsync(exportPath)).TrimEnd('\n').Split('\n');
        Assert.Equal(3, lines.Length);
        Assert.Equal(2, HistoryExportFormat.ParseHeaderLine(lines[0]).EventCount);
    }

    [Fact]
    public async Task ImportCommandMergesRefreshesHistoryAndReportsCounts()
    {
        await viewModel.InitializeAsync();
        var file = Path.Combine(directory, "incoming.jsonl");
        await WriteExportFileAsync(file, "imported clip");
        importPath = file;

        await viewModel.ImportHistoryCommand.ExecuteAsync(null);

        Assert.Contains("新增 1", viewModel.HistoryTransferStatus);
        Assert.Contains(viewModel.History, item => item.Text == "imported clip");

        // Second run of the same file: idempotent, nothing added.
        await viewModel.ImportHistoryCommand.ExecuteAsync(null);
        Assert.Contains("已存在 1", viewModel.HistoryTransferStatus);
        Assert.Single(viewModel.History, item => item.Text == "imported clip");
    }

    [Fact]
    public async Task CancelledPickersLeaveEverythingUntouched()
    {
        await viewModel.InitializeAsync();
        exportPath = null;
        importPath = null;

        await viewModel.ExportHistoryCommand.ExecuteAsync(null);
        await viewModel.ImportHistoryCommand.ExecuteAsync(null);

        Assert.Equal(string.Empty, viewModel.HistoryTransferStatus);
    }

    [Fact]
    public async Task ForeignFilesFailWithAStatusLineAndNoChanges()
    {
        await viewModel.InitializeAsync();
        var file = Path.Combine(directory, "not-an-export.jsonl");
        await File.WriteAllTextAsync(file, "{\"hello\":\"world\"}\n");
        importPath = file;

        await viewModel.ImportHistoryCommand.ExecuteAsync(null);

        Assert.Contains("导入失败", viewModel.HistoryTransferStatus);
        Assert.Contains("未做任何改动", viewModel.HistoryTransferStatus);
        Assert.Empty(viewModel.History);
    }

    private async Task WriteExportFileAsync(string path, string content)
    {
        // Built through a scratch store so the file is a faithful v1 document.
        var scratch = new SqliteClipboardEventStore(
            Path.Combine(directory, $"scratch-{Guid.NewGuid():N}.db"),
            PhoneDeviceId);
        await using (scratch)
        {
            await scratch.StoreAsync(Content(content));
            await using var writer = new StreamWriter(path, append: false);
            await scratch.ExportHistoryAsync(writer, DateTimeOffset.UtcNow);
        }
    }

    private static AcceptedClipboardContent Content(string text)
    {
        var bytes = System.Text.Encoding.UTF8.GetBytes(text);
        var hash = Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(bytes)).ToLowerInvariant();
        return new AcceptedClipboardContent(text, hash, bytes.Length, "test", DateTimeOffset.UtcNow);
    }

    public ValueTask DisposeAsync()
    {
        SqliteConnection.ClearAllPools();
        Directory.Delete(directory, recursive: true);
        return ValueTask.CompletedTask;
    }
}

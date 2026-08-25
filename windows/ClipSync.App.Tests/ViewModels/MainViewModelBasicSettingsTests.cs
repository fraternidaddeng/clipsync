using System.IO;
using ClipSync.App.Clipboard;
using ClipSync.App.Ui;
using ClipSync.App.ViewModels;
using ClipSync.Core.Clipboard;
using ClipSync.Core.Storage;
using Microsoft.Data.Sqlite;

namespace ClipSync.App.Tests.ViewModels;

/// <summary>
/// 基础设置（settings-roadmap P0-1/P0-5/P1-7/P1-9/P1-15 的 Windows 半边）: defaults,
/// persistence through the settings table under the roadmap key contract, fallback for
/// unreadable stored values, the confirmed 清空历史 command, and the adjustable
/// entry-cap cleanup. 开机自启's registry mechanism lives in StartupRegistrationTests;
/// here only its intent mirror is covered.
/// </summary>
public sealed class MainViewModelBasicSettingsTests : IAsyncDisposable
{
    private const string LocalDeviceId = "11111111-1111-4111-8111-111111111111";

    private readonly string directory;
    private readonly SqliteClipboardEventStore store;
    private readonly Win32ClipboardAdapter adapter = new();

    public MainViewModelBasicSettingsTests()
    {
        directory = Path.Combine(Path.GetTempPath(), "clipsync-basic-settings-tests", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(directory);
        store = new SqliteClipboardEventStore(Path.Combine(directory, "vm.db"), LocalDeviceId);
    }

    private MainViewModel CreateViewModel(Func<bool>? clearHistoryConfirmer = null) => new(
        store,
        new ClipboardCapturePolicy(),
        adapter,
        clearHistoryConfirmer: clearHistoryConfirmer);

    [Fact]
    public async Task DefaultsAreStandardScaleFourLinesTwoThousandEntriesAndEverythingOff()
    {
        var viewModel = CreateViewModel();
        await viewModel.InitializeAsync();

        Assert.Equal(HistoryDisplayOptions.StandardScaleKey, viewModel.HistoryFontScaleKey);
        Assert.Equal(1.0, viewModel.HistoryFontScale);
        Assert.Equal("4", viewModel.PreviewLinesKey);
        Assert.Equal(4, viewModel.PreviewLines);
        Assert.Equal(2000, viewModel.RetentionMaxEntries);
        Assert.False(viewModel.LaunchAtStartup);
        Assert.Equal(string.Empty, viewModel.FlyoutHotkey);
        Assert.Equal(string.Empty, viewModel.FlyoutHotkeyStatus);
    }

    [Fact]
    public async Task SettingsRoundTripThroughTheStoreUnderTheRoadmapKeys()
    {
        var viewModel = CreateViewModel();
        await viewModel.InitializeAsync();
        viewModel.HistoryFontScaleKey = HistoryDisplayOptions.LargeScaleKey;
        viewModel.PreviewLinesKey = "6";
        viewModel.RetentionMaxEntries = 500;
        viewModel.LaunchAtStartup = true;
        viewModel.FlyoutHotkey = "Ctrl+Alt+V";

        await viewModel.SaveSettingsFromUiAsync();

        // The wire forms follow the roadmap key contract: factor and plain numbers.
        Assert.Equal("1.15", await store.GetSettingAsync("ui_history_font_scale"));
        Assert.Equal("6", await store.GetSettingAsync("ui_preview_lines"));
        Assert.Equal("500", await store.GetSettingAsync("retention_max_entries"));
        Assert.Equal("True", await store.GetSettingAsync("launch_at_startup"));
        Assert.Equal("Ctrl+Alt+V", await store.GetSettingAsync("hotkey_flyout"));

        var reloaded = CreateViewModel();
        await reloaded.InitializeAsync();
        Assert.Equal(HistoryDisplayOptions.LargeScaleKey, reloaded.HistoryFontScaleKey);
        Assert.Equal(1.15, reloaded.HistoryFontScale);
        Assert.Equal(6, reloaded.PreviewLines);
        Assert.Equal(500, reloaded.RetentionMaxEntries);
        Assert.True(reloaded.LaunchAtStartup);
        Assert.Equal("Ctrl+Alt+V", reloaded.FlyoutHotkey);
    }

    [Fact]
    public async Task UnreadableStoredValuesFallBackToDefaultsWithoutErroring()
    {
        await store.InitializeAsync();
        await store.SetSettingAsync("ui_history_font_scale", "gigantic");
        await store.SetSettingAsync("ui_preview_lines", "17");
        await store.SetSettingAsync("retention_max_entries", "999999");
        await store.SetSettingAsync("launch_at_startup", "sideways");

        var viewModel = CreateViewModel();
        await viewModel.InitializeAsync();

        Assert.Equal(HistoryDisplayOptions.StandardScaleKey, viewModel.HistoryFontScaleKey);
        Assert.Equal("4", viewModel.PreviewLinesKey);
        Assert.Equal(2000, viewModel.RetentionMaxEntries);
        Assert.False(viewModel.LaunchAtStartup);
    }

    [Fact]
    public async Task SaveClampsTheEntryCapIntoItsRange()
    {
        var viewModel = CreateViewModel();
        await viewModel.InitializeAsync();

        viewModel.RetentionMaxEntries = 7;
        await viewModel.SaveSettingsFromUiAsync();
        Assert.Equal(100, viewModel.RetentionMaxEntries);

        viewModel.RetentionMaxEntries = 90000;
        await viewModel.SaveSettingsFromUiAsync();
        Assert.Equal(2000, viewModel.RetentionMaxEntries);
    }

    [Fact]
    public async Task DecliningTheConfirmationLeavesHistoryUntouched()
    {
        var viewModel = CreateViewModel(clearHistoryConfirmer: () => false);
        await viewModel.InitializeAsync();
        await store.StoreAsync(Content("keep me"));
        await viewModel.RefreshFromCaptureAsync();

        await viewModel.ClearCommand.ExecuteAsync(null);

        Assert.Single(viewModel.History);
        Assert.Equal(string.Empty, viewModel.HistoryTransferStatus);
    }

    [Fact]
    public async Task ConfirmedClearRemovesEverythingAndStatesTheCount()
    {
        var viewModel = CreateViewModel(clearHistoryConfirmer: () => true);
        await viewModel.InitializeAsync();
        await store.StoreAsync(Content("first"));
        await store.StoreAsync(Content("second"));
        await viewModel.RefreshFromCaptureAsync();

        await viewModel.ClearCommand.ExecuteAsync(null);

        Assert.Empty(viewModel.History);
        Assert.Contains("已清空 2 条", viewModel.HistoryTransferStatus, StringComparison.Ordinal);
        Assert.Contains("只发生在本机", viewModel.HistoryTransferStatus, StringComparison.Ordinal);
    }

    [Fact]
    public async Task SavingASmallerEntryCapTrimsTheOldestEntries()
    {
        var viewModel = CreateViewModel();
        await viewModel.InitializeAsync();
        var baseTime = DateTimeOffset.UtcNow - TimeSpan.FromMinutes(10);
        for (var i = 0; i < 105; i++)
        {
            await store.StoreAsync(Content($"clip {i:D3}", baseTime + TimeSpan.FromSeconds(i)));
        }

        viewModel.RetentionMaxEntries = 100;
        await viewModel.SaveSettingsFromUiAsync();

        Assert.Equal(100, viewModel.History.Count);
        // The newest entries survive; the five oldest expired.
        Assert.Equal("clip 104", viewModel.History[0].Text);
        Assert.DoesNotContain(viewModel.History, item => item.Text == "clip 004");
    }

    private static AcceptedClipboardContent Content(string text, DateTimeOffset? capturedAt = null)
    {
        var bytes = System.Text.Encoding.UTF8.GetBytes(text);
        var hash = Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(bytes)).ToLowerInvariant();
        return new AcceptedClipboardContent(text, hash, bytes.Length, "test", capturedAt ?? DateTimeOffset.UtcNow);
    }

    public ValueTask DisposeAsync()
    {
        SqliteConnection.ClearAllPools();
        Directory.Delete(directory, recursive: true);
        return ValueTask.CompletedTask;
    }
}

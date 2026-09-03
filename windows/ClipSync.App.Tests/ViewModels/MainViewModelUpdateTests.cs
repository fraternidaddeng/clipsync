using System.IO;
using System.Net;
using System.Net.Http;
using System.Text;
using ClipSync.App.Clipboard;
using ClipSync.App.Update;
using ClipSync.App.ViewModels;
using ClipSync.Core.Clipboard;
using ClipSync.Core.Storage;
using ClipSync.Core.Update;
using Microsoft.Data.Sqlite;

namespace ClipSync.App.Tests.ViewModels;

public sealed class MainViewModelUpdateTests : IAsyncDisposable
{
    private const string LocalDeviceId = "11111111-1111-4111-8111-111111111111";

    private readonly string directory;
    private readonly SqliteClipboardEventStore store;
    private readonly Win32ClipboardAdapter adapter = new();

    public MainViewModelUpdateTests()
    {
        directory = Path.Combine(Path.GetTempPath(), "clipsync-update-vm-tests", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(directory);
        store = new SqliteClipboardEventStore(Path.Combine(directory, "vm.db"), LocalDeviceId);
    }

    public async ValueTask DisposeAsync()
    {
        await store.DisposeAsync();
        if (Directory.Exists(directory))
        {
            Directory.Delete(directory, recursive: true);
        }
    }

    [Fact]
    public async Task CheckForUpdatesReportsAvailableWhenLatestRanksHigher()
    {
        var json =
            """
            {"tag_name":"v0.4.0","html_url":"https://example.test/r","assets":[
              {"name":"ClipSync-windows-x64.zip","browser_download_url":"https://example.test/w.zip","size":12,
               "digest":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}]}
            """;
        using var updater = new WindowsAppUpdater(
            currentVersion: "0.3.0",
            client: new GitHubReleaseClient(
                currentVersion: "0.3.0",
                handler: new OneShotHandler(json),
                latestUri: new Uri("https://example.test/latest")));
        var viewModel = new MainViewModel(store, new ClipboardCapturePolicy(), adapter, appUpdater: updater);
        await viewModel.InitializeAsync();

        await viewModel.Update.CheckCommand.ExecuteAsync(null);

        Assert.True(viewModel.Update.Available);
        Assert.Contains("0.4.0", viewModel.Update.Status);
        Assert.Contains("0.3.0", viewModel.Update.Status);
        Assert.False(viewModel.Update.Busy);
        Assert.True(viewModel.Update.DownloadCommand.CanExecute(null));
        // App.xaml.cs keeps talking to the main view model: the status setter and the event forward.
        Assert.Equal(viewModel.Update.Status, viewModel.UpdateStatus);
        viewModel.UpdateStatus = "restated by the app layer";
        Assert.Equal("restated by the app layer", viewModel.Update.Status);
    }

    [Fact]
    public void ReadyToApplyForwardsSubscriptionsToTheChild()
    {
        using var updater = new WindowsAppUpdater(currentVersion: "0.3.0");
        var viewModel = new MainViewModel(store, new ClipboardCapturePolicy(), adapter, appUpdater: updater);
        var seen = new List<string>();
        void OnReady(string script) => seen.Add(script);

        viewModel.UpdateReadyToApply += OnReady;
        RaiseReadyToApply(viewModel.Update, "helper.cmd");
        viewModel.UpdateReadyToApply -= OnReady;
        RaiseReadyToApply(viewModel.Update, "ignored.cmd");

        Assert.Equal(["helper.cmd"], seen);
    }

    /// <summary>Fires the child's event through the same delegate the download path would invoke.</summary>
    private static void RaiseReadyToApply(UpdateViewModel update, string script)
    {
        var field = typeof(UpdateViewModel).GetField(
            nameof(UpdateViewModel.ReadyToApply),
            System.Reflection.BindingFlags.Instance | System.Reflection.BindingFlags.NonPublic);
        (field?.GetValue(update) as Action<string>)?.Invoke(script);
    }

    [Fact]
    public async Task CheckForUpdatesReportsUpToDateWhenLocalMatchesLatest()
    {
        var json =
            """
            {"tag_name":"v0.3.0","html_url":"https://example.test/r","assets":[
              {"name":"ClipSync-windows-x64.zip","browser_download_url":"https://example.test/w.zip","size":12,
               "digest":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}]}
            """;
        using var updater = new WindowsAppUpdater(
            currentVersion: "0.3.0",
            client: new GitHubReleaseClient(
                currentVersion: "0.3.0",
                handler: new OneShotHandler(json),
                latestUri: new Uri("https://example.test/latest")));
        var viewModel = new MainViewModel(store, new ClipboardCapturePolicy(), adapter, appUpdater: updater);
        await viewModel.InitializeAsync();

        await viewModel.Update.CheckCommand.ExecuteAsync(null);

        Assert.False(viewModel.Update.Available);
        Assert.Contains("0.3.0", viewModel.Update.Status);
    }

    private sealed class OneShotHandler(string body) : HttpMessageHandler
    {
        protected override Task<HttpResponseMessage> SendAsync(
            HttpRequestMessage request,
            CancellationToken cancellationToken) =>
            Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK)
            {
                Content = new StringContent(body, Encoding.UTF8, "application/json"),
            });
    }
}

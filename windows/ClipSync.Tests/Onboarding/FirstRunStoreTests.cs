using ClipSync.Core.Onboarding;
using ClipSync.Core.Storage;
using Microsoft.Data.Sqlite;

namespace ClipSync.Tests.Onboarding;

/// <summary>
/// The Windows mirror of the Android FirstRunStoreTest: the tutorial shows once, an
/// already-paired install is marked seen instead of interrupted, and the flag persists.
/// </summary>
public sealed class FirstRunStoreTests
{
    [Fact]
    public async Task FreshUnpairedInstallShowsTheTutorialUntilItIsDismissed()
    {
        await using var database = new TemporaryDatabase();
        await using var store = database.CreateStore();
        await store.InitializeAsync();
        var firstRun = new FirstRunStore(store);

        Assert.True(await firstRun.ShouldShowOnboardingAsync(alreadyPaired: false));
        // Asking alone never marks it seen — only an explicit dismissal does.
        Assert.True(await firstRun.ShouldShowOnboardingAsync(alreadyPaired: false));

        await firstRun.MarkOnboardingSeenAsync();
        Assert.False(await firstRun.ShouldShowOnboardingAsync(alreadyPaired: false));
    }

    [Fact]
    public async Task AlreadyPairedInstallIsMarkedSeenInsteadOfInterrupted()
    {
        await using var database = new TemporaryDatabase();
        await using var store = database.CreateStore();
        await store.InitializeAsync();
        var firstRun = new FirstRunStore(store);

        Assert.False(await firstRun.ShouldShowOnboardingAsync(alreadyPaired: true));
        // The decision sticks even if the pairing is forgotten later.
        Assert.False(await firstRun.ShouldShowOnboardingAsync(alreadyPaired: false));
    }

    [Fact]
    public async Task TheSeenFlagPersistsThroughTheSameSettingsStore()
    {
        await using var database = new TemporaryDatabase();
        await using (var store = database.CreateStore())
        {
            await store.InitializeAsync();
            await new FirstRunStore(store).MarkOnboardingSeenAsync();
        }

        await using var reopened = database.CreateStore();
        await reopened.InitializeAsync();
        Assert.False(await new FirstRunStore(reopened).ShouldShowOnboardingAsync(alreadyPaired: false));
    }

    [Fact]
    public async Task AnUnreadableStoredValueReadsAsNotSeen()
    {
        await using var database = new TemporaryDatabase();
        await using var store = database.CreateStore();
        await store.InitializeAsync();
        await store.SetSettingAsync(FirstRunStore.OnboardingSeenKey, "definitely-not-a-bool");

        Assert.True(await new FirstRunStore(store).ShouldShowOnboardingAsync(alreadyPaired: false));
    }

    private sealed class TemporaryDatabase : IAsyncDisposable
    {
        private readonly string directory = System.IO.Path.Combine(
            System.IO.Path.GetTempPath(),
            "clipsync-firstrun-tests",
            Guid.NewGuid().ToString("N"));

        public TemporaryDatabase()
        {
            Directory.CreateDirectory(directory);
        }

        public SqliteClipboardEventStore CreateStore() =>
            new(System.IO.Path.Combine(directory, "firstrun.db"), "windows-local");

        public ValueTask DisposeAsync()
        {
            SqliteConnection.ClearAllPools();
            Directory.Delete(directory, recursive: true);
            return ValueTask.CompletedTask;
        }
    }
}

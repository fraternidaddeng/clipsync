using ClipSync.Core.Storage;

namespace ClipSync.Core.Onboarding;

/// <summary>
/// Remembers whether the first-open tutorial has been shown — the Windows mirror of the
/// Android <c>FirstRunStore</c>. Pure decision logic over the settings table, so the
/// once-only rule stays unit-testable without WPF.
/// </summary>
public sealed class FirstRunStore(SqliteClipboardEventStore store)
{
    /// <summary>Settings-table key; holds "True" once the tutorial was walked or skipped.</summary>
    public const string OnboardingSeenKey = "onboarding_seen";

    /// <summary>
    /// The tutorial shows exactly once, and only to an install that has not paired yet:
    /// an already-paired install (e.g. the data directory survived a reinstall) has walked
    /// past everything the tutorial explains, so it is marked seen instead of interrupted.
    /// Asking alone never marks it seen — only an explicit dismissal (or the paired
    /// shortcut here) does.
    /// </summary>
    public async ValueTask<bool> ShouldShowOnboardingAsync(
        bool alreadyPaired,
        CancellationToken cancellationToken = default)
    {
        var stored = await store.GetSettingAsync(OnboardingSeenKey, cancellationToken).ConfigureAwait(false);
        if (bool.TryParse(stored, out var seen) && seen)
        {
            return false;
        }

        if (alreadyPaired)
        {
            await MarkOnboardingSeenAsync(cancellationToken).ConfigureAwait(false);
            return false;
        }

        return true;
    }

    /// <summary>Persists the seen flag; walking to the end, 稍后设置, and closing the window all land here.</summary>
    public ValueTask MarkOnboardingSeenAsync(CancellationToken cancellationToken = default) =>
        store.SetSettingAsync(OnboardingSeenKey, bool.TrueString, cancellationToken);
}

namespace ClipSync.Core.Onboarding;

/// <summary>
/// The first-open tutorial's steps, in walking order (the Windows five-step mirror of the
/// Android tutorial): welcome, the pairing QR, the 特权直读 overview, the optional
/// Bluetooth fallback, and the send-off.
/// </summary>
public enum OnboardingStep
{
    Welcome,
    Pair,
    PrivilegedRead,
    BluetoothFallback,
    Finish,
}

/// <summary>
/// The tutorial's step walk as pure data, separate from the window: what the app promises
/// here is a commitment (five steps, welcome first, the send-off last, never a trap),
/// so tests can hold the structure to it without opening WPF UI.
/// </summary>
public static class OnboardingSteps
{
    /// <summary>The steps in walking order; welcome first, the send-off last.</summary>
    public static IReadOnlyList<OnboardingStep> All { get; } = Enum.GetValues<OnboardingStep>();

    /// <summary>继续 from <paramref name="index"/>; the last step has nowhere further to go.</summary>
    public static int Next(int index) => Math.Min(index + 1, All.Count - 1);

    /// <summary>上一步 from <paramref name="index"/>; the first step has nowhere back to go.</summary>
    public static int Previous(int index) => Math.Max(index - 1, 0);

    /// <summary>The send-off: the only step without 稍后设置 (its ghost action already leaves).</summary>
    public static bool IsLast(int index) => index == All.Count - 1;
}

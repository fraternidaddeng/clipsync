using ClipSync.Core.Onboarding;

namespace ClipSync.Tests.Onboarding;

/// <summary>
/// Pins the tutorial's structural promises (the Windows mirror of the Android
/// OnboardingContentTest): five steps, welcome first, the send-off last, and a
/// step walk that never escapes the ends.
/// </summary>
public sealed class OnboardingStepsTests
{
    [Fact]
    public void WalksFiveStepsFromWelcomeToFinish()
    {
        Assert.Equal(
            new[]
            {
                OnboardingStep.Welcome,
                OnboardingStep.Pair,
                OnboardingStep.PrivilegedRead,
                OnboardingStep.BluetoothFallback,
                OnboardingStep.Finish,
            },
            OnboardingSteps.All);
    }

    [Fact]
    public void NextAdvancesButStopsAtTheLastStep()
    {
        Assert.Equal(1, OnboardingSteps.Next(0));
        Assert.Equal(4, OnboardingSteps.Next(3));
        Assert.Equal(4, OnboardingSteps.Next(4));
    }

    [Fact]
    public void PreviousWalksBackButStopsAtTheFirstStep()
    {
        Assert.Equal(3, OnboardingSteps.Previous(4));
        Assert.Equal(0, OnboardingSteps.Previous(1));
        Assert.Equal(0, OnboardingSteps.Previous(0));
    }

    [Fact]
    public void OnlyTheSendOffIsTheLastStep()
    {
        Assert.False(OnboardingSteps.IsLast(0));
        Assert.False(OnboardingSteps.IsLast(3));
        Assert.True(OnboardingSteps.IsLast(4));
    }
}

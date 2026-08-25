using ClipSync.App.Diagnostics;

namespace ClipSync.App.Tests.Diagnostics;

public class ConsoleWindowGuardTests
{
    [Fact]
    public void ApphostLaunchWithoutConsoleIsLeftUntouched()
    {
        var native = new FakeNativeConsole { ConsoleWindow = nint.Zero };

        Assert.False(ConsoleWindowGuard.DetachInheritedConsole(native));
        Assert.Equal(0, native.FreeConsoleCalls);
    }

    [Fact]
    public void InheritedConsoleIsDetachedExactlyOnce()
    {
        var native = new FakeNativeConsole { ConsoleWindow = 0x1234 };

        Assert.True(ConsoleWindowGuard.DetachInheritedConsole(native));
        Assert.Equal(1, native.FreeConsoleCalls);
    }

    [Fact]
    public void FailedDetachReportsFalseInsteadOfThrowing()
    {
        var native = new FakeNativeConsole { ConsoleWindow = 0x1234, FreeConsoleResult = false };

        Assert.False(ConsoleWindowGuard.DetachInheritedConsole(native));
        Assert.Equal(1, native.FreeConsoleCalls);
    }

    private sealed class FakeNativeConsole : ConsoleWindowGuard.INativeConsole
    {
        public nint ConsoleWindow { get; init; }

        public bool FreeConsoleResult { get; init; } = true;

        public int FreeConsoleCalls { get; private set; }

        public nint GetConsoleWindow() => ConsoleWindow;

        public bool FreeConsole()
        {
            FreeConsoleCalls++;
            return FreeConsoleResult;
        }
    }
}

using ClipSync.App.Tray;
using System.Windows.Input;

namespace ClipSync.App.Tests.Tray;

/// <summary>
/// 运行 · 全局快捷键（P1-9，呼出浮窗 / 暂停同步）: the canonical chord syntax stored in
/// <c>hotkey_flyout</c> / <c>hotkey_pause</c> and its two mappings — parsing into
/// RegisterHotKey arguments and building from live WPF keyboard state.
/// </summary>
public sealed class HotkeyGestureTests
{
    [Theory]
    [InlineData("Ctrl+Alt+V", HotkeyGesture.ModifierControl | HotkeyGesture.ModifierAlt, 0x56u)]
    [InlineData("Ctrl+Shift+7", HotkeyGesture.ModifierControl | HotkeyGesture.ModifierShift, 0x37u)]
    [InlineData("Win+C", HotkeyGesture.ModifierWin, 0x43u)]
    [InlineData("Alt+F1", HotkeyGesture.ModifierAlt, 0x70u)]
    [InlineData("Ctrl+Alt+Shift+Win+F24", HotkeyGesture.ModifierControl | HotkeyGesture.ModifierAlt | HotkeyGesture.ModifierShift | HotkeyGesture.ModifierWin, 0x87u)]
    public void ValidGesturesParseToWin32Arguments(string text, uint expectedModifiers, uint expectedKey)
    {
        Assert.True(HotkeyGesture.TryParse(text, out var modifiers, out var virtualKey));
        Assert.Equal(expectedModifiers, modifiers);
        Assert.Equal(expectedKey, virtualKey);
    }

    [Theory]
    [InlineData(null)] // off
    [InlineData("")] // off
    [InlineData("V")] // no modifier
    [InlineData("Shift+V")] // Shift alone would shadow typing
    [InlineData("Ctrl+Alt")] // no main key
    [InlineData("Ctrl+Ctrl+V")] // duplicate modifier
    [InlineData("Ctrl+Alt+Esc")] // unsupported key token
    [InlineData("Ctrl+Alt+F25")] // beyond VK_F24
    [InlineData("Meta+V")] // unknown modifier token
    public void UnusableGesturesAreRejected(string? text) =>
        Assert.False(HotkeyGesture.TryParse(text, out _, out _));

    [Fact]
    public void WpfChordsBuildTheCanonicalString()
    {
        Assert.Equal("Ctrl+Alt+V", HotkeyGesture.FromKey(ModifierKeys.Control | ModifierKeys.Alt, Key.V));
        Assert.Equal("Ctrl+Shift+9", HotkeyGesture.FromKey(ModifierKeys.Control | ModifierKeys.Shift, Key.D9));
        Assert.Equal("Win+F5", HotkeyGesture.FromKey(ModifierKeys.Windows, Key.F5));
        Assert.Equal(
            "Ctrl+Alt+Shift+Win+A",
            HotkeyGesture.FromKey(
                ModifierKeys.Control | ModifierKeys.Alt | ModifierKeys.Shift | ModifierKeys.Windows,
                Key.A));
    }

    [Fact]
    public void EveryBuiltGestureParsesBack()
    {
        var gesture = HotkeyGesture.FromKey(ModifierKeys.Control | ModifierKeys.Alt, Key.C);
        Assert.NotNull(gesture);
        Assert.True(HotkeyGesture.TryParse(gesture, out _, out _));
    }

    [Fact]
    public void UnusableWpfChordsBuildNothing()
    {
        // No usable modifier.
        Assert.Null(HotkeyGesture.FromKey(ModifierKeys.None, Key.V));
        Assert.Null(HotkeyGesture.FromKey(ModifierKeys.Shift, Key.V));
        // A modifier key itself is not a main key.
        Assert.Null(HotkeyGesture.FromKey(ModifierKeys.Control, Key.LeftAlt));
        // Unsupported main keys (navigation, numpad) are refused rather than guessed.
        Assert.Null(HotkeyGesture.FromKey(ModifierKeys.Control, Key.Left));
        Assert.Null(HotkeyGesture.FromKey(ModifierKeys.Control, Key.NumPad3));
    }
}

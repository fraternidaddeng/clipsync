using System.Windows.Input;

namespace ClipSync.App.Tray;

/// <summary>
/// 运行 · 全局快捷键（settings-roadmap P1-9：呼出浮窗 / 暂停同步）的组合键语法。The
/// stored form of <c>hotkey_flyout</c> and <c>hotkey_pause</c> is a canonical string such
/// as <c>Ctrl+Alt+V</c>: modifiers in the fixed order Ctrl, Alt, Shift, Win, then one
/// main key (A–Z, 0–9 or F1–F24). A gesture must carry Ctrl, Alt or Win — Shift alone
/// would shadow ordinary typing. The empty string means the hotkey is off (the default).
/// </summary>
public static class HotkeyGesture
{
    // RegisterHotKey modifier flags (winuser.h).
    public const uint ModifierAlt = 0x0001;
    public const uint ModifierControl = 0x0002;
    public const uint ModifierShift = 0x0004;
    public const uint ModifierWin = 0x0008;
    public const uint ModifierNoRepeat = 0x4000;

    /// <summary>
    /// Parses a canonical gesture into RegisterHotKey arguments. Returns false for the
    /// empty string, unknown tokens, a missing main key, or a Shift-only (or bare) chord.
    /// </summary>
    public static bool TryParse(string? text, out uint modifiers, out uint virtualKey)
    {
        modifiers = 0;
        virtualKey = 0;
        if (string.IsNullOrWhiteSpace(text))
        {
            return false;
        }

        var tokens = text.Split('+', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries);
        if (tokens.Length < 2)
        {
            return false;
        }

        for (var i = 0; i < tokens.Length - 1; i++)
        {
            var modifier = tokens[i] switch
            {
                "Ctrl" => ModifierControl,
                "Alt" => ModifierAlt,
                "Shift" => ModifierShift,
                "Win" => ModifierWin,
                _ => 0u,
            };
            if (modifier == 0 || (modifiers & modifier) != 0)
            {
                return false;
            }

            modifiers |= modifier;
        }

        if ((modifiers & (ModifierControl | ModifierAlt | ModifierWin)) == 0)
        {
            return false;
        }

        virtualKey = VirtualKeyFor(tokens[^1]);
        return virtualKey != 0;
    }

    /// <summary>
    /// Builds the canonical gesture from live WPF keyboard state, or null when the chord
    /// is not usable (a bare or Shift-only chord, a modifier key itself, an unsupported key).
    /// </summary>
    public static string? FromKey(ModifierKeys modifiers, Key key)
    {
        if ((modifiers & (ModifierKeys.Control | ModifierKeys.Alt | ModifierKeys.Windows)) == 0)
        {
            return null;
        }

        var keyToken = KeyTokenFor(key);
        if (keyToken is null)
        {
            return null;
        }

        var parts = new List<string>(5);
        if ((modifiers & ModifierKeys.Control) != 0)
        {
            parts.Add("Ctrl");
        }

        if ((modifiers & ModifierKeys.Alt) != 0)
        {
            parts.Add("Alt");
        }

        if ((modifiers & ModifierKeys.Shift) != 0)
        {
            parts.Add("Shift");
        }

        if ((modifiers & ModifierKeys.Windows) != 0)
        {
            parts.Add("Win");
        }

        parts.Add(keyToken);
        return string.Join('+', parts);
    }

    private static string? KeyTokenFor(Key key) => key switch
    {
        >= Key.A and <= Key.Z => ((char)('A' + (key - Key.A))).ToString(),
        >= Key.D0 and <= Key.D9 => ((char)('0' + (key - Key.D0))).ToString(),
        >= Key.F1 and <= Key.F24 => $"F{key - Key.F1 + 1}",
        _ => null,
    };

    private static uint VirtualKeyFor(string token)
    {
        if (token.Length == 1)
        {
            var character = token[0];
            if (character is >= 'A' and <= 'Z')
            {
                return (uint)character; // VK_A..VK_Z equal their ASCII codes.
            }

            if (character is >= '0' and <= '9')
            {
                return (uint)character; // VK_0..VK_9 equal their ASCII codes.
            }

            return 0;
        }

        if (token.Length is 2 or 3
            && token[0] == 'F'
            && int.TryParse(token.AsSpan(1), System.Globalization.NumberStyles.None, System.Globalization.CultureInfo.InvariantCulture, out var function)
            && function is >= 1 and <= 24)
        {
            return 0x70u + (uint)(function - 1); // VK_F1 = 0x70.
        }

        return 0;
    }
}

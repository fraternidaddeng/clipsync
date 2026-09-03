using ClipSync.App.Pairing;

namespace ClipSync.App.Tests.Pairing;

public sealed class WpfPairingApproverTests
{
    [Fact]
    public void ShortNamesReachTheBalloonTrimmedButIntact()
    {
        Assert.Equal("Pixel 8", WpfPairingApprover.NoticeName("  Pixel 8 "));
        Assert.Equal(new string('a', 40), WpfPairingApprover.NoticeName(new string('a', 40)));
    }

    [Fact]
    public void LongNamesAreCutToTheBalloonBudgetWithAnEllipsis()
    {
        var name = WpfPairingApprover.NoticeName(new string('a', 64));

        Assert.Equal(40, name.Length);
        Assert.EndsWith("…", name, StringComparison.Ordinal);
        Assert.StartsWith(new string('a', 39), name, StringComparison.Ordinal);
    }

    [Fact]
    public void TheCutNeverSplitsASurrogatePair()
    {
        // 38 ASCII chars, then an emoji straddling the cut index (chars 38-39), then more text.
        var name = WpfPairingApprover.NoticeName(new string('b', 38) + "😀" + new string('c', 10));

        Assert.EndsWith("…", name, StringComparison.Ordinal);
        Assert.All(name, character => Assert.False(char.IsSurrogate(character)));
        Assert.Equal(new string('b', 38) + "…", name);
    }
}

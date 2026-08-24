using ClipSync.App.Tray;

namespace ClipSync.App.Tests.Tray;

public class TrayStateMapperTests
{
    [Theory]
    [InlineData(false, false, false, TrayState.Flow)]
    [InlineData(false, false, true, TrayState.Attention)]
    [InlineData(false, true, false, TrayState.Paused)]
    [InlineData(false, true, true, TrayState.Paused)]
    [InlineData(true, false, false, TrayState.Private)]
    [InlineData(true, false, true, TrayState.Private)]
    [InlineData(true, true, false, TrayState.Private)]
    [InlineData(true, true, true, TrayState.Private)]
    public void MapAppliesCharterPriority(bool isPrivateMode, bool isPaused, bool needsAttention, TrayState expected) =>
        Assert.Equal(expected, TrayStateMapper.Map(isPrivateMode, isPaused, needsAttention));
}

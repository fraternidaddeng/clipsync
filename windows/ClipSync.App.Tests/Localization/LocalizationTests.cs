using System.Globalization;
using ClipSync.App.Localization;
using ClipSync.App.Ui;

namespace ClipSync.App.Tests.Localization;

/// <summary>
/// 语言（settings-roadmap P1#16）: the resource pipeline's promises — neutral zh-Hans text
/// resolves for every generated key, a lost key degrades to the key itself instead of an
/// empty string, composite formatting follows the current culture, and RTL resolution
/// (阿拉伯语 best-effort) is a pure function of the chosen key + system culture.
/// Test UI culture is pinned to zh-Hans by <see cref="TestCulture"/>.
/// </summary>
public sealed class LocalizationTests
{
    [Fact]
    public void NeutralResourcesCarryEveryGeneratedKey()
    {
        // Spot-check across pages; the generator guarantees property↔resx parity, this
        // guards the embedding (resx must actually ship inside the main assembly).
        Assert.Equal("剪剪相传", Strings.App_Name);
        Assert.Equal("剪 剪 相 传", Strings.Brand_Wordmark);
        Assert.Equal("历史", Strings.Nav_History);
        Assert.Equal("配对新设备", Strings.Pairing_Title);
        Assert.Equal("语言", Strings.Prefs_Language_Title);
    }

    [Fact]
    public void LostKeysDegradeToTheKeyItselfNeverAnEmptyString()
    {
        Assert.Equal("No_Such_Key_Ever", Strings.Get("No_Such_Key_Ever"));
    }

    [Fact]
    public void FormatSubstitutesArgumentsIntoTheResourcePattern()
    {
        Assert.Equal("监听中 · 已连 3 台", Strings.Format(nameof(Strings.Tray_Status_ConnectedFormat), 3));
        Assert.Equal(
            "导入完成：新增 5 · 已存在 2 · 冲突 0",
            Strings.Format(nameof(Strings.Transfer_ImportedFormat), 5, 2, 0));
    }

    [Theory]
    [InlineData("ar", "en-US", true)] // 阿拉伯语手选 → 镜像，不看系统
    [InlineData("zh-Hans", "ar-SA", false)] // 非 RTL 手选 → 不镜像，即使系统是 RTL
    [InlineData(LanguageCatalog.FollowSystemKey, "ar-SA", true)] // 跟随系统 → 系统说了算
    [InlineData(LanguageCatalog.FollowSystemKey, "en-US", false)]
    public void RightToLeftFollowsTheChosenKeyThenTheSystem(string key, string systemCulture, bool expected)
    {
        Assert.Equal(
            expected,
            LocalizationManager.ResolveIsRightToLeft(key, CultureInfo.GetCultureInfo(systemCulture)));
    }

    [Fact]
    public void ArabicIsTheOnlyRightToLeftCatalogLanguage()
    {
        Assert.All(
            LanguageCatalog.Languages,
            language => Assert.Equal(
                language.Tag == "ar",
                LocalizationManager.ResolveIsRightToLeft(language.Tag, CultureInfo.InvariantCulture)));
    }
}

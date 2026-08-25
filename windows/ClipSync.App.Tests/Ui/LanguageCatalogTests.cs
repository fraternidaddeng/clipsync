using ClipSync.App.Ui;

namespace ClipSync.App.Tests.Ui;

/// <summary>
/// Pins the P1#16 language catalog exactly — tags, order, endonyms and the RTL flag. The
/// Android <c>LanguageCatalogTest</c> pins the same list against the same roadmap table; if
/// either side drifts, one of the two tests fails and the catalogs must be re-aligned.
/// </summary>
public sealed class LanguageCatalogTests
{
    [Fact]
    public void CatalogMatchesTheRoadmapTableExactly()
    {
        var expected = new (string Tag, string NativeName)[]
        {
            ("zh-Hans", "简体中文"),
            ("zh-Hant", "繁體中文"),
            ("en", "English"),
            ("ja", "日本語"),
            ("ko", "한국어"),
            ("es", "Español"),
            ("fr", "Français"),
            ("de", "Deutsch"),
            ("pt-BR", "Português (Brasil)"),
            ("ru", "Русский"),
            ("ar", "العربية"),
            ("it", "Italiano"),
            ("vi", "Tiếng Việt"),
            ("th", "ไทย"),
            ("id", "Bahasa Indonesia"),
            ("hi", "हिन्दी"),
            ("tr", "Türkçe"),
            ("pl", "Polski"),
            ("nl", "Nederlands"),
        };

        Assert.Equal(expected, LanguageCatalog.Languages.Select(l => (l.Tag, l.NativeName)));
    }

    [Fact]
    public void OnlyArabicIsRightToLeft()
    {
        var rightToLeft = Assert.Single(LanguageCatalog.Languages, l => l.RightToLeft);
        Assert.Equal("ar", rightToLeft.Tag);
    }

    [Fact]
    public void TagsAreUniqueAndFollowSystemIsNotALanguage()
    {
        Assert.Equal(
            LanguageCatalog.Languages.Count,
            LanguageCatalog.Languages.Select(l => l.Tag).Distinct(StringComparer.Ordinal).Count());
        // 「跟随系统」 is a stored value, never a picker language entry.
        Assert.Null(LanguageCatalog.ByTag(LanguageCatalog.FollowSystemKey));
    }

    [Theory]
    [InlineData(LanguageCatalog.FollowSystemKey)]
    [InlineData("zh-Hans")]
    [InlineData("ar")]
    public void LegalStoredValuesRoundTripThroughTheStore(string stored)
    {
        Assert.True(LanguageCatalog.IsValidStoredValue(stored));
        Assert.Equal(stored, LanguageCatalog.KeyForStored(stored));
        Assert.Equal(stored, LanguageCatalog.StoredFor(stored));
    }

    [Theory]
    [InlineData(null)]
    [InlineData("")]
    [InlineData("zh")]
    [InlineData("pt")]
    [InlineData("ZH-HANS")]
    [InlineData("zz-ZZ")]
    public void OffCatalogStoredValuesFallBackToFollowSystem(string? stored)
    {
        Assert.False(LanguageCatalog.IsValidStoredValue(stored));
        Assert.Equal(LanguageCatalog.FollowSystemKey, LanguageCatalog.KeyForStored(stored));
    }
}

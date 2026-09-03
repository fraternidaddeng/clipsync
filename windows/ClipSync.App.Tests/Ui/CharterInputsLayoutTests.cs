using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using ClipSync.App.ViewModels;

namespace ClipSync.App.Tests.Ui;

/// <summary>
/// Layout assertions for the shared inset inputs (Resources/CharterInputs.xaml), run on an
/// STA thread the way MessageOnlyClipboardWindowSmokeTests does. They guard the two regressions
/// fixed on 2026-09-03: a doubled inner padding that clipped 12.5px text inside a 30px box,
/// and the combo box's closed state showing a record's type name instead of DisplayName.
/// The dictionary is loaded on its own (no fonts, no colour tokens): DynamicResource brushes
/// resolve to nothing, which is fine for measuring.
/// </summary>
public sealed class CharterInputsLayoutTests
{
    private const string DictionaryUri = "/ClipSync.App;component/Resources/CharterInputs.xaml";

    /// <summary>The explicit height every single-line inset box in the main window uses.</summary>
    private const double BoxHeight = 30;

    [Fact]
    public void InsetTextBoxAtThirtyPixelsDoesNotClipItsSingleLine() => RunOnSta(() =>
    {
        var dictionary = LoadDictionary();
        var textBox = new TextBox
        {
            Style = (Style)dictionary["InsetTextBox"],
            Text = "Ctrl+Alt+V 剪剪相传 gjpqy",
            Height = BoxHeight,
        };

        Layout(textBox, width: 240, height: BoxHeight);

        var host = Assert.IsType<ScrollViewer>(textBox.Template.FindName("PART_ContentHost", textBox));
        // A real line of 12.5px text measures well above 12px; anything less means the text
        // view never laid out and the clipping check below would pass vacuously.
        Assert.True(host.ExtentHeight > 12, $"The text view measured only {host.ExtentHeight:F2}px.");
        Assert.True(
            host.ViewportHeight >= host.ExtentHeight,
            $"Text needs {host.ExtentHeight:F2}px but the content host only shows {host.ViewportHeight:F2}px.");
        Assert.Equal(BoxHeight, textBox.ActualHeight);

        // Padding is applied once, by the TextBox itself: 12 each side horizontally (tokens §7
        // spacing scale), 6 vertically, which with the 1px border leaves 16px of viewport.
        Assert.Equal(new Thickness(12, 6, 12, 6), textBox.Padding);
    });

    /// <summary>
    /// Same check under the bundled faces the main window really uses (CsSans for the search
    /// and rename boxes, CsMono for hotkey / address boxes at 11.5px): the composite family's
    /// line spacing is what decides whether 16px of viewport is enough.
    /// </summary>
    [Theory]
    [InlineData("CsSans", 12.5)]
    [InlineData("CsMono", 11.5)]
    public void InsetTextBoxDoesNotClipUnderTheBundledFonts(string fontKey, double fontSize) => RunOnSta(() =>
    {
        var inputs = LoadDictionary();
        var controls = (ResourceDictionary)Application.LoadComponent(
            new Uri("/ClipSync.App;component/Resources/CharterControls.xaml", UriKind.Relative));
        var textBox = new TextBox
        {
            Style = (Style)inputs["InsetTextBox"],
            FontFamily = (FontFamily)controls[fontKey],
            FontSize = fontSize,
            Text = "Ctrl+Alt+V 192.168.1.5 剪剪相传 gjpqy",
            Height = BoxHeight,
        };

        Layout(textBox, width: 240, height: BoxHeight);

        var host = Assert.IsType<ScrollViewer>(textBox.Template.FindName("PART_ContentHost", textBox));
        Assert.True(host.ExtentHeight > 12, $"The text view measured only {host.ExtentHeight:F2}px.");
        Assert.True(
            host.ViewportHeight >= host.ExtentHeight,
            $"{fontKey} {fontSize}px needs {host.ExtentHeight:F2}px but the content host only shows {host.ViewportHeight:F2}px.");
    });

    [Fact]
    public void InsetComboBoxClosedStateShowsTheRecordDisplayName() => RunOnSta(() =>
    {
        var dictionary = LoadDictionary();
        var options = new[]
        {
            new LanguageOption("system", "跟随系统"),
            new LanguageOption("ja", "日本語"),
        };
        var comboBox = new ComboBox
        {
            Style = (Style)dictionary["InsetComboBox"],
            ItemsSource = options,
            DisplayMemberPath = nameof(LanguageOption.DisplayName),
            SelectedValuePath = nameof(LanguageOption.Key),
            SelectedValue = "ja",
            MinWidth = 176,
        };

        Layout(comboBox, width: 240, height: BoxHeight);

        var texts = Descendants(comboBox).OfType<TextBlock>().Select(block => block.Text).ToArray();
        Assert.Contains("日本語", texts);
        Assert.DoesNotContain(texts, text => text.Contains(nameof(LanguageOption), StringComparison.Ordinal));
        Assert.Equal(new Thickness(12, 6, 12, 6), comboBox.Padding);
    });

    /// <summary>
    /// Application.LoadComponent runs Application's static initialiser, which registers the
    /// pack:// scheme and the compiled-resource container — the test host has no Application
    /// instance, and a bare <c>new ResourceDictionary { Source = pack://… }</c> fails without it.
    /// </summary>
    private static ResourceDictionary LoadDictionary() =>
        (ResourceDictionary)Application.LoadComponent(new Uri(DictionaryUri, UriKind.Relative));

    private static void Layout(FrameworkElement element, double width, double height)
    {
        element.Measure(new Size(width, height));
        element.Arrange(new Rect(0, 0, width, height));
        element.UpdateLayout();
    }

    private static IEnumerable<DependencyObject> Descendants(DependencyObject root)
    {
        var count = VisualTreeHelper.GetChildrenCount(root);
        for (var i = 0; i < count; i++)
        {
            var child = VisualTreeHelper.GetChild(root, i);
            yield return child;
            foreach (var grandchild in Descendants(child))
            {
                yield return grandchild;
            }
        }
    }

    private static void RunOnSta(Action body)
    {
        Exception? failure = null;
        var thread = new Thread(() =>
        {
            try
            {
                body();
            }
            catch (Exception exception)
            {
                failure = exception;
            }
        });
        thread.SetApartmentState(ApartmentState.STA);
        thread.Start();
        Assert.True(thread.Join(TimeSpan.FromSeconds(30)), "The layout STA thread did not finish.");
        if (failure is not null)
        {
            throw new Xunit.Sdk.XunitException(failure.ToString());
        }
    }
}

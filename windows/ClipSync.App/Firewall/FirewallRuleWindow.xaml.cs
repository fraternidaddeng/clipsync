using System.Windows;
using ClipSync.App.Localization;

namespace ClipSync.App.Firewall;

/// <summary>
/// The confirmation step before the only system-level write this app performs (ADR 0006 §2):
/// the exact <c>netsh</c> lines are shown as they will run (the Block-rule deletes first when the
/// check found any, with a sentence naming them), the profile scope defaults to Private with
/// Public as an explicit opt-in, and the UAC transparency gap (the prompt names <c>netsh.exe</c>,
/// not this app, and shows no arguments) is stated in words. Closing the window any way but 确认
/// runs nothing.
/// </summary>
public partial class FirewallRuleWindow : Window
{
    private readonly FirewallRulePromptRequest request;

    public FirewallRuleWindow(FirewallRulePromptRequest request)
    {
        ArgumentNullException.ThrowIfNull(request);
        InitializeComponent();
        // 阿拉伯语 RTL（P1#16）：整窗镜像；命令是机器文本，XAML 里钉回 LTR。
        FlowDirection = LocalizationManager.WindowFlowDirection;
        this.request = request;

        if (request.IsRemoval)
        {
            Title = Strings.Conduit_FirewallRule_Remove;
            TitleText.Text = Strings.Conduit_FirewallRule_Remove;
            ProfilePanel.Visibility = Visibility.Collapsed;
        }

        PublicHintBox.Visibility = !request.IsRemoval && (request.ActiveProfiles & FirewallProfiles.Public) != 0
            ? Visibility.Visible
            : Visibility.Collapsed;
        UpdateCommand();
    }

    /// <summary>The command the user confirmed; null when the window was cancelled or closed.</summary>
    public FirewallRuleCommand? ConfirmedCommand { get; private set; }

    private FirewallProfiles SelectedProfiles =>
        (PrivateBox.IsChecked == true ? FirewallProfiles.Private : FirewallProfiles.None)
        | (PublicBox.IsChecked == true ? FirewallProfiles.Public : FirewallProfiles.None);

    private FirewallRuleCommand? BuildCommand()
    {
        if (request.IsRemoval)
        {
            return FirewallRuleCommand.Remove();
        }

        var profiles = SelectedProfiles;
        return profiles == FirewallProfiles.None
            ? null
            : FirewallRuleCommand.Allow(profiles, request.BlockRules, request.ReplaceExisting);
    }

    private void UpdateCommand()
    {
        var command = BuildCommand();
        CommandBox.Text = command?.ToDisplayString() ?? string.Empty;
        ConfirmButton.IsEnabled = command is not null;

        var blockRuleNames = command?.BlockRuleNames ?? [];
        BlockNoteText.Text = blockRuleNames.Count > 0
            ? Strings.Format(nameof(Strings.FirewallRule_Window_BlockNoteFormat), string.Join(", ", blockRuleNames))
            : string.Empty;
        BlockNoteText.Visibility = blockRuleNames.Count > 0 ? Visibility.Visible : Visibility.Collapsed;
    }

    private void OnProfileChanged(object sender, RoutedEventArgs e)
    {
        // Checked fires for the XAML default before the rest of the tree exists.
        if (CommandBox is not null && BlockNoteText is not null && ConfirmButton is not null)
        {
            UpdateCommand();
        }
    }

    private void OnConfirmClicked(object sender, RoutedEventArgs e)
    {
        ConfirmedCommand = BuildCommand();
        if (ConfirmedCommand is not null)
        {
            Close();
        }
    }

    private void OnCancelClicked(object sender, RoutedEventArgs e) => Close();
}

using System.Windows;
using ClipSync.Peer.Pairing;

namespace ClipSync.App.Pairing;

/// <summary>
/// The explicit user confirmation required by protocol section 9. Closing the window
/// without clicking Approve counts as a rejection.
/// </summary>
public partial class PairingApprovalWindow : Window
{
    public PairingApprovalWindow(PairingCandidate candidate)
    {
        InitializeComponent();
        DeviceNameText.Text = candidate.DisplayName;
        PlatformText.Text = candidate.Platform switch
        {
            "android" => "Android 设备",
            "windows" => "Windows 设备",
            _ => candidate.Platform
        };
        RepairWarningBox.Visibility = candidate.IsRepair ? Visibility.Visible : Visibility.Collapsed;
        SupersedeNoticeBox.Visibility = candidate.ReplacesSameNamePeer ? Visibility.Visible : Visibility.Collapsed;
    }

    public bool Approved { get; private set; }

    private void OnApproveClicked(object sender, RoutedEventArgs e)
    {
        Approved = true;
        Close();
    }

    private void OnRejectClicked(object sender, RoutedEventArgs e) => Close();
}

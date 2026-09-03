using ClipSync.App.Localization;
using ClipSync.App.Update;
using ClipSync.Core.Update;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using System.Net.Http;

namespace ClipSync.App.ViewModels;

/// <summary>
/// 偏好 · 关于: this portable copy's version against GitHub <c>/releases/latest</c>. Checking
/// never downloads; a newer ZIP is offered as a separate action, and applying it hands a
/// verified helper script to the app layer, which restarts the process.
/// Exposed by <see cref="MainViewModel.Update"/>; XAML binds <c>Update.*</c>.
/// </summary>
public partial class UpdateViewModel(WindowsAppUpdater updater) : ObservableObject
{
    /// <summary>Stamped assembly version shown in 偏好 · 关于.</summary>
    [ObservableProperty]
    private string appVersion = LocalAppVersion.Read();

    /// <summary>Idle / checking / up-to-date / available / progress / error for the GitHub updater.</summary>
    [ObservableProperty]
    private string status = string.Empty;

    [ObservableProperty]
    [NotifyCanExecuteChangedFor(nameof(CheckCommand))]
    [NotifyCanExecuteChangedFor(nameof(DownloadCommand))]
    private bool busy;

    [ObservableProperty]
    [NotifyCanExecuteChangedFor(nameof(DownloadCommand))]
    private bool available;

    private UpdateCheckResult? pending;

    /// <summary>
    /// Raised after the ZIP has been verified and extracted. The app layer launches
    /// the helper script and exits so files can be replaced.
    /// </summary>
    public event Action<string>? ReadyToApply;

    private bool CanCheck() => !Busy;

    [RelayCommand(CanExecute = nameof(CanCheck))]
    private async Task CheckAsync()
    {
        Busy = true;
        Available = false;
        pending = null;
        Status = Strings.Prefs_Update_Checking;
        try
        {
            var result = await updater.CheckAsync();
            pending = result;
            if (result.Payload is null)
            {
                Status = Strings.Prefs_Update_Error_NoAsset;
                return;
            }

            if (result.UpdateAvailable)
            {
                Available = true;
                Status = Strings.Format(
                    nameof(Strings.Prefs_Update_AvailableFormat),
                    result.Latest.VersionLabel,
                    result.CurrentVersion);
            }
            else
            {
                Status = Strings.Format(
                    nameof(Strings.Prefs_Update_UpToDateFormat),
                    result.CurrentVersion);
            }
        }
        catch (HttpRequestException)
        {
            Status = Strings.Prefs_Update_Error_Network;
        }
        catch (FormatException)
        {
            Status = Strings.Prefs_Update_Error_Parse;
        }
        catch (Exception)
        {
            Status = Strings.Prefs_Update_Error_Network;
        }
        finally
        {
            Busy = false;
        }
    }

    private bool CanDownload() => Available && !Busy && pending?.Payload is not null;

    [RelayCommand(CanExecute = nameof(CanDownload))]
    private async Task DownloadAsync()
    {
        var check = pending;
        if (check is null || !check.UpdateAvailable || check.Payload is null)
        {
            return;
        }

        Busy = true;
        var progress = new Progress<UpdateDownloadProgress>(p =>
            Status = Strings.Format(nameof(Strings.Prefs_Update_DownloadingFormat), p.Percent));
        try
        {
            Status = Strings.Format(nameof(Strings.Prefs_Update_DownloadingFormat), 0);
            var script = await updater.PrepareApplyAsync(check, progress);
            Status = Strings.Prefs_Update_Restarting;
            ReadyToApply?.Invoke(script);
        }
        catch (HttpRequestException)
        {
            Status = Strings.Prefs_Update_Error_Network;
        }
        catch (InvalidOperationException exception) when (
            exception.Message.Contains("SHA-256", StringComparison.Ordinal))
        {
            Status = Strings.Prefs_Update_Error_Hash;
        }
        catch (Exception)
        {
            Status = Strings.Prefs_Update_Error_Apply;
        }
        finally
        {
            Busy = false;
        }
    }
}

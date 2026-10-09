using System.Windows;
using ClipSync.App.ViewModels;

namespace ClipSync.App;

public partial class MainWindow : Window
{
    private readonly MainViewModel viewModel;

    public MainWindow(MainViewModel viewModel)
    {
        InitializeComponent();
        // 阿拉伯语 RTL（P1#16）：整窗镜像（导航、通路四段、偏好行随文化方向翻转）；
        // 指纹、快捷键、监听地址等机器文本在 XAML 里各自钉回 LTR。
        FlowDirection = Localization.LocalizationManager.WindowFlowDirection;
        this.viewModel = viewModel;
        viewModel.History.CollectionChanged += OnHistoryCollectionChanged;
        DataContext = viewModel;
        Loaded += OnLoaded;
        Closing += OnClosing;
        viewModel.DetailRequested += OpenSelectedDetail;
        // 无线配对二维码：payload 是数据（VM），像素是视图的事——文本一变就按当前 DPI 重栅格。
        viewModel.PropertyChanged += OnViewModelPropertyChanged;
    }

    private readonly System.Collections.Generic.Dictionary<System.Guid, double> flipFirstPositions = new();
    private readonly System.Collections.Generic.HashSet<System.Guid> flipNewlyAddedIds = new();
    private bool flipPending;

    /// <summary>The wireless pairing QR's intended edge in device-independent units (matches the XAML frame).</summary>
    private const double WirelessQrEdgeDips = 200;

    private void OnViewModelPropertyChanged(object? sender, System.ComponentModel.PropertyChangedEventArgs e)
    {
        if (e.PropertyName == nameof(MainViewModel.WirelessQrText))
        {
            RenderWirelessQr();
        }
    }

    /// <summary>
    /// Rasters the wireless-pairing QR with whole physical pixels per module and lays the
    /// image out at the bitmap's exact physical size, so no DPI scale resamples the modules
    /// (same recipe as PairingQrWindow / ui-gap-audit P3).
    /// </summary>
    private void RenderWirelessQr()
    {
        var payload = viewModel.WirelessQrText;
        if (payload.Length == 0)
        {
            WirelessQrImage.Source = null;
            return;
        }

        var pixelsPerDip = System.Windows.Media.VisualTreeHelper.GetDpi(this).PixelsPerDip;
        var rendered = Pairing.PairingQrRenderer.RenderPngForDpi(payload, pixelsPerDip, WirelessQrEdgeDips);
        var image = new System.Windows.Media.Imaging.BitmapImage();
        image.BeginInit();
        image.CacheOption = System.Windows.Media.Imaging.BitmapCacheOption.OnLoad;
        image.StreamSource = new System.IO.MemoryStream(rendered.Png);
        image.EndInit();
        image.Freeze();
        WirelessQrImage.Source = image;
        WirelessQrImage.Width = rendered.PixelEdge / pixelsPerDip;
        WirelessQrImage.Height = rendered.PixelEdge / pixelsPerDip;
    }

    /// <summary>Moving to a monitor with another scale re-rasters the same payload — never a new secret.</summary>
    protected override void OnDpiChanged(DpiScale oldDpi, DpiScale newDpi)
    {
        base.OnDpiChanged(oldDpi, newDpi);
        RenderWirelessQr();
        UpdateNavPillIndicator(animate: false);
    }

    private async void OnLoaded(object sender, RoutedEventArgs e)
    {
        Loaded -= OnLoaded;
        UpdateNavPillIndicator(animate: false);
        NavStack.SizeChanged += (_, _) => UpdateNavPillIndicator(animate: false);
        await viewModel.InitializeAsync();

        // 首启落「通路」页（pc-ui-inventory #14）：还没配对也没有任何历史时，
        // 网络段的「配对新设备」应当是第一眼；其余情况保持历史页。
        if (!viewModel.HasPairedDevices && viewModel.History.Count == 0)
        {
            NavConduit.IsChecked = true;
        }
    }

    private void OnNavTabChecked(object sender, RoutedEventArgs e)
    {
        if (!IsLoaded)
        {
            return;
        }

        UpdateNavPillIndicator(animate: true);
    }

    private System.Windows.Controls.RadioButton? GetCheckedNavTab() =>
        NavHistory.IsChecked == true ? NavHistory :
        NavConduit.IsChecked == true ? NavConduit :
        NavPrefs.IsChecked == true ? NavPrefs : null;

    private void UpdateNavPillIndicator(bool animate)
    {
        var target = GetCheckedNavTab();
        if (target is null || target.ActualHeight <= 0 || NavStack.ActualHeight <= 0)
        {
            return;
        }

        var targetY = target.TranslatePoint(new Point(0, 0), NavStack).Y;
        NavPillIndicator.Height = target.ActualHeight;

        if (!animate || !SystemParameters.ClientAreaAnimation)
        {
            NavPillTranslate.BeginAnimation(System.Windows.Media.TranslateTransform.YProperty, null);
            NavPillScale.BeginAnimation(System.Windows.Media.ScaleTransform.ScaleXProperty, null);
            NavPillScale.BeginAnimation(System.Windows.Media.ScaleTransform.ScaleYProperty, null);
            NavPillTranslate.Y = targetY;
            NavPillScale.ScaleX = 1.0;
            NavPillScale.ScaleY = 1.0;
            return;
        }

        var currentY = NavPillTranslate.Y;
        var delta = targetY - currentY;
        if (System.Math.Abs(delta) < 0.5)
        {
            return;
        }

        var yAnim = new System.Windows.Media.Animation.DoubleAnimationUsingKeyFrames();
        yAnim.KeyFrames.Add(new System.Windows.Media.Animation.SplineDoubleKeyFrame(
            targetY + (delta * 0.045),
            System.Windows.Media.Animation.KeyTime.FromTimeSpan(System.TimeSpan.FromMilliseconds(170)),
            new System.Windows.Media.Animation.KeySpline(0.16, 1, 0.3, 1)));
        yAnim.KeyFrames.Add(new System.Windows.Media.Animation.SplineDoubleKeyFrame(
            targetY,
            System.Windows.Media.Animation.KeyTime.FromTimeSpan(System.TimeSpan.FromMilliseconds(280)),
            new System.Windows.Media.Animation.KeySpline(0.16, 1, 0.3, 1)));

        var scaleYAnim = new System.Windows.Media.Animation.DoubleAnimationUsingKeyFrames();
        scaleYAnim.KeyFrames.Add(new System.Windows.Media.Animation.SplineDoubleKeyFrame(
            1.10,
            System.Windows.Media.Animation.KeyTime.FromTimeSpan(System.TimeSpan.FromMilliseconds(95)),
            new System.Windows.Media.Animation.KeySpline(0.2, 0, 0.4, 1)));
        scaleYAnim.KeyFrames.Add(new System.Windows.Media.Animation.SplineDoubleKeyFrame(
            0.97,
            System.Windows.Media.Animation.KeyTime.FromTimeSpan(System.TimeSpan.FromMilliseconds(195)),
            new System.Windows.Media.Animation.KeySpline(0.16, 1, 0.3, 1)));
        scaleYAnim.KeyFrames.Add(new System.Windows.Media.Animation.SplineDoubleKeyFrame(
            1.0,
            System.Windows.Media.Animation.KeyTime.FromTimeSpan(System.TimeSpan.FromMilliseconds(280)),
            new System.Windows.Media.Animation.KeySpline(0.16, 1, 0.3, 1)));

        var scaleXAnim = new System.Windows.Media.Animation.DoubleAnimationUsingKeyFrames();
        scaleXAnim.KeyFrames.Add(new System.Windows.Media.Animation.SplineDoubleKeyFrame(
            0.95,
            System.Windows.Media.Animation.KeyTime.FromTimeSpan(System.TimeSpan.FromMilliseconds(95)),
            new System.Windows.Media.Animation.KeySpline(0.2, 0, 0.4, 1)));
        scaleXAnim.KeyFrames.Add(new System.Windows.Media.Animation.SplineDoubleKeyFrame(
            1.015,
            System.Windows.Media.Animation.KeyTime.FromTimeSpan(System.TimeSpan.FromMilliseconds(195)),
            new System.Windows.Media.Animation.KeySpline(0.16, 1, 0.3, 1)));
        scaleXAnim.KeyFrames.Add(new System.Windows.Media.Animation.SplineDoubleKeyFrame(
            1.0,
            System.Windows.Media.Animation.KeyTime.FromTimeSpan(System.TimeSpan.FromMilliseconds(280)),
            new System.Windows.Media.Animation.KeySpline(0.16, 1, 0.3, 1)));

        NavPillTranslate.BeginAnimation(System.Windows.Media.TranslateTransform.YProperty, yAnim);
        NavPillScale.BeginAnimation(System.Windows.Media.ScaleTransform.ScaleYProperty, scaleYAnim);
        NavPillScale.BeginAnimation(System.Windows.Media.ScaleTransform.ScaleXProperty, scaleXAnim);
    }

    /// <summary>
    /// Non-extruding FLIP transition for history items: snapshots pre-layout Y coordinates
    /// before the ItemsPanel re-arranges, then glides surviving cards via TranslateTransform.Y
    /// and fades/lifts newly arrived cards without animating Height.
    /// </summary>
    private void OnHistoryCollectionChanged(object? sender, System.Collections.Specialized.NotifyCollectionChangedEventArgs e)
    {
        if (!IsLoaded || !SystemParameters.ClientAreaAnimation || !HistoryListBox.IsVisible)
        {
            return;
        }

        if (!flipPending)
        {
            flipFirstPositions.Clear();
            flipNewlyAddedIds.Clear();
            foreach (var item in viewModel.History)
            {
                if (HistoryListBox.ItemContainerGenerator.ContainerFromItem(item) is System.Windows.Controls.ListBoxItem container
                    && container.IsLoaded
                    && container.ActualHeight > 0)
                {
                    flipFirstPositions[item.EventId] = container.TranslatePoint(new Point(0, 0), HistoryListBox).Y;
                }
            }

            flipPending = true;
            Dispatcher.BeginInvoke(System.Windows.Threading.DispatcherPriority.Loaded, new System.Action(PlayHistoryFlip));
        }

        if (e.NewItems is not null)
        {
            foreach (var newItem in e.NewItems)
            {
                if (newItem is HistoryItemViewModel vm && !flipFirstPositions.ContainsKey(vm.EventId))
                {
                    flipNewlyAddedIds.Add(vm.EventId);
                }
            }
        }
    }

    private void PlayHistoryFlip()
    {
        flipPending = false;
        if (!SystemParameters.ClientAreaAnimation || !HistoryListBox.IsVisible)
        {
            flipFirstPositions.Clear();
            flipNewlyAddedIds.Clear();
            return;
        }

        var hadPriorItems = flipFirstPositions.Count > 0;
        var maxAnimatedIndex = System.Math.Min(viewModel.History.Count, 14);
        var easeOut = new System.Windows.Media.Animation.CubicEase
        {
            EasingMode = System.Windows.Media.Animation.EasingMode.EaseOut
        };

        for (var i = 0; i < maxAnimatedIndex; i++)
        {
            var item = viewModel.History[i];
            if (HistoryListBox.ItemContainerGenerator.ContainerFromItem(item) is not System.Windows.Controls.ListBoxItem container
                || !container.IsLoaded
                || container.ActualHeight <= 0)
            {
                continue;
            }

            var (scale, translate) = EnsureItemTransform(container);
            if (hadPriorItems && flipFirstPositions.TryGetValue(item.EventId, out var firstY))
            {
                translate.BeginAnimation(System.Windows.Media.TranslateTransform.YProperty, null);
                translate.Y = 0;
                var lastY = container.TranslatePoint(new Point(0, 0), HistoryListBox).Y;
                var deltaY = firstY - lastY;
                if (System.Math.Abs(deltaY) > 1.0 && System.Math.Abs(deltaY) < 420.0)
                {
                    var slide = new System.Windows.Media.Animation.DoubleAnimation(
                        fromValue: deltaY,
                        toValue: 0,
                        duration: new Duration(System.TimeSpan.FromMilliseconds(240)))
                    {
                        EasingFunction = easeOut
                    };
                    translate.BeginAnimation(System.Windows.Media.TranslateTransform.YProperty, slide);
                }
            }
            else if (hadPriorItems && flipNewlyAddedIds.Contains(item.EventId) && flipNewlyAddedIds.Count <= 4)
            {
                var fade = new System.Windows.Media.Animation.DoubleAnimation(
                    fromValue: 0,
                    toValue: 1,
                    duration: new Duration(System.TimeSpan.FromMilliseconds(200)))
                {
                    EasingFunction = easeOut
                };
                var lift = new System.Windows.Media.Animation.DoubleAnimation(
                    fromValue: -12,
                    toValue: 0,
                    duration: new Duration(System.TimeSpan.FromMilliseconds(240)))
                {
                    EasingFunction = easeOut
                };
                var pop = new System.Windows.Media.Animation.DoubleAnimationUsingKeyFrames();
                pop.KeyFrames.Add(new System.Windows.Media.Animation.DiscreteDoubleKeyFrame(
                    0.97,
                    System.Windows.Media.Animation.KeyTime.FromTimeSpan(System.TimeSpan.Zero)));
                pop.KeyFrames.Add(new System.Windows.Media.Animation.SplineDoubleKeyFrame(
                    1.008,
                    System.Windows.Media.Animation.KeyTime.FromTimeSpan(System.TimeSpan.FromMilliseconds(130)),
                    new System.Windows.Media.Animation.KeySpline(0.16, 1, 0.3, 1)));
                pop.KeyFrames.Add(new System.Windows.Media.Animation.SplineDoubleKeyFrame(
                    1.0,
                    System.Windows.Media.Animation.KeyTime.FromTimeSpan(System.TimeSpan.FromMilliseconds(240)),
                    new System.Windows.Media.Animation.KeySpline(0.16, 1, 0.3, 1)));

                container.BeginAnimation(UIElement.OpacityProperty, fade);
                translate.BeginAnimation(System.Windows.Media.TranslateTransform.YProperty, lift);
                scale.BeginAnimation(System.Windows.Media.ScaleTransform.ScaleXProperty, pop);
                scale.BeginAnimation(System.Windows.Media.ScaleTransform.ScaleYProperty, pop);
            }
        }

        flipFirstPositions.Clear();
        flipNewlyAddedIds.Clear();
    }

    private static (System.Windows.Media.ScaleTransform Scale, System.Windows.Media.TranslateTransform Translate) EnsureItemTransform(
        System.Windows.Controls.ListBoxItem container)
    {
        if (container.RenderTransform is System.Windows.Media.TransformGroup group
            && group.Children.Count >= 2
            && group.Children[0] is System.Windows.Media.ScaleTransform existingScale
            && group.Children[1] is System.Windows.Media.TranslateTransform existingTranslate)
        {
            return (existingScale, existingTranslate);
        }

        var scale = new System.Windows.Media.ScaleTransform(1, 1);
        var translate = new System.Windows.Media.TranslateTransform(0, 0);
        var newGroup = new System.Windows.Media.TransformGroup();
        newGroup.Children.Add(scale);
        newGroup.Children.Add(translate);
        container.RenderTransformOrigin = new Point(0.5, 0.5);
        container.RenderTransform = newGroup;
        return (scale, translate);
    }

    private void OnClosing(object? sender, System.ComponentModel.CancelEventArgs e)
    {
        if (Application.Current.ShutdownMode == ShutdownMode.OnExplicitShutdown)
        {
            e.Cancel = true;
            Hide();
        }
    }

    // 改动即生效：开关在点击时保存，文本框在失焦时保存（没有「保存设置」按钮）。
    private async void OnSettingToggled(object sender, RoutedEventArgs e) =>
        await viewModel.SaveSettingsFromUiAsync();

    // 语言（P1#16）：下拉改选即落库；界面语言重启后生效（行内赭注如实陈述）。
    // IsLoaded 闸门滤掉窗口构造期间绑定初始化触发的 SelectionChanged。
    private async void OnLanguageSelectionChanged(object sender, System.Windows.Controls.SelectionChangedEventArgs e)
    {
        if (IsLoaded)
        {
            await viewModel.SaveSettingsFromUiAsync();
        }
    }

    private async void OnSettingLostFocus(object sender, RoutedEventArgs e) =>
        await viewModel.SaveSettingsFromUiAsync();

    private async void OnRetentionMinusClicked(object sender, RoutedEventArgs e)
    {
        viewModel.RetentionDays = System.Math.Max(1, viewModel.RetentionDays - 1);
        await viewModel.SaveSettingsFromUiAsync();
    }

    private async void OnRetentionPlusClicked(object sender, RoutedEventArgs e)
    {
        viewModel.RetentionDays = System.Math.Min(3650, viewModel.RetentionDays + 1);
        await viewModel.SaveSettingsFromUiAsync();
    }

    // 保留条数（P1-15）：100–2000，每步 100——逐条步进对 2000 的量级没有意义。
    private async void OnMaxEntriesMinusClicked(object sender, RoutedEventArgs e)
    {
        viewModel.RetentionMaxEntries = System.Math.Max(100, viewModel.RetentionMaxEntries - 100);
        await viewModel.SaveSettingsFromUiAsync();
    }

    private async void OnMaxEntriesPlusClicked(object sender, RoutedEventArgs e)
    {
        viewModel.RetentionMaxEntries = System.Math.Min(2000, viewModel.RetentionMaxEntries + 100);
        await viewModel.SaveSettingsFromUiAsync();
    }

    // 全局快捷键录入框（P1-9，呼出浮窗与暂停同步共用）：输入框内直接按组合键设置，
    // Backspace/Delete/Esc 清除。Alt 组合以 Key.System 到达，真实按键在 SystemKey 里；
    // 不成组合的按键（如 Tab 导航）不拦截，键盘用户仍能离开输入框。
    private async void OnHotkeyBoxPreviewKeyDown(object sender, System.Windows.Input.KeyEventArgs e) =>
        await CaptureHotkeyChordAsync(e, () => viewModel.FlyoutHotkey, value => viewModel.FlyoutHotkey = value);

    private async void OnPauseHotkeyBoxPreviewKeyDown(object sender, System.Windows.Input.KeyEventArgs e) =>
        await CaptureHotkeyChordAsync(e, () => viewModel.PauseHotkey, value => viewModel.PauseHotkey = value);

    private async System.Threading.Tasks.Task CaptureHotkeyChordAsync(
        System.Windows.Input.KeyEventArgs e,
        System.Func<string> current,
        System.Action<string> assign)
    {
        var key = e.Key == System.Windows.Input.Key.System ? e.SystemKey : e.Key;
        if (key is System.Windows.Input.Key.Back
            or System.Windows.Input.Key.Delete
            or System.Windows.Input.Key.Escape)
        {
            e.Handled = true;
            if (current().Length > 0)
            {
                assign(string.Empty);
                await viewModel.SaveSettingsFromUiAsync();
            }

            return;
        }

        var gesture = Tray.HotkeyGesture.FromKey(System.Windows.Input.Keyboard.Modifiers, key);
        if (gesture is null)
        {
            return;
        }

        e.Handled = true;
        if (gesture == current())
        {
            return;
        }

        assign(gesture);
        await viewModel.SaveSettingsFromUiAsync();
    }

    private void OnPairNewDeviceClicked(object sender, RoutedEventArgs e) =>
        ((App)Application.Current).ShowPairingWindow(this);

    private void OnHistoryItemDoubleClick(object sender, System.Windows.Input.MouseButtonEventArgs e) =>
        OpenSelectedDetail();

    /// <summary>
    /// Opens the read-only detail window for the selected clip. Copy inside the
    /// detail view routes through the same suppression path as the list buttons.
    /// </summary>
    private void OpenSelectedDetail()
    {
        var detail = viewModel.GetSelectedDetail();
        if (detail is null)
        {
            return;
        }

        var window = new DetailWindow(detail, () =>
        {
            if (detail.IsImage)
            {
                if (detail.ContentHash is not null)
                {
                    viewModel.CopyImage(detail.ContentHash);
                }
            }
            else
            {
                viewModel.CopyText(detail.Text);
            }
        })
        {
            Owner = this
        };
        window.ShowDialog();
    }

    // 空状态的幽灵「去配对」：与 Android 同一动线——先到通路页的网络段，
    // 让用户看见配对在整条通路里的位置，而不是直接弹二维码。
    private void OnGoToConduitClicked(object sender, RoutedEventArgs e) =>
        NavConduit.IsChecked = true;

    /// <summary>首开引导收尾的「前往通路页」：与空状态的去配对同一动线。</summary>
    public void FocusConduitPage() => NavConduit.IsChecked = true;

    // 帮助 · 重新查看引导（对齐 Android 偏好页再入口）：重看不改任何设置与配对。
    private void OnReplayOnboardingClicked(object sender, RoutedEventArgs e) =>
        ((App)Application.Current).ShowOnboardingWindow(this);

    // 帮助 · 使用前必读：只在点击时打开浏览器（按界面语言选文档版本）；打不开就在行内说明。
    private void OnOpenPrivacyDocClicked(object sender, RoutedEventArgs e)
    {
        var opened = Docs.ExternalLinks.TryOpen(
            Docs.PrivacyDocLink.For(System.Globalization.CultureInfo.CurrentUICulture.Name));
        PrivacyDocOpenFailedText.Visibility = opened ? Visibility.Collapsed : Visibility.Visible;
    }

    // 自绘 chrome 的窗控三钮（WindowChrome 去掉了系统标题栏）。
    private void OnMinimizeClicked(object sender, RoutedEventArgs e) =>
        WindowState = WindowState.Minimized;

    private void OnMaxRestoreClicked(object sender, RoutedEventArgs e) =>
        WindowState = WindowState == WindowState.Maximized
            ? WindowState.Normal
            : WindowState.Maximized;

    // 与系统关闭按钮同路径：OnClosing 把它变成「隐藏到托盘」。
    private void OnCloseClicked(object sender, RoutedEventArgs e) => Close();
}

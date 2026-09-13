using System.Diagnostics;
using System.Net.Http;
using System.Reflection;
using System.Text.Json;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Threading;

namespace TifusiVpn;

public partial class MainWindow : Window
{
    private const string TelegramUsername = "javadheydeari";
    private const string UpdateRepo = "javadtifusi-eng/Tifusi-VPN";
    private const string ExeName = "TifusiVPN.exe";
    private const string InternetCheckUrl = "https://www.google.com/generate_204";

    private static readonly HttpClient Http = new() { Timeout = TimeSpan.FromSeconds(10) };

    private readonly AppSettings _settings = SettingsStore.Load();
    private readonly DispatcherTimer _ticker = new() { Interval = TimeSpan.FromSeconds(1) };

    private bool _busy;
    private bool _wasConnected;
    private DateTime? _connectedSince;
    private (long Rx, long Tx)? _lastCounters;
    private long? _downPerSec;
    private long? _upPerSec;
    private bool _internetChecked;
    private long? _latencyMs;
    private DateTime _lastInternetCheck = DateTime.MinValue;
    private bool _internetCheckRunning;
    private string? _error;
    private string? _updateUrl;

    private static int BuildNumber => Assembly.GetExecutingAssembly().GetName().Version?.Build ?? 0;

    public MainWindow()
    {
        InitializeComponent();
        Strings.Lang = _settings.Language == "fa" ? "fa" : "en";
        (Strings.IsPersian ? LangFa : LangEn).IsChecked = true;
        SubInput.Text = _settings.SubscriptionLink ?? "";

        ApplyLanguage();
        RenderServers();
        RenderHome();

        _ticker.Tick += (_, _) => Tick();
        _ticker.Start();
        Loaded += async (_, _) =>
        {
            await RefreshSilentlyAsync();
            await CheckForUpdateAsync();
        };
    }

    private ServerProfile? Selected =>
        _settings.Servers.FirstOrDefault(s => s.Id == _settings.SelectedServerId) ?? _settings.Servers.FirstOrDefault();

    // ---------- Language ----------

    private void ApplyLanguage()
    {
        FlowDirection = Strings.IsPersian ? FlowDirection.RightToLeft : FlowDirection.LeftToRight;
        TabHome.Content = Strings.S("nav_home");
        TabServers.Content = Strings.S("nav_servers");
        TabProfile.Content = Strings.S("nav_profile");
        TabAbout.Content = Strings.S("nav_about");
        SubTitle.Text = Strings.S("sub_title");
        GetServersButton.Content = Strings.S("sub_add");
        RefreshButton.Content = Strings.S("sub_refresh");
        NoServersText.Text = Strings.S("no_servers");
        LanguageTitle.Text = Strings.S("language");
        ContactTitle.Text = Strings.S("contact_us");
        TelegramButton.Content = Strings.F("telegram", TelegramUsername);
        AboutTitle.Text = Strings.S("nav_about");
        VersionText.Text = Strings.F("version", BuildNumber);
        WindowsVersionText.Text = Strings.F("windows_version", Environment.OSVersion.Version);
        CheckUpdateButton.Content = Strings.S("update_check");
        DownloadUpdateButton.Content = Strings.S("update_download");
    }

    private void Language_Checked(object sender, RoutedEventArgs e)
    {
        if (sender is not RadioButton { Tag: string tag } || !IsInitialized) return;
        if (Strings.Lang == tag && _settings.Language == tag) return;
        Strings.Lang = tag;
        _settings.Language = tag;
        SettingsStore.Save(_settings);
        ApplyLanguage();
        RenderServers();
        RenderHome();
    }

    // ---------- Navigation ----------

    private void Tab_Checked(object sender, RoutedEventArgs e)
    {
        if (!IsInitialized) return;
        HomePanel.Visibility = TabHome.IsChecked == true ? Visibility.Visible : Visibility.Collapsed;
        ServersPanel.Visibility = TabServers.IsChecked == true ? Visibility.Visible : Visibility.Collapsed;
        ProfilePanel.Visibility = TabProfile.IsChecked == true ? Visibility.Visible : Visibility.Collapsed;
        AboutPanel.Visibility = TabAbout.IsChecked == true ? Visibility.Visible : Visibility.Collapsed;
    }

    // ---------- Home ----------

    private async void PowerButton_Click(object sender, RoutedEventArgs e)
    {
        if (_busy) return;
        if (VpnManager.IsConnected)
        {
            _busy = true;
            RenderHome();
            await Task.Run(VpnManager.Disconnect);
            _busy = false;
            RenderHome();
            return;
        }

        var profile = Selected;
        if (profile == null)
        {
            ShowError(Strings.S("no_server"));
            return;
        }
        if (profile.Protocol == "IKEV2" && !string.IsNullOrEmpty(profile.Psk))
        {
            ShowError(Strings.S("err_psk"));
            return;
        }

        _busy = true;
        _error = null;
        RenderHome();

        var provisioned = await Task.Run(() => VpnManager.Provision(profile));
        if (!provisioned.Success)
        {
            _busy = false;
            ShowError(Strings.F("err_provision", FirstLine(provisioned.Output)));
            return;
        }

        var result = await Task.Run(() => VpnManager.Connect(profile));
        _busy = false;
        if (!result.Success)
        {
            ShowError(Strings.F("err_connect", result.Code, Hint(result.Code)));
        }
        Tick();
    }

    private void Tick()
    {
        var connected = VpnManager.IsConnected;
        if (connected && !_wasConnected)
        {
            _connectedSince = DateTime.Now;
            _lastCounters = null;
            _internetChecked = false;
            _latencyMs = null;
            _lastInternetCheck = DateTime.MinValue;
            _error = null;
        }
        else if (!connected && _wasConnected)
        {
            _connectedSince = null;
            _downPerSec = _upPerSec = null;
            _internetChecked = false;
        }
        _wasConnected = connected;

        if (connected)
        {
            var counters = VpnManager.Counters();
            if (counters is { } now && _lastCounters is { } before)
            {
                _downPerSec = Math.Max(0, now.Rx - before.Rx);
                _upPerSec = Math.Max(0, now.Tx - before.Tx);
            }
            _lastCounters = counters;

            // "Connected" only means the tunnel is up; this checks traffic really gets through it.
            if (!_internetCheckRunning && DateTime.Now - _lastInternetCheck > TimeSpan.FromSeconds(15))
            {
                _ = CheckInternetAsync();
            }
        }
        RenderHome();
    }

    private async Task CheckInternetAsync()
    {
        _internetCheckRunning = true;
        _lastInternetCheck = DateTime.Now;
        long? latency = null;
        try
        {
            var watch = Stopwatch.StartNew();
            using var response = await Http.GetAsync(InternetCheckUrl);
            if ((int)response.StatusCode < 400) latency = watch.ElapsedMilliseconds;
        }
        catch (Exception)
        {
            latency = null;
        }
        _latencyMs = latency;
        _internetChecked = true;
        _internetCheckRunning = false;
        RenderHome();
    }

    private void RenderHome()
    {
        var connected = _wasConnected;
        var connecting = _busy && !connected;
        var profile = Selected;

        StatusText.Text = Strings.S(connected ? "status_connected" : connecting ? "status_connecting" : "status_disconnected");
        StatusText.Foreground = connected ? (Brush)FindResource("GreenBrush") : Brushes.White;
        StatusDot.Fill = connected ? (Brush)FindResource("GreenBrush") : (Brush)FindResource("MutedBrush");
        DurationText.Text = _connectedSince is { } since ? (DateTime.Now - since).ToString(@"hh\:mm\:ss") : "00:00:00";

        ServerNameText.Text = profile?.Name ?? "—";
        ServerAddressText.Text = profile?.Server ?? "";

        DownText.Text = "↓  " + (connected ? Speed(_downPerSec) : "—");
        UpText.Text = "↑  " + (connected ? Speed(_upPerSec) : "—");
        PingText.Text = !connected ? "—"
            : !_internetChecked ? "…"
            : _latencyMs is { } ms ? Strings.F("internet_ok", ms) : Strings.S("internet_fail");

        var quota = profile != null && profile.Id.StartsWith("sub:") ? QuotaLabel(_settings.Info) : null;
        QuotaText.Text = quota ?? "";
        QuotaText.Visibility = quota == null ? Visibility.Collapsed : Visibility.Visible;

        PowerButton.BorderBrush = connected
            ? (Brush)FindResource("GreenBrush")
            : connecting ? (Brush)FindResource("AccentBrush") : new SolidColorBrush(Color.FromRgb(0x3A, 0x44, 0x70));
        PowerLabel.Text = Strings.S(connected || connecting ? "action_disconnect" : "action_connect");

        ErrorText.Text = _error ?? "";
        ErrorText.Visibility = _error == null ? Visibility.Collapsed : Visibility.Visible;
    }

    private void ShowError(string message)
    {
        _error = message;
        RenderHome();
    }

    private static string? QuotaLabel(SubscriptionInfo? info)
    {
        if (info == null) return null;
        string days;
        if (info.Expire is not { } expire)
        {
            days = Strings.S("quota_no_expiry");
        }
        else
        {
            var secondsLeft = expire - DateTimeOffset.UtcNow.ToUnixTimeSeconds();
            days = secondsLeft <= 0 ? Strings.S("quota_expired") : Strings.F("quota_days", (int)Math.Ceiling(secondsLeft / 86400.0));
        }
        var data = info.Limit is { } limit
            ? Strings.F("quota_data", Bytes(Math.Max(0, limit - info.Used)))
            : Strings.S("quota_unlimited");
        return $"⏳ {days}   ·   📦 {data}";
    }

    private static string Speed(long? bytesPerSec)
    {
        if (bytesPerSec is not { } value) return "—";
        var kbps = value * 8 / 1000.0;
        return kbps >= 1000 ? $"{kbps / 1000:0.0} Mbps" : $"{kbps:0} kbps";
    }

    private static string Bytes(long bytes)
    {
        var gb = bytes / 1024.0 / 1024.0 / 1024.0;
        var mb = bytes / 1024.0 / 1024.0;
        return gb >= 1 ? $"{gb:0.0} GB" : $"{mb:0} MB";
    }

    private static string Hint(int code) => code switch
    {
        691 => Strings.S("hint_691"),
        809 => Strings.S("hint_809"),
        13801 => Strings.S("hint_13801"),
        _ => Strings.S("hint_other"),
    };

    private static string FirstLine(string text) =>
        text.Split('\n', StringSplitOptions.RemoveEmptyEntries).FirstOrDefault()?.Trim() ?? text;

    // ---------- Servers ----------

    private async void GetServers_Click(object sender, RoutedEventArgs e) => await ImportAsync(SubInput.Text, showMessage: true);

    private async void Refresh_Click(object sender, RoutedEventArgs e) =>
        await ImportAsync(_settings.SubscriptionLink ?? SubInput.Text, showMessage: true);

    private async Task RefreshSilentlyAsync()
    {
        if (!string.IsNullOrWhiteSpace(_settings.SubscriptionLink))
        {
            await ImportAsync(_settings.SubscriptionLink, showMessage: false);
        }
    }

    private async Task ImportAsync(string link, bool showMessage)
    {
        if (string.IsNullOrWhiteSpace(link)) return;
        if (showMessage) SetSubMessage(Strings.S("sub_loading"), isError: false);
        GetServersButton.IsEnabled = RefreshButton.IsEnabled = false;
        try
        {
            var (servers, info) = await SubscriptionClient.FetchAsync(link);
            _settings.Servers = _settings.Servers.Where(s => !s.Id.StartsWith("sub:")).Concat(servers).ToList();
            _settings.Info = info;
            _settings.SubscriptionLink = link.Trim();
            if (_settings.Servers.All(s => s.Id != _settings.SelectedServerId))
            {
                _settings.SelectedServerId = _settings.Servers.FirstOrDefault()?.Id;
            }
            SettingsStore.Save(_settings);
            if (showMessage) SetSubMessage(Strings.F("sub_imported", servers.Count), isError: false);
        }
        catch (SubscriptionException ex)
        {
            if (showMessage)
            {
                var text = ex.Key == "sub_network" ? Strings.F("sub_network", ex.Detail ?? "") : Strings.S(ex.Key);
                SetSubMessage(text, isError: true);
            }
        }
        finally
        {
            GetServersButton.IsEnabled = RefreshButton.IsEnabled = true;
            RenderServers();
            RenderHome();
        }
    }

    private void SetSubMessage(string text, bool isError)
    {
        SubMessage.Text = text;
        SubMessage.Foreground = isError ? (Brush)FindResource("ErrorBrush") : (Brush)FindResource("AccentBrush");
        SubMessage.Visibility = Visibility.Visible;
    }

    private void RenderServers()
    {
        var selectedId = Selected?.Id;
        ServerList.ItemsSource = _settings.Servers.Select(s => new ServerRow
        {
            Id = s.Id,
            Name = s.Name,
            Subtitle = $"{s.Protocol} · {s.Server}",
            BorderColor = s.Id == selectedId ? "#29B6F6" : "#26305A",
        }).ToList();
        NoServersText.Visibility = _settings.Servers.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
    }

    private void Server_Click(object sender, RoutedEventArgs e)
    {
        if (sender is not Button { Tag: string id }) return;
        _settings.SelectedServerId = id;
        SettingsStore.Save(_settings);
        RenderServers();
        RenderHome();
        TabHome.IsChecked = true;
    }

    // ---------- Profile ----------

    private void Telegram_Click(object sender, RoutedEventArgs e) => OpenUrl($"https://t.me/{TelegramUsername}");

    // ---------- About ----------

    private async void CheckUpdate_Click(object sender, RoutedEventArgs e) => await CheckForUpdateAsync();

    private void DownloadUpdate_Click(object sender, RoutedEventArgs e)
    {
        if (_updateUrl != null) OpenUrl(_updateUrl);
    }

    /// <summary>
    /// CI tags every build v&lt;run number&gt; and stamps the same number into the exe's version, so the
    /// latest GitHub release says directly whether this copy is behind.
    /// </summary>
    private async Task CheckForUpdateAsync()
    {
        UpdateText.Text = Strings.S("update_checking");
        DownloadUpdateButton.Visibility = Visibility.Collapsed;
        try
        {
            using var request = new HttpRequestMessage(HttpMethod.Get, $"https://api.github.com/repos/{UpdateRepo}/releases/latest");
            request.Headers.UserAgent.ParseAdd("TifusiVPN-Windows");
            using var response = await Http.SendAsync(request);
            response.EnsureSuccessStatusCode();
            using var doc = JsonDocument.Parse(await response.Content.ReadAsStringAsync());
            var tag = doc.RootElement.GetProperty("tag_name").GetString() ?? "";
            if (!int.TryParse(tag.TrimStart('v'), out var latest)) throw new FormatException(tag);

            if (latest > BuildNumber)
            {
                _updateUrl = $"https://github.com/{UpdateRepo}/releases/latest/download/{ExeName}";
                UpdateText.Text = Strings.F("update_available", latest);
                DownloadUpdateButton.Visibility = Visibility.Visible;
            }
            else
            {
                UpdateText.Text = Strings.S("update_latest");
            }
        }
        catch (Exception)
        {
            UpdateText.Text = Strings.S("update_failed");
        }
    }

    private static void OpenUrl(string url)
    {
        try
        {
            Process.Start(new ProcessStartInfo(url) { UseShellExecute = true });
        }
        catch (Exception)
        {
            // No browser registered; nothing useful to do.
        }
    }
}

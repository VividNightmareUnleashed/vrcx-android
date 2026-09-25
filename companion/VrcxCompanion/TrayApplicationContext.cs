using VrcxCompanion.Core;
using VrcxCompanion.Core.Diagnostics;
using VrcxCompanion.UI;

namespace VrcxCompanion;

/// <summary>The tray icon and its menu. There is no main window.</summary>
internal sealed class TrayApplicationContext : ApplicationContext
{
    private readonly CompanionHost _host;
    private readonly ICompanionLog _log;
    private readonly NotifyIcon _icon;
    private readonly Control _invoker = new();
    private readonly ToolStripMenuItem _autostart;
    private StatusForm? _status;
    private PairingForm? _pairing;
    private DevicesForm? _devices;

    public TrayApplicationContext(CompanionHost host, ICompanionLog log)
    {
        _host = host;
        _log = log;
        _invoker.CreateControl();
        _ = _invoker.Handle;

        var menu = BuildMenu(ShowStatus, ShowPairing, ShowDevices, ToggleAutostart, AllowFirewallAsync, Quit, out _autostart);
        menu.Opening += (_, _) => _autostart.Checked = SafeAutostartEnabled();

        _icon = new NotifyIcon
        {
            Icon = FormParts.AppIcon(SystemInformation.SmallIconSize) ?? SystemIcons.Application,
            Text = "VRCX Companion",
            ContextMenuStrip = menu,
            Visible = true,
        };
        _icon.MouseClick += (_, e) =>
        {
            if (e.Button == MouseButtons.Left)
                ShowStatus();
        };

        _host.Engine.SessionsChanged += () => Post(UpdateTooltip);
        UpdateTooltip();

        if (!_host.Listening)
            Balloon("VRCX Companion cannot accept connections", _host.ListenError ?? "The port is in use.", ToolTipIcon.Error);
        else if (_host.Devices.List().Count == 0)
            Balloon("VRCX Companion is running", "Right-click this icon and choose \"Pair new device...\" to connect your phone.", ToolTipIcon.Info);
    }

    /// <summary>The tray menu: Status..., Pair new device..., Paired devices..., Start with Windows, Allow through Windows Firewall..., Quit.</summary>
    internal static ContextMenuStrip BuildMenu(Action status, Action pair, Action devices, Action toggleAutostart,
        Func<Task> allowFirewall, Action quit, out ToolStripMenuItem autostart)
    {
        var menu = new ContextMenuStrip
        {
            Renderer = new DarkMenuRenderer(),
            BackColor = Theme.Background,
            ForeColor = Theme.Text,
            Font = Theme.Body,
            ShowImageMargin = true,
        };
        menu.Items.Add(new ToolStripMenuItem("Status...", null, (_, _) => status()) { Font = new Font(Theme.Body, FontStyle.Bold) });
        menu.Items.Add(new ToolStripMenuItem("Pair new device...", null, (_, _) => pair()));
        menu.Items.Add(new ToolStripMenuItem("Paired devices...", null, (_, _) => devices()));
        menu.Items.Add(new ToolStripSeparator());
        autostart = new ToolStripMenuItem("Start with Windows", null, (_, _) => toggleAutostart());
        menu.Items.Add(autostart);
        menu.Items.Add(new ToolStripMenuItem("Allow through Windows Firewall...", null, async (_, _) => await allowFirewall()));
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add(new ToolStripMenuItem("Quit", null, (_, _) => quit()));
        foreach (ToolStripItem item in menu.Items)
            item.ForeColor = Theme.Text;
        return menu;
    }

    private void Post(Action action)
    {
        if (_invoker.IsHandleCreated && !_invoker.IsDisposed)
            _invoker.BeginInvoke(action);
    }

    private void UpdateTooltip()
    {
        var count = _host.Engine.Sessions.Count;
        var text = count switch
        {
            0 => "VRCX Companion",
            1 => "VRCX Companion - 1 phone connected",
            _ => $"VRCX Companion - {count} phones connected",
        };
        _icon.Text = text.Length > 63 ? text[..63] : text;
    }

    private void Balloon(string title, string text, ToolTipIcon icon)
    {
        _icon.BalloonTipTitle = title;
        _icon.BalloonTipText = text;
        _icon.BalloonTipIcon = icon;
        _icon.ShowBalloonTip(5000);
    }

    private void ShowStatus() => Show(ref _status, () => new StatusForm(_host, ShowPairing, ShowDevices));

    private void ShowPairing() => Show(ref _pairing, () => new PairingForm(_host));

    private void ShowDevices() => Show(ref _devices, () => new DevicesForm(_host, ShowPairing));

    private void Show<T>(ref T? field, Func<T> create) where T : Form
    {
        if (field is { IsDisposed: false })
        {
            if (field.WindowState == FormWindowState.Minimized)
                field.WindowState = FormWindowState.Normal;
            field.Activate();
            return;
        }
        var form = create();
        field = form;
        form.Show();
        form.Activate();
    }

    private bool SafeAutostartEnabled()
    {
        try
        {
            return Autostart.IsEnabled();
        }
        catch (Exception e) when (e is System.Security.SecurityException or UnauthorizedAccessException or IOException)
        {
            return false;
        }
    }

    private void ToggleAutostart()
    {
        try
        {
            var enable = !SafeAutostartEnabled();
            Autostart.SetEnabled(enable);
            _autostart.Checked = enable;
            _log.Info(enable ? "start with Windows enabled" : "start with Windows disabled");
        }
        catch (Exception e) when (e is System.Security.SecurityException or UnauthorizedAccessException or IOException)
        {
            _log.Warn("could not change start with Windows", e);
            MessageBox.Show("The setting could not be changed: " + e.Message, "VRCX Companion", MessageBoxButtons.OK, MessageBoxIcon.Warning);
        }
    }

    private async Task AllowFirewallAsync()
    {
        var tcp = _host.TcpPort;
        var udp = _host.DiscoveryPort;
        var answer = MessageBox.Show(
            "Windows will ask for administrator permission to add two inbound firewall rules, limited to private networks " +
            $"and to addresses on your local subnet:\n\n  TCP {tcp} (phone connections)\n  UDP {udp} (discovery)\n\nContinue?",
            "Allow through Windows Firewall", MessageBoxButtons.OKCancel, MessageBoxIcon.Information);
        if (answer != DialogResult.OK)
            return;
        var result = await Firewall.AddRulesAsync(tcp, udp);
        switch (result)
        {
            case null:
                return;
            case 0:
                _log.Info("firewall rules added");
                Balloon("Firewall rules added", "Phones on your private network can now reach VRCX Companion.", ToolTipIcon.Info);
                break;
            default:
                _log.Warn($"adding firewall rules failed (exit code {result})");
                MessageBox.Show($"The firewall rules could not be added (netsh exit code {result}).", "VRCX Companion",
                    MessageBoxButtons.OK, MessageBoxIcon.Warning);
                break;
        }
    }

    private void Quit()
    {
        _icon.Visible = false;
        foreach (var f in new Form?[] { _status, _pairing, _devices })
        {
            if (f is { IsDisposed: false })
                f.Close();
        }
        ExitThread();
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing)
        {
            _icon.Visible = false;
            _icon.Dispose();
            _invoker.Dispose();
        }
        base.Dispose(disposing);
    }
}

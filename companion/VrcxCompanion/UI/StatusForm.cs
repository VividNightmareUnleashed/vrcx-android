using System.Net;
using VrcxCompanion.Core;
using VrcxCompanion.Core.Net;
using VrcxCompanion.Core.Security;

namespace VrcxCompanion.UI;

/// <summary>
/// Status: server, networks (with the Public-network warning and "Allow on this network"), VRChat/SteamVR, log
/// directory, bytes sent, connected devices.
/// </summary>
internal sealed class StatusForm : Form
{
    private readonly CompanionHost _host;
    private readonly System.Windows.Forms.Timer _timer = new() { Interval = 1000 };
    private readonly StatusDot _server = new();
    private readonly StatusDot _vrchat = new();
    private readonly StatusDot _steamvr = new();
    private readonly StatusDot _logDir = new();
    private readonly Label _logPath = FormParts.Text("", muted: true, Theme.Small);
    private readonly Label _bytes = FormParts.Text("");
    private readonly Label _addresses = FormParts.Text("", muted: false);
    private readonly Label _identity = FormParts.Text("", muted: true, Theme.Small);
    private readonly FlowLayoutPanel _networks = new()
    {
        FlowDirection = FlowDirection.TopDown,
        AutoSize = true,
        WrapContents = false,
        BackColor = Color.Transparent,
        Margin = new Padding(0),
    };
    private readonly FlowLayoutPanel _sessions = new();
    private readonly CardPanel _serverCard = new();
    private readonly TableLayoutPanel _serverTable = FormParts.KeyValueTable();
    private IDisposable? _observer;
    private IReadOnlyList<IPAddress> _cachedAddresses = Array.Empty<IPAddress>();
    private long _addressesAt;
    private string _sessionsKey = "";
    private string? _networksKey;

    public StatusForm(CompanionHost host, Action openPairing, Action openDevices)
    {
        _host = host;
        Text = "VRCX Companion";
        Icon = FormParts.AppIcon();
        StartPosition = FormStartPosition.CenterScreen;
        FormBorderStyle = FormBorderStyle.FixedSingle;
        MaximizeBox = false;
        ClientSize = new Size(560, 640);
        Padding = new Padding(20);
        Theme.ApplyTo(this);

        var content = new Panel { Dock = DockStyle.Fill, BackColor = Theme.Background, AutoScroll = true };

        _serverCard.Dock = DockStyle.Top;
        _serverCard.Padding = new Padding(16, 12, 16, 12);
        var table = _serverTable;
        foreach (var dot in new[] { _server, _vrchat, _steamvr, _logDir })
            dot.Size = new Size(350, 22);
        FormParts.AddRow(table, "Server", _server);
        FormParts.AddRow(table, "Network", _networks);
        FormParts.AddRow(table, "VRChat", _vrchat);
        FormParts.AddRow(table, "SteamVR", _steamvr);
        var logBox = new FlowLayoutPanel
        {
            FlowDirection = FlowDirection.TopDown,
            AutoSize = true,
            WrapContents = false,
            BackColor = Color.Transparent,
            Margin = new Padding(0),
        };
        _logPath.MaximumSize = new Size(350, 0);
        _logPath.Margin = new Padding(0, 0, 0, 2);
        _logDir.Margin = new Padding(0);
        logBox.Controls.Add(_logDir);
        logBox.Controls.Add(_logPath);
        FormParts.AddRow(table, "Log directory", logBox);
        FormParts.AddRow(table, "Sent", _bytes);
        _addresses.MaximumSize = new Size(350, 0);
        FormParts.AddRow(table, "This PC", _addresses);
        _identity.MaximumSize = new Size(350, 0);
        FormParts.AddRow(table, "Fingerprint", _identity);
        _serverCard.Controls.Add(table);

        var spacer = new Panel { Dock = DockStyle.Top, Height = 16, BackColor = Theme.Background };

        var devicesCard = new CardPanel { Dock = DockStyle.Fill, Padding = new Padding(16, 12, 16, 12) };
        var devicesHeading = FormParts.Heading("Connected devices");
        devicesHeading.Dock = DockStyle.Top;
        _sessions.Dock = DockStyle.Fill;
        _sessions.FlowDirection = FlowDirection.TopDown;
        _sessions.WrapContents = false;
        _sessions.AutoScroll = true;
        _sessions.BackColor = Theme.Background;
        devicesCard.Controls.Add(_sessions);
        devicesCard.Controls.Add(devicesHeading);

        content.Controls.Add(devicesCard);
        content.Controls.Add(spacer);
        content.Controls.Add(_serverCard);

        var buttons = FormParts.ButtonRow(
            FormParts.Button("Pair new device...", (_, _) => openPairing()),
            FormParts.Button("Paired devices...", (_, _) => openDevices()),
            FormParts.Button("Close", (_, _) => Close(), primary: true));

        Controls.Add(content);
        Controls.Add(buttons);

        _timer.Tick += (_, _) => RefreshStatus();
        Shown += (_, _) =>
        {
            _observer = _host.ObserveStatus();
            RefreshStatus();
            _timer.Start();
        };
        FormClosed += (_, _) =>
        {
            _timer.Stop();
            _timer.Dispose();
            _observer?.Dispose();
        };
    }

    private void RefreshStatus()
    {
        var s = _host.GetStatus();

        if (s.Listening)
        {
            _server.DotColor = Theme.Success;
            _server.Text = $"Listening, TCP {s.TcpPort}" + (s.DiscoveryActive ? $" · UDP {s.DiscoveryPort}" : " · no discovery");
        }
        else
        {
            _server.DotColor = Theme.Danger;
            _server.Text = "Not listening: " + (s.ListenError ?? "stopped");
        }
        if (s.Listening && !s.DiscoveryActive && s.DiscoveryError != null)
        {
            _server.DotColor = Theme.Warning;
            _server.Text = $"Listening, TCP {s.TcpPort} · no discovery ({s.DiscoveryError})";
        }

        SetProcess(_vrchat, s.Process?.VrchatRunning);
        SetProcess(_steamvr, s.Process?.SteamVrRunning);

        _logDir.DotColor = s.LogDirectoryExists switch
        {
            true => Theme.Success,
            false => Theme.Warning,
            null => Theme.MutedText,
        };
        _logDir.Text = s.LogDirectoryExists switch
        {
            true => "Found",
            false => "Not found (start VRChat once)",
            null => "Checking...",
        };
        _logPath.Text = s.LogDirectory;
        _bytes.Text = FormParts.Bytes(s.TotalBytesSent) + " since start";

        if (Environment.TickCount64 - _addressesAt > 10_000 || _addressesAt == 0)
        {
            _cachedAddresses = LocalAddress.GetAdvertisedAddresses();
            _addressesAt = Environment.TickCount64;
        }
        _addresses.Text = _cachedAddresses.Count == 0
            ? "No local network address"
            : string.Join(", ", _cachedAddresses.Select(PairingPayload.FormatHost));
        _identity.Text = _host.Identity.Fingerprint;
        RefreshNetworks();
        FitServerCard();

        var key =string.Join("|", s.Sessions.Select(x => $"{x.DeviceId}:{x.Subscribed}:{x.Syncing}:{x.BytesSent / 4096}"));
        if (key != _sessionsKey)
        {
            _sessionsKey = key;
            RenderSessions(s.Sessions);
        }
    }

    /// <summary>Sizes the server card to its table so no row is clipped.</summary>
    private void FitServerCard()
    {
        var inner = _serverCard.ClientSize.Width - _serverCard.Padding.Horizontal;
        var height = _serverTable.GetPreferredSize(new Size(inner, 0)).Height + _serverCard.Padding.Vertical;
        if (_serverCard.Height != height)
            _serverCard.Height = height;
    }

    /// <summary>
    /// One line per connected network with the gate's verdict (PROTOCOL.md §1). A Public network gets the explanation
    /// of how to make it Private, and the explicit opt-in.
    /// </summary>
    private void RefreshNetworks()
    {
        var gate = _host.NetworkGate;
        var networks = gate.Describe();
        var blockedOn = gate.LastBlocked?.Network?.NetworkId;
        var key = gate.CategoriesAvailable + "|" + blockedOn + "|" +
                  string.Join("|", networks.Select(n => $"{n.Network.NetworkId}:{n.Network.Name}:{n.Network.Category}:{n.Network.IsMain}:{n.Allowed}"));
        if (key == _networksKey)
            return;
        _networksKey = key;

        _networks.SuspendLayout();
        foreach (Control c in _networks.Controls)
            c.Dispose();
        _networks.Controls.Clear();
        if (!gate.CategoriesAvailable)
        {
            _networks.Controls.Add(Line(Theme.Warning, "Network type unknown"));
            _networks.Controls.Add(Note("Windows did not report whether your networks are private, so phones on any local network can connect."));
        }
        else if (networks.Count == 0)
        {
            _networks.Controls.Add(Line(Theme.MutedText, "Not connected to a network"));
        }
        foreach (var (network, allowed) in networks)
        {
            var name = string.IsNullOrWhiteSpace(network.Name) ? "Unnamed network" : network.Name;
            switch (network.Category)
            {
                case NetworkCategory.Private or NetworkCategory.Domain:
                    _networks.Controls.Add(Line(Theme.Success, $"{name} · {network.Category}"));
                    break;
                case NetworkCategory.Public when allowed:
                    _networks.Controls.Add(Line(Theme.Success, $"{name} · Public, allowed by you"));
                    _networks.Controls.Add(SmallButton("Stop allowing", () => gate.DisallowPublic(network.NetworkId)));
                    break;
                // The PC's main network, or one a phone just tried: explain the fix and offer the opt-in. Other
                // Public networks (a virtual adapter's "Unidentified network") only get a line.
                case NetworkCategory.Public when network.IsMain || network.NetworkId == blockedOn:
                    _networks.Controls.Add(Line(Theme.Warning, $"{name} · Public: phones are ignored"));
                    _networks.Controls.Add(Note(
                        "Windows marks this network as Public, so VRCX Companion ignores phones on it. If it is your home " +
                        "network, set it to Private: Windows Settings > Network & internet > this network's properties > " +
                        "Network profile type. Or allow VRCX Companion on this network only:"));
                    _networks.Controls.Add(SmallButton("Allow on this network", () => ConfirmAllow(network.NetworkId, name)));
                    break;
                case NetworkCategory.Public:
                    _networks.Controls.Add(Line(Theme.MutedText, $"{name} · Public, ignored"));
                    break;
                default:
                    _networks.Controls.Add(Line(Theme.MutedText, $"{name} · unidentified, ignored"));
                    break;
            }
        }
        _networks.ResumeLayout();
    }

    private void ConfirmAllow(Guid networkId, string name)
    {
        var answer = MessageBox.Show(
            $"Allow VRCX Companion on \"{name}\"?\n\nWindows treats this network as Public. Only allow it if it is your own " +
            "network, such as your home Wi-Fi: other devices on it will be able to find this PC and ask to pair.\n\n" +
            "Setting the network to Private in Windows Settings is the better fix.",
            "Allow on this network", MessageBoxButtons.OKCancel, MessageBoxIcon.Warning, MessageBoxDefaultButton.Button2);
        if (answer == DialogResult.OK)
            _host.NetworkGate.AllowPublic(networkId);
    }

    private static StatusDot Line(Color color, string text) =>
        new() { DotColor = color, Text = text, Size = new Size(350, 22), Margin = new Padding(0) };

    private static Label Note(string text)
    {
        var label = FormParts.Text(text, muted: true, Theme.Small);
        label.MaximumSize = new Size(350, 0);
        label.Margin = new Padding(0, 0, 0, 4);
        return label;
    }

    private static ThemedButton SmallButton(string text, Action onClick)
    {
        var button = FormParts.Button(text, (_, _) => onClick());
        button.Margin = new Padding(0, 0, 0, 6);
        return button;
    }

    private static void SetProcess(StatusDot dot, bool? running)
    {
        dot.DotColor = running switch
        {
            true => Theme.Success,
            false => Theme.MutedText,
            null => Theme.MutedText,
        };
        dot.Text = running switch
        {
            true => "Running",
            false => "Not running",
            null => "Checking...",
        };
    }

    private void RenderSessions(IReadOnlyList<SessionStatus> sessions)
    {
        _sessions.SuspendLayout();
        foreach (Control c in _sessions.Controls)
            c.Dispose();
        _sessions.Controls.Clear();
        if (sessions.Count == 0)
        {
            _sessions.Controls.Add(FormParts.Text("No phone is connected.", muted: true));
        }
        foreach (var x in sessions)
        {
            var dot = new StatusDot
            {
                DotColor = x.Syncing ? Theme.Warning : Theme.Success,
                Text = $"{(string.IsNullOrEmpty(x.DeviceName) ? "Phone" : x.DeviceName)}  ·  {x.RemoteAddress}",
                Size = new Size(460, 22),
                Margin = new Padding(0, 2, 0, 0),
            };
            var state = x.Syncing ? "catching up" : x.Subscribed ? "streaming" : "connected";
            var details = FormParts.Text(
                $"{state} since {FormParts.LocalTime(x.ConnectedAtUtc.UtcDateTime)}  ·  {FormParts.Bytes(x.BytesSent)} sent",
                muted: true, Theme.Small);
            details.Margin = new Padding(Theme.Scale(this, 16), 0, 0, 8);
            _sessions.Controls.Add(dot);
            _sessions.Controls.Add(details);
        }
        _sessions.ResumeLayout();
    }
}

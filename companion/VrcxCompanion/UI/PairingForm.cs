using System.Collections;
using System.Drawing.Drawing2D;
using QRCoder;
using VrcxCompanion.Core;
using VrcxCompanion.Core.Net;
using VrcxCompanion.Core.Security;

namespace VrcxCompanion.UI;

/// <summary>Draws a QR module matrix crisply (dark modules on white, quiet zone included).</summary>
internal sealed class QrView : Control
{
    private List<BitArray>? _modules;

    public QrView()
    {
        SetStyle(ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer | ControlStyles.UserPaint |
                 ControlStyles.ResizeRedraw, true);
        BackColor = Theme.Background;
    }

    public void SetPayload(string? payload)
    {
        if (payload == null)
        {
            _modules = null;
        }
        else
        {
            using var generator = new QRCodeGenerator();
            using var data = generator.CreateQrCode(payload, QRCodeGenerator.ECCLevel.M);
            _modules = data.ModuleMatrix.Select(row => new BitArray(row)).ToList();
        }
        Invalidate();
    }

    protected override void OnPaint(PaintEventArgs e)
    {
        var g = e.Graphics;
        g.Clear(Parent?.BackColor ?? Theme.Background);
        g.SmoothingMode = SmoothingMode.AntiAlias;
        var side = Math.Min(Width, Height) - 1;
        var card = new Rectangle((Width - side) / 2, (Height - side) / 2, side, side);
        using (var path = Theme.RoundedRect(card, Theme.Scale(this, Theme.Radius)))
        using (var white = new SolidBrush(Color.White))
            g.FillPath(white, path);
        if (_modules == null || _modules.Count == 0)
            return;

        g.SmoothingMode = SmoothingMode.None;
        var n = _modules.Count;
        var module = Math.Max(1, side / n);
        var size = module * n;
        var x0 = card.X + (side - size) / 2;
        var y0 = card.Y + (side - size) / 2;
        using var black = new SolidBrush(Color.Black);
        for (var y = 0; y < n; y++)
        {
            var row = _modules[y];
            for (var x = 0; x < row.Length; x++)
            {
                if (row[x])
                    g.FillRectangle(black, x0 + x * module, y0 + y * module, module, module);
            }
        }
    }
}

/// <summary>Pairing window: QR code, the code, a countdown and the PC's addresses.</summary>
internal sealed class PairingForm : Form
{
    private readonly CompanionHost _host;
    private readonly QrView _qr = new();
    private readonly Label _code = new();
    private readonly Label _countdown = FormParts.Text("", muted: true);
    private readonly Label _addresses = FormParts.Text("", muted: true, Theme.Small);
    private readonly StatusDot _state = new();
    private readonly ThemedButton _newCode;
    private readonly System.Windows.Forms.Timer _timer = new() { Interval = 1000 };

    public PairingForm(CompanionHost host)
    {
        _host = host;
        Text = "Pair new device";
        Icon = FormParts.AppIcon();
        StartPosition = FormStartPosition.CenterScreen;
        FormBorderStyle = FormBorderStyle.FixedSingle;
        MaximizeBox = false;
        MinimizeBox = false;
        ClientSize = new Size(420, 690);
        Padding = new Padding(24, 20, 24, 20);
        Theme.ApplyTo(this);

        var layout = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            ColumnCount = 1,
            BackColor = Theme.Background,
        };
        layout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));

        var intro = FormParts.Text("In VRCX on your phone, open Settings, PC companion, Pair. Scan this QR code or type the code below. " +
                                   "The phone must be on the same network as this PC.", muted: true);
        intro.MaximumSize = new Size(370, 0);
        intro.TextAlign = ContentAlignment.MiddleCenter;
        intro.Anchor = AnchorStyles.None;
        intro.Margin = new Padding(0, 0, 0, 12);

        _qr.Size = new Size(280, 280);
        _qr.Anchor = AnchorStyles.None;
        _qr.Margin = new Padding(0, 4, 0, 12);

        _code.AutoSize = true;
        _code.Font = Theme.Code;
        _code.ForeColor = Theme.Text;
        _code.Anchor = AnchorStyles.None;
        _code.UseMnemonic = false;
        _code.Margin = new Padding(0, 0, 0, 2);

        _countdown.Anchor = AnchorStyles.None;
        _addresses.Anchor = AnchorStyles.None;
        _addresses.MaximumSize = new Size(370, 0);
        _addresses.TextAlign = ContentAlignment.MiddleCenter;
        _state.Size = new Size(300, 24);
        _state.Anchor = AnchorStyles.None;
        _state.Margin = new Padding(0, 12, 0, 0);

        foreach (var c in new Control[] { intro, _qr, _code, _countdown, _addresses, _state })
        {
            layout.RowCount++;
            layout.RowStyles.Add(new RowStyle(SizeType.AutoSize));
            layout.Controls.Add(c);
        }

        _newCode = FormParts.Button("New code", (_, _) => OpenWindow());
        var buttons = FormParts.ButtonRow(_newCode, FormParts.Button("Close", (_, _) => Close(), primary: true));

        Controls.Add(layout);
        Controls.Add(buttons);

        _timer.Tick += (_, _) => UpdateState(_host.Pairing.Current);
        _host.Pairing.StateChanged += OnStateChanged;
        Shown += (_, _) =>
        {
            OpenWindow();
            _timer.Start();
        };
        FormClosed += (_, _) =>
        {
            _timer.Stop();
            _timer.Dispose();
            _host.Pairing.StateChanged -= OnStateChanged;
            _host.Pairing.Cancel();
        };
    }

    private void OpenWindow()
    {
        var window = _host.OpenPairingWindow();
        var addresses = LocalAddress.GetAdvertisedAddresses();
        _qr.SetPayload(_host.BuildPairingUri(window, addresses));
        _code.Text = window.DisplayCode;
        _addresses.Text = addresses.Count == 0
            ? "No local network address found. Connect this PC to your home network."
            : "This PC: " + string.Join(", ", addresses.Select(PairingPayload.FormatHost)) + $"\nPort {_host.TcpPort}";
        // A Public network makes pairing fail without any hint on the phone (PROTOCOL.md §1): say so here.
        var ignored = _host.NetworkGate.Describe().Where(n => !n.Allowed && n.Network.IsMain).Select(n => n.Network).ToList();
        if (ignored.Count > 0)
        {
            var name = ignored[0].Name is { Length: > 0 } n ? $"\"{n}\"" : "this network";
            _addresses.Text += $"\nPhones on {name} are ignored because Windows marks it as " +
                               (ignored[0].Category == NetworkCategory.Public ? "Public" : "unidentified") +
                               ". Open Status to fix this.";
        }
        UpdateState(window);
    }

    private void OnStateChanged(PairingWindowInfo info)
    {
        if (IsHandleCreated && !IsDisposed)
            BeginInvoke(() => UpdateState(info));
    }

    private void UpdateState(PairingWindowInfo? info)
    {
        if (info == null || IsDisposed)
            return;
        var open = info.State == PairingWindowState.Open;
        var remaining = info.ExpiresAtUtc - DateTimeOffset.UtcNow;
        if (remaining < TimeSpan.Zero)
            remaining = TimeSpan.Zero;
        _countdown.Text = open ? $"Expires in {(int)remaining.TotalMinutes}:{remaining.Seconds:00}" : " ";
        _qr.Visible = open;
        _code.ForeColor = open ? Theme.Text : Theme.MutedText;
        _newCode.Visible = !open;
        switch (info.State)
        {
            case PairingWindowState.Open:
                _state.DotColor = Theme.Warning;
                _state.Text = info.FailedAttempts == 0
                    ? "Waiting for your phone..."
                    : $"Wrong code entered ({info.FailedAttempts} of 5 attempts used)";
                break;
            case PairingWindowState.Paired:
                _state.DotColor = Theme.Success;
                _state.Text = $"Paired with {info.PairedDeviceName}";
                break;
            case PairingWindowState.Expired:
                _state.DotColor = Theme.MutedText;
                _state.Text = "The code expired.";
                break;
            case PairingWindowState.TooManyAttempts:
                _state.DotColor = Theme.Danger;
                _state.Text = "Too many wrong codes. Pairing was stopped.";
                break;
            case PairingWindowState.Cancelled:
                _state.DotColor = Theme.MutedText;
                _state.Text = "Pairing was cancelled.";
                break;
        }
        _state.FitWidth();
    }
}

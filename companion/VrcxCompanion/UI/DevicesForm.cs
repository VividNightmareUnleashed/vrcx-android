using VrcxCompanion.Core;
using VrcxCompanion.Core.Security;

namespace VrcxCompanion.UI;

/// <summary>Paired devices with "Forget".</summary>
internal sealed class DevicesForm : Form
{
    private readonly CompanionHost _host;
    private readonly FlowLayoutPanel _list = new();

    public DevicesForm(CompanionHost host, Action openPairing)
    {
        _host = host;
        Text = "Paired devices";
        Icon = FormParts.AppIcon();
        StartPosition = FormStartPosition.CenterScreen;
        FormBorderStyle = FormBorderStyle.FixedSingle;
        MaximizeBox = false;
        MinimizeBox = false;
        ClientSize = new Size(520, 420);
        Padding = new Padding(20);
        Theme.ApplyTo(this);

        var heading = FormParts.Heading("Paired devices");
        heading.Dock = DockStyle.Top;
        var hint = FormParts.Text("A forgotten device is disconnected and has to pair again.", muted: true, Theme.Small);
        hint.AutoSize = false;
        hint.Height = Theme.Scale(this, 28);
        hint.Dock = DockStyle.Top;
        hint.Padding = new Padding(0, 0, 0, 12);

        _list.Dock = DockStyle.Fill;
        _list.FlowDirection = FlowDirection.TopDown;
        _list.WrapContents = false;
        _list.AutoScroll = true;
        _list.BackColor = Theme.Background;

        var buttons = FormParts.ButtonRow(
            FormParts.Button("Pair new device...", (_, _) => openPairing()),
            FormParts.Button("Close", (_, _) => Close(), primary: true));

        Controls.Add(_list);
        Controls.Add(hint);
        Controls.Add(heading);
        Controls.Add(buttons);

        _host.Devices.Changed += OnChanged;
        _host.Engine.SessionsChanged += OnChanged;
        FormClosed += (_, _) =>
        {
            _host.Devices.Changed -= OnChanged;
            _host.Engine.SessionsChanged -= OnChanged;
        };
        Shown += (_, _) => Render();
    }

    private void OnChanged()
    {
        if (IsHandleCreated && !IsDisposed)
            BeginInvoke(Render);
    }

    private void Render()
    {
        if (IsDisposed)
            return;
        var devices = _host.Devices.List();
        var connected = _host.Engine.Sessions.Select(s => s.DeviceId).ToHashSet();
        _list.SuspendLayout();
        foreach (Control c in _list.Controls)
            c.Dispose();
        _list.Controls.Clear();
        if (devices.Count == 0)
            _list.Controls.Add(FormParts.Text("No paired devices. Use \"Pair new device...\" to add a phone.", muted: true));
        var width = _list.ClientSize.Width - Theme.Scale(this, 4);
        foreach (var d in devices)
            _list.Controls.Add(Row(d, connected.Contains(d.DeviceId), width));
        _list.ResumeLayout();
    }

    private Control Row(PairedDevice device, bool isConnected, int width)
    {
        var card = new CardPanel
        {
            Width = width,
            Height = Theme.Scale(this, 64),
            Margin = new Padding(0, 0, 0, 8),
            Padding = new Padding(12, 8, 12, 8),
        };
        var name = new StatusDot
        {
            DotColor = isConnected ? Theme.Success : Theme.MutedText,
            Text = string.IsNullOrEmpty(device.DeviceName) ? "Phone" : device.DeviceName,
            Font = Theme.Label,
            Location = new Point(Theme.Scale(this, 12), Theme.Scale(this, 8)),
            Size = new Size(width - Theme.Scale(this, 130), Theme.Scale(this, 22)),
            BackColor = Theme.Background,
        };
        var details = FormParts.Text(
            $"{(isConnected ? "Connected" : "Last seen " + FormParts.LocalTime(device.LastSeenUtc))}  ·  paired {FormParts.LocalTime(device.PairedAtUtc)}",
            muted: true, Theme.Small);
        details.Location = new Point(Theme.Scale(this, 28), Theme.Scale(this, 34));
        var forget = new ThemedButton { Text = "Forget", Destructive = true };
        forget.Size = forget.GetPreferredSize(Size.Empty);
        forget.Location = new Point(width - forget.Width - Theme.Scale(this, 12), (card.Height - forget.Height) / 2);
        forget.Click += (_, _) =>
        {
            var answer = MessageBox.Show(this,
                $"Forget \"{name.Text}\"? It will be disconnected and has to be paired again.",
                "Forget device", MessageBoxButtons.OKCancel, MessageBoxIcon.Question, MessageBoxDefaultButton.Button2);
            if (answer == DialogResult.OK)
                _host.RevokeDevice(device.DeviceId);
        };
        card.Controls.Add(name);
        card.Controls.Add(details);
        card.Controls.Add(forget);
        return card;
    }
}

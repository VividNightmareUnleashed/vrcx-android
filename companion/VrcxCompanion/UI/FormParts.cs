using System.Globalization;

namespace VrcxCompanion.UI;

/// <summary>Small helpers for building themed layouts in code.</summary>
internal static class FormParts
{
    public static Label Text(string text, bool muted = false, Font? font = null) => new()
    {
        Text = text,
        AutoSize = true,
        ForeColor = muted ? Theme.MutedText : Theme.Text,
        BackColor = Color.Transparent,
        Font = font ?? Theme.Body,
        Margin = new Padding(0, 4, 0, 4),
        UseMnemonic = false,
    };

    public static Label Heading(string text) => new()
    {
        Text = text,
        AutoSize = true,
        ForeColor = Theme.Text,
        BackColor = Color.Transparent,
        Font = Theme.Heading,
        Margin = new Padding(0, 0, 0, 8),
        UseMnemonic = false,
    };

    public static ThemedButton Button(string text, EventHandler onClick, bool primary = false)
    {
        var b = new ThemedButton { Text = text, Primary = primary, Margin = new Padding(8, 0, 0, 0) };
        b.Size = b.GetPreferredSize(Size.Empty);
        b.Click += onClick;
        return b;
    }

    /// <summary>A right-aligned row of buttons.</summary>
    public static FlowLayoutPanel ButtonRow(params Control[] buttons)
    {
        var row = new FlowLayoutPanel
        {
            FlowDirection = FlowDirection.RightToLeft,
            Dock = DockStyle.Bottom,
            AutoSize = true,
            AutoSizeMode = AutoSizeMode.GrowAndShrink,
            WrapContents = false,
            BackColor = Theme.Background,
            Padding = new Padding(0, 12, 0, 0),
        };
        foreach (var b in buttons.Reverse())
            row.Controls.Add(b);
        return row;
    }

    /// <summary>Two-column key/value table.</summary>
    public static TableLayoutPanel KeyValueTable()
    {
        var t = new TableLayoutPanel
        {
            ColumnCount = 2,
            AutoSize = true,
            AutoSizeMode = AutoSizeMode.GrowAndShrink,
            Dock = DockStyle.Top,
            BackColor = Color.Transparent,
            Margin = new Padding(0),
            Padding = new Padding(0),
        };
        t.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 120));
        t.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        return t;
    }

    public static void AddRow(TableLayoutPanel table, string key, Control value)
    {
        var row = table.RowCount++;
        table.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        var k = Text(key, muted: true);
        table.Controls.Add(k, 0, row);
        value.Margin = new Padding(0, 4, 0, 4);
        value.Anchor = AnchorStyles.Left | AnchorStyles.Right;
        table.Controls.Add(value, 1, row);
    }

    public static string Bytes(long bytes)
    {
        string[] units = { "B", "KB", "MB", "GB", "TB" };
        double v = bytes;
        var i = 0;
        while (v >= 1024 && i < units.Length - 1)
        {
            v /= 1024;
            i++;
        }
        return i == 0 ? $"{bytes} B" : v.ToString(v >= 100 ? "0" : "0.0", CultureInfo.CurrentCulture) + " " + units[i];
    }

    public static string LocalTime(DateTime utc) =>
        utc.ToLocalTime().Date == DateTime.Now.Date
            ? utc.ToLocalTime().ToString("t", CultureInfo.CurrentCulture)
            : utc.ToLocalTime().ToString("g", CultureInfo.CurrentCulture);

    public static Icon? AppIcon(Size? size = null)
    {
        using var stream = typeof(FormParts).Assembly.GetManifestResourceStream("VrcxCompanion.VRCX.ico");
        if (stream == null)
            return null;
        return size is { } s ? new Icon(stream, s) : new Icon(stream);
    }
}

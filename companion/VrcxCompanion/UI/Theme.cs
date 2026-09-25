using System.Drawing.Drawing2D;
using System.Runtime.InteropServices;

namespace VrcxCompanion.UI;

/// <summary>Dark theme close to VRCX: background #171717, text #fafafa, borders rgba(255,255,255,0.1), 6 px radius.</summary>
internal static class Theme
{
    public static readonly Color Background = Color.FromArgb(0x17, 0x17, 0x17);
    public static readonly Color Surface = Color.FromArgb(0x1c, 0x1c, 0x1c);
    public static readonly Color Hover = Color.FromArgb(0x26, 0x26, 0x26);
    public static readonly Color Pressed = Color.FromArgb(0x30, 0x30, 0x30);

    /// <summary>rgba(255,255,255,0.1) over #171717.</summary>
    public static readonly Color Border = Color.FromArgb(0x2e, 0x2e, 0x2e);

    public static readonly Color Text = Color.FromArgb(0xfa, 0xfa, 0xfa);
    public static readonly Color MutedText = Color.FromArgb(0xa1, 0xa1, 0xa1);
    public static readonly Color Success = Color.FromArgb(0x22, 0xc5, 0x5e);
    public static readonly Color Warning = Color.FromArgb(0xf5, 0x9e, 0x0b);
    public static readonly Color Danger = Color.FromArgb(0xef, 0x44, 0x44);
    public const int Radius = 6;

    public static readonly Font Body = new("Segoe UI", 9f, FontStyle.Regular);
    public static readonly Font Small = new("Segoe UI", 8.25f, FontStyle.Regular);
    public static readonly Font Heading = new("Segoe UI Semibold", 11f, FontStyle.Regular);
    public static readonly Font Label = new("Segoe UI Semibold", 9f, FontStyle.Regular);
    public static readonly Font Code = new("Consolas", 24f, FontStyle.Bold);

    public static void ApplyTo(Form form)
    {
        form.BackColor = Background;
        form.ForeColor = Text;
        form.Font = Body;
        form.AutoScaleMode = AutoScaleMode.Dpi;
        form.HandleCreated += (_, _) => ApplyWindowChrome(form.Handle);
        if (form.IsHandleCreated)
            ApplyWindowChrome(form.Handle);
    }

    public static GraphicsPath RoundedRect(Rectangle r, int radius)
    {
        var path = new GraphicsPath();
        var d = radius * 2;
        if (radius <= 0 || r.Width < d || r.Height < d)
        {
            path.AddRectangle(r);
            return path;
        }
        path.AddArc(r.X, r.Y, d, d, 180, 90);
        path.AddArc(r.Right - d, r.Y, d, d, 270, 90);
        path.AddArc(r.Right - d, r.Bottom - d, d, d, 0, 90);
        path.AddArc(r.X, r.Bottom - d, d, d, 90, 90);
        path.CloseFigure();
        return path;
    }

    public static int Scale(Control c, int value) => (int)Math.Round(value * c.DeviceDpi / 96.0);

    /// <summary>Dark title bar, caption and border colours (Windows 10 20H1+ / Windows 11; ignored elsewhere).</summary>
    private static void ApplyWindowChrome(IntPtr hwnd)
    {
        try
        {
            var on = 1;
            if (DwmSetWindowAttribute(hwnd, 20, ref on, sizeof(int)) != 0)
                DwmSetWindowAttribute(hwnd, 19, ref on, sizeof(int));
            var caption = ColorRef(Background);
            DwmSetWindowAttribute(hwnd, 35, ref caption, sizeof(int));
            var border = ColorRef(Border);
            DwmSetWindowAttribute(hwnd, 34, ref border, sizeof(int));
            var text = ColorRef(Text);
            DwmSetWindowAttribute(hwnd, 36, ref text, sizeof(int));
            var round = 2; // DWMWCP_ROUND
            DwmSetWindowAttribute(hwnd, 33, ref round, sizeof(int));
        }
        catch (DllNotFoundException)
        {
        }
        catch (EntryPointNotFoundException)
        {
        }
    }

    private static int ColorRef(Color c) => c.R | (c.G << 8) | (c.B << 16);

    [DllImport("dwmapi.dll")]
    private static extern int DwmSetWindowAttribute(IntPtr hwnd, int attribute, ref int value, int size);
}

/// <summary>A panel with a 1 px rounded border.</summary>
internal sealed class CardPanel : Panel
{
    public CardPanel()
    {
        SetStyle(ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer | ControlStyles.ResizeRedraw |
                 ControlStyles.UserPaint, true);
        BackColor = Theme.Background;
        ForeColor = Theme.Text;
        Padding = new Padding(12);
    }

    public Color FillColor { get; set; } = Theme.Background;

    protected override void OnPaint(PaintEventArgs e)
    {
        e.Graphics.Clear(Parent?.BackColor ?? Theme.Background);
        e.Graphics.SmoothingMode = SmoothingMode.AntiAlias;
        var r = new Rectangle(0, 0, Width - 1, Height - 1);
        using var path = Theme.RoundedRect(r, Theme.Scale(this, Theme.Radius));
        using (var fill = new SolidBrush(FillColor))
            e.Graphics.FillPath(fill, path);
        using var pen = new Pen(Theme.Border, 1f);
        e.Graphics.DrawPath(pen, path);
    }
}

/// <summary>A flat rounded button in the VRCX style.</summary>
internal sealed class ThemedButton : Button
{
    private bool _hover;
    private bool _pressed;

    public ThemedButton()
    {
        SetStyle(ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer | ControlStyles.UserPaint |
                 ControlStyles.ResizeRedraw, true);
        FlatStyle = FlatStyle.Flat;
        FlatAppearance.BorderSize = 0;
        BackColor = Theme.Background;
        ForeColor = Theme.Text;
        Font = Theme.Body;
        Cursor = Cursors.Hand;
        AutoSize = false;
        Height = 30;
        Padding = new Padding(12, 0, 12, 0);
    }

    /// <summary>Primary buttons are filled with the text colour (like shadcn's default variant).</summary>
    public bool Primary { get; set; }

    /// <summary>Destructive buttons use red text.</summary>
    public bool Destructive { get; set; }

    public override Size GetPreferredSize(Size proposedSize)
    {
        var text = TextRenderer.MeasureText(Text, Font);
        return new Size(text.Width + Padding.Horizontal + 8, Math.Max(Theme.Scale(this, 30), text.Height + 12));
    }

    protected override void OnMouseEnter(EventArgs e)
    {
        _hover = true;
        Invalidate();
        base.OnMouseEnter(e);
    }

    protected override void OnMouseLeave(EventArgs e)
    {
        _hover = false;
        _pressed = false;
        Invalidate();
        base.OnMouseLeave(e);
    }

    protected override void OnMouseDown(MouseEventArgs mevent)
    {
        _pressed = true;
        Invalidate();
        base.OnMouseDown(mevent);
    }

    protected override void OnMouseUp(MouseEventArgs mevent)
    {
        _pressed = false;
        Invalidate();
        base.OnMouseUp(mevent);
    }

    protected override void OnPaint(PaintEventArgs e)
    {
        var g = e.Graphics;
        g.Clear(Parent?.BackColor ?? Theme.Background);
        g.SmoothingMode = SmoothingMode.AntiAlias;
        var r = new Rectangle(0, 0, Width - 1, Height - 1);
        using var path = Theme.RoundedRect(r, Theme.Scale(this, Theme.Radius));
        Color fill, text, border;
        if (!Enabled)
        {
            fill = Theme.Background;
            text = Color.FromArgb(0x5a, 0x5a, 0x5a);
            border = Theme.Border;
        }
        else if (Primary)
        {
            fill = _pressed ? Color.FromArgb(0xd4, 0xd4, 0xd4) : _hover ? Color.FromArgb(0xe5, 0xe5, 0xe5) : Theme.Text;
            text = Theme.Background;
            border = fill;
        }
        else
        {
            fill = _pressed ? Theme.Pressed : _hover ? Theme.Hover : Theme.Background;
            text = Destructive ? Theme.Danger : Theme.Text;
            border = Theme.Border;
        }
        using (var b = new SolidBrush(fill))
            g.FillPath(b, path);
        using (var p = new Pen(border))
            g.DrawPath(p, path);
        TextRenderer.DrawText(g, Text, Font, r, text, TextFormatFlags.HorizontalCenter | TextFormatFlags.VerticalCenter |
                                                     TextFormatFlags.SingleLine | TextFormatFlags.EndEllipsis);
        if (Focused && ShowFocusCues)
        {
            var inner = Rectangle.Inflate(r, -3, -3);
            using var focus = Theme.RoundedRect(inner, Math.Max(1, Theme.Scale(this, Theme.Radius) - 2));
            using var fp = new Pen(Color.FromArgb(0x73, 0x73, 0x73)) { DashStyle = DashStyle.Dot };
            g.DrawPath(fp, focus);
        }
    }
}

/// <summary>A small coloured status dot followed by text.</summary>
internal sealed class StatusDot : Control
{
    public StatusDot()
    {
        SetStyle(ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer | ControlStyles.UserPaint |
                 ControlStyles.ResizeRedraw | ControlStyles.SupportsTransparentBackColor, true);
        ForeColor = Theme.Text;
        BackColor = Theme.Background;
        Font = Theme.Body;
        Height = 20;
    }

    public Color DotColor { get; set; } = Theme.MutedText;

    /// <summary>Sets the width to fit the dot and the text.</summary>
    public void FitWidth()
    {
        Width = Theme.Scale(this, 16) + TextRenderer.MeasureText(Text, Font).Width + 4;
    }

    protected override void OnTextChanged(EventArgs e)
    {
        base.OnTextChanged(e);
        Invalidate();
    }

    protected override void OnPaint(PaintEventArgs e)
    {
        var g = e.Graphics;
        g.Clear(BackColor);
        g.SmoothingMode = SmoothingMode.AntiAlias;
        var d = Theme.Scale(this, 8);
        var y = (Height - d) / 2;
        using (var b = new SolidBrush(DotColor))
            g.FillEllipse(b, 1, y, d, d);
        var textRect = new Rectangle(d + Theme.Scale(this, 8), 0, Width - d - Theme.Scale(this, 8), Height);
        TextRenderer.DrawText(g, Text, Font, textRect, ForeColor, TextFormatFlags.VerticalCenter | TextFormatFlags.Left |
                                                                  TextFormatFlags.EndEllipsis | TextFormatFlags.SingleLine);
    }
}

/// <summary>Context menu colours.</summary>
internal sealed class DarkMenuRenderer : ToolStripProfessionalRenderer
{
    public DarkMenuRenderer() : base(new DarkColors())
    {
        RoundedEdges = false;
    }

    protected override void OnRenderItemText(ToolStripItemTextRenderEventArgs e)
    {
        e.TextColor = e.Item.Enabled ? Theme.Text : Theme.MutedText;
        base.OnRenderItemText(e);
    }

    protected override void OnRenderItemCheck(ToolStripItemImageRenderEventArgs e)
    {
        var g = e.Graphics;
        g.SmoothingMode = SmoothingMode.AntiAlias;
        var r = e.ImageRectangle;
        using var pen = new Pen(Theme.Text, 1.6f);
        var x = r.X + r.Width * 0.2f;
        var y = r.Y + r.Height * 0.5f;
        g.DrawLines(pen, new[]
        {
            new PointF(x, y),
            new PointF(r.X + r.Width * 0.42f, r.Y + r.Height * 0.72f),
            new PointF(r.X + r.Width * 0.8f, r.Y + r.Height * 0.28f),
        });
    }

    protected override void OnRenderSeparator(ToolStripSeparatorRenderEventArgs e)
    {
        var y = e.Item.Height / 2;
        using var pen = new Pen(Theme.Border);
        e.Graphics.DrawLine(pen, 8, y, e.Item.Width - 8, y);
    }

    private sealed class DarkColors : ProfessionalColorTable
    {
        public override Color ToolStripDropDownBackground => Theme.Background;
        public override Color ImageMarginGradientBegin => Theme.Background;
        public override Color ImageMarginGradientMiddle => Theme.Background;
        public override Color ImageMarginGradientEnd => Theme.Background;
        public override Color MenuBorder => Theme.Border;
        public override Color MenuItemBorder => Theme.Hover;
        public override Color MenuItemSelected => Theme.Hover;
        public override Color MenuItemSelectedGradientBegin => Theme.Hover;
        public override Color MenuItemSelectedGradientEnd => Theme.Hover;
        public override Color MenuItemPressedGradientBegin => Theme.Pressed;
        public override Color MenuItemPressedGradientEnd => Theme.Pressed;
        public override Color CheckBackground => Theme.Background;
        public override Color CheckSelectedBackground => Theme.Hover;
        public override Color CheckPressedBackground => Theme.Pressed;
        public override Color SeparatorDark => Theme.Border;
        public override Color SeparatorLight => Theme.Border;
    }
}

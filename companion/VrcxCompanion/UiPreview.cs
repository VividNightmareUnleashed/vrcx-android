using System.Drawing.Imaging;
using System.Net;
using System.Text;
using VrcxCompanion.Core;
using VrcxCompanion.Core.Client;
using VrcxCompanion.Core.Diagnostics;
using VrcxCompanion.Core.Security;
using VrcxCompanion.Core.Settings;
using VrcxCompanion.UI;

namespace VrcxCompanion;

/// <summary>
/// <c>--render-ui &lt;dir&gt;</c>: renders the status, pairing and paired-devices windows off-screen to PNG files and
/// exits (for checking the layout without running the tray app). Uses a loopback server with temporary data, like
/// <c>--selftest</c>.
/// </summary>
internal static class UiPreview
{
    public static int Run(string[] args)
    {
        var index = Array.IndexOf(args, "--render-ui");
        var outDir = index >= 0 && index + 1 < args.Length ? args[index + 1] : Environment.CurrentDirectory;
        Directory.CreateDirectory(outDir);
        ApplicationConfiguration.Initialize();

        var root = Path.Combine(Path.GetTempPath(), "vrcx-companion-preview-" + Guid.NewGuid().ToString("N")[..8]);
        var logDir = Path.Combine(root, "VRChat");
        Directory.CreateDirectory(logDir);
        File.WriteAllBytes(Path.Combine(logDir, "output_log_2000-01-01_00-00-00.txt"), Encoding.UTF8.GetBytes("preview\r\n"));
        var host = CompanionHost.Create(new CompanionHostOptions
        {
            Paths = new AppPaths(Path.Combine(root, "data")),
            LogDirectory = logDir,
            BindAddress = IPAddress.Loopback,
            TcpPort = 0,
            DiscoveryPort = 0,
            InMemoryDevices = true,
            Log = NullLog.Instance,
        });
        host.Start();
        CompanionClient? client = null;
        try
        {
            host.Devices.AddOrReplace("preview-tablet", "Galaxy Tab S9", "unused-token");
            var window = host.OpenPairingWindow();
            client = Task.Run(async () =>
            {
                var c = await CompanionClient.ConnectAsync(IPAddress.Loopback, host.TcpPort, host.Identity.Fingerprint, CancellationToken.None);
                await c.PairAsync(window.Code, "preview-phone", "Pixel 8 Pro", CancellationToken.None);
                await c.ReceiveAsync(CancellationToken.None);
                await c.SubscribeAsync(0, Array.Empty<(string, string, long)>(), CancellationToken.None);
                await c.ReceiveUntilAsync("syncComplete", CancellationToken.None);
                return c;
            }).GetAwaiter().GetResult();

            Render(new StatusForm(host, () => { }, () => { }), Path.Combine(outDir, "status.png"));
            Render(new DevicesForm(host, () => { }), Path.Combine(outDir, "devices.png"));
            Render(new PairingForm(host), Path.Combine(outDir, "pairing.png"));
            RenderMenu(Path.Combine(outDir, "menu.png"));
            return 0;
        }
        finally
        {
            client?.DisposeAsync().AsTask().Wait(2000);
            Task.Run(() => host.DisposeAsync().AsTask()).Wait(10000);
            CompanionIdentity.Delete(new AppPaths(Path.Combine(root, "data")).IdentityFile, new DpapiProtector("identity"));
            try
            {
                Directory.Delete(root, recursive: true);
            }
            catch (Exception e) when (e is IOException or UnauthorizedAccessException)
            {
            }
        }
    }

    /// <summary>Draws the tray menu without showing it (no tray icon is created).</summary>
    private static void RenderMenu(string path)
    {
        using var menu = TrayApplicationContext.BuildMenu(() => { }, () => { }, () => { }, () => { }, () => Task.CompletedTask, () => { },
            out var autostart);
        autostart.Checked = true;
        _ = menu.Handle;
        menu.PerformLayout();
        menu.Size = menu.GetPreferredSize(Size.Empty);
        menu.PerformLayout();
        using var bitmap = new Bitmap(menu.Width, menu.Height);
        menu.DrawToBitmap(bitmap, new Rectangle(Point.Empty, menu.Size));
        bitmap.Save(path, ImageFormat.Png);
    }

    private static void Render(Form form, string path)
    {
        form.StartPosition = FormStartPosition.Manual;
        form.Location = new Point(-32000, -32000);
        form.ShowInTaskbar = false;
        form.Show();
        var until = Environment.TickCount64 + 1500;
        while (Environment.TickCount64 < until)
        {
            Application.DoEvents();
            Thread.Sleep(20);
        }
        using var bitmap = new Bitmap(form.ClientSize.Width, form.ClientSize.Height);
        using (var g = Graphics.FromImage(bitmap))
            g.Clear(Theme.Background);
        foreach (Control c in form.Controls)
            c.DrawToBitmap(bitmap, c.Bounds);
        bitmap.Save(path, ImageFormat.Png);
        form.Close();
        form.Dispose();
    }
}

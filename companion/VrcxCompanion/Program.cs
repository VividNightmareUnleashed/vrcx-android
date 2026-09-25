using Microsoft.Win32;
using VrcxCompanion.Core;
using VrcxCompanion.Core.Diagnostics;
using VrcxCompanion.Core.Settings;

namespace VrcxCompanion;

internal static class Program
{
    private const string MutexName = @"Local\VRCX-Companion-2f6d1c4e-single-instance";

    [STAThread]
    private static int Main(string[] args)
    {
        if (args.Contains("--selftest", StringComparer.OrdinalIgnoreCase))
            return SelfTest.Run(args);
        if (args.Contains("--render-ui", StringComparer.OrdinalIgnoreCase))
            return UiPreview.Run(args);

        using var mutex = new Mutex(initiallyOwned: true, MutexName, out var createdNew);
        if (!createdNew)
        {
            MessageBox.Show("VRCX Companion is already running. Its icon is in the notification area of the taskbar.",
                "VRCX Companion", MessageBoxButtons.OK, MessageBoxIcon.Information);
            return 0;
        }

        ApplicationConfiguration.Initialize();
        var paths = AppPaths.Default;
        using var log = new FileLog(paths.LogFile);
        log.Info($"VRCX Companion {CompanionHostOptions.DefaultVersion()} starting");
        Application.ThreadException += (_, e) => log.Error("unhandled UI exception", e.Exception);
        AppDomain.CurrentDomain.UnhandledException += (_, e) => log.Error("unhandled exception", e.ExceptionObject as Exception);
        TaskScheduler.UnobservedTaskException += (_, e) =>
        {
            log.Warn("unobserved task exception", e.Exception);
            e.SetObserved();
        };

        var settings = CompanionSettings.LoadOrCreate(paths.SettingsFile, log);
        CompanionHost host;
        try
        {
            host = CompanionHost.Create(new CompanionHostOptions
            {
                Paths = paths,
                TcpPort = settings.TcpPort,
                DiscoveryPort = settings.DiscoveryPort,
                Log = log,
            });
        }
        catch (Exception e)
        {
            log.Error("startup failed", e);
            MessageBox.Show("VRCX Companion could not start: " + e.Message, "VRCX Companion", MessageBoxButtons.OK, MessageBoxIcon.Error);
            return 1;
        }

        host.Start();
        void OnTimeChanged(object? sender, EventArgs e) => host.NotifyTimeChanged();
        SystemEvents.TimeChanged += OnTimeChanged;
        try
        {
            using var context = new TrayApplicationContext(host, log);
            Application.Run(context);
        }
        finally
        {
            SystemEvents.TimeChanged -= OnTimeChanged;
            Task.Run(() => host.DisposeAsync().AsTask()).Wait(TimeSpan.FromSeconds(10));
            log.Info("exit");
        }
        return 0;
    }
}

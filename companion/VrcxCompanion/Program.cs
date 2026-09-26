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

        var openPairing = args.Contains("--pair", StringComparer.OrdinalIgnoreCase);
        using var mutex = new Mutex(initiallyOwned: true, MutexName, out var createdNew);
        if (!createdNew)
        {
            // Already running: ask that instance to show a window instead of starting a second one.
            if (!SignalRunningInstance(openPairing ? PairingEventName : StatusEventName))
            {
                MessageBox.Show("VRCX Companion is already running. Its icon is in the notification area of the taskbar.",
                    "VRCX Companion", MessageBoxButtons.OK, MessageBoxIcon.Information);
            }
            return 0;
        }
        using var statusSignal = new EventWaitHandle(false, EventResetMode.AutoReset, StatusEventName);
        using var pairingSignal = new EventWaitHandle(false, EventResetMode.AutoReset, PairingEventName);

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
                AllowedPublicNetworks = settings.AllowedPublicNetworkIds(),
                Log = log,
            });
            // "Allow on this network" in the Status window is remembered in settings.json.
            host.NetworkGate.AllowedNetworksChanged += ids => settings.SetAllowedPublicNetworks(ids, paths.SettingsFile, log);
        }
        catch (Exception e)
        {
            log.Error("startup failed", e);
            MessageBox.Show("VRCX Companion could not start: " + e.Message, "VRCX Companion", MessageBoxButtons.OK, MessageBoxIcon.Error);
            return 1;
        }

        host.Start();
        TrayApplicationContext? context = null;
        void OnTimeChanged(object? sender, EventArgs e) => host.NotifyTimeChanged();
        void OnSessionEnded(object? sender, SessionEndedEventArgs e)
        {
            // Logoff or shutdown: Windows may end the process as soon as this handler returns, so the host is stopped
            // here (sessions closed, listeners released) before the message loop is asked to exit.
            log.Info($"Windows session ending ({e.Reason})");
            Stop(host, TimeSpan.FromSeconds(4));
            context?.RequestExit();
        }
        SystemEvents.TimeChanged += OnTimeChanged;
        SystemEvents.SessionEnded += OnSessionEnded;
        try
        {
            using var tray = new TrayApplicationContext(host, log);
            context = tray;
            using var stopSignals = new ManualResetEvent(false);
            var signals = new Thread(() =>
            {
                WaitHandle[] handles = [stopSignals, statusSignal, pairingSignal];
                while (true)
                {
                    var index = WaitHandle.WaitAny(handles);
                    if (index == 0)
                        return;
                    if (index == 1)
                        tray.RequestShowStatus();
                    else
                        tray.RequestShowPairing();
                }
            }) { IsBackground = true, Name = "instance-signals" };
            signals.Start();
            if (openPairing)
                tray.RequestShowPairing();
            try
            {
                Application.Run(tray);
            }
            finally
            {
                stopSignals.Set();
                signals.Join(TimeSpan.FromSeconds(2));
            }
        }
        finally
        {
            SystemEvents.SessionEnded -= OnSessionEnded;
            SystemEvents.TimeChanged -= OnTimeChanged;
            Stop(host, TimeSpan.FromSeconds(10));
            log.Info("exit");
        }
        return 0;
    }

    private const string StatusEventName = @"Local\VRCX-Companion-2f6d1c4e-show-status";
    private const string PairingEventName = @"Local\VRCX-Companion-2f6d1c4e-show-pairing";

    /// <summary>Signals the running instance to open a window. False when it is not listening (for example still starting).</summary>
    private static bool SignalRunningInstance(string eventName)
    {
        try
        {
            using var handle = EventWaitHandle.OpenExisting(eventName);
            return handle.Set();
        }
        catch (Exception e) when (e is WaitHandleCannotBeOpenedException or UnauthorizedAccessException or IOException)
        {
            return false;
        }
    }

    /// <summary>Disposes the host off the UI thread, waiting at most <paramref name="timeout"/>. Later calls return at once.</summary>
    private static void Stop(CompanionHost host, TimeSpan timeout) =>
        Task.Run(() => host.DisposeAsync().AsTask()).Wait(timeout);
}

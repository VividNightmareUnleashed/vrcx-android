using System.Net;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;
using VrcxCompanion.Core;
using VrcxCompanion.Core.Client;
using VrcxCompanion.Core.Diagnostics;
using VrcxCompanion.Core.Security;
using VrcxCompanion.Core.Settings;

namespace VrcxCompanion;

/// <summary>
/// <c>--selftest [--out file]</c>: starts the server headless on loopback with ephemeral ports and a temporary data
/// and log directory, runs a protocol client against it (discovery, pairing, subscribe, live data, idle mode, reconnect
/// with auth and have offsets) and exits with 0 on success. It also checks that Windows' network categories can be
/// read. It never touches the real VRChat folder, %APPDATA%, the registry, the firewall or network settings.
/// </summary>
internal static class SelfTest
{
    private const string Marker = "SELFTEST-SECRET-LINE-CONTENT";

    public static int Run(string[] args)
    {
        AttachConsole(AttachParentProcess);
        var outIndex = Array.IndexOf(args, "--out");
        var outPath = outIndex >= 0 && outIndex + 1 < args.Length ? args[outIndex + 1] : null;
        var report = new StringBuilder();
        void Line(string s)
        {
            report.AppendLine(s);
            try
            {
                Console.WriteLine(s);
            }
            catch (IOException)
            {
            }
        }

        int result;
        try
        {
            result = Task.Run(() => RunAsync(Line)).GetAwaiter().GetResult();
        }
        catch (Exception e)
        {
            Line("FAIL  " + e.GetType().Name + ": " + e.Message);
            result = 1;
        }
        Line(result == 0 ? "SELFTEST PASSED" : "SELFTEST FAILED");
        if (outPath != null)
            File.WriteAllText(outPath, report.ToString());
        return result;
    }

    private static async Task<int> RunAsync(Action<string> line)
    {
        var failures = 0;
        void Check(bool ok, string what)
        {
            line((ok ? "ok    " : "FAIL  ") + what);
            if (!ok)
                failures++;
        }

        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(60));
        var ct = timeout.Token;
        var root = Path.Combine(Path.GetTempPath(), "vrcx-companion-selftest-" + Guid.NewGuid().ToString("N")[..8]);
        var dataDir = Path.Combine(root, "data");
        var logDir = Path.Combine(root, "VRChat");
        Directory.CreateDirectory(logDir);
        var log = new MemoryLog();
        try
        {
            var secret = RandomNumberGenerator.GetBytes(32);
            var dpapi = new DpapiProtector("selftest");
            Check(dpapi.Unprotect(dpapi.Protect(secret)).AsSpan().SequenceEqual(secret), "DPAPI round trip");

            var logName = "output_log_2000-01-01_00-00-00.txt";
            var logPath = Path.Combine(logDir, logName);
            var first = Encoding.UTF8.GetBytes($"2000.01.01 00:00:00 Log        -  {Marker} first\r\npartial");
            await File.WriteAllBytesAsync(logPath, first, ct);

            await using var host = CompanionHost.Create(new CompanionHostOptions
            {
                Paths = new AppPaths(dataDir),
                LogDirectory = logDir,
                BindAddress = IPAddress.Loopback,
                TcpPort = 0,
                DiscoveryPort = 0,
                Log = log,
                PollInterval = TimeSpan.FromMilliseconds(250),
            });
            host.Start();
            Check(host.Listening, $"server listening on 127.0.0.1:{host.TcpPort}");
            Check(host.DiscoveryActive, $"discovery on 127.0.0.1:{host.DiscoveryPort}");
            // Read only; the categories themselves are whatever Windows Settings say.
            var networks = host.NetworkGate.Describe();
            Check(host.NetworkGate.CategoriesAvailable,
                $"Windows network categories readable ({networks.Count} network(s), {networks.Count(n => n.Allowed)} accepting phones)");

            var found = await CompanionClient.DiscoverAsync(new IPEndPoint(IPAddress.Loopback, host.DiscoveryPort), TimeSpan.FromSeconds(3), ct);
            Check(found.Count == 1 && found[0].Fingerprint == host.Identity.Fingerprint && found[0].Port == host.TcpPort,
                "discovery reply");

            var window = host.OpenPairingWindow();
            string token;
            string fileId;
            await using (var c1 = await CompanionClient.ConnectAsync(IPAddress.Loopback, host.TcpPort, host.Identity.Fingerprint, ct))
            {
                Check(c1.Hello.GetProperty("pairing").GetBoolean(), $"TLS ({c1.NegotiatedProtocol}) with pinned fingerprint, hello");
                var (ok, t, proofValid, reason) = await c1.PairAsync(window.DisplayCode, "selftest-device", "Self test", ct);
                Check(ok && proofValid, "pairing with the code" + (reason != null ? " (" + reason + ")" : ""));
                token = t ?? "";
                var info = await c1.ReceiveAsync(ct);
                Check(info?.Type == "info" && info.Json.GetProperty("dirExists").GetBoolean(), "info");

                await c1.SubscribeAsync(0, Array.Empty<(string, string, long)>(), ct);
                var sync = await c1.ReceiveUntilAsync("syncComplete", ct);
                Check(string.Join(",", sync.Select(m => m.Type)) == "snapshot,process,data,syncComplete", "subscribe sequence");
                var data = sync.FirstOrDefault(m => m.Type == "data");
                Check(data?.Data != null && data.Data.AsSpan().SequenceEqual(first), "initial bytes, partial line untouched");
                fileId = data?.Header?.FileId ?? "";

                var more = Encoding.UTF8.GetBytes(" line completed\r\n");
                await using (var fs = new FileStream(logPath, FileMode.Append, FileAccess.Write, FileShare.ReadWrite | FileShare.Delete))
                    await fs.WriteAsync(more, ct);
                var live = await c1.ReceiveUntilAsync("data", ct);
                var liveData = live.Last();
                Check(liveData.Header?.Offset == first.Length && liveData.Data != null && liveData.Data.AsSpan().SequenceEqual(more),
                    "live append");

                await c1.SendIdleAsync(true, ct);
                var idle = (await c1.ReceiveUntilAsync("idle", ct)).Last();
                Check(idle.Json.GetProperty("on").GetBoolean() && host.Engine.Sessions.Any(s => s.Idle), "idle mode confirmed");
                await c1.SendIdleAsync(false, ct);
                var active = (await c1.ReceiveUntilAsync("idle", ct)).Last();
                Check(!active.Json.GetProperty("on").GetBoolean(), "back to the foreground confirmed");
            }

            var length = new FileInfo(logPath).Length;
            await using (var c2 = await CompanionClient.ConnectAsync(IPAddress.Loopback, host.TcpPort, host.Identity.Fingerprint, ct))
            {
                Check(await c2.AuthAsync("selftest-device", token, ct), "reconnect with token");
                await c2.ReceiveAsync(ct); // info
                await c2.SubscribeAsync(0, new[] { (logName, fileId, length) }, ct);
                var sync = await c2.ReceiveUntilAsync("syncComplete", ct);
                Check(sync.All(m => m.Type != "data"), "have offsets: nothing resent");
            }

            Check(host.Devices.List().Count == 1 && File.Exists(Path.Combine(dataDir, "devices.bin")), "device stored (DPAPI)");
            Check(!File.ReadAllText(Path.Combine(dataDir, "devices.bin"), Encoding.Latin1).Contains(token), "token not stored");
            Check(log.Entries.All(e => !e.Contains(Marker) && !e.Contains(token)), "log contains no line contents or tokens");
        }
        finally
        {
            // The identity's key lives in the user's key store, not under root.
            CompanionIdentity.Delete(new AppPaths(dataDir).IdentityFile, new DpapiProtector("identity"));
            try
            {
                Directory.Delete(root, recursive: true);
            }
            catch (Exception e) when (e is IOException or UnauthorizedAccessException)
            {
            }
        }
        return failures == 0 ? 0 : 1;
    }

    private const uint AttachParentProcess = 0xFFFFFFFF;

    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool AttachConsole(uint processId);
}

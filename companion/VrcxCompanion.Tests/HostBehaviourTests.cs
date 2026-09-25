using System.Diagnostics;
using System.Net;
using VrcxCompanion.Core;
using VrcxCompanion.Core.Client;
using VrcxCompanion.Core.Protocol;
using VrcxCompanion.Core.Security;
using VrcxCompanion.Core.Settings;

namespace VrcxCompanion.Tests;

/// <summary>
/// Host behaviour on loopback that the main end-to-end test does not cover: <c>info.dirExists</c> across idle
/// periods (§5.4) and the 20 s receive timeout (§5.9).
/// </summary>
public sealed class HostBehaviourTests : IAsyncLifetime
{
    private const string LogName = "output_log_2024-01-01_09-59-00.txt";

    private readonly TempDir _data = new();
    private readonly TempDir _root = new();
    private readonly FakeProcessProbe _probe = new();
    private readonly CompanionIdentity _identity = CompanionIdentity.CreateEphemeral("TESTPC");
    private readonly CancellationTokenSource _timeout = new(TimeSpan.FromSeconds(60));
    private readonly List<CompanionHost> _hosts = new();

    /// <summary>The VRChat log directory; it does not exist until a test creates it.</summary>
    private string LogDir => Path.Combine(_root.Path, "VRChat");

    private CancellationToken Ct => _timeout.Token;

    public Task InitializeAsync() => Task.CompletedTask;

    public async Task DisposeAsync()
    {
        foreach (var host in _hosts)
            await host.DisposeAsync();
        _identity.Dispose();
        _data.Dispose();
        _root.Dispose();
    }

    private CompanionHost StartHost(TimeSpan? receiveTimeout = null, TimeSpan? heartbeatInterval = null)
    {
        var host = CompanionHost.Create(new CompanionHostOptions
        {
            Paths = new AppPaths(_data.Path),
            LogDirectory = LogDir,
            BindAddress = IPAddress.Loopback,
            TcpPort = 0,
            EnableDiscovery = false,
            MachineName = "TESTPC",
            Protector = new PlainProtector(),
            ProcessProbe = _probe,
            Identity = _identity,
            InMemoryDevices = true,
            UseFileSystemWatcher = false,
            PollInterval = TimeSpan.FromMilliseconds(100),
            HeartbeatInterval = heartbeatInterval ?? TimeSpan.FromMilliseconds(500),
            ReceiveTimeout = receiveTimeout ?? ProtocolConstants.ReceiveTimeout,
        });
        _hosts.Add(host);
        host.Start();
        return host;
    }

    private async Task<CompanionClient> ConnectAndAuthAsync(CompanionHost host, string deviceId, TimeSpan? heartbeatReplyAfter = null)
    {
        host.Devices.AddOrReplace(deviceId, "Phone " + deviceId, "token-" + deviceId);
        var client = await CompanionClient.ConnectAsync(IPAddress.Loopback, host.TcpPort, _identity.Fingerprint, Ct);
        if (heartbeatReplyAfter != null)
            client.HeartbeatReplyAfter = heartbeatReplyAfter == Timeout.InfiniteTimeSpan ? null : heartbeatReplyAfter;
        Assert.True(await client.AuthAsync(deviceId, "token-" + deviceId, Ct));
        return client;
    }

    private void CreateLogDirectory()
    {
        Directory.CreateDirectory(LogDir);
        File.WriteAllBytes(Path.Combine(LogDir, LogName), LogFiles.Line("2024.01.01 09:59:00 Log        -  x"));
    }

    private static async Task WaitUntilAsync(Func<bool> condition, string what)
    {
        var deadline = Environment.TickCount64 + 10000;
        while (!condition())
        {
            Assert.True(Environment.TickCount64 < deadline, "timed out waiting for " + what);
            await Task.Delay(20);
        }
    }

    private static bool DirExists(ReceivedMessage info)
    {
        Assert.Equal("info", info.Type);
        return info.Json.GetProperty("dirExists").GetBoolean();
    }

    [Fact]
    public async Task InfoAfterAnIdlePeriodReportsTheDirectoryAsItIsNow()
    {
        var host = StartHost();
        await using (var first = await ConnectAndAuthAsync(host, "dev-1"))
        {
            Assert.False(DirExists((await first.ReceiveAsync(Ct))!));
            await first.SubscribeAsync(0, Array.Empty<(string, string, long)>(), Ct);
            var sync = await first.ReceiveUntilAsync("syncComplete", Ct);
            Assert.Equal(0, sync[0].Json.GetProperty("files").GetArrayLength());
            Assert.False(host.Engine.LastDirectoryExists);
        }

        // Nobody is subscribed: the engine goes idle (a few poll intervals) and stops looking at the directory, which
        // then appears.
        await WaitUntilAsync(() => host.Engine.Sessions.Count == 0, "the session to end");
        await Task.Delay(400);
        CreateLogDirectory();

        await using var second = await ConnectAndAuthAsync(host, "dev-1");
        Assert.True(DirExists((await second.ReceiveAsync(Ct))!));
        Assert.Null(host.Engine.LastDirectoryExists); // idle: the engine forgot what it saw

        // The engine's first look after the idle period agrees with that info: no second info follows.
        await second.SubscribeAsync(0, Array.Empty<(string, string, long)>(), Ct);
        var messages = await second.ReceiveUntilAsync("syncComplete", Ct);
        Assert.Equal(new[] { "snapshot", "process", "data", "syncComplete" }, messages.Select(m => m.Type));
        await WaitUntilAsync(() => host.Engine.LastDirectoryExists == true, "the first observation");
        var later = await second.ReceiveUntilAsync("heartbeat", Ct, keepHeartbeats: true);
        later.AddRange(await second.ReceiveUntilAsync("heartbeat", Ct, keepHeartbeats: true));
        Assert.DoesNotContain(later, m => m.Type == "info");
    }

    [Fact]
    public async Task UnsubscribedPhoneHearsAboutADirectoryThatAppearedWhileNothingWasPolled()
    {
        var host = StartHost();
        await using var waiting = await ConnectAndAuthAsync(host, "dev-waiting");
        Assert.False(DirExists((await waiting.ReceiveAsync(Ct))!));

        // Nobody is subscribed, so nothing polls the directory when it appears.
        CreateLogDirectory();
        await Task.Delay(300);
        Assert.Null(host.Engine.LastDirectoryExists);

        await using var active = await ConnectAndAuthAsync(host, "dev-active");
        Assert.True(DirExists((await active.ReceiveAsync(Ct))!));
        await active.SubscribeAsync(0, Array.Empty<(string, string, long)>(), Ct);
        await active.ReceiveUntilAsync("syncComplete", Ct);

        // The engine's first look finds the directory: the phone that was told otherwise gets a new info.
        var info = (await waiting.ReceiveUntilAsync("info", Ct)).Last();
        Assert.True(DirExists(info));
    }

    [Fact]
    public void ReceiveTimeoutIsTheProtocolsTwentySeconds()
    {
        Assert.Equal(TimeSpan.FromSeconds(20), ProtocolConstants.ReceiveTimeout);
        Assert.Equal(TimeSpan.FromSeconds(20), ProtocolConstants.HandshakeTimeout);
        var options = new CompanionHostOptions { Paths = new AppPaths(_data.Path) };
        Assert.Equal(ProtocolConstants.ReceiveTimeout, options.ReceiveTimeout);
        Assert.Equal(ProtocolConstants.HandshakeTimeout, options.HandshakeTimeout);
    }

    [Fact]
    public async Task SilentPhoneIsDroppedAfterTheReceiveTimeoutWhileAnAnsweringOneStays()
    {
        CreateLogDirectory();
        var timeout = TimeSpan.FromMilliseconds(1500);
        var host = StartHost(receiveTimeout: timeout, heartbeatInterval: TimeSpan.FromMilliseconds(200));
        await using var silent = await ConnectAndAuthAsync(host, "silent", Timeout.InfiniteTimeSpan);
        var clock = Stopwatch.StartNew(); // the silent phone's last frame (auth) was just sent
        await using var answering = await ConnectAndAuthAsync(host, "answering", TimeSpan.FromMilliseconds(500));
        await WaitUntilAsync(() => host.Engine.Sessions.Count == 2, "both sessions");

        var silentClosedAfter = Task.Run(async () =>
        {
            try
            {
                while (await silent.ReceiveAsync(Ct) != null)
                {
                }
            }
            catch (IOException)
            {
            }
            return clock.Elapsed;
        });
        var answeringStayed = Task.Run(async () =>
        {
            var heartbeats = 0;
            while (clock.Elapsed < TimeSpan.FromSeconds(4))
            {
                var m = await answering.ReceiveAsync(Ct);
                if (m == null)
                    return -1;
                if (m.Type == "heartbeat")
                    heartbeats++;
            }
            return heartbeats;
        });

        var closedAfter = await silentClosedAfter;
        Assert.InRange(closedAfter, timeout - TimeSpan.FromMilliseconds(300), timeout + TimeSpan.FromSeconds(3));
        Assert.True(await answeringStayed > 5, "the answering phone was disconnected");
        var session = Assert.Single(host.Engine.Sessions);
        Assert.Equal("answering", session.DeviceId);
    }

    [Fact]
    public async Task DisposeCanBeCalledTwice()
    {
        var host = StartHost();
        await host.DisposeAsync();
        await host.DisposeAsync();
        Assert.False(host.Pairing.IsOpen);
    }
}

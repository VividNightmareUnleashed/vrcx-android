using System.Net;
using System.Security.Authentication;
using VrcxCompanion.Core;
using VrcxCompanion.Core.Client;
using VrcxCompanion.Core.Diagnostics;
using VrcxCompanion.Core.Security;
using VrcxCompanion.Core.Settings;

namespace VrcxCompanion.Tests;

/// <summary>
/// The whole server on loopback with an SslStream client: discovery, pairing, info, subscribe, live data, reconnect
/// with auth and have offsets, one session per device, revocation.
/// </summary>
public sealed class EndToEndTests : IAsyncLifetime
{
    private const string LogName = "output_log_2024-01-01_09-59-00.txt";

    private readonly TempDir _data = new();
    private readonly TempDir _logs = new();
    private readonly FakeProcessProbe _probe = new();
    private readonly MemoryLog _log = new();
    private readonly CompanionIdentity _identity = CompanionIdentity.CreateEphemeral("TESTPC");
    private CompanionHost _host = null!;
    private readonly CancellationTokenSource _timeout = new(TimeSpan.FromSeconds(60));

    public Task InitializeAsync()
    {
        _host = CompanionHost.Create(new CompanionHostOptions
        {
            Paths = new AppPaths(_data.Path),
            LogDirectory = _logs.Path,
            BindAddress = IPAddress.Loopback,
            TcpPort = 0,
            DiscoveryPort = 0,
            MachineName = "TESTPC",
            Protector = new PlainProtector(),
            ProcessProbe = _probe,
            Identity = _identity,
            Log = _log,
            UseFileSystemWatcher = false,
            PollInterval = TimeSpan.FromMilliseconds(100),
            HeartbeatInterval = TimeSpan.FromMilliseconds(500),
        });
        _host.Start();
        return Task.CompletedTask;
    }

    public async Task DisposeAsync()
    {
        await _host.DisposeAsync();
        _identity.Dispose();
        _data.Dispose();
        _logs.Dispose();
    }

    private CancellationToken Ct => _timeout.Token;

    private Task<CompanionClient> ConnectAsync(string? fingerprint = null) =>
        CompanionClient.ConnectAsync(IPAddress.Loopback, _host.TcpPort, fingerprint ?? _identity.Fingerprint, Ct);

    [Fact]
    public async Task PairReconnectSubscribeAndStream()
    {
        Assert.True(_host.Listening, _host.ListenError);
        var logPath = _logs.File(LogName);
        var first = LogFiles.Line("2024.01.01 09:59:00 Log        -  [Behaviour] Entering Room: Home");
        File.WriteAllBytes(logPath, first);
        _probe.Vrchat = true;

        // Discovery.
        var found = Assert.Single(await CompanionClient.DiscoverAsync(new IPEndPoint(IPAddress.Loopback, _host.DiscoveryPort),
            TimeSpan.FromSeconds(3), Ct));
        Assert.Equal(_identity.CompanionId, found.Id);
        Assert.Equal("TESTPC", found.Name);
        Assert.Equal(_host.TcpPort, found.Port);
        Assert.Equal(_identity.Fingerprint, found.Fingerprint);
        Assert.False(found.Pairing);

        // Pair.
        var window = _host.OpenPairingWindow();
        Assert.True((await CompanionClient.DiscoverAsync(new IPEndPoint(IPAddress.Loopback, _host.DiscoveryPort), TimeSpan.FromSeconds(3), Ct))
            .Single().Pairing);
        string token;
        string fileId;
        await using (var c1 = await ConnectAsync())
        {
            Assert.Equal(1, c1.Hello.GetProperty("v").GetInt32());
            Assert.Equal(_identity.CompanionId, c1.Hello.GetProperty("id").GetString());
            Assert.True(c1.Hello.GetProperty("pairing").GetBoolean());
            Assert.Equal(43, c1.HelloNonce.Length);

            // The user types the code in lower case with the dash.
            var (ok, t, proofValid, _) = await c1.PairAsync(window.DisplayCode.ToLowerInvariant(), "dev-1", "Pixel 8", Ct);
            Assert.True(ok);
            Assert.True(proofValid);
            token = t!;

            var info = await c1.ReceiveAsync(Ct);
            Assert.Equal("info", info!.Type);
            Assert.Equal("TESTPC", info.Json.GetProperty("machineName").GetString());
            Assert.Equal(_logs.Path, info.Json.GetProperty("logDir").GetString());
            Assert.True(info.Json.GetProperty("dirExists").GetBoolean());
            Assert.Equal(TimeZoneInfo.Local.Id, info.Json.GetProperty("tz").GetProperty("windowsId").GetString());
            Assert.True(Math.Abs(info.Json.GetProperty("pcUtcNowMs").GetInt64() - DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()) < 60000);

            await c1.SubscribeAsync(0, Array.Empty<(string, string, long)>(), Ct);
            var sync = await c1.ReceiveUntilAsync("syncComplete", Ct);
            Assert.Equal(new[] { "snapshot", "process", "data", "syncComplete" }, sync.Select(m => m.Type));
            Assert.True(sync[1].Json.GetProperty("vrchatRunning").GetBoolean());
            Assert.Equal(first, sync[2].Data);
            fileId = sync[2].Header!.FileId;

            // Live data and a heartbeat.
            var second = LogFiles.Line("2024.01.01 10:00:00 Log        -  [Behaviour] OnPlayerJoined Someone");
            LogFiles.Append(logPath, second);
            var live = await c1.ReceiveUntilAsync("data", Ct);
            Assert.Equal(first.Length, live.Last().Header!.Offset);
            Assert.Equal(second, live.Last().Data);
            var hb = await c1.ReceiveUntilAsync("heartbeat", Ct, keepHeartbeats: true);
            Assert.True(hb.Last().Json.GetProperty("pcUtcNowMs").GetInt64() > 0);
        }
        Assert.False(_host.Pairing.IsOpen);

        var device = Assert.Single(_host.Devices.List());
        Assert.Equal("Pixel 8", device.DeviceName);

        // Written while the phone was away, then VRChat exits.
        var third = LogFiles.Line("2024.01.01 10:01:00 Log        -  VRCApplication: HandleApplicationQuit");
        LogFiles.Append(logPath, third);
        _probe.Vrchat = false;
        var haveLength = new FileInfo(logPath).Length - third.Length;

        // Reconnect with auth and have offsets: only the missing bytes, then the process state.
        await using (var c2 = await ConnectAsync())
        {
            Assert.False(c2.Hello.GetProperty("pairing").GetBoolean());
            Assert.True(await c2.AuthAsync("dev-1", token, Ct));
            Assert.Equal("info", (await c2.ReceiveAsync(Ct))!.Type);
            await c2.SubscribeAsync(DateTime.UtcNow.AddDays(-1).Ticks, new[] { (LogName, fileId, haveLength) }, Ct);
            var sync = await c2.ReceiveUntilAsync("syncComplete", Ct);
            Assert.Equal(new[] { "snapshot", "process", "data", "syncComplete" }, sync.Select(m => m.Type));
            Assert.False(sync[1].Json.GetProperty("vrchatRunning").GetBoolean());
            Assert.Equal(haveLength, sync[2].Header!.Offset);
            Assert.Equal(third, sync[2].Data);

            // A second connection of the same device replaces this one.
            await using var c3 = await ConnectAsync();
            Assert.True(await c3.AuthAsync("dev-1", token, Ct));
            await AssertClosedAsync(c2);

            // Revoked on the PC: the session closes and the next auth fails.
            _host.RevokeDevice("dev-1");
            await AssertClosedAsync(c3);
        }

        await using (var c4 = await ConnectAsync())
            Assert.False(await c4.AuthAsync("dev-1", token, Ct));
        Assert.Empty(_host.Devices.List());
        Assert.DoesNotContain(_log.Entries, e => e.Contains("Entering Room") || e.Contains("OnPlayerJoined") || e.Contains(token));
    }

    [Fact]
    public async Task WrongCodeAndClosedWindowAreRefused()
    {
        await using (var c = await ConnectAsync())
        {
            var (ok, _, _, reason) = await c.PairAsync("ABCDE-12345", "dev-x", "X", Ct);
            Assert.False(ok);
            Assert.Equal("closed", reason);
        }

        var window = _host.OpenPairingWindow();
        var wrong = window.Code == "0000000000" ? "1111111111" : "0000000000";
        await using (var c = await ConnectAsync())
        {
            var (ok, _, _, reason) = await c.PairAsync(wrong, "dev-x", "X", Ct);
            Assert.False(ok);
            Assert.Equal("code", reason);
            await AssertClosedAsync(c);
        }
        Assert.Equal(1, _host.Pairing.Current!.FailedAttempts);
    }

    [Fact]
    public async Task ClientRejectsAnotherCertificate()
    {
        await Assert.ThrowsAnyAsync<AuthenticationException>(() => ConnectAsync("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"));
    }

    [Fact]
    public async Task UnknownTokenGetsAuthFail()
    {
        await using var c = await ConnectAsync();
        Assert.False(await c.AuthAsync("nobody", "token", Ct));
        await AssertClosedAsync(c);
    }

    [Fact]
    public async Task SubscribeBeforeAuthClosesTheConnection()
    {
        await using var c = await ConnectAsync();
        await c.SubscribeAsync(0, Array.Empty<(string, string, long)>(), Ct);
        await AssertClosedAsync(c);
    }

    [Fact]
    public async Task StatusReportsSessionsAndBytes()
    {
        File.WriteAllBytes(_logs.File(LogName), LogFiles.Line("x"));
        var window = _host.OpenPairingWindow();
        await using var c = await ConnectAsync();
        var (ok, _, _, _) = await c.PairAsync(window.Code, "dev-s", "Tablet", Ct);
        Assert.True(ok);
        await c.ReceiveAsync(Ct); // info
        await c.SubscribeAsync(0, Array.Empty<(string, string, long)>(), Ct);
        await c.ReceiveUntilAsync("syncComplete", Ct);

        var status = _host.GetStatus();
        var s = Assert.Single(status.Sessions);
        Assert.Equal("Tablet", s.DeviceName);
        Assert.True(s.Subscribed);
        Assert.True(status.TotalBytesSent > 0);
        Assert.Equal(_logs.Path, status.LogDirectory);
        Assert.True(status.LogDirectoryExists);
        Assert.NotNull(status.Process);
    }

    private async Task AssertClosedAsync(CompanionClient client)
    {
        try
        {
            while (true)
            {
                var m = await client.ReceiveAsync(Ct);
                if (m == null)
                    return;
                Assert.True(m.Type is "heartbeat" or "info" or "authFail" or "pairFail" or "snapshot" or "process" or "syncComplete",
                    "unexpected " + m.Type);
            }
        }
        catch (IOException)
        {
        }
    }
}

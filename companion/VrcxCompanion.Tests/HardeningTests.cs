using System.Collections.Concurrent;
using System.Diagnostics;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;
using VrcxCompanion.Core;
using VrcxCompanion.Core.Client;
using VrcxCompanion.Core.Diagnostics;
using VrcxCompanion.Core.Logs;
using VrcxCompanion.Core.Net;
using VrcxCompanion.Core.Security;
using VrcxCompanion.Core.Settings;
using VrcxCompanion.Core.Sync;

namespace VrcxCompanion.Tests;

/// <summary>A category source for tests: every lookup is answered by <see cref="Resolve"/>.</summary>
internal sealed class FakeNetworkSource : INetworkCategorySource
{
    public static readonly NetworkProfile Home = new(Guid.Parse("11111111-2222-3333-4444-555555555555"), "HomeWiFi", NetworkCategory.Public);
    public static readonly NetworkProfile Office = new(Guid.Parse("66666666-2222-3333-4444-555555555555"), "Office", NetworkCategory.Domain);

    public volatile bool IsAvailable = true;
    public Func<IPAddress?, int, NetworkProfile?> Resolve = (_, _) => Home;
    public readonly ConcurrentQueue<(IPAddress? Address, int Index)> Lookups = new();

    public bool Available => IsAvailable;

    public NetworkProfile? Find(IPAddress? localAddress, int interfaceIndex = -1)
    {
        Lookups.Enqueue((localAddress, interfaceIndex));
        return Resolve(localAddress, interfaceIndex);
    }

    public IReadOnlyList<NetworkProfile> ConnectedNetworks() => new[] { Home, Office };
}

/// <summary>Per-address handshake limits and the separate session budget of <see cref="CompanionServer"/>.</summary>
public sealed class ConnectionLimitTests : IDisposable
{
    private readonly CancellationTokenSource _cts = new(TimeSpan.FromSeconds(30));
    private readonly List<TcpClient> _clients = new();
    private readonly ConcurrentQueue<IConnectionSlot> _slots = new();
    private readonly ConcurrentQueue<Socket> _handled = new();
    private readonly SemaphoreSlim _arrived = new(0);

    public void Dispose()
    {
        _cts.Cancel();
        foreach (var c in _clients)
            c.Dispose();
    }

    /// <summary>A server whose handler holds every connection open (in the handshake) until the test ends.</summary>
    private (CompanionServer server, Task loop) Start(ConnectionLimits limits)
    {
        var server = new CompanionServer(IPAddress.Loopback, 0, async (socket, slot, ct) =>
        {
            _slots.Enqueue(slot);
            _handled.Enqueue(socket);
            _arrived.Release();
            try
            {
                await Task.Delay(Timeout.Infinite, ct);
            }
            catch (OperationCanceledException)
            {
            }
            socket.Dispose();
        }, NullLog.Instance, limits);
        server.Start();
        return (server, server.RunAcceptLoopAsync(_cts.Token));
    }

    private async Task<TcpClient> ConnectFromAsync(string source, int port)
    {
        var client = new TcpClient(new IPEndPoint(IPAddress.Parse(source), 0));
        _clients.Add(client);
        await client.ConnectAsync(IPAddress.Loopback, port, _cts.Token);
        return client;
    }

    private async Task WaitArrivalsAsync(int count)
    {
        for (var i = 0; i < count; i++)
            Assert.True(await _arrived.WaitAsync(TimeSpan.FromSeconds(10)), "a connection never reached the handler");
    }

    private static async Task<bool> IsClosedAsync(TcpClient client, int waitMs = 2000)
    {
        using var cts = new CancellationTokenSource(waitMs);
        try
        {
            var read = await client.GetStream().ReadAsync(new byte[1], cts.Token);
            return read == 0;
        }
        catch (OperationCanceledException)
        {
            return false; // still open
        }
        catch (IOException)
        {
            return true; // reset
        }
    }

    [Fact]
    public async Task OneAddressGetsOnlyItsShareOfHandshakes()
    {
        var (server, loop) = Start(new ConnectionLimits(HandshakesPerPeer: 2, Handshakes: 8, Sessions: 4));
        using (server)
        {
            await ConnectFromAsync("127.0.0.2", server.Port);
            await ConnectFromAsync("127.0.0.2", server.Port);
            await WaitArrivalsAsync(2);

            var third = await ConnectFromAsync("127.0.0.2", server.Port);
            Assert.True(await IsClosedAsync(third), "the third handshake from one address was accepted");
            Assert.Equal(1, server.RefusedByLimit);

            // Another address is not affected.
            var other = await ConnectFromAsync("127.0.0.3", server.Port);
            await WaitArrivalsAsync(1);
            Assert.False(await IsClosedAsync(other, 300));
            Assert.Equal(3, server.PendingHandshakes);
            _cts.Cancel();
            await loop;
        }
    }

    [Fact]
    public async Task WhenEveryHandshakeSlotIsTakenTheOldestOfTheBusiestAddressMakesRoom()
    {
        var (server, loop) = Start(new ConnectionLimits(HandshakesPerPeer: 3, Handshakes: 4, Sessions: 4));
        using (server)
        {
            var oldest = await ConnectFromAsync("127.0.0.2", server.Port);
            await WaitArrivalsAsync(1);
            await ConnectFromAsync("127.0.0.2", server.Port);
            await ConnectFromAsync("127.0.0.2", server.Port);
            var single = await ConnectFromAsync("127.0.0.3", server.Port);
            await WaitArrivalsAsync(3);
            Assert.Equal(4, server.PendingHandshakes);

            var phone = await ConnectFromAsync("127.0.0.4", server.Port);
            await WaitArrivalsAsync(1);
            Assert.True(await IsClosedAsync(oldest), "the oldest handshake of the busiest address stayed open");
            Assert.False(await IsClosedAsync(single, 300));
            Assert.False(await IsClosedAsync(phone, 300));
            Assert.Equal(4, server.PendingHandshakes);
            _cts.Cancel();
            await loop;
        }
    }

    [Fact]
    public async Task AuthenticatedSessionsHaveTheirOwnBudget()
    {
        var (server, loop) = Start(new ConnectionLimits(HandshakesPerPeer: 1, Handshakes: 1, Sessions: 1));
        using (server)
        {
            await ConnectFromAsync("127.0.0.2", server.Port);
            await WaitArrivalsAsync(1);
            Assert.True(_slots.TryDequeue(out var first));
            Assert.True(first.TryPromote());
            Assert.True(first.TryPromote()); // idempotent
            Assert.Equal(0, server.PendingHandshakes);
            Assert.Equal(1, server.ActiveSessions);

            // The promoted connection no longer counts as a handshake: the same address may start another one.
            var second = await ConnectFromAsync("127.0.0.2", server.Port);
            await WaitArrivalsAsync(1);
            Assert.False(await IsClosedAsync(second, 300));
            Assert.True(_slots.TryDequeue(out var secondSlot));
            // ... but the session budget is full.
            Assert.False(secondSlot.TryPromote());
            Assert.Equal(1, server.PendingHandshakes);
            Assert.Equal(1, server.ActiveSessions);
            _cts.Cancel();
            await loop;
        }
    }

    [Fact]
    public async Task IdleHandshakesFromOtherHostsDoNotLockAPairedPhoneOut()
    {
        using var data = new TempDir();
        using var logs = new TempDir();
        using var identity = CompanionIdentity.CreateEphemeral("TESTPC");
        await using var host = CompanionHost.Create(new CompanionHostOptions
        {
            Paths = new AppPaths(data.Path),
            LogDirectory = logs.Path,
            BindAddress = IPAddress.Loopback,
            TcpPort = 0,
            EnableDiscovery = false,
            Protector = new PlainProtector(),
            ProcessProbe = new FakeProcessProbe(),
            Identity = identity,
            InMemoryDevices = true,
            UseFileSystemWatcher = false,
            ConnectionLimits = new ConnectionLimits(HandshakesPerPeer: 3, Handshakes: 32, Sessions: 16),
        });
        host.Start();
        host.Devices.AddOrReplace("phone", "Phone", "token-phone");

        // An idle host on the network opens many sockets and never speaks.
        var attacker = new List<TcpClient>();
        for (var i = 0; i < 40; i++)
        {
            var c = new TcpClient(new IPEndPoint(IPAddress.Parse("127.0.0.9"), 0));
            attacker.Add(c);
            await c.ConnectAsync(IPAddress.Loopback, host.TcpPort, _cts.Token);
        }
        try
        {
            await using var phone = await CompanionClient.ConnectAsync(IPAddress.Loopback, host.TcpPort, identity.Fingerprint, _cts.Token);
            Assert.True(await phone.AuthAsync("phone", "token-phone", _cts.Token));
            Assert.Equal("info", (await phone.ReceiveAsync(_cts.Token))!.Type);
        }
        finally
        {
            foreach (var c in attacker)
                c.Dispose();
        }
    }
}

/// <summary>Network-category gating (PROTOCOL.md §1) with an injected category source.</summary>
public sealed class NetworkGateTests
{
    private static NetworkGate Gate(FakeNetworkSource source, IEnumerable<Guid>? allowed = null) =>
        new(source, allowed, NullLog.Instance);

    [Theory]
    [InlineData(NetworkCategory.Private, true)]
    [InlineData(NetworkCategory.Domain, true)]
    [InlineData(NetworkCategory.Loopback, true)]
    [InlineData(NetworkCategory.Public, false)]
    [InlineData(NetworkCategory.Unknown, false)]
    public void OnlyPrivateDomainAndLoopbackPassByDefault(NetworkCategory category, bool expected)
    {
        var source = new FakeNetworkSource { Resolve = (_, _) => FakeNetworkSource.Home with { Category = category } };
        Assert.Equal(expected, Gate(source).CheckDiscovery(IPAddress.Broadcast, 7));
    }

    [Fact]
    public void AnInterfaceWithoutANetworkIsRefused()
    {
        var source = new FakeNetworkSource { Resolve = (_, _) => null };
        var gate = Gate(source);
        Assert.False(gate.CheckDiscovery(IPAddress.Parse("192.168.1.255"), 3));
        Assert.Equal(1, gate.BlockedCount);
        Assert.Null(gate.LastBlocked!.Network);
    }

    [Fact]
    public void WithoutCategoriesTheGateReliesOnTheAddressFilter()
    {
        var source = new FakeNetworkSource { IsAvailable = false, Resolve = (_, _) => null };
        var gate = Gate(source);
        Assert.True(gate.CheckDiscovery(IPAddress.Broadcast, 3));
        Assert.False(gate.CategoriesAvailable);
        Assert.Empty(gate.Describe());
    }

    [Fact]
    public void APublicNetworkPassesOnlyAfterTheUserAllowedIt()
    {
        var source = new FakeNetworkSource();
        var gate = Gate(source);
        var saved = new List<IReadOnlyCollection<Guid>>();
        var blocked = new List<BlockedPeer>();
        gate.AllowedNetworksChanged += ids => saved.Add(ids);
        gate.Blocked += b => blocked.Add(b);

        Assert.False(gate.CheckDiscovery(IPAddress.Broadcast, 3));
        Assert.False(gate.CheckDiscovery(IPAddress.Broadcast, 3));
        Assert.Equal(2, gate.BlockedCount);
        var only = Assert.Single(blocked); // raised once per network
        Assert.Equal(FakeNetworkSource.Home, only.Network);
        Assert.True(only.Discovery);
        Assert.Contains(gate.Describe(), d => d.Network == FakeNetworkSource.Home && !d.Allowed);

        gate.AllowPublic(FakeNetworkSource.Home.NetworkId);
        Assert.True(gate.CheckDiscovery(IPAddress.Broadcast, 3));
        Assert.Equal(new[] { FakeNetworkSource.Home.NetworkId }, Assert.Single(saved));
        Assert.Contains(gate.Describe(), d => d.Network == FakeNetworkSource.Home && d.Allowed);

        // Another Public network is still refused.
        source.Resolve = (_, _) => FakeNetworkSource.Home with { NetworkId = Guid.NewGuid(), Name = "Cafe" };
        Assert.False(gate.CheckDiscovery(IPAddress.Broadcast, 3));

        gate.DisallowPublic(FakeNetworkSource.Home.NetworkId);
        source.Resolve = (_, _) => FakeNetworkSource.Home;
        Assert.False(gate.CheckDiscovery(IPAddress.Broadcast, 3));
        Assert.Empty(saved[^1]);
    }

    [Fact]
    public void AllowedNetworksComeFromTheSettings()
    {
        var gate = Gate(new FakeNetworkSource(), new[] { FakeNetworkSource.Home.NetworkId });
        Assert.True(gate.CheckDiscovery(IPAddress.Broadcast, 3));
    }

    [Fact]
    public void AFailingLookupNeverOpensTheGate()
    {
        var source = new FakeNetworkSource { Resolve = (_, _) => throw new InvalidOperationException("boom") };
        Assert.False(Gate(source).CheckDiscovery(IPAddress.Broadcast, 3));
    }

    [Fact]
    public async Task ConnectionOnAPublicNetworkIsClosedBeforeAnyByte()
    {
        var source = new FakeNetworkSource();
        var gate = Gate(source);
        var handled = 0;
        using var server = new CompanionServer(IPAddress.Loopback, 0, (s, _) =>
        {
            Interlocked.Increment(ref handled);
            s.Dispose();
            return Task.CompletedTask;
        }, NullLog.Instance) { Gate = gate };
        server.Start();
        using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(10));
        var loop = server.RunAcceptLoopAsync(cts.Token);

        using (var tcp = new TcpClient())
        {
            await tcp.ConnectAsync(IPAddress.Loopback, server.Port, cts.Token);
            int read;
            try
            {
                read = await tcp.GetStream().ReadAsync(new byte[16], cts.Token);
            }
            catch (IOException)
            {
                read = 0;
            }
            Assert.Equal(0, read);
        }
        Assert.Equal(0, Volatile.Read(ref handled));
        Assert.Equal(1, gate.BlockedCount);
        Assert.False(gate.LastBlocked!.Discovery);
        // The local address the connection arrived at was looked up.
        Assert.Contains(source.Lookups, l => IPAddress.Loopback.Equals(l.Address));

        gate.AllowPublic(FakeNetworkSource.Home.NetworkId);
        using (var tcp = new TcpClient())
        {
            await tcp.ConnectAsync(IPAddress.Loopback, server.Port, cts.Token);
            var deadline = Environment.TickCount64 + 5000;
            while (Volatile.Read(ref handled) == 0)
            {
                Assert.True(Environment.TickCount64 < deadline, "the allowed connection never reached the handler");
                await Task.Delay(20);
            }
        }
        cts.Cancel();
        await loop;
    }

    [Fact]
    public async Task DiscoveryIsAnsweredOnlyWhereTheGateAllows()
    {
        var source = new FakeNetworkSource();
        var gate = Gate(source);
        var reply = Encoding.UTF8.GetBytes("{\"t\":\"vrcx-companion\",\"v\":1,\"id\":\"cid\",\"name\":\"PC\",\"port\":1,\"fp\":\"fp\",\"pairing\":false}");
        using var responder = new DiscoveryResponder(IPAddress.Loopback, 0, () => reply, NullLog.Instance) { Gate = gate };
        responder.Start();
        using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(20));
        var loop = responder.RunAsync(cts.Token);
        var target = new IPEndPoint(IPAddress.Loopback, responder.Port);

        Assert.Empty(await CompanionClient.DiscoverAsync(target, TimeSpan.FromMilliseconds(500), cts.Token));
        Assert.Equal(0, responder.RepliesSent);
        Assert.True(gate.LastBlocked!.Discovery);
        // Looked up by the interface the request arrived on.
        Assert.Contains(source.Lookups, l => l.Index >= 0);

        source.Resolve = (_, _) => FakeNetworkSource.Office;
        Assert.Single(await CompanionClient.DiscoverAsync(target, TimeSpan.FromSeconds(3), cts.Token));
        cts.Cancel();
        await loop;
    }

    [Fact]
    public async Task HostOnAPublicNetworkIgnoresPhonesUntilAllowedAndRemembersTheChoice()
    {
        using var data = new TempDir();
        using var logs = new TempDir();
        using var identity = CompanionIdentity.CreateEphemeral("TESTPC");
        var source = new FakeNetworkSource();
        using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(30));
        await using var host = CompanionHost.Create(new CompanionHostOptions
        {
            Paths = new AppPaths(data.Path),
            LogDirectory = logs.Path,
            BindAddress = IPAddress.Loopback,
            TcpPort = 0,
            DiscoveryPort = 0,
            Protector = new PlainProtector(),
            ProcessProbe = new FakeProcessProbe(),
            Identity = identity,
            InMemoryDevices = true,
            UseFileSystemWatcher = false,
            NetworkCategories = source,
        });
        var settings = new CompanionSettings();
        var settingsPath = Path.Combine(data.Path, "settings.json");
        host.NetworkGate.AllowedNetworksChanged += ids => settings.SetAllowedPublicNetworks(ids, settingsPath, NullLog.Instance);
        host.Start();

        await Assert.ThrowsAnyAsync<Exception>(() =>
            CompanionClient.ConnectAsync(IPAddress.Loopback, host.TcpPort, identity.Fingerprint, cts.Token));
        Assert.Empty(await CompanionClient.DiscoverAsync(new IPEndPoint(IPAddress.Loopback, host.DiscoveryPort),
            TimeSpan.FromMilliseconds(500), cts.Token));

        host.NetworkGate.AllowPublic(FakeNetworkSource.Home.NetworkId);
        await using (var c = await CompanionClient.ConnectAsync(IPAddress.Loopback, host.TcpPort, identity.Fingerprint, cts.Token))
            Assert.Equal(identity.CompanionId, c.Hello.GetProperty("id").GetString());
        Assert.Single(await CompanionClient.DiscoverAsync(new IPEndPoint(IPAddress.Loopback, host.DiscoveryPort),
            TimeSpan.FromSeconds(3), cts.Token));

        var reloaded = CompanionSettings.LoadOrCreate(settingsPath, NullLog.Instance);
        Assert.Equal(new[] { FakeNetworkSource.Home.NetworkId }, reloaded.AllowedPublicNetworkIds());
    }

    [Fact]
    public void WindowsSourceReadsTheRealCategories()
    {
        if (!OperatingSystem.IsWindows())
            return;
        using var source = new WindowsNetworkCategorySource(NullLog.Instance);
        Assert.Equal(NetworkCategory.Loopback, source.Find(IPAddress.Loopback)!.Category);
        Assert.Equal(NetworkCategory.Loopback, source.Find(IPAddress.IPv6Loopback)!.Category);
        // Only read: Windows Settings decides the categories.
        Assert.True(source.Available, "INetworkListManager could not be read");
        foreach (var network in source.ConnectedNetworks())
        {
            Assert.NotEqual(Guid.Empty, network.NetworkId);
            Assert.Contains(network.Category, new[] { NetworkCategory.Public, NetworkCategory.Private, NetworkCategory.Domain });
        }
        foreach (var address in LocalAddress.GetAdvertisedAddresses())
            source.Find(address); // never throws
    }

    [Fact]
    public void SettingsKeepValidAllowedNetworksOnly()
    {
        using var dir = new TempDir();
        var path = dir.File("settings.json");
        var id = Guid.NewGuid();
        File.WriteAllText(path, $"{{ \"allowedPublicNetworks\": [\"{id}\", \"not-a-guid\", \"{Guid.Empty}\"] }}");
        var settings = CompanionSettings.LoadOrCreate(path, NullLog.Instance);
        Assert.Equal(new[] { id }, settings.AllowedPublicNetworkIds());

        settings.SetAllowedPublicNetworks(Array.Empty<Guid>(), path, NullLog.Instance);
        Assert.Empty(CompanionSettings.LoadOrCreate(path, NullLog.Instance).AllowedPublicNetworkIds());
        Assert.Equal(49460, CompanionSettings.LoadOrCreate(path, NullLog.Instance).TcpPort);
    }
}

/// <summary>A device revoked while its connection was between the token check and the session registration.</summary>
public sealed class RevocationRaceTests : IDisposable
{
    private readonly TempDir _dir = new();

    public void Dispose() => _dir.Dispose();

    [Fact]
    public void SessionOfARevokedDeviceIsRefused()
    {
        var paired = new HashSet<string> { "kept" };
        using var engine = new SyncEngine(new LogDirectoryTailer(_dir.Path, NullLog.Instance, useWatcher: false), new FakeProcessProbe(),
            NullLog.Instance)
        {
            IsDevicePaired = id => paired.Contains(id),
        };
        var kept = new StreamSession("kept", "Phone", "127.0.0.1", NullLog.Instance);
        var revoked = new StreamSession("revoked", "Phone", "127.0.0.1", NullLog.Instance);
        Assert.True(engine.AddSession(kept));
        Assert.False(engine.AddSession(revoked));
        Assert.True(revoked.Closed.IsCancellationRequested);
        Assert.Equal("revoked", revoked.CloseReason);
        Assert.Equal("kept", Assert.Single(engine.Sessions).DeviceId);
    }

    [Fact]
    public async Task HostChecksTheDeviceStoreWhenASessionRegisters()
    {
        using var data = new TempDir();
        using var identity = CompanionIdentity.CreateEphemeral("TESTPC");
        await using var host = CompanionHost.Create(new CompanionHostOptions
        {
            Paths = new AppPaths(data.Path),
            LogDirectory = _dir.Path,
            BindAddress = IPAddress.Loopback,
            TcpPort = 0,
            EnableDiscovery = false,
            Protector = new PlainProtector(),
            ProcessProbe = new FakeProcessProbe(),
            Identity = identity,
            InMemoryDevices = true,
            UseFileSystemWatcher = false,
        });
        host.Devices.AddOrReplace("dev", "Phone", "token");
        // The connection checked the token ...
        Assert.True(host.Devices.Verify("dev", "token"));
        // ... then the user forgot the device before the session was registered.
        host.RevokeDevice("dev");
        var late = new StreamSession("dev", "Phone", "127.0.0.1", NullLog.Instance);
        Assert.False(host.Engine.AddSession(late));
        Assert.Empty(host.Engine.Sessions);
        Assert.Equal("revoked", late.CloseReason);
    }
}

/// <summary>The phone's <c>idle</c> hint (PROTOCOL.md §5.11): batching, stretched heartbeats and receive timeout.</summary>
public sealed class IdleModeTests : IAsyncLifetime
{
    private const string A = "output_log_2024-01-01_09-59-00.txt";

    private readonly TempDir _dir = new();
    private readonly TempDir _data = new();
    private readonly FakeProcessProbe _probe = new();
    private readonly ManualTime _time = new(new DateTimeOffset(2024, 1, 1, 10, 0, 0, TimeSpan.Zero));
    private readonly CancellationTokenSource _cts = new(TimeSpan.FromSeconds(60));
    private readonly List<Task> _writers = new();
    private readonly CompanionIdentity _identity = CompanionIdentity.CreateEphemeral("TESTPC");
    private CompanionHost? _host;

    public Task InitializeAsync() => Task.CompletedTask;

    public async Task DisposeAsync()
    {
        _cts.Cancel();
        try
        {
            await Task.WhenAll(_writers).WaitAsync(TimeSpan.FromSeconds(5));
        }
        catch (Exception e) when (e is OperationCanceledException or TimeoutException)
        {
        }
        if (_host != null)
            await _host.DisposeAsync();
        _identity.Dispose();
        _dir.Dispose();
        _data.Dispose();
    }

    private static string[] Types(IEnumerable<ReceivedMessage> messages) => messages.Select(m => m.Type).ToArray();

    [Fact]
    public async Task IdlePhoneGetsLogGrowthInBatchesButProcessChangesAtOnce()
    {
        using var engine = new SyncEngine(new LogDirectoryTailer(_dir.Path, NullLog.Instance, _time, useWatcher: false), _probe,
            NullLog.Instance, _time)
        {
            IdleFlushInterval = TimeSpan.FromSeconds(10),
        };
        var path = _dir.File(A);
        File.WriteAllBytes(path, LogFiles.Line("2024.01.01 09:59:00 Log        -  start"));
        var session = new StreamSession("dev", "Phone", "127.0.0.1", NullLog.Instance);
        var sink = new RecordingSink();
        Assert.True(engine.AddSession(session));
        _writers.Add(Task.Run(() => session.RunWriterAsync(sink, _cts.Token)));
        engine.Subscribe(session, 0, Array.Empty<HaveEntry>());
        await sink.WaitForAsync("syncComplete");

        engine.SetIdle(session, true);
        Assert.True(session.Idle);

        // Growth within the interval stays on the PC.
        var from = sink.Count;
        LogFiles.Append(path, LogFiles.Line("one"));
        engine.Tick();
        _time.Advance(TimeSpan.FromSeconds(4));
        LogFiles.Append(path, LogFiles.Line("two"));
        engine.Tick();
        await sink.WaitQuietAsync(150);
        Assert.Equal(from, sink.Count);

        // After the interval, everything since the last flush goes out at once.
        _time.Advance(TimeSpan.FromSeconds(7));
        engine.Tick();
        var batch = await sink.WaitForAsync("data", from);
        Assert.Equal(new[] { "snapshot", "data" }, Types(batch));
        Assert.Equal(LogFiles.Line("one").Concat(LogFiles.Line("two")).ToArray(), batch[1].Data);

        // A process change is not held back, and still travels behind the bytes written before it.
        from = sink.Count;
        _time.Advance(TimeSpan.FromSeconds(1));
        LogFiles.Append(path, LogFiles.Line("VRCApplication: HandleApplicationQuit"));
        _probe.Vrchat = true;
        engine.Tick();
        var withProcess = await sink.WaitForAsync("process", from);
        Assert.Equal(new[] { "snapshot", "data", "process" }, Types(withProcess));

        // Back in the foreground: growth is sent on the next poll.
        engine.SetIdle(session, false);
        from = sink.Count;
        LogFiles.Append(path, LogFiles.Line("three"));
        engine.Tick();
        var live = await sink.WaitForAsync("data", from);
        Assert.Equal(LogFiles.Line("three"), live.Last().Data);
    }

    [Fact]
    public async Task SubscribeWhileIdleIsAnsweredAtOnce()
    {
        using var engine = new SyncEngine(new LogDirectoryTailer(_dir.Path, NullLog.Instance, _time, useWatcher: false), _probe,
            NullLog.Instance, _time);
        File.WriteAllBytes(_dir.File(A), LogFiles.Line("x"));
        var session = new StreamSession("dev", "Phone", "127.0.0.1", NullLog.Instance);
        var sink = new RecordingSink();
        engine.AddSession(session);
        _writers.Add(Task.Run(() => session.RunWriterAsync(sink, _cts.Token)));
        engine.SetIdle(session, true);
        engine.Subscribe(session, 0, Array.Empty<HaveEntry>());
        Assert.Equal(new[] { "snapshot", "process", "data", "syncComplete" }, Types(await sink.WaitForAsync("syncComplete")));
    }

    private CompanionHost StartHost(TimeSpan heartbeat, TimeSpan idleHeartbeat, TimeSpan receive, TimeSpan idleReceive,
        TimeSpan? poll = null)
    {
        _host = CompanionHost.Create(new CompanionHostOptions
        {
            Paths = new AppPaths(_data.Path),
            LogDirectory = _dir.Path,
            BindAddress = IPAddress.Loopback,
            TcpPort = 0,
            EnableDiscovery = false,
            Protector = new PlainProtector(),
            ProcessProbe = _probe,
            Identity = _identity,
            InMemoryDevices = true,
            UseFileSystemWatcher = false,
            PollInterval = poll ?? TimeSpan.FromMilliseconds(100),
            HeartbeatInterval = heartbeat,
            IdleHeartbeatInterval = idleHeartbeat,
            ReceiveTimeout = receive,
            IdleReceiveTimeout = idleReceive,
        });
        _host.Start();
        _host.Devices.AddOrReplace("dev", "Phone", "token");
        return _host;
    }

    private async Task<CompanionClient> ConnectAsync(CompanionHost host, TimeSpan? replyAfter)
    {
        var client = await CompanionClient.ConnectAsync(IPAddress.Loopback, host.TcpPort, _identity.Fingerprint, _cts.Token);
        client.HeartbeatReplyAfter = replyAfter;
        Assert.True(await client.AuthAsync("dev", "token", _cts.Token));
        Assert.Equal("info", (await client.ReceiveAsync(_cts.Token))!.Type);
        return client;
    }

    private static bool IdleOn(ReceivedMessage m) => m.Json.GetProperty("on").GetBoolean();

    [Fact]
    public async Task IdleIsConfirmedAndStretchesHeartbeatsAndTheReceiveTimeout()
    {
        var host = StartHost(heartbeat: TimeSpan.FromMilliseconds(250), idleHeartbeat: TimeSpan.FromMilliseconds(1500),
            receive: TimeSpan.FromMilliseconds(1200), idleReceive: TimeSpan.FromSeconds(6));
        // A phone that stays silent after going idle.
        await using var client = await ConnectAsync(host, replyAfter: null);

        await client.SendIdleAsync(true, _cts.Token);
        var confirm = (await client.ReceiveUntilAsync("idle", _cts.Token)).Last();
        Assert.True(IdleOn(confirm));
        Assert.True(Assert.Single(host.Engine.Sessions).Idle);
        var silentSince = Stopwatch.StartNew();

        // Heartbeats now come after 1.5 s of silence, and the silent phone outlives the normal 1.2 s timeout.
        var gaps = new List<TimeSpan>();
        var last = Stopwatch.StartNew();
        while (gaps.Count < 2)
        {
            var m = await client.ReceiveAsync(_cts.Token);
            Assert.NotNull(m);
            if (m!.Type != "heartbeat")
                continue;
            gaps.Add(last.Elapsed);
            last.Restart();
        }
        Assert.All(gaps, g => Assert.InRange(g, TimeSpan.FromMilliseconds(1300), TimeSpan.FromMilliseconds(2500)));
        Assert.True(silentSince.Elapsed > TimeSpan.FromMilliseconds(2500));
        Assert.Single(host.Engine.Sessions);

        // Back to the foreground: confirmed, and heartbeats every 250 ms of silence again (the phone answers again,
        // as it must inside the normal receive timeout; its pings do not count as the companion's silence).
        client.HeartbeatReplyAfter = TimeSpan.FromMilliseconds(100);
        await client.SendIdleAsync(false, _cts.Token);
        var back = (await client.ReceiveUntilAsync("idle", _cts.Token)).Last();
        Assert.False(IdleOn(back));
        var quick = Stopwatch.StartNew();
        var beats = 0;
        while (beats < 3)
        {
            if ((await client.ReceiveAsync(_cts.Token))?.Type == "heartbeat")
                beats++;
        }
        Assert.True(quick.Elapsed < TimeSpan.FromMilliseconds(1200), $"3 heartbeats took {quick.Elapsed}");
    }

    [Fact]
    public async Task SilentIdlePhoneIsDroppedAfterTheIdleReceiveTimeout()
    {
        var host = StartHost(heartbeat: TimeSpan.FromMilliseconds(250), idleHeartbeat: TimeSpan.FromMilliseconds(500),
            receive: TimeSpan.FromMilliseconds(800), idleReceive: TimeSpan.FromSeconds(2));
        await using var client = await ConnectAsync(host, replyAfter: null);
        await client.SendIdleAsync(true, _cts.Token);
        var clock = Stopwatch.StartNew();
        try
        {
            while (await client.ReceiveAsync(_cts.Token) != null)
            {
            }
        }
        catch (IOException)
        {
        }
        Assert.InRange(clock.Elapsed, TimeSpan.FromMilliseconds(1700), TimeSpan.FromSeconds(5));
    }

    [Fact]
    public async Task NoHeartbeatWhileOtherFramesKeepTheConnectionBusy()
    {
        var path = _dir.File(A);
        File.WriteAllBytes(path, LogFiles.Line("start"));
        var host = StartHost(heartbeat: TimeSpan.FromMilliseconds(700), idleHeartbeat: TimeSpan.FromSeconds(5),
            receive: TimeSpan.FromSeconds(5), idleReceive: TimeSpan.FromSeconds(10), poll: TimeSpan.FromMilliseconds(50));
        await using var client = await ConnectAsync(host, replyAfter: TimeSpan.FromMilliseconds(500));
        await client.SubscribeAsync(0, Array.Empty<(string, string, long)>(), _cts.Token);
        await client.ReceiveUntilAsync("syncComplete", _cts.Token);

        // VRChat keeps writing: data goes out every poll, so no heartbeat is needed.
        using var busy = new CancellationTokenSource(TimeSpan.FromSeconds(2));
        var writer = Task.Run(async () =>
        {
            var i = 0;
            while (!busy.IsCancellationRequested)
            {
                LogFiles.Append(path, LogFiles.Line("line " + i++));
                await Task.Delay(150);
            }
        });
        var received = new List<ReceivedMessage>();
        var log = new List<string>();
        var clock = Stopwatch.StartNew();
        // Read while the writer still runs (it stops after 2 s; then a heartbeat is due again).
        while (clock.Elapsed < TimeSpan.FromMilliseconds(1500))
        {
            var m = (await client.ReceiveAsync(_cts.Token))!;
            received.Add(m);
            log.Add($"{clock.ElapsedMilliseconds}:{m.Type}");
        }
        await writer;
        Assert.Contains(received, m => m.Type == "data");
        Assert.True(received.All(m => m.Type != "heartbeat"), string.Join(" ", log));

        // Silence again: heartbeats resume.
        await client.ReceiveUntilAsync("heartbeat", _cts.Token, keepHeartbeats: true);
    }
}

public sealed class LogRedactionTests
{
    [Fact]
    public void ParserExceptionsAreLoggedWithoutTheirMessage()
    {
        const string secret = "authcookie_0123456789";
        var memory = new MemoryLog();
        memory.Warn("could not parse", new JsonException($"'{secret}' is an invalid start of a value."));
        memory.Warn("could not parse", new FormatException(secret));
        memory.Warn("i/o", new IOException("disk full"));
        Assert.DoesNotContain(memory.Entries, e => e.Contains(secret));
        Assert.Contains(memory.Entries, e => e.Contains("JsonException"));
        Assert.Contains(memory.Entries, e => e.Contains("disk full"));

        using var dir = new TempDir();
        using (var file = new FileLog(dir.File("companion.log")))
            file.Error("could not parse", new JsonException(secret));
        var text = File.ReadAllText(dir.File("companion.log"));
        Assert.DoesNotContain(secret, text);
        Assert.Contains("System.Text.Json.JsonException", text);
    }
}

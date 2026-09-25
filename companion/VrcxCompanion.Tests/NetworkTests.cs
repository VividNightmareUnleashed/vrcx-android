using System.Diagnostics;
using System.Net;
using System.Net.Sockets;
using System.Text;
using VrcxCompanion.Core.Client;
using VrcxCompanion.Core.Diagnostics;
using VrcxCompanion.Core.Net;
using VrcxCompanion.Core.Processes;

namespace VrcxCompanion.Tests;

public class NetworkTests
{
    [Fact]
    public async Task DisallowedPeerIsClosedBeforeAnyByteIsSent()
    {
        var handled = false;
        using var server = new CompanionServer(IPAddress.Loopback, 0, (s, _) =>
        {
            handled = true;
            s.Dispose();
            return Task.CompletedTask;
        }, NullLog.Instance) { IsAllowedPeer = _ => false };
        server.Start();
        using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(10));
        var loop = server.RunAcceptLoopAsync(cts.Token);

        using var tcp = new TcpClient();
        await tcp.ConnectAsync(IPAddress.Loopback, server.Port, cts.Token);
        var buffer = new byte[64];
        int read;
        try
        {
            read = await tcp.GetStream().ReadAsync(buffer, cts.Token);
        }
        catch (IOException)
        {
            read = 0; // reset
        }
        Assert.Equal(0, read);
        Assert.False(handled);
        Assert.Equal(1, server.RejectedPeers);
        cts.Cancel();
        await loop;
    }

    [Fact]
    public async Task AllowedPeerReachesTheHandler()
    {
        var handled = new TaskCompletionSource();
        using var server = new CompanionServer(IPAddress.Loopback, 0, (s, _) =>
        {
            handled.TrySetResult();
            s.Dispose();
            return Task.CompletedTask;
        }, NullLog.Instance);
        server.Start();
        using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(10));
        var loop = server.RunAcceptLoopAsync(cts.Token);
        using var tcp = new TcpClient();
        await tcp.ConnectAsync(IPAddress.Loopback, server.Port, cts.Token);
        await handled.Task.WaitAsync(cts.Token);
        cts.Cancel();
        await loop;
    }

    [Fact]
    public async Task DiscoveryAnswersOnlyValidRequestsFromAllowedPeers()
    {
        var reply = Encoding.UTF8.GetBytes("{\"t\":\"vrcx-companion\",\"v\":1,\"id\":\"cid\",\"name\":\"PC\",\"port\":49460,\"fp\":\"fp\",\"pairing\":true}");
        using var responder = new DiscoveryResponder(IPAddress.Loopback, 0, () => reply, NullLog.Instance);
        responder.Start();
        using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(20));
        var loop = responder.RunAsync(cts.Token);
        var target = new IPEndPoint(IPAddress.Loopback, responder.Port);

        var found = Assert.Single(await CompanionClient.DiscoverAsync(target, TimeSpan.FromSeconds(3), cts.Token));
        Assert.Equal("cid", found.Id);
        Assert.Equal(49460, found.Port);
        Assert.True(found.Pairing);

        // Anything else gets no answer.
        using (var udp = new UdpClient(AddressFamily.InterNetwork))
        {
            await udp.SendAsync(Encoding.UTF8.GetBytes("{\"t\":\"something-else\"}"), target, cts.Token);
            await udp.SendAsync(Encoding.UTF8.GetBytes("not json"), target, cts.Token);
            await udp.SendAsync(new byte[2000], target, cts.Token);
            using var wait = CancellationTokenSource.CreateLinkedTokenSource(cts.Token);
            wait.CancelAfter(500);
            await Assert.ThrowsAnyAsync<OperationCanceledException>(async () => await udp.ReceiveAsync(wait.Token));
        }
        Assert.Equal(1, responder.RepliesSent);
        cts.Cancel();
        await loop;
    }

    [Fact]
    public async Task DiscoveryIgnoresDisallowedPeers()
    {
        using var responder = new DiscoveryResponder(IPAddress.Loopback, 0, () => new byte[] { (byte)'{', (byte)'}' }, NullLog.Instance)
        {
            IsAllowedPeer = _ => false,
        };
        responder.Start();
        using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(10));
        var loop = responder.RunAsync(cts.Token);
        var found = await CompanionClient.DiscoverAsync(new IPEndPoint(IPAddress.Loopback, responder.Port), TimeSpan.FromMilliseconds(500), cts.Token);
        Assert.Empty(found);
        Assert.Equal(0, responder.RepliesSent);
        cts.Cancel();
        await loop;
    }

    [Theory]
    [InlineData("{\"t\":\"vrcx-discover\",\"v\":1}", true)]
    [InlineData("{\"t\":\"vrcx-discover\",\"v\":2,\"extra\":true}", true)]
    [InlineData("{\"t\":\"vrcx-companion\"}", false)]
    [InlineData("{}", false)]
    [InlineData("[1]", false)]
    [InlineData("vrcx-discover", false)]
    public void DiscoverRequestParsing(string payload, bool expected) =>
        Assert.Equal(expected, DiscoveryResponder.IsDiscoverRequest(Encoding.UTF8.GetBytes(payload)));
}

public class ProcessProbeTests
{
    [Theory]
    [InlineData("VRChat.exe", "VRChat")]
    [InlineData("vrserver.EXE", "vrserver")]
    [InlineData("vrmonitor", "vrmonitor")]
    [InlineData("some.tool.exe", "some.tool")]
    public void ShortNameMatchesProcessName(string exe, string expected) => Assert.Equal(expected, SystemProcessProbe.ShortName(exe));

    [Fact]
    public void DetectsStartAndExitOfANamedProcess()
    {
        using var probe = new SystemProcessProbe("PING", "no-such-process-name");
        Assert.False(probe.Poll().VrchatRunning);
        var psi = new ProcessStartInfo(Path.Combine(Environment.SystemDirectory, "PING.EXE"), "-n 30 127.0.0.1")
        {
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardOutput = true,
        };
        using var process = Process.Start(psi)!;
        try
        {
            var state = probe.Poll();
            Assert.True(state.VrchatRunning);
            Assert.False(state.SteamVrRunning);
            Assert.True(probe.Poll().VrchatRunning); // tracked through its handle
        }
        finally
        {
            process.Kill();
            process.WaitForExit(5000);
        }
        Assert.False(probe.Poll().VrchatRunning);
    }

    [Fact]
    public void RealProbeDoesNotThrow()
    {
        using var probe = new SystemProcessProbe();
        probe.Poll();
        probe.Poll();
    }
}

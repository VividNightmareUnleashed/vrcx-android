using System.Net;
using System.Net.Sockets;
using System.Text.Json;
using VrcxCompanion.Core.Diagnostics;
using VrcxCompanion.Core.Protocol;

namespace VrcxCompanion.Core.Net;

/// <summary>
/// Answers <c>{"t":"vrcx-discover","v":1}</c> datagrams (PROTOCOL.md §2) with a unicast reply to the sender, only for
/// local senders. Replies are rate limited.
/// </summary>
public sealed class DiscoveryResponder : IDisposable
{
    private const int MaxRequestBytes = 1024;
    private const int SioUdpConnReset = unchecked((int)0x9800000C);

    private readonly IPAddress _bindAddress;
    private readonly int _requestedPort;
    private readonly Func<byte[]> _buildReply;
    private readonly ICompanionLog _log;
    private readonly int _maxRepliesPerSecond;
    private Socket? _socket;
    private long _windowStart;
    private int _repliesInWindow;

    public DiscoveryResponder(IPAddress bindAddress, int port, Func<byte[]> buildReply, ICompanionLog log, int maxRepliesPerSecond = 20)
    {
        _bindAddress = bindAddress;
        _requestedPort = port;
        _buildReply = buildReply;
        _log = log;
        _maxRepliesPerSecond = maxRepliesPerSecond;
    }

    public int Port { get; private set; }
    public long RepliesSent { get; private set; }

    public void Start()
    {
        var socket = new Socket(_bindAddress.AddressFamily, SocketType.Dgram, ProtocolType.Udp);
        try
        {
            if (OperatingSystem.IsWindows())
            {
                socket.ExclusiveAddressUse = true;
                // Without this, an ICMP "port unreachable" for one reply makes the next receive fail.
                socket.IOControl(SioUdpConnReset, new byte[] { 0, 0, 0, 0 }, null);
            }
            socket.Bind(new IPEndPoint(_bindAddress, _requestedPort));
        }
        catch
        {
            socket.Dispose();
            throw;
        }
        _socket = socket;
        Port = ((IPEndPoint)socket.LocalEndPoint!).Port;
    }

    public async Task RunAsync(CancellationToken cancellationToken)
    {
        var socket = _socket ?? throw new InvalidOperationException("not started");
        var buffer = new byte[MaxRequestBytes + 1];
        EndPoint any = new IPEndPoint(_bindAddress.AddressFamily == AddressFamily.InterNetworkV6 ? IPAddress.IPv6Any : IPAddress.Any, 0);
        while (!cancellationToken.IsCancellationRequested)
        {
            SocketReceiveFromResult result;
            try
            {
                result = await socket.ReceiveFromAsync(buffer, SocketFlags.None, any, cancellationToken).ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
                return;
            }
            catch (ObjectDisposedException)
            {
                return;
            }
            catch (SocketException e) when (e.SocketErrorCode is SocketError.ConnectionReset or SocketError.MessageSize)
            {
                continue;
            }
            catch (SocketException e)
            {
                _log.Warn($"discovery receive failed: {e.SocketErrorCode}");
                try
                {
                    await Task.Delay(1000, cancellationToken).ConfigureAwait(false);
                }
                catch (OperationCanceledException)
                {
                    return;
                }
                continue;
            }

            if (result.RemoteEndPoint is not IPEndPoint remote || !LocalAddress.IsLocal(remote.Address))
                continue;
            if (result.ReceivedBytes is 0 or > MaxRequestBytes || !IsDiscoverRequest(buffer.AsSpan(0, result.ReceivedBytes)))
                continue;
            if (!TakeReplySlot())
                continue;
            try
            {
                await socket.SendToAsync(_buildReply(), SocketFlags.None, remote, cancellationToken).ConfigureAwait(false);
                RepliesSent++;
            }
            catch (OperationCanceledException)
            {
                return;
            }
            catch (SocketException e)
            {
                _log.Debug($"discovery reply failed: {e.SocketErrorCode}");
            }
        }
    }

    internal static bool IsDiscoverRequest(ReadOnlySpan<byte> payload)
    {
        try
        {
            var reader = new Utf8JsonReader(payload, new JsonReaderOptions { MaxDepth = 4 });
            using var doc = JsonDocument.ParseValue(ref reader);
            return doc.RootElement.GetString("t") == ProtocolConstants.DiscoverRequestType;
        }
        catch (JsonException)
        {
            return false;
        }
    }

    private bool TakeReplySlot()
    {
        var now = Environment.TickCount64;
        if (now - _windowStart >= 1000)
        {
            _windowStart = now;
            _repliesInWindow = 0;
        }
        return ++_repliesInWindow <= _maxRepliesPerSecond;
    }

    public void Dispose()
    {
        _socket?.Dispose();
        _socket = null;
    }
}

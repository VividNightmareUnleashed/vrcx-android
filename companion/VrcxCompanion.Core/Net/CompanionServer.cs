using System.Net;
using System.Net.Sockets;
using VrcxCompanion.Core.Diagnostics;

namespace VrcxCompanion.Core.Net;

/// <summary>
/// TCP listener. Listens on all interfaces (dual-mode IPv6 socket) unless a bind address is given, and closes peers
/// without a local address (PROTOCOL.md §1) before any byte is sent.
/// </summary>
public sealed class CompanionServer : IDisposable
{
    private readonly IPAddress _bindAddress;
    private readonly int _requestedPort;
    private readonly Func<Socket, CancellationToken, Task> _handle;
    private readonly ICompanionLog _log;
    private readonly int _maxConnections;
    private Socket? _listener;
    private int _active;
    private long _rejected;
    private DateTimeOffset _lastRejectLog = DateTimeOffset.MinValue;

    public CompanionServer(IPAddress bindAddress, int port, Func<Socket, CancellationToken, Task> handle, ICompanionLog log,
        int maxConnections = 32)
    {
        _bindAddress = bindAddress;
        _requestedPort = port;
        _handle = handle;
        _log = log;
        _maxConnections = maxConnections;
    }

    public int Port { get; private set; }

    /// <summary>Which peers may connect. PROTOCOL.md §1: local addresses only (replaceable in tests).</summary>
    internal Func<IPAddress?, bool> IsAllowedPeer { get; init; } = LocalAddress.IsLocal;

    public int ActiveConnections => Volatile.Read(ref _active);
    public long RejectedPeers => Interlocked.Read(ref _rejected);

    /// <summary>Binds and listens. Throws <see cref="SocketException"/> when the port is taken.</summary>
    public void Start()
    {
        var socket = new Socket(_bindAddress.AddressFamily, SocketType.Stream, ProtocolType.Tcp);
        try
        {
            if (_bindAddress.AddressFamily == AddressFamily.InterNetworkV6 && _bindAddress.Equals(IPAddress.IPv6Any))
                socket.DualMode = true;
            if (OperatingSystem.IsWindows())
                socket.ExclusiveAddressUse = true;
            socket.Bind(new IPEndPoint(_bindAddress, _requestedPort));
            socket.Listen(64);
        }
        catch
        {
            socket.Dispose();
            throw;
        }
        _listener = socket;
        Port = ((IPEndPoint)socket.LocalEndPoint!).Port;
    }

    public async Task RunAcceptLoopAsync(CancellationToken cancellationToken)
    {
        var listener = _listener ?? throw new InvalidOperationException("not started");
        while (!cancellationToken.IsCancellationRequested)
        {
            Socket client;
            try
            {
                client = await listener.AcceptAsync(cancellationToken).ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
                return;
            }
            catch (ObjectDisposedException)
            {
                return;
            }
            catch (SocketException e)
            {
                _log.Debug($"accept failed: {e.SocketErrorCode}");
                continue;
            }

            var remote = (client.RemoteEndPoint as IPEndPoint)?.Address;
            if (!IsAllowedPeer(remote))
            {
                Reject(client, remote);
                continue;
            }
            if (Interlocked.Increment(ref _active) > _maxConnections)
            {
                Interlocked.Decrement(ref _active);
                _log.Warn("too many connections; closing a new one");
                Abort(client);
                continue;
            }
            _ = Task.Run(async () =>
            {
                try
                {
                    await _handle(client, cancellationToken).ConfigureAwait(false);
                }
                catch (Exception e)
                {
                    _log.Error("connection handler failed", e);
                }
                finally
                {
                    Interlocked.Decrement(ref _active);
                }
            }, CancellationToken.None);
        }
    }

    private void Reject(Socket client, IPAddress? remote)
    {
        Interlocked.Increment(ref _rejected);
        Abort(client);
        var now = DateTimeOffset.UtcNow;
        if (now - _lastRejectLog > TimeSpan.FromMinutes(1))
        {
            _lastRejectLog = now;
            _log.Warn($"closed a connection from a non-local address ({remote}); further ones are not logged for a minute");
        }
    }

    private static void Abort(Socket client)
    {
        try
        {
            client.LingerState = new LingerOption(true, 0);
        }
        catch (SocketException)
        {
        }
        client.Dispose();
    }

    public void Dispose()
    {
        _listener?.Dispose();
        _listener = null;
    }
}

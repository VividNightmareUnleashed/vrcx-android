using System.Net;
using System.Net.Sockets;
using VrcxCompanion.Core.Diagnostics;
using VrcxCompanion.Core.Protocol;

namespace VrcxCompanion.Core.Net;

/// <summary>Connection budgets of <see cref="CompanionServer"/> (PROTOCOL.md §1).</summary>
public sealed record ConnectionLimits(int HandshakesPerPeer, int Handshakes, int Sessions)
{
    public static readonly ConnectionLimits Default =
        new(ProtocolConstants.MaxHandshakesPerPeer, ProtocolConstants.MaxHandshakes, ProtocolConstants.MaxSessions);
}

/// <summary>
/// A connection's place in the server's budgets. It starts in the handshake budget; <see cref="TryPromote"/> moves it
/// to the session budget once the peer authenticated, after which it is never evicted.
/// </summary>
public interface IConnectionSlot
{
    /// <summary>Moves the connection to the session budget. False when every session slot is taken.</summary>
    bool TryPromote();
}

/// <summary>
/// TCP listener. Listens on all interfaces (dual-mode IPv6 socket) unless a bind address is given, and closes peers
/// without a local address (PROTOCOL.md §1), and peers on a network the <see cref="Gate"/> refuses, before any byte is
/// sent.
/// <para>
/// Unauthenticated connections have their own budget: at most <see cref="ConnectionLimits.HandshakesPerPeer"/> per
/// source address (a further one is closed at once), and at most <see cref="ConnectionLimits.Handshakes"/> in total
/// (a further one replaces the oldest handshake of the address that holds the most). Authenticated sessions count
/// against <see cref="ConnectionLimits.Sessions"/> only, so idle sockets from other hosts can never lock paired phones
/// out.
/// </para>
/// </summary>
public sealed class CompanionServer : IDisposable
{
    private readonly IPAddress _bindAddress;
    private readonly int _requestedPort;
    private readonly Func<Socket, IConnectionSlot, CancellationToken, Task> _handle;
    private readonly ICompanionLog _log;
    private readonly ConnectionLimits _limits;
    private readonly object _slotGate = new();
    private readonly List<Slot> _handshakes = new();
    private int _sessions;
    private long _sequence;
    private Socket? _listener;
    private long _rejected;
    private long _refusedByLimit;
    private DateTimeOffset _lastRejectLog = DateTimeOffset.MinValue;
    private DateTimeOffset _lastLimitLog = DateTimeOffset.MinValue;

    public CompanionServer(IPAddress bindAddress, int port, Func<Socket, IConnectionSlot, CancellationToken, Task> handle, ICompanionLog log,
        ConnectionLimits? limits = null)
    {
        _bindAddress = bindAddress;
        _requestedPort = port;
        _handle = handle;
        _log = log;
        _limits = limits ?? ConnectionLimits.Default;
    }

    /// <summary>A server whose handler does not take part in the session budget (tests).</summary>
    public CompanionServer(IPAddress bindAddress, int port, Func<Socket, CancellationToken, Task> handle, ICompanionLog log,
        ConnectionLimits? limits = null)
        : this(bindAddress, port, (socket, _, ct) => handle(socket, ct), log, limits)
    {
    }

    public int Port { get; private set; }

    /// <summary>Which peers may connect. PROTOCOL.md §1: local addresses only (replaceable in tests).</summary>
    internal Func<IPAddress?, bool> IsAllowedPeer { get; init; } = LocalAddress.IsLocal;

    /// <summary>The network-category gate (PROTOCOL.md §1). Null: no gate (tests of other parts).</summary>
    public NetworkGate? Gate { get; init; }

    public int ActiveConnections
    {
        get
        {
            lock (_slotGate)
                return _handshakes.Count + _sessions;
        }
    }

    public int PendingHandshakes
    {
        get
        {
            lock (_slotGate)
                return _handshakes.Count;
        }
    }

    public int ActiveSessions
    {
        get
        {
            lock (_slotGate)
                return _sessions;
        }
    }

    /// <summary>Peers closed because their address is not local.</summary>
    public long RejectedPeers => Interlocked.Read(ref _rejected);

    /// <summary>Connections closed or replaced because of the handshake limits.</summary>
    public long RefusedByLimit => Interlocked.Read(ref _refusedByLimit);

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

            var remote = Normalize((client.RemoteEndPoint as IPEndPoint)?.Address);
            if (!IsAllowedPeer(remote))
            {
                Reject(client, remote);
                continue;
            }
            if (Gate != null && !Gate.CheckConnection(client))
            {
                Abort(client);
                continue;
            }
            var slot = Admit(client, remote!, out var evicted);
            if (evicted != null)
            {
                Interlocked.Increment(ref _refusedByLimit);
                Abort(evicted.Socket);
            }
            if (slot == null)
            {
                Interlocked.Increment(ref _refusedByLimit);
                LogLimit("too many connections in the handshake from one address; closing a new one");
                Abort(client);
                continue;
            }
            if (evicted != null)
                LogLimit("too many connections in the handshake; closed the oldest one");
            _ = Task.Run(async () =>
            {
                try
                {
                    await _handle(client, slot, cancellationToken).ConfigureAwait(false);
                }
                catch (Exception e)
                {
                    _log.Error("connection handler failed", e);
                }
                finally
                {
                    Release(slot);
                }
            }, CancellationToken.None);
        }
    }

    /// <summary>
    /// Takes a handshake slot for a new connection. Returns null when the peer already holds its share. When every
    /// handshake slot is taken, the oldest handshake of the address holding the most is returned in
    /// <paramref name="evicted"/> (already released) for the caller to close.
    /// </summary>
    private Slot? Admit(Socket socket, IPAddress peer, out Slot? evicted)
    {
        evicted = null;
        lock (_slotGate)
        {
            if (_handshakes.Count(s => s.Peer.Equals(peer)) >= _limits.HandshakesPerPeer)
                return null;
            if (_handshakes.Count >= _limits.Handshakes)
            {
                evicted = _handshakes
                    .GroupBy(s => s.Peer)
                    .OrderByDescending(g => g.Count())
                    .First()
                    .OrderBy(s => s.Sequence)
                    .First();
                _handshakes.Remove(evicted);
                evicted.State = SlotState.Released;
            }
            var slot = new Slot(this, socket, peer, ++_sequence);
            _handshakes.Add(slot);
            return slot;
        }
    }

    private bool Promote(Slot slot)
    {
        lock (_slotGate)
        {
            if (slot.State == SlotState.Session)
                return true;
            if (slot.State != SlotState.Handshake || _sessions >= _limits.Sessions)
                return false;
            _handshakes.Remove(slot);
            _sessions++;
            slot.State = SlotState.Session;
            return true;
        }
    }

    private void Release(Slot slot)
    {
        lock (_slotGate)
        {
            switch (slot.State)
            {
                case SlotState.Handshake:
                    _handshakes.Remove(slot);
                    break;
                case SlotState.Session:
                    _sessions--;
                    break;
            }
            slot.State = SlotState.Released;
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

    private void LogLimit(string message)
    {
        var now = DateTimeOffset.UtcNow;
        if (now - _lastLimitLog <= TimeSpan.FromMinutes(1))
            return;
        _lastLimitLog = now;
        _log.Warn(message + "; further ones are not logged for a minute");
    }

    private static IPAddress? Normalize(IPAddress? address) =>
        address is { AddressFamily: AddressFamily.InterNetworkV6, IsIPv4MappedToIPv6: true } ? address.MapToIPv4() : address;

    private static void Abort(Socket client)
    {
        try
        {
            client.LingerState = new LingerOption(true, 0);
        }
        catch (Exception e) when (e is SocketException or ObjectDisposedException)
        {
        }
        client.Dispose();
    }

    public void Dispose()
    {
        _listener?.Dispose();
        _listener = null;
    }

    private enum SlotState
    {
        Handshake,
        Session,
        Released,
    }

    private sealed class Slot : IConnectionSlot
    {
        private readonly CompanionServer _server;

        public Slot(CompanionServer server, Socket socket, IPAddress peer, long sequence)
        {
            _server = server;
            Socket = socket;
            Peer = peer;
            Sequence = sequence;
        }

        public Socket Socket { get; }
        public IPAddress Peer { get; }
        public long Sequence { get; }

        /// <summary>Guarded by the server's slot lock.</summary>
        public SlotState State { get; set; } = SlotState.Handshake;

        public bool TryPromote() => _server.Promote(this);
    }
}

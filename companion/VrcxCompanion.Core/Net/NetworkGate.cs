using System.Net;
using System.Net.Sockets;
using VrcxCompanion.Core.Diagnostics;

namespace VrcxCompanion.Core.Net;

/// <summary>How Windows categorizes a network (Network List Manager), plus this PC's own loopback.</summary>
public enum NetworkCategory
{
    /// <summary>The interface belongs to no network Windows knows (still identifying, or a virtual adapter).</summary>
    Unknown,
    Public,
    Private,
    Domain,

    /// <summary>127.0.0.0/8 and ::1: the PC itself (for example the Android emulator's 10.0.2.2).</summary>
    Loopback,
}

/// <summary>A network one of this PC's interfaces is connected to.</summary>
/// <param name="NetworkId">Windows' id of the network profile (stable across reconnects); empty for loopback.</param>
/// <param name="Name">The profile name Windows shows (usually the Wi-Fi name).</param>
/// <param name="IsMain">
/// A physical adapter with a default gateway is on this network: it is where phones are expected (a Hyper-V or VPN
/// adapter's "Unidentified network" is not).
/// </param>
public sealed record NetworkProfile(Guid NetworkId, string Name, NetworkCategory Category, bool IsMain = false)
{
    public static readonly NetworkProfile LoopbackProfile = new(Guid.Empty, "This PC", NetworkCategory.Loopback);
}

/// <summary>Tells which network a local address or interface belongs to. Implemented with INetworkListManager.</summary>
public interface INetworkCategorySource
{
    /// <summary>False when Windows could not be asked at all; the gate then relies on the address filter alone.</summary>
    bool Available { get; }

    /// <summary>
    /// The network of the interface with index <paramref name="interfaceIndex"/> (when it is not -1), otherwise of the
    /// interface that owns <paramref name="localAddress"/>; <see cref="NetworkProfile.LoopbackProfile"/> for a loopback
    /// address. Null when no interface matches.
    /// </summary>
    NetworkProfile? Find(IPAddress? localAddress, int interfaceIndex = -1);

    /// <summary>Networks the PC is connected to now (for the status window).</summary>
    IReadOnlyList<NetworkProfile> ConnectedNetworks();
}

/// <summary>A connection or discovery request that the gate turned away.</summary>
public sealed record BlockedPeer(NetworkProfile? Network, bool Discovery, DateTimeOffset AtUtc);

/// <summary>
/// PROTOCOL.md §1: connections are accepted, and discovery answered, only on networks Windows categorizes as Private
/// or Domain (and on loopback). A Public network is refused unless the user explicitly allowed that network
/// ("Allow on this network" in the Status window), because many home Wi-Fi networks are Public by default while cafés
/// and hotels share the same private address ranges. Unknown networks are refused. When Windows cannot report
/// categories at all, the gate lets everything through and relies on the local-address filter (the behaviour before
/// the gate existed); the Status window says so.
/// </summary>
public sealed class NetworkGate
{
    private readonly object _gate = new();
    private readonly INetworkCategorySource _source;
    private readonly ICompanionLog _log;
    private readonly TimeProvider _time;
    private readonly HashSet<Guid> _allowedPublic;
    private BlockedPeer? _lastBlocked;
    private long _blocked;
    private DateTimeOffset _lastBlockLog = DateTimeOffset.MinValue;

    public NetworkGate(INetworkCategorySource source, IEnumerable<Guid>? allowedPublicNetworks, ICompanionLog log, TimeProvider? time = null)
    {
        _source = source;
        _log = log;
        _time = time ?? TimeProvider.System;
        _allowedPublic = new HashSet<Guid>(allowedPublicNetworks ?? Array.Empty<Guid>());
        _allowedPublic.Remove(Guid.Empty);
    }

    /// <summary>A gate that lets every peer through (tests of other parts, and platforms without categories).</summary>
    public static NetworkGate AllowAll(ICompanionLog? log = null) => new(new UnavailableNetworkSource(), null, log ?? NullLog.Instance);

    public bool CategoriesAvailable => _source.Available;

    /// <summary>Raised (on the thread that checked) the first time a peer is turned away after a change of network.</summary>
    public event Action<BlockedPeer>? Blocked;

    /// <summary>Raised after <see cref="AllowPublic"/> or <see cref="DisallowPublic"/> changed the list.</summary>
    public event Action<IReadOnlyCollection<Guid>>? AllowedNetworksChanged;

    public IReadOnlyCollection<Guid> AllowedPublicNetworks
    {
        get
        {
            lock (_gate)
                return _allowedPublic.ToArray();
        }
    }

    public long BlockedCount => Interlocked.Read(ref _blocked);

    public BlockedPeer? LastBlocked
    {
        get
        {
            lock (_gate)
                return _lastBlocked;
        }
    }

    public bool IsAllowedPublic(Guid networkId)
    {
        lock (_gate)
            return _allowedPublic.Contains(networkId);
    }

    /// <summary>Whether the gate lets peers on <paramref name="network"/> through.</summary>
    public bool Allows(NetworkProfile? network)
    {
        if (!_source.Available)
            return true;
        return network?.Category switch
        {
            NetworkCategory.Private or NetworkCategory.Domain or NetworkCategory.Loopback => true,
            NetworkCategory.Public => IsAllowedPublic(network.NetworkId),
            _ => false,
        };
    }

    /// <summary>Checks a TCP connection by the local address it arrived at.</summary>
    public bool CheckConnection(Socket socket)
    {
        IPAddress? local;
        try
        {
            local = (socket.LocalEndPoint as IPEndPoint)?.Address;
        }
        catch (Exception e) when (e is SocketException or ObjectDisposedException)
        {
            return false;
        }
        return Check(Normalize(local), -1, discovery: false);
    }

    /// <summary>Checks a discovery request by the interface it arrived on (the destination is a broadcast address).</summary>
    public bool CheckDiscovery(IPAddress? destination, int interfaceIndex) => Check(Normalize(destination), interfaceIndex, discovery: true);

    private bool Check(IPAddress? local, int interfaceIndex, bool discovery)
    {
        if (!_source.Available)
            return true;
        NetworkProfile? network;
        try
        {
            network = _source.Find(local, interfaceIndex);
        }
        catch (Exception e)
        {
            // Never let a failing lookup open the gate.
            _log.Warn("network category lookup failed", e);
            network = null;
        }
        if (Allows(network))
            return true;
        OnBlocked(network, discovery);
        return false;
    }

    private void OnBlocked(NetworkProfile? network, bool discovery)
    {
        Interlocked.Increment(ref _blocked);
        var now = _time.GetUtcNow();
        var entry = new BlockedPeer(network, discovery, now);
        bool raise;
        bool log;
        lock (_gate)
        {
            raise = _lastBlocked?.Network?.NetworkId != network?.NetworkId || _lastBlocked?.Network?.Category != network?.Category;
            _lastBlocked = entry;
            log = now - _lastBlockLog > TimeSpan.FromMinutes(1);
            if (log)
                _lastBlockLog = now;
        }
        if (log)
        {
            // The network name (often the Wi-Fi name) is left out of the log file.
            var category = network?.Category.ToString() ?? "unknown";
            _log.Warn($"ignored a {(discovery ? "discovery request" : "connection")} on a {category} network; " +
                      "further ones are not logged for a minute");
        }
        if (raise)
        {
            try
            {
                Blocked?.Invoke(entry);
            }
            catch (Exception e)
            {
                _log.Error("blocked-network handler failed", e);
            }
        }
    }

    /// <summary>The user chose "Allow on this network" for a Public network.</summary>
    public void AllowPublic(Guid networkId)
    {
        if (networkId == Guid.Empty)
            return;
        IReadOnlyCollection<Guid> list;
        lock (_gate)
        {
            if (!_allowedPublic.Add(networkId))
                return;
            list = _allowedPublic.ToArray();
            _lastBlocked = null;
        }
        _log.Info("a Public network was allowed by the user");
        AllowedNetworksChanged?.Invoke(list);
    }

    public void DisallowPublic(Guid networkId)
    {
        IReadOnlyCollection<Guid> list;
        lock (_gate)
        {
            if (!_allowedPublic.Remove(networkId))
                return;
            list = _allowedPublic.ToArray();
        }
        _log.Info("a Public network is no longer allowed");
        AllowedNetworksChanged?.Invoke(list);
    }

    /// <summary>The connected networks with the gate's verdict, for the status window.</summary>
    public IReadOnlyList<(NetworkProfile Network, bool Allowed)> Describe()
    {
        if (!_source.Available)
            return Array.Empty<(NetworkProfile, bool)>();
        IReadOnlyList<NetworkProfile> networks;
        try
        {
            networks = _source.ConnectedNetworks();
        }
        catch (Exception e)
        {
            _log.Warn("could not list networks", e);
            return Array.Empty<(NetworkProfile, bool)>();
        }
        return networks.Select(n => (n, Allows(n))).ToArray();
    }

    private static IPAddress? Normalize(IPAddress? address) =>
        address is { AddressFamily: AddressFamily.InterNetworkV6, IsIPv4MappedToIPv6: true } ? address.MapToIPv4() : address;
}

public static class NetworkCategorySources
{
    /// <summary>The Network List Manager source on Windows; elsewhere a source that reports categories as unavailable.</summary>
    public static INetworkCategorySource CreateDefault(ICompanionLog log) =>
        OperatingSystem.IsWindows() ? new WindowsNetworkCategorySource(log) : new UnavailableNetworkSource();
}

/// <summary>A source for platforms (or failures) where Windows' categories cannot be read.</summary>
public sealed class UnavailableNetworkSource : INetworkCategorySource
{
    public bool Available => false;
    public NetworkProfile? Find(IPAddress? localAddress, int interfaceIndex = -1) => null;
    public IReadOnlyList<NetworkProfile> ConnectedNetworks() => Array.Empty<NetworkProfile>();
}

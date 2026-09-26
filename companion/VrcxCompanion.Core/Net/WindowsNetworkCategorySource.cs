using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Runtime.Versioning;
using VrcxCompanion.Core.Diagnostics;

namespace VrcxCompanion.Core.Net;

/// <summary>
/// Reads network categories from Windows' Network List Manager (INetworkListManager): each network connection names
/// its adapter and its network, and the network has a category (Public, Private, Domain). Adapters are matched to
/// .NET's <see cref="NetworkInterface"/> by id, which on Windows is the adapter GUID. The result is cached for a few
/// seconds and dropped when an address changes; a category the user switches in Windows Settings is picked up after at
/// most <see cref="CacheLifetime"/>. Only reads: it never changes a category.
/// </summary>
[SupportedOSPlatform("windows")]
public sealed class WindowsNetworkCategorySource : INetworkCategorySource, IDisposable
{
    public static readonly TimeSpan CacheLifetime = TimeSpan.FromSeconds(5);

    private static readonly Guid NetworkListManagerClsid = new("DCB00C01-570F-4A9B-8D69-199FDBA5723B");

    private readonly object _gate = new();
    private readonly ICompanionLog _log;
    private Snapshot? _snapshot;
    private long _attemptAt;
    private bool _attempted;
    private bool _everWorked;
    private bool _loggedFailure;

    public WindowsNetworkCategorySource(ICompanionLog log)
    {
        _log = log;
        NetworkChange.NetworkAddressChanged += OnAddressChanged;
    }

    /// <summary>
    /// False only while Windows has never answered. Once it has, a later failure keeps the last answer instead of
    /// opening the gate (an address the last answer does not know belongs to no network, which the gate refuses).
    /// </summary>
    public bool Available
    {
        get
        {
            Current();
            lock (_gate)
                return _everWorked;
        }
    }

    public NetworkProfile? Find(IPAddress? localAddress, int interfaceIndex = -1)
    {
        if (localAddress != null && IPAddress.IsLoopback(localAddress))
            return NetworkProfile.LoopbackProfile;
        var snapshot = Current();
        if (snapshot == null)
            return null;
        if (interfaceIndex >= 0 && snapshot.ByIndex.TryGetValue(interfaceIndex, out var byIndex))
            return byIndex;
        if (localAddress != null && snapshot.ByAddress.TryGetValue(localAddress, out var byAddress))
            return byAddress;
        return null;
    }

    public IReadOnlyList<NetworkProfile> ConnectedNetworks() => Current()?.Networks ?? Array.Empty<NetworkProfile>();

    private void OnAddressChanged(object? sender, EventArgs e)
    {
        // Read again on the next lookup; if that fails, the last answer stays in use.
        lock (_gate)
            _attempted = false;
    }

    private Snapshot? Current()
    {
        lock (_gate)
        {
            if (_attempted && Environment.TickCount64 - _attemptAt < (long)CacheLifetime.TotalMilliseconds)
                return _snapshot;
            _attempted = true;
            _attemptAt = Environment.TickCount64;
            try
            {
                _snapshot = Build();
                _everWorked = true;
                _loggedFailure = false;
            }
            catch (Exception e)
            {
                if (!_loggedFailure)
                {
                    _loggedFailure = true;
                    _log.Warn(_everWorked
                        ? "Windows did not report network categories this time; using the last answer"
                        : "Windows did not report network categories; relying on the local-address filter alone", e);
                }
            }
            return _snapshot;
        }
    }

    private static Snapshot Build()
    {
        // Adapter GUID -> network, from the Network List Manager.
        var byAdapter = new Dictionary<Guid, NetworkProfile>();
        var networks = new Dictionary<Guid, NetworkProfile>();
        var type = Type.GetTypeFromCLSID(NetworkListManagerClsid, throwOnError: true)!;
        var manager = (INetworkListManager)Activator.CreateInstance(type)!;
        IEnumNetworkConnections? connections = null;
        try
        {
            connections = manager.GetNetworkConnections();
            while (true)
            {
                var hr = connections.Next(1, out var connection, out var fetched);
                if (hr != 0 || fetched == 0 || connection == null)
                    break;
                INetwork? network = null;
                try
                {
                    var adapter = connection.GetAdapterId();
                    network = connection.GetNetwork();
                    var id = network.GetNetworkId();
                    if (!networks.TryGetValue(id, out var profile))
                    {
                        profile = new NetworkProfile(id, network.GetName() ?? "", MapCategory(network.GetCategory()));
                        networks[id] = profile;
                    }
                    byAdapter[adapter] = profile;
                }
                finally
                {
                    if (network != null)
                        Marshal.ReleaseComObject(network);
                    Marshal.ReleaseComObject(connection);
                }
            }
        }
        finally
        {
            if (connections != null)
                Marshal.ReleaseComObject(connections);
            Marshal.ReleaseComObject(manager);
        }

        // Adapters of those networks, through the adapter GUID (NetworkInterface.Id on Windows).
        var adapters = new List<(Guid Network, IPInterfaceProperties Props)>();
        var main = new HashSet<Guid>();
        foreach (var ni in NetworkInterface.GetAllNetworkInterfaces())
        {
            if (!Guid.TryParse(ni.Id, out var adapterId) || !byAdapter.TryGetValue(adapterId, out var profile))
                continue;
            IPInterfaceProperties props;
            try
            {
                props = ni.GetIPProperties();
            }
            catch (NetworkInformationException)
            {
                continue;
            }
            adapters.Add((profile.NetworkId, props));
            var hasGateway = props.GatewayAddresses.Any(g => !g.Address.Equals(IPAddress.Any) && !g.Address.Equals(IPAddress.IPv6Any));
            if (hasGateway && !LocalAddress.LooksVirtual(ni))
                main.Add(profile.NetworkId);
        }
        foreach (var id in main)
            networks[id] = networks[id] with { IsMain = true };

        // Interface index and unicast addresses -> network.
        var byIndex = new Dictionary<int, NetworkProfile>();
        var byAddress = new Dictionary<IPAddress, NetworkProfile>();
        foreach (var (networkId, props) in adapters)
        {
            var profile = networks[networkId];
            try
            {
                if (props.GetIPv4Properties() is { } v4)
                    byIndex[v4.Index] = profile;
            }
            catch (NetworkInformationException)
            {
            }
            try
            {
                if (props.GetIPv6Properties() is { } v6)
                    byIndex.TryAdd(v6.Index, profile);
            }
            catch (NetworkInformationException)
            {
            }
            foreach (var ua in props.UnicastAddresses)
                byAddress[ua.Address] = profile;
        }
        return new Snapshot(networks.Values.OrderByDescending(n => n.IsMain).ToArray(), byIndex, byAddress);
    }

    private static NetworkCategory MapCategory(int category) => category switch
    {
        0 => NetworkCategory.Public,
        1 => NetworkCategory.Private,
        2 => NetworkCategory.Domain,
        _ => NetworkCategory.Unknown,
    };

    public void Dispose() => NetworkChange.NetworkAddressChanged -= OnAddressChanged;

    private sealed record Snapshot(
        IReadOnlyList<NetworkProfile> Networks,
        IReadOnlyDictionary<int, NetworkProfile> ByIndex,
        IReadOnlyDictionary<IPAddress, NetworkProfile> ByAddress);

    // ---- COM interop (netlistmgr.idl). Methods are listed in vtable order up to the last one used. ----

    [ComImport, Guid("DCB00000-570F-4A9B-8D69-199FDBA5723B"), InterfaceType(ComInterfaceType.InterfaceIsDual)]
    private interface INetworkListManager
    {
        [return: MarshalAs(UnmanagedType.Interface)]
        object GetNetworks(int flags);

        [return: MarshalAs(UnmanagedType.Interface)]
        INetwork GetNetwork(Guid networkId);

        [return: MarshalAs(UnmanagedType.Interface)]
        IEnumNetworkConnections GetNetworkConnections();
    }

    [ComImport, Guid("DCB00002-570F-4A9B-8D69-199FDBA5723B"), InterfaceType(ComInterfaceType.InterfaceIsDual)]
    private interface INetwork
    {
        [return: MarshalAs(UnmanagedType.BStr)]
        string GetName();

        void SetName([MarshalAs(UnmanagedType.BStr)] string name);

        [return: MarshalAs(UnmanagedType.BStr)]
        string GetDescription();

        void SetDescription([MarshalAs(UnmanagedType.BStr)] string description);

        Guid GetNetworkId();

        int GetDomainType();

        [return: MarshalAs(UnmanagedType.Interface)]
        IEnumNetworkConnections GetNetworkConnections();

        void GetTimeCreatedAndConnected(out uint lowCreated, out uint highCreated, out uint lowConnected, out uint highConnected);

        short IsConnectedToInternet { get; }

        short IsConnected { get; }

        int GetConnectivity();

        int GetCategory();
    }

    [ComImport, Guid("DCB00005-570F-4A9B-8D69-199FDBA5723B"), InterfaceType(ComInterfaceType.InterfaceIsDual)]
    private interface INetworkConnection
    {
        [return: MarshalAs(UnmanagedType.Interface)]
        INetwork GetNetwork();

        short IsConnectedToInternet { get; }

        short IsConnected { get; }

        int GetConnectivity();

        Guid GetConnectionId();

        Guid GetAdapterId();
    }

    [ComImport, Guid("DCB00006-570F-4A9B-8D69-199FDBA5723B"), InterfaceType(ComInterfaceType.InterfaceIsDual)]
    private interface IEnumNetworkConnections
    {
        [return: MarshalAs(UnmanagedType.Interface)]
        object GetNewEnum();

        [PreserveSig]
        int Next(uint count, [MarshalAs(UnmanagedType.Interface)] out INetworkConnection? connection, out uint fetched);
    }
}

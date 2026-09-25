using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;

namespace VrcxCompanion.Core.Net;

/// <summary>The "local address" definition of PROTOCOL.md §1.</summary>
public static class LocalAddress
{
    /// <summary>
    /// IPv4 10/8, 172.16/12, 192.168/16, 169.254/16, 127/8, 100.64/10; IPv6 fe80::/10, fc00::/7, ::1; and
    /// IPv4-mapped IPv6 forms of the IPv4 ranges.
    /// </summary>
    public static bool IsLocal(IPAddress? address)
    {
        if (address is null)
            return false;
        if (address.AddressFamily == AddressFamily.InterNetworkV6 && address.IsIPv4MappedToIPv6)
            address = address.MapToIPv4();

        if (address.AddressFamily == AddressFamily.InterNetwork)
        {
            Span<byte> b = stackalloc byte[4];
            if (!address.TryWriteBytes(b, out _))
                return false;
            return b[0] == 10
                   || (b[0] == 172 && (b[1] & 0xF0) == 16)
                   || (b[0] == 192 && b[1] == 168)
                   || (b[0] == 169 && b[1] == 254)
                   || b[0] == 127
                   || (b[0] == 100 && (b[1] & 0xC0) == 64);
        }

        if (address.AddressFamily == AddressFamily.InterNetworkV6)
        {
            Span<byte> b = stackalloc byte[16];
            if (!address.TryWriteBytes(b, out _))
                return false;
            if (b[0] == 0xFE && (b[1] & 0xC0) == 0x80)
                return true; // fe80::/10
            if ((b[0] & 0xFE) == 0xFC)
                return true; // fc00::/7
            return address.Equals(IPAddress.IPv6Loopback);
        }

        return false;
    }

    /// <summary>
    /// Addresses the phone can use to reach this PC, for the pairing QR code and the pairing window: local IPv4
    /// addresses of interfaces that are up (interfaces with a default gateway first), then unique local IPv6
    /// addresses. Loopback and link-local IPv6 (which needs a scope id) are left out.
    /// </summary>
    public static IReadOnlyList<IPAddress> GetAdvertisedAddresses()
    {
        var ranked = new List<(int rank, int order, IPAddress address)>();
        var order = 0;
        NetworkInterface[] interfaces;
        try
        {
            interfaces = NetworkInterface.GetAllNetworkInterfaces();
        }
        catch (NetworkInformationException)
        {
            return Array.Empty<IPAddress>();
        }

        foreach (var ni in interfaces)
        {
            if (ni.OperationalStatus != OperationalStatus.Up || ni.NetworkInterfaceType == NetworkInterfaceType.Loopback)
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
            var hasGateway = props.GatewayAddresses.Any(g => !g.Address.Equals(IPAddress.Any) && !g.Address.Equals(IPAddress.IPv6Any));
            var virtualAdapter = LooksVirtual(ni);
            foreach (var ua in props.UnicastAddresses)
            {
                var a = ua.Address;
                if (IPAddress.IsLoopback(a) || !IsLocal(a))
                    continue;
                int rank;
                if (a.AddressFamily == AddressFamily.InterNetwork)
                {
                    var linkLocal = a.GetAddressBytes() is [169, 254, ..];
                    rank = linkLocal ? 4 : hasGateway && !virtualAdapter ? 0 : virtualAdapter ? 3 : 1;
                }
                else
                {
                    if (a.IsIPv6LinkLocal)
                        continue;
                    rank = hasGateway && !virtualAdapter ? 2 : 5;
                }
                ranked.Add((rank, order++, a));
            }
        }
        return ranked.OrderBy(r => r.rank).ThenBy(r => r.order).Select(r => r.address).Distinct().ToArray();
    }

    private static bool LooksVirtual(NetworkInterface ni)
    {
        var text = (ni.Description + " " + ni.Name).ToLowerInvariant();
        return text.Contains("virtual") || text.Contains("hyper-v") || text.Contains("vethernet") || text.Contains("vmware") ||
               text.Contains("virtualbox") || text.Contains("wsl") || text.Contains("tap-") || text.Contains("wireguard") ||
               text.Contains("tailscale") || text.Contains("zerotier");
    }
}

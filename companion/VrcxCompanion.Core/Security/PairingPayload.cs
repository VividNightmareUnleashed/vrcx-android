using System.Net;
using System.Net.Sockets;

namespace VrcxCompanion.Core.Security;

/// <summary>The pairing QR payload (PROTOCOL.md §5.2).</summary>
public static class PairingPayload
{
    /// <summary>
    /// <c>vrcxc://pair?v=1&amp;id=&lt;companionId&gt;&amp;n=&lt;url-encoded name&gt;&amp;h=&lt;comma-separated addresses&gt;&amp;p=&lt;port&gt;&amp;fp=&lt;fp&gt;&amp;c=&lt;code&gt;</c>
    /// </summary>
    public static string Build(string companionId, string machineName, IEnumerable<IPAddress> hosts, int port, string fingerprint,
        string normalizedCode)
    {
        var h = string.Join(",", hosts.Select(FormatHost));
        return "vrcxc://pair?v=1" +
               "&id=" + Uri.EscapeDataString(companionId) +
               "&n=" + Uri.EscapeDataString(machineName) +
               "&h=" + h +
               "&p=" + port.ToString(System.Globalization.CultureInfo.InvariantCulture) +
               "&fp=" + fingerprint +
               "&c=" + normalizedCode;
    }

    /// <summary>IPv4 dotted, IPv6 compressed without brackets or scope id.</summary>
    public static string FormatHost(IPAddress address)
    {
        if (address.AddressFamily == AddressFamily.InterNetworkV6 && address.IsIPv4MappedToIPv6)
            address = address.MapToIPv4();
        var s = address.ToString();
        var scope = s.IndexOf('%');
        return scope >= 0 ? s[..scope] : s;
    }
}

using System.Globalization;

namespace VrcxCompanion.Core.Settings;

/// <summary>
/// The Windows Firewall rules the tray menu offers to add (PROTOCOL.md §1): inbound TCP (session) and UDP (discovery),
/// profile Private, remote addresses LocalSubnet. Run once, elevated, through <c>cmd.exe /c</c>.
/// </summary>
public static class FirewallCommand
{
    public const string TcpRuleName = "VRCX Companion (TCP)";
    public const string UdpRuleName = "VRCX Companion (UDP discovery)";

    /// <summary>The argument string for <c>cmd.exe</c>: removes older copies of the rules, then adds both.</summary>
    public static string BuildCmdArguments(int tcpPort, int udpPort)
    {
        var tcp = tcpPort.ToString(CultureInfo.InvariantCulture);
        var udp = udpPort.ToString(CultureInfo.InvariantCulture);
        var commands = string.Join(" ",
            $"netsh advfirewall firewall delete rule name=\"{TcpRuleName}\" >nul 2>&1 &",
            $"netsh advfirewall firewall delete rule name=\"{UdpRuleName}\" >nul 2>&1 &",
            $"netsh advfirewall firewall add rule name=\"{TcpRuleName}\" dir=in action=allow protocol=TCP localport={tcp} profile=private remoteip=localsubnet &&",
            $"netsh advfirewall firewall add rule name=\"{UdpRuleName}\" dir=in action=allow protocol=UDP localport={udp} profile=private remoteip=localsubnet");
        return "/c \"" + commands + "\"";
    }
}

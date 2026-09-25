namespace VrcxCompanion.Core.Protocol;

/// <summary>Constants from docs/PROTOCOL.md.</summary>
public static class ProtocolConstants
{
    public const int Version = 1;
    public const int DefaultTcpPort = 49460;
    public const int DefaultDiscoveryPort = 49461;

    /// <summary>Maximum value of the frame length field (type + payload).</summary>
    public const int MaxFrameLength = 1_048_576;

    public const byte FrameTypeControl = 0x01;
    public const byte FrameTypeData = 0x02;

    /// <summary>The companion reads log chunks of at most 256 KiB.</summary>
    public const int MaxChunkBytes = 256 * 1024;

    /// <summary>At most 4 MiB of unacknowledged data in flight.</summary>
    public const long MaxBytesInFlight = 4L * 1024 * 1024;

    public static readonly TimeSpan PollInterval = TimeSpan.FromMilliseconds(1000);
    public static readonly TimeSpan HeartbeatInterval = TimeSpan.FromSeconds(5);
    public static readonly TimeSpan PairingWindowDuration = TimeSpan.FromMinutes(5);
    public const int PairingMaxFailedAttempts = 5;

    /// <summary>
    /// Receive timeout on the companion side. PROTOCOL.md 5.9 says "either side closes after 20 s without receiving
    /// anything", but the phone only pings every 30 s when idle, so a 20 s limit on the companion would drop idle
    /// connections. The companion waits for two missed pings instead.
    /// </summary>
    public static readonly TimeSpan ReceiveTimeout = TimeSpan.FromSeconds(65);

    /// <summary>Time allowed between the TCP accept and a valid pair/auth message.</summary>
    public static readonly TimeSpan HandshakeTimeout = TimeSpan.FromSeconds(30);

    public const string DiscoverRequestType = "vrcx-discover";
    public const string DiscoverReplyType = "vrcx-companion";
    public const string PairUriScheme = "vrcxc";
}

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

    /// <summary>
    /// PROTOCOL.md §5.9: a <c>heartbeat</c> goes out when nothing else was sent for this long, so the phone hears from
    /// the companion at least this often.
    /// </summary>
    public static readonly TimeSpan HeartbeatInterval = TimeSpan.FromSeconds(5);

    /// <summary>The heartbeat interval while the phone is idle (PROTOCOL.md §5.11).</summary>
    public static readonly TimeSpan IdleHeartbeatInterval = TimeSpan.FromSeconds(30);

    /// <summary>
    /// While the phone is idle, log growth is sent at most this often (PROTOCOL.md §5.11). Process changes, the
    /// subscribe sequence, fetch answers and <c>info</c> are not held back.
    /// </summary>
    public static readonly TimeSpan IdleFlushInterval = TimeSpan.FromSeconds(10);

    public static readonly TimeSpan PairingWindowDuration = TimeSpan.FromMinutes(5);
    public const int PairingMaxFailedAttempts = 5;

    /// <summary>
    /// PROTOCOL.md §5.9: the companion closes a session after 20 s without receiving anything. The phone keeps an
    /// idle connection inside this limit by answering a received frame (ping, or a pending ack) whenever it has sent
    /// nothing for 10 s, so a phone that vanished without closing (Wi-Fi drop, sleep) is dropped after 20 s.
    /// </summary>
    public static readonly TimeSpan ReceiveTimeout = TimeSpan.FromSeconds(20);

    /// <summary>The receive timeout while the phone is idle (PROTOCOL.md §5.11): it answers only every 45 s.</summary>
    public static readonly TimeSpan IdleReceiveTimeout = TimeSpan.FromSeconds(90);

    /// <summary>
    /// Time allowed between the TCP accept and a valid pair/auth message (TLS handshake included). The phone sends
    /// pair or auth right after <c>hello</c>, so this only has to cover a slow TLS handshake.
    /// </summary>
    public static readonly TimeSpan HandshakeTimeout = TimeSpan.FromSeconds(10);

    /// <summary>Connections from one address that may be in the handshake at the same time (PROTOCOL.md §1).</summary>
    public const int MaxHandshakesPerPeer = 3;

    /// <summary>
    /// Connections in the handshake in total. When they are all taken, a new connection replaces the oldest one of
    /// the address holding the most.
    /// </summary>
    public const int MaxHandshakes = 32;

    /// <summary>Authenticated sessions; a separate budget, so handshakes can never lock paired phones out.</summary>
    public const int MaxSessions = 16;

    public const string DiscoverRequestType = "vrcx-discover";
    public const string DiscoverReplyType = "vrcx-companion";
    public const string PairUriScheme = "vrcxc";
}

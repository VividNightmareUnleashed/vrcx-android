using System.Net;
using System.Net.Security;
using System.Net.Sockets;
using System.Reflection;
using VrcxCompanion.Core.Diagnostics;
using VrcxCompanion.Core.Logs;
using VrcxCompanion.Core.Net;
using VrcxCompanion.Core.Processes;
using VrcxCompanion.Core.Protocol;
using VrcxCompanion.Core.Security;
using VrcxCompanion.Core.Settings;
using VrcxCompanion.Core.Sync;

namespace VrcxCompanion.Core;

public sealed class CompanionHostOptions
{
    public required AppPaths Paths { get; init; }

    /// <summary>Log directory; defaults to <see cref="LogDirectoryLocator.DefaultPath"/>.</summary>
    public string? LogDirectory { get; init; }

    /// <summary>TCP bind address. Default: all interfaces (dual-mode IPv6).</summary>
    public IPAddress BindAddress { get; init; } = IPAddress.IPv6Any;

    /// <summary>UDP bind address. Default: <see cref="IPAddress.Any"/>, or the TCP bind address when that is not a wildcard.</summary>
    public IPAddress? DiscoveryBindAddress { get; init; }

    public int TcpPort { get; init; } = ProtocolConstants.DefaultTcpPort;
    public int DiscoveryPort { get; init; } = ProtocolConstants.DefaultDiscoveryPort;
    public bool EnableDiscovery { get; init; } = true;
    public string MachineName { get; init; } = Environment.MachineName;
    public string CompanionVersion { get; init; } = DefaultVersion();

    /// <summary>Protection for identity.bin and devices.bin. Default: DPAPI (CurrentUser).</summary>
    public ISecretProtector? Protector { get; init; }

    public IProcessProbe? ProcessProbe { get; init; }
    public ICompanionLog Log { get; init; } = NullLog.Instance;
    public TimeProvider Time { get; init; } = TimeProvider.System;

    /// <summary>An identity to use instead of identity.bin (tests, self test). The host does not dispose it.</summary>
    public CompanionIdentity? Identity { get; init; }

    /// <summary>Keep paired devices in memory only (self test).</summary>
    public bool InMemoryDevices { get; init; }

    public bool UseFileSystemWatcher { get; init; } = true;
    public TimeSpan PollInterval { get; init; } = ProtocolConstants.PollInterval;
    public TimeSpan HeartbeatInterval { get; init; } = ProtocolConstants.HeartbeatInterval;
    public TimeSpan ReceiveTimeout { get; init; } = ProtocolConstants.ReceiveTimeout;
    public TimeSpan HandshakeTimeout { get; init; } = ProtocolConstants.HandshakeTimeout;
    public TimeSpan TimeZoneCheckInterval { get; init; } = TimeSpan.FromSeconds(60);
    public TimeSpan PairingWindowDuration { get; init; } = ProtocolConstants.PairingWindowDuration;

    public static string DefaultVersion()
    {
        var v = typeof(CompanionHost).Assembly.GetName().Version;
        return v == null ? "1.0.0" : $"{v.Major}.{v.Minor}.{Math.Max(0, v.Build)}";
    }
}

public sealed record SessionStatus(
    string DeviceId,
    string DeviceName,
    string RemoteAddress,
    DateTimeOffset ConnectedAtUtc,
    long BytesSent,
    long RawLogBytesSent,
    bool Subscribed,
    bool Syncing);

public sealed record CompanionStatus(
    bool Listening,
    string? ListenError,
    int TcpPort,
    bool DiscoveryActive,
    string? DiscoveryError,
    int DiscoveryPort,
    IReadOnlyList<SessionStatus> Sessions,
    ProcessState? Process,
    string LogDirectory,
    bool? LogDirectoryExists,
    long TotalBytesSent,
    bool PairingOpen);

/// <summary>
/// Composition root of the companion: identity, paired devices, pairing window, log tailer, process monitor, sync
/// engine, TLS server and discovery responder. It makes no outbound connections.
/// </summary>
public sealed class CompanionHost : IAsyncDisposable
{
    private readonly CompanionHostOptions _options;
    private readonly ICompanionLog _log;
    private readonly bool _ownsIdentity;
    private readonly SslStreamCertificateContext _certContext;
    private readonly List<Task> _tasks = new();
    private readonly object _tzGate = new();
    private CancellationTokenSource? _cts;
    private CompanionServer? _server;
    private DiscoveryResponder? _discovery;
    private TimeZoneSnapshot _tz;
    private long _bytesSent;

    private CompanionHost(CompanionHostOptions options)
    {
        _options = options;
        _log = options.Log;
        Directory.CreateDirectory(options.Paths.DataDirectory);

        if (options.Identity != null)
        {
            Identity = options.Identity;
        }
        else
        {
            Identity = CompanionIdentity.LoadOrCreate(options.Paths.IdentityFile, options.Protector ?? new DpapiProtector("identity"),
                options.MachineName, _log);
            _ownsIdentity = true;
        }
        Devices = new DeviceStore(options.InMemoryDevices ? null : options.Paths.DevicesFile,
            options.Protector ?? new DpapiProtector("devices"), _log, options.Time);
        Pairing = new PairingManager(Devices, _log, options.Time, options.PairingWindowDuration);
        var tailer = new LogDirectoryTailer(options.LogDirectory ?? LogDirectoryLocator.DefaultPath, _log, options.Time,
            options.UseFileSystemWatcher);
        Engine = new SyncEngine(tailer, options.ProcessProbe ?? new SystemProcessProbe(), _log, options.Time)
        {
            PollInterval = options.PollInterval,
        };
        Engine.DirectoryExistsChanged += _ => BroadcastInfo();
        _certContext = SslStreamCertificateContext.Create(Identity.Certificate, additionalCertificates: null, offline: true);
        _tz = TimeZoneReader.Capture(TimeZoneInfo.Local, options.Time.GetUtcNow());
    }

    public static CompanionHost Create(CompanionHostOptions options) => new(options);

    public CompanionIdentity Identity { get; }
    public DeviceStore Devices { get; }
    public PairingManager Pairing { get; }
    public SyncEngine Engine { get; }
    public string MachineName => _options.MachineName;
    public int TcpPort => _server?.Port ?? _options.TcpPort;
    public int DiscoveryPort => _discovery?.Port ?? _options.DiscoveryPort;
    public bool Listening { get; private set; }
    public string? ListenError { get; private set; }
    public bool DiscoveryActive { get; private set; }
    public string? DiscoveryError { get; private set; }
    public long TotalBytesSent => Interlocked.Read(ref _bytesSent);

    /// <summary>Binds the listeners and starts the loops. Bind failures are reported through the status, not thrown.</summary>
    public void Start()
    {
        if (_cts != null)
            throw new InvalidOperationException("already started");
        _cts = new CancellationTokenSource();
        var ct = _cts.Token;
        var context = new ServerContext
        {
            Identity = Identity,
            CertificateContext = _certContext,
            MachineName = _options.MachineName,
            Pairing = Pairing,
            Devices = Devices,
            Engine = Engine,
            Info = CurrentInfo,
            Log = _log,
            Time = _options.Time,
            CountBytesSent = n => Interlocked.Add(ref _bytesSent, n),
            HeartbeatInterval = _options.HeartbeatInterval,
            ReceiveTimeout = _options.ReceiveTimeout,
            HandshakeTimeout = _options.HandshakeTimeout,
        };

        _server = new CompanionServer(_options.BindAddress, _options.TcpPort,
            (socket, token) => new ClientConnection(socket, context).RunAsync(token), _log);
        try
        {
            _server.Start();
            Listening = true;
            _log.Info($"listening on TCP {_server.Port}");
            _tasks.Add(_server.RunAcceptLoopAsync(ct));
        }
        catch (SocketException e)
        {
            ListenError = $"TCP port {_options.TcpPort}: {e.SocketErrorCode}";
            _log.Error("could not listen: " + ListenError);
        }

        if (_options.EnableDiscovery)
        {
            var bind = _options.DiscoveryBindAddress ??
                       (_options.BindAddress.Equals(IPAddress.IPv6Any) || _options.BindAddress.Equals(IPAddress.Any)
                           ? IPAddress.Any
                           : _options.BindAddress);
            _discovery = new DiscoveryResponder(bind, _options.DiscoveryPort, BuildDiscoveryReply, _log);
            try
            {
                _discovery.Start();
                DiscoveryActive = true;
                _log.Info($"discovery on UDP {_discovery.Port}");
                _tasks.Add(_discovery.RunAsync(ct));
            }
            catch (SocketException e)
            {
                DiscoveryError = $"UDP port {_options.DiscoveryPort}: {e.SocketErrorCode}";
                _log.Error("discovery unavailable: " + DiscoveryError);
            }
        }

        _tasks.Add(Engine.RunAsync(ct));
        _tasks.Add(TimeZoneLoopAsync(ct));
    }

    public byte[] BuildDiscoveryReply() =>
        ControlMessages.DiscoveryReply(Identity.CompanionId, _options.MachineName, TcpPort, Identity.Fingerprint, Pairing.IsOpen);

    public InfoSnapshot CurrentInfo()
    {
        TimeZoneSnapshot tz;
        lock (_tzGate)
            tz = _tz;
        var exists = Engine.LastDirectoryExists ?? SafeDirectoryExists(Engine.LogDirectory);
        return new InfoSnapshot(_options.CompanionVersion, _options.MachineName, tz, Engine.LogDirectory, exists);
    }

    private static bool SafeDirectoryExists(string path)
    {
        var di = new DirectoryInfo(path);
        di.Refresh();
        return LogDirectoryLocator.IsUsable(di);
    }

    /// <summary>Opens a pairing window and returns it.</summary>
    public PairingWindowInfo OpenPairingWindow() => Pairing.Open();

    /// <summary>QR payload for a pairing window.</summary>
    public string BuildPairingUri(PairingWindowInfo window, IReadOnlyList<IPAddress>? addresses = null) =>
        PairingPayload.Build(Identity.CompanionId, _options.MachineName, addresses ?? LocalAddress.GetAdvertisedAddresses(), TcpPort,
            Identity.Fingerprint, window.Code);

    /// <summary>Forgets a device and closes its sessions; its next connection gets <c>authFail</c>.</summary>
    public void RevokeDevice(string deviceId)
    {
        if (Devices.Remove(deviceId))
            _log.Info("device removed");
        Engine.CloseDevice(deviceId, "revoked");
    }

    /// <summary>Called on <c>SystemEvents.TimeChanged</c>.</summary>
    public void NotifyTimeChanged() => ThreadPool.QueueUserWorkItem(_ => CheckTimeZone());

    public IDisposable ObserveStatus() => Engine.ObserveStatus();

    public CompanionStatus GetStatus()
    {
        var sessions = Engine.Sessions.Select(s => new SessionStatus(s.DeviceId, s.DeviceName, s.RemoteAddress, s.ConnectedAtUtc,
            s.BytesSent, s.RawLogBytesSent, s.Subscribed, s.Syncing)).ToArray();
        return new CompanionStatus(Listening, ListenError, TcpPort, DiscoveryActive, DiscoveryError, DiscoveryPort, sessions,
            Engine.LastProcessState, Engine.LogDirectory, Engine.LastDirectoryExists, TotalBytesSent, Pairing.IsOpen);
    }

    private void CheckTimeZone()
    {
        try
        {
            var tz = TimeZoneReader.CaptureLocal(_options.Time.GetUtcNow());
            bool changed;
            lock (_tzGate)
            {
                changed = tz != _tz;
                _tz = tz;
            }
            if (changed)
            {
                _log.Info($"time zone changed ({tz.WindowsId}, UTC offset {tz.CurrentUtcOffsetMin} min)");
                BroadcastInfo();
            }
        }
        catch (Exception e)
        {
            _log.Warn("time zone check failed", e);
        }
    }

    private void BroadcastInfo() =>
        Engine.Broadcast("info", () => ControlMessages.Info(CurrentInfo(), _options.Time.GetUtcNow()));

    private async Task TimeZoneLoopAsync(CancellationToken ct)
    {
        using var timer = new PeriodicTimer(_options.TimeZoneCheckInterval, _options.Time);
        try
        {
            while (await timer.WaitForNextTickAsync(ct).ConfigureAwait(false))
            {
                if (Engine.Sessions.Count > 0)
                    CheckTimeZone();
            }
        }
        catch (OperationCanceledException)
        {
        }
    }

    public async ValueTask DisposeAsync()
    {
        var cts = _cts;
        if (cts != null)
        {
            cts.Cancel();
            _server?.Dispose();
            _discovery?.Dispose();
            foreach (var s in Engine.Sessions)
                s.RequestClose("shutdown");
            try
            {
                await Task.WhenAll(_tasks).WaitAsync(TimeSpan.FromSeconds(5)).ConfigureAwait(false);
            }
            catch (Exception e) when (e is OperationCanceledException or TimeoutException)
            {
            }
            var deadline = Environment.TickCount64 + 3000;
            while ((_server?.ActiveConnections ?? 0) > 0 && Environment.TickCount64 < deadline)
                await Task.Delay(20).ConfigureAwait(false);
            cts.Dispose();
        }
        Engine.Dispose();
        if (_ownsIdentity)
            Identity.Dispose();
        _log.Info("stopped");
    }
}

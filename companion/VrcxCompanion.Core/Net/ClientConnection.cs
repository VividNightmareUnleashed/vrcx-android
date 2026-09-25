using System.Net;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Authentication;
using System.Text.Json;
using VrcxCompanion.Core.Diagnostics;
using VrcxCompanion.Core.Protocol;
using VrcxCompanion.Core.Security;
using VrcxCompanion.Core.Sync;

namespace VrcxCompanion.Core.Net;

/// <summary>What a connection needs from the host.</summary>
internal sealed class ServerContext
{
    public required CompanionIdentity Identity { get; init; }
    public required SslStreamCertificateContext CertificateContext { get; init; }
    public required string MachineName { get; init; }
    public required PairingManager Pairing { get; init; }
    public required DeviceStore Devices { get; init; }
    public required SyncEngine Engine { get; init; }
    public required Func<InfoSnapshot> Info { get; init; }
    public required ICompanionLog Log { get; init; }
    public required TimeProvider Time { get; init; }
    public required Action<long> CountBytesSent { get; init; }
    public TimeSpan HeartbeatInterval { get; init; } = ProtocolConstants.HeartbeatInterval;
    public TimeSpan ReceiveTimeout { get; init; } = ProtocolConstants.ReceiveTimeout;
    public TimeSpan HandshakeTimeout { get; init; } = ProtocolConstants.HandshakeTimeout;
    public int MaxHaveEntries { get; init; } = 100_000;
}

/// <summary>Serializes frame writes to the TLS stream and counts bytes.</summary>
internal sealed class StreamFrameSink : IFrameSink
{
    private readonly SemaphoreSlim _lock = new(1, 1);
    private readonly Stream _stream;
    private readonly Action<int> _onWrite;

    public StreamFrameSink(Stream stream, Action<int> onWrite)
    {
        _stream = stream;
        _onWrite = onWrite;
    }

    public async ValueTask WriteFrameAsync(ReadOnlyMemory<byte> frame, CancellationToken cancellationToken)
    {
        await _lock.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            await _stream.WriteAsync(frame, cancellationToken).ConfigureAwait(false);
            _onWrite(frame.Length);
        }
        finally
        {
            _lock.Release();
        }
    }
}

/// <summary>One accepted TCP connection: TLS, hello, pair/auth, then the session (PROTOCOL.md §5).</summary>
internal sealed class ClientConnection
{
    private readonly Socket _socket;
    private readonly ServerContext _ctx;
    private readonly string _remote;
    private StreamSession? _session;

    public ClientConnection(Socket socket, ServerContext context)
    {
        _socket = socket;
        _ctx = context;
        _remote = (socket.RemoteEndPoint as IPEndPoint)?.Address.ToString() ?? "?";
    }

    public async Task RunAsync(CancellationToken serverToken)
    {
        using var cts = CancellationTokenSource.CreateLinkedTokenSource(serverToken);
        var log = _ctx.Log;
        var stream = new NetworkStream(_socket, ownsSocket: true);
        var ssl = new SslStream(stream, leaveInnerStreamOpen: false);
        Task? writer = null;
        Task? heartbeat = null;
        try
        {
            _socket.NoDelay = true;
            cts.CancelAfter(_ctx.HandshakeTimeout);
            await ssl.AuthenticateAsServerAsync(new SslServerAuthenticationOptions
            {
                ServerCertificateContext = _ctx.CertificateContext,
                ClientCertificateRequired = false,
                EnabledSslProtocols = SslProtocols.Tls12 | SslProtocols.Tls13,
                CertificateRevocationCheckMode = System.Security.Cryptography.X509Certificates.X509RevocationMode.NoCheck,
                AllowRenegotiation = false,
            }, cts.Token).ConfigureAwait(false);

            var sink = new StreamFrameSink(ssl, n =>
            {
                _session?.RecordWrite(n);
                _ctx.CountBytesSent(n);
            });
            var helloNonce = Base64Url.RandomBytes(32);
            await sink.WriteFrameAsync(FrameCodec.EncodeControl(
                ControlMessages.Hello(_ctx.Identity.CompanionId, _ctx.MachineName, helloNonce, _ctx.Pairing.IsOpen)), cts.Token).ConfigureAwait(false);

            var session = await HandshakeAsync(ssl, sink, helloNonce, cts.Token).ConfigureAwait(false);
            if (session == null)
                return;
            _session = session;
            cts.CancelAfter(Timeout.InfiniteTimeSpan);

            _ctx.Engine.AddSession(session);
            log.Info($"session started ({_remote})");
            // Built after the session is registered: a directory change observed from here on is either in this
            // info or makes the host queue another one (sent after this frame, as the writer starts below).
            var info = _ctx.Info();
            session.ReportedDirExists = info.DirExists;
            await sink.WriteFrameAsync(FrameCodec.EncodeControl(ControlMessages.Info(info, _ctx.Time.GetUtcNow())), cts.Token)
                .ConfigureAwait(false);

            using var closeRegistration = session.Closed.Register(() => SafeCancel(cts));
            writer = RunGuardedAsync(() => session.RunWriterAsync(sink, cts.Token), cts);
            heartbeat = RunGuardedAsync(() => HeartbeatLoopAsync(sink, cts.Token), cts);
            await ReadLoopAsync(ssl, session, cts).ConfigureAwait(false);
        }
        catch (OperationCanceledException)
        {
            if (_session == null && !serverToken.IsCancellationRequested)
                log.Info($"connection from {_remote} closed during the handshake (timeout)");
        }
        catch (AuthenticationException e)
        {
            log.Info($"TLS handshake with {_remote} failed: {e.GetType().Name}");
        }
        catch (ProtocolException e)
        {
            log.Warn($"protocol error from {_remote}: {e.Message}");
        }
        catch (Exception e) when (e is IOException or SocketException or ObjectDisposedException or EndOfStreamException)
        {
            log.Info($"connection from {_remote} ended: {e.GetType().Name}");
        }
        catch (Exception e)
        {
            log.Error($"connection from {_remote} failed", e);
        }
        finally
        {
            SafeCancel(cts);
            if (writer != null)
                await writer.ConfigureAwait(false);
            if (heartbeat != null)
                await heartbeat.ConfigureAwait(false);
            if (_session != null)
            {
                _ctx.Engine.RemoveSession(_session);
                _ctx.Devices.Touch(_session.DeviceId);
                log.Info($"session ended ({_remote}{(_session.CloseReason is { } r ? ", " + r : "")}, {_session.BytesSent} bytes sent)");
            }
            await ssl.DisposeAsync().ConfigureAwait(false);
            _socket.Dispose();
        }
    }

    /// <summary>Waits for <c>pair</c> or <c>auth</c>. Returns the authenticated session, or null after a failure reply.</summary>
    private async Task<StreamSession?> HandshakeAsync(SslStream ssl, IFrameSink sink, string helloNonce, CancellationToken ct)
    {
        while (true)
        {
            var frame = await FrameCodec.ReadFrameAsync(ssl, ct).ConfigureAwait(false);
            if (frame == null)
                return null;
            if (!frame.Value.IsControl || !ControlMessages.TryParse(frame.Value.Payload, out var doc, out var type))
                continue;
            using (doc)
            {
                var root = doc!.RootElement;
                switch (type)
                {
                    case "pair":
                    {
                        var result = _ctx.Pairing.TryPair(_ctx.Identity.Fingerprint, helloNonce, root);
                        if (!result.Success)
                        {
                            _ctx.Log.Info($"pairing from {_remote} refused ({result.FailReason})");
                            await sink.WriteFrameAsync(FrameCodec.EncodeControl(ControlMessages.PairFail(result.FailReason!)), ct).ConfigureAwait(false);
                            return null;
                        }
                        await sink.WriteFrameAsync(FrameCodec.EncodeControl(ControlMessages.Paired(result.Token!, result.ServerProof!)), ct)
                            .ConfigureAwait(false);
                        _ctx.Log.Info($"device paired from {_remote}");
                        return NewSession(result.DeviceId!, result.DeviceName!);
                    }
                    case "auth":
                    {
                        var deviceId = root.GetString("deviceId");
                        var token = root.GetString("token");
                        if (!_ctx.Devices.Verify(deviceId, token))
                        {
                            _ctx.Log.Info($"authentication from {_remote} refused");
                            await sink.WriteFrameAsync(FrameCodec.EncodeControl(ControlMessages.AuthFail()), ct).ConfigureAwait(false);
                            return null;
                        }
                        await sink.WriteFrameAsync(FrameCodec.EncodeControl(ControlMessages.AuthOk()), ct).ConfigureAwait(false);
                        var device = _ctx.Devices.Find(deviceId!);
                        return NewSession(deviceId!, device?.DeviceName ?? "");
                    }
                    case "subscribe" or "fetch" or "ack":
                        throw new ProtocolException($"'{type}' before authentication");
                    default:
                        continue; // ping and unknown types are ignored
                }
            }
        }
    }

    private StreamSession NewSession(string deviceId, string deviceName) =>
        new(deviceId, deviceName, _remote, _ctx.Log);

    private async Task ReadLoopAsync(SslStream ssl, StreamSession session, CancellationTokenSource cts)
    {
        while (!cts.IsCancellationRequested)
        {
            cts.CancelAfter(_ctx.ReceiveTimeout);
            var frame = await FrameCodec.ReadFrameAsync(ssl, cts.Token).ConfigureAwait(false);
            if (frame == null)
                return;
            if (!frame.Value.IsControl || !ControlMessages.TryParse(frame.Value.Payload, out var doc, out var type))
                continue;
            using (doc)
                Handle(session, type!, doc!.RootElement);
        }
    }

    private void Handle(StreamSession session, string type, JsonElement root)
    {
        switch (type)
        {
            case "subscribe":
            {
                var since = root.GetInt64OrNull("sinceUtcTicks") ?? 0;
                var have = new List<HaveEntry>();
                if (root.TryGetProperty("have", out var arr) && arr.ValueKind == JsonValueKind.Array)
                {
                    foreach (var e in arr.EnumerateArray())
                    {
                        if (have.Count >= _ctx.MaxHaveEntries)
                            break;
                        var name = e.GetString("name");
                        var fileId = e.GetString("fileId");
                        var length = e.GetInt64OrNull("length");
                        if (name != null && fileId != null && length is >= 0)
                            have.Add(new HaveEntry(name, fileId, length.Value));
                    }
                }
                _ctx.Engine.Subscribe(session, since, have);
                break;
            }
            case "ack":
            {
                if (root.GetInt64OrNull("bytes") is { } bytes)
                    session.OnAck(bytes);
                break;
            }
            case "fetch":
            {
                var name = root.GetString("name");
                var fileId = root.GetString("fileId");
                var from = root.GetInt64OrNull("fromOffset");
                if (name != null && fileId != null && from is >= 0)
                    _ctx.Engine.Fetch(session, name, fileId, from.Value);
                break;
            }
            default:
                // ping, and pair/auth on an authenticated connection, and unknown types: ignored.
                break;
        }
    }

    private async Task HeartbeatLoopAsync(IFrameSink sink, CancellationToken ct)
    {
        using var timer = new PeriodicTimer(_ctx.HeartbeatInterval, _ctx.Time);
        while (await timer.WaitForNextTickAsync(ct).ConfigureAwait(false))
            await sink.WriteFrameAsync(FrameCodec.EncodeControl(ControlMessages.Heartbeat(_ctx.Time.GetUtcNow())), ct).ConfigureAwait(false);
    }

    /// <summary>Runs a background loop; any failure closes the connection. Never throws.</summary>
    private async Task RunGuardedAsync(Func<Task> loop, CancellationTokenSource cts)
    {
        try
        {
            await loop().ConfigureAwait(false);
        }
        catch (OperationCanceledException)
        {
        }
        catch (Exception e) when (e is IOException or SocketException or ObjectDisposedException or InvalidOperationException)
        {
            _ctx.Log.Debug($"connection to {_remote}: write failed ({e.GetType().Name})");
        }
        catch (Exception e)
        {
            _ctx.Log.Error($"connection to {_remote}: writer failed", e);
        }
        finally
        {
            SafeCancel(cts);
        }
    }

    private static void SafeCancel(CancellationTokenSource cts)
    {
        try
        {
            cts.Cancel();
        }
        catch (ObjectDisposedException)
        {
        }
    }
}

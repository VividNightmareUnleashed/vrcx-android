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
    public TimeSpan IdleHeartbeatInterval { get; init; } = ProtocolConstants.IdleHeartbeatInterval;
    public TimeSpan ReceiveTimeout { get; init; } = ProtocolConstants.ReceiveTimeout;
    public TimeSpan IdleReceiveTimeout { get; init; } = ProtocolConstants.IdleReceiveTimeout;
    public TimeSpan HandshakeTimeout { get; init; } = ProtocolConstants.HandshakeTimeout;
    public int MaxHaveEntries { get; init; } = 100_000;
}

/// <summary>Serializes frame writes to the TLS stream, counts bytes and remembers when the last frame went out.</summary>
internal sealed class StreamFrameSink : IFrameSink
{
    private readonly SemaphoreSlim _lock = new(1, 1);
    private readonly Stream _stream;
    private readonly Action<int> _onWrite;
    private readonly TimeProvider _time;
    private long _lastWrite;

    public StreamFrameSink(Stream stream, Action<int> onWrite, TimeProvider time)
    {
        _stream = stream;
        _onWrite = onWrite;
        _time = time;
        _lastWrite = time.GetTimestamp();
    }

    /// <summary>Time since the last frame was written (or since the sink was created).</summary>
    public TimeSpan SinceLastWrite => _time.GetElapsedTime(Interlocked.Read(ref _lastWrite));

    public async ValueTask WriteFrameAsync(ReadOnlyMemory<byte> frame, CancellationToken cancellationToken)
    {
        await _lock.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            await _stream.WriteAsync(frame, cancellationToken).ConfigureAwait(false);
            Interlocked.Exchange(ref _lastWrite, _time.GetTimestamp());
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
    private readonly IConnectionSlot? _slot;
    private readonly string _remote;
    private StreamSession? _session;

    /// <param name="slot">The connection's place in the server's budgets; null when there is no session budget.</param>
    public ClientConnection(Socket socket, ServerContext context, IConnectionSlot? slot = null)
    {
        _socket = socket;
        _ctx = context;
        _slot = slot;
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
            }, _ctx.Time);
            var helloNonce = Base64Url.RandomBytes(32);
            await sink.WriteFrameAsync(FrameCodec.EncodeControl(
                ControlMessages.Hello(_ctx.Identity.CompanionId, _ctx.MachineName, helloNonce, _ctx.Pairing.IsOpen)), cts.Token).ConfigureAwait(false);

            var session = await HandshakeAsync(ssl, sink, helloNonce, cts.Token).ConfigureAwait(false);
            if (session == null)
                return;
            _session = session;
            cts.CancelAfter(Timeout.InfiniteTimeSpan);

            if (!_ctx.Engine.AddSession(session))
            {
                // The device was forgotten while this connection was between its check and here (§5.3).
                log.Info($"session from {_remote} refused: the device is no longer paired");
                return;
            }
            log.Info($"session started ({_remote})");
            // Built after the session is registered: a directory change observed from here on is either in this
            // info or makes the host queue another one (sent after this frame, as the writer starts below).
            var info = _ctx.Info();
            session.ReportedDirExists = info.DirExists;
            await sink.WriteFrameAsync(FrameCodec.EncodeControl(ControlMessages.Info(info, _ctx.Time.GetUtcNow())), cts.Token)
                .ConfigureAwait(false);

            using var closeRegistration = session.Closed.Register(() => SafeCancel(cts));
            writer = RunGuardedAsync(() => session.RunWriterAsync(sink, cts.Token), cts);
            heartbeat = RunGuardedAsync(() => HeartbeatLoopAsync(sink, session, cts.Token), cts);
            await ReadLoopAsync(ssl, sink, session, cts).ConfigureAwait(false);
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

    /// <summary>
    /// Waits for <c>pair</c> or <c>auth</c>. Returns the authenticated session, or null after a failure reply (or when
    /// every session slot is taken).
    /// </summary>
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
                        // The token goes out even without a session slot: the phone stores the pairing and comes back.
                        var promoted = TryPromote();
                        await sink.WriteFrameAsync(FrameCodec.EncodeControl(ControlMessages.Paired(result.Token!, result.ServerProof!)), ct)
                            .ConfigureAwait(false);
                        _ctx.Log.Info($"device paired from {_remote}");
                        return promoted ? NewSession(result.DeviceId!, result.DeviceName!) : null;
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
                        // No authOk without a session slot: the phone retries later instead of taking the close as a
                        // revocation.
                        if (!TryPromote())
                            return null;
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

    private bool TryPromote()
    {
        if (_slot == null || _slot.TryPromote())
            return true;
        _ctx.Log.Warn($"every session slot is taken; closing an authenticated connection from {_remote}");
        return false;
    }

    private StreamSession NewSession(string deviceId, string deviceName) =>
        new(deviceId, deviceName, _remote, _ctx.Log);

    private async Task ReadLoopAsync(SslStream ssl, IFrameSink sink, StreamSession session, CancellationTokenSource cts)
    {
        while (!cts.IsCancellationRequested)
        {
            // Set before every read: the idle mode (§5.11) may have changed with the previous frame.
            cts.CancelAfter(session.Idle ? _ctx.IdleReceiveTimeout : _ctx.ReceiveTimeout);
            var frame = await FrameCodec.ReadFrameAsync(ssl, cts.Token).ConfigureAwait(false);
            if (frame == null)
                return;
            if (!frame.Value.IsControl || !ControlMessages.TryParse(frame.Value.Payload, out var doc, out var type))
                continue;
            bool? idle = null;
            using (doc)
            {
                if (type == "idle")
                    idle = doc!.RootElement.GetBoolOrNull("on");
                else
                    Handle(session, type!, doc!.RootElement);
            }
            if (idle is { } on)
            {
                _ctx.Engine.SetIdle(session, on);
                // Confirmed at once, outside the ordered queue: the phone switches its own timings when it reads this.
                await sink.WriteFrameAsync(FrameCodec.EncodeControl(ControlMessages.Idle(on)), cts.Token).ConfigureAwait(false);
            }
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

    /// <summary>
    /// §5.9: a heartbeat goes out only after the connection was silent for the heartbeat interval (5 s, or 30 s while
    /// the phone is idle), so the phone hears something at least that often without an extra packet next to data.
    /// </summary>
    private async Task HeartbeatLoopAsync(StreamFrameSink sink, StreamSession session, CancellationToken ct)
    {
        while (true)
        {
            ct.ThrowIfCancellationRequested();
            var interval = session.Idle ? _ctx.IdleHeartbeatInterval : _ctx.HeartbeatInterval;
            var silent = sink.SinceLastWrite;
            if (silent >= interval)
            {
                await sink.WriteFrameAsync(FrameCodec.EncodeControl(ControlMessages.Heartbeat(_ctx.Time.GetUtcNow())), ct).ConfigureAwait(false);
                continue;
            }
            var wait = interval - silent;
            if (wait < TimeSpan.FromMilliseconds(1))
                wait = TimeSpan.FromMilliseconds(1);
            // A change of the idle mode shortens (or lengthens) the current wait.
            await Task.WhenAny(Task.Delay(wait, _ctx.Time, ct), session.WaitForModeChangeAsync(ct)).ConfigureAwait(false);
        }
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

using System.Net;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Authentication;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Text.Json;
using VrcxCompanion.Core.Protocol;
using VrcxCompanion.Core.Security;

namespace VrcxCompanion.Core.Client;

/// <summary>A received frame, decoded.</summary>
public sealed class ReceivedMessage
{
    public required string Type { get; init; }

    /// <summary>Control messages: the JSON text.</summary>
    public string? JsonText { get; init; }

    /// <summary>Data frames: header and inflated bytes.</summary>
    public FileDataHeader? Header { get; init; }

    public byte[]? Data { get; init; }
    public int WireBytes { get; init; }

    public JsonElement Json => JsonDocument.Parse(JsonText ?? "{}").RootElement.Clone();

    public override string ToString() =>
        Header is { } h ? $"data {h.Name} @{h.Offset} +{h.Len}{(h.Compressed ? " z" : "")}" : JsonText ?? Type;
}

public sealed record DiscoveredCompanion(string Id, string Name, int Port, string Fingerprint, bool Pairing, IPAddress Address);

/// <summary>
/// A minimal protocol client (the phone side), used by the tests and by <c>--selftest</c>. It pins the server
/// certificate by SPKI fingerprint exactly as PROTOCOL.md §3 requires.
/// </summary>
public sealed class CompanionClient : IAsyncDisposable
{
    private readonly TcpClient _tcp;
    private readonly SslStream _ssl;
    private readonly SemaphoreSlim _writeLock = new(1, 1);
    private long _dataWireBytes;
    private long _lastAck;

    private CompanionClient(TcpClient tcp, SslStream ssl, string fingerprint)
    {
        _tcp = tcp;
        _ssl = ssl;
        Fingerprint = fingerprint;
    }

    public string Fingerprint { get; }
    public JsonElement Hello { get; private set; }
    public string HelloNonce => Hello.GetProperty("nonce").GetString() ?? "";

    /// <summary>Send <c>ack</c> automatically every <see cref="AutoAckEvery"/> data wire bytes (0 = never).</summary>
    public long AutoAckEvery { get; set; } = 1024 * 1024;

    public long DataWireBytesReceived => Interlocked.Read(ref _dataWireBytes);

    /// <summary>
    /// A received <c>heartbeat</c> is answered with <c>ping</c> when nothing was sent for this long, as the phone does,
    /// to stay inside the companion's 20 s receive timeout (PROTOCOL.md §5.9). Null: never answer (a silent peer).
    /// </summary>
    public TimeSpan? HeartbeatReplyAfter { get; set; } = TimeSpan.FromSeconds(10);

    private long _lastSentAt = Environment.TickCount64;

    public static async Task<CompanionClient> ConnectAsync(IPAddress address, int port, string expectedFingerprint,
        CancellationToken cancellationToken)
    {
        var tcp = new TcpClient(address.AddressFamily) { NoDelay = true };
        try
        {
            await tcp.ConnectAsync(address, port, cancellationToken).ConfigureAwait(false);
            var ssl = new SslStream(tcp.GetStream(), leaveInnerStreamOpen: false);
            await ssl.AuthenticateAsClientAsync(new SslClientAuthenticationOptions
            {
                TargetHost = "vrcx-companion",
                EnabledSslProtocols = SslProtocols.Tls12 | SslProtocols.Tls13,
                CertificateRevocationCheckMode = X509RevocationMode.NoCheck,
                RemoteCertificateValidationCallback = (_, certificate, _, _) =>
                    certificate != null &&
                    CompanionIdentity.ComputeFingerprint(certificate as X509Certificate2 ?? new X509Certificate2(certificate)) ==
                    expectedFingerprint,
            }, cancellationToken).ConfigureAwait(false);
            var client = new CompanionClient(tcp, ssl, expectedFingerprint);
            var hello = await client.ReceiveAsync(cancellationToken).ConfigureAwait(false)
                        ?? throw new IOException("connection closed before hello");
            if (hello.Type != "hello")
                throw new ProtocolException("expected hello, got " + hello.Type);
            client.Hello = hello.Json;
            return client;
        }
        catch
        {
            tcp.Dispose();
            throw;
        }
    }

    public string? NegotiatedProtocol => _ssl.SslProtocol.ToString();

    public async Task SendAsync(byte[] json, CancellationToken cancellationToken)
    {
        await _writeLock.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            await _ssl.WriteAsync(FrameCodec.EncodeControl(json), cancellationToken).ConfigureAwait(false);
            Volatile.Write(ref _lastSentAt, Environment.TickCount64);
        }
        finally
        {
            _writeLock.Release();
        }
    }

    public Task SendAsync(string json, CancellationToken cancellationToken) => SendAsync(Encoding.UTF8.GetBytes(json), cancellationToken);

    /// <summary>Receives one frame; null at the end of the stream.</summary>
    public async Task<ReceivedMessage?> ReceiveAsync(CancellationToken cancellationToken)
    {
        var frame = await FrameCodec.ReadFrameAsync(_ssl, cancellationToken).ConfigureAwait(false);
        if (frame == null)
            return null;
        var wire = 5 + frame.Value.Payload.Length;
        if (frame.Value.IsData)
        {
            var (header, data) = FrameCodec.DecodeData(frame.Value.Payload);
            var total = Interlocked.Add(ref _dataWireBytes, wire);
            if (AutoAckEvery > 0 && total - _lastAck >= AutoAckEvery)
            {
                _lastAck = total;
                await AckAsync(total, cancellationToken).ConfigureAwait(false);
            }
            return new ReceivedMessage { Type = "data", Header = header, Data = data, WireBytes = wire };
        }
        var text = Encoding.UTF8.GetString(frame.Value.Payload);
        using var doc = JsonDocument.Parse(text);
        var type = doc.RootElement.GetProperty("t").GetString() ?? "";
        if (type == "heartbeat" && HeartbeatReplyAfter is { } after &&
            Environment.TickCount64 - Volatile.Read(ref _lastSentAt) >= (long)after.TotalMilliseconds)
            await SendAsync("{\"t\":\"ping\"}", cancellationToken).ConfigureAwait(false);
        return new ReceivedMessage
        {
            Type = type,
            JsonText = text,
            WireBytes = wire,
        };
    }

    /// <summary>Receives frames until one of the given type arrives (heartbeats are skipped). Returns every frame received.</summary>
    public async Task<List<ReceivedMessage>> ReceiveUntilAsync(string type, CancellationToken cancellationToken, bool keepHeartbeats = false)
    {
        var list = new List<ReceivedMessage>();
        while (true)
        {
            var m = await ReceiveAsync(cancellationToken).ConfigureAwait(false) ?? throw new EndOfStreamException("closed before " + type);
            if (m.Type != "heartbeat" || keepHeartbeats)
                list.Add(m);
            if (m.Type == type)
                return list;
        }
    }

    public Task AckAsync(long bytes, CancellationToken cancellationToken) =>
        SendAsync(Json.Build(w =>
        {
            w.WriteString("t", "ack");
            w.WriteNumber("bytes", bytes);
        }), cancellationToken);

    /// <summary>Pairs with a code. Returns the token and whether the server proof matched.</summary>
    public async Task<(bool ok, string? token, bool serverProofValid, string? failReason)> PairAsync(string code, string deviceId,
        string deviceName, CancellationToken cancellationToken)
    {
        var normalized = CrockfordCode.Normalize(code) ?? code;
        var clientNonce = Base64Url.RandomBytes(32);
        var proof = PairingCrypto.ClientProof(normalized, Fingerprint, HelloNonce, clientNonce);
        await SendAsync(Json.Build(w =>
        {
            w.WriteString("t", "pair");
            w.WriteString("deviceId", deviceId);
            w.WriteString("deviceName", deviceName);
            w.WriteString("nonce", clientNonce);
            w.WriteString("proof", proof);
        }), cancellationToken).ConfigureAwait(false);
        var reply = await ReceiveAsync(cancellationToken).ConfigureAwait(false) ?? throw new EndOfStreamException("closed");
        if (reply.Type == "pairFail")
            return (false, null, false, reply.Json.GetProperty("reason").GetString());
        if (reply.Type != "paired")
            throw new ProtocolException("unexpected " + reply.Type);
        var token = reply.Json.GetProperty("token").GetString();
        var serverProof = reply.Json.GetProperty("proof").GetString();
        var expected = PairingCrypto.ServerProof(normalized, Fingerprint, clientNonce, HelloNonce);
        return (true, token, expected == serverProof, null);
    }

    public async Task<bool> AuthAsync(string deviceId, string token, CancellationToken cancellationToken)
    {
        await SendAsync(Json.Build(w =>
        {
            w.WriteString("t", "auth");
            w.WriteString("deviceId", deviceId);
            w.WriteString("token", token);
        }), cancellationToken).ConfigureAwait(false);
        var reply = await ReceiveAsync(cancellationToken).ConfigureAwait(false) ?? throw new EndOfStreamException("closed");
        return reply.Type == "authOk";
    }

    public Task SubscribeAsync(long sinceUtcTicks, IEnumerable<(string name, string fileId, long length)> have,
        CancellationToken cancellationToken) =>
        SendAsync(Json.Build(w =>
        {
            w.WriteString("t", "subscribe");
            w.WriteNumber("sinceUtcTicks", sinceUtcTicks);
            w.WriteStartArray("have");
            foreach (var (name, fileId, length) in have)
            {
                w.WriteStartObject();
                w.WriteString("name", name);
                w.WriteString("fileId", fileId);
                w.WriteNumber("length", length);
                w.WriteEndObject();
            }
            w.WriteEndArray();
        }), cancellationToken);

    /// <summary>Sends a discovery request to <paramref name="target"/> and collects replies until the timeout.</summary>
    public static async Task<List<DiscoveredCompanion>> DiscoverAsync(IPEndPoint target, TimeSpan timeout, CancellationToken cancellationToken)
    {
        using var udp = new UdpClient(target.AddressFamily);
        if (target.Address.Equals(IPAddress.Broadcast))
            udp.EnableBroadcast = true;
        var request = Encoding.UTF8.GetBytes("{\"t\":\"vrcx-discover\",\"v\":1}");
        await udp.SendAsync(request, target, cancellationToken).ConfigureAwait(false);
        var result = new List<DiscoveredCompanion>();
        using var cts = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        cts.CancelAfter(timeout);
        try
        {
            while (true)
            {
                var r = await udp.ReceiveAsync(cts.Token).ConfigureAwait(false);
                using var doc = JsonDocument.Parse(r.Buffer);
                var e = doc.RootElement;
                if (e.GetProperty("t").GetString() != "vrcx-companion")
                    continue;
                result.Add(new DiscoveredCompanion(
                    e.GetProperty("id").GetString() ?? "",
                    e.GetProperty("name").GetString() ?? "",
                    e.GetProperty("port").GetInt32(),
                    e.GetProperty("fp").GetString() ?? "",
                    e.GetProperty("pairing").GetBoolean(),
                    r.RemoteEndPoint.Address));
                if (!target.Address.Equals(IPAddress.Broadcast))
                    break;
            }
        }
        catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
        {
        }
        return result;
    }

    public async ValueTask DisposeAsync()
    {
        await _ssl.DisposeAsync().ConfigureAwait(false);
        _tcp.Dispose();
    }
}

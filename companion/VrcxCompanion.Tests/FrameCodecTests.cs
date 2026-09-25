using System.Buffers.Binary;
using System.IO.Compression;
using System.Text;
using System.Text.Json;
using VrcxCompanion.Core.Protocol;

namespace VrcxCompanion.Tests;

public class FrameCodecTests
{
    [Fact]
    public async Task ControlFrameRoundTrip()
    {
        var json = Encoding.UTF8.GetBytes("{\"t\":\"ping\"}");
        var frame = FrameCodec.EncodeControl(json);

        Assert.Equal(4 + 1 + json.Length, frame.Length);
        Assert.Equal((uint)(1 + json.Length), BinaryPrimitives.ReadUInt32BigEndian(frame));
        Assert.Equal(0x01, frame[4]);

        var decoded = await FrameCodec.ReadFrameAsync(new MemoryStream(frame), CancellationToken.None);
        Assert.NotNull(decoded);
        Assert.True(decoded!.Value.IsControl);
        Assert.Equal(json, decoded.Value.Payload);
    }

    [Fact]
    public async Task CompressibleDataUsesRawDeflate()
    {
        var raw = Encoding.UTF8.GetBytes(string.Concat(Enumerable.Repeat("2024.01.01 09:59:00 Log        -  [Behaviour] OnPlayerJoined Someone\r\n", 400)));
        using var encoded = FrameCodec.EncodeData("output_log_2024-01-01_09-59-00.txt", "0011aabb0000000000001234", 1234, raw);
        Assert.True(encoded.Compressed);
        Assert.True(encoded.Length < raw.Length / 5);

        var frame = await FrameCodec.ReadFrameAsync(new MemoryStream(encoded.ToArray()), CancellationToken.None);
        Assert.True(frame!.Value.IsData);

        // Header layout: uint16 BE header length, then the JSON header.
        var payload = frame.Value.Payload;
        var headerLength = BinaryPrimitives.ReadUInt16BigEndian(payload);
        using var header = JsonDocument.Parse(payload.AsMemory(2, headerLength));
        Assert.Equal("output_log_2024-01-01_09-59-00.txt", header.RootElement.GetProperty("name").GetString());
        Assert.Equal("0011aabb0000000000001234", header.RootElement.GetProperty("fileId").GetString());
        Assert.Equal(1234, header.RootElement.GetProperty("offset").GetInt64());
        Assert.Equal(raw.Length, header.RootElement.GetProperty("len").GetInt32());
        Assert.Equal(1, header.RootElement.GetProperty("z").GetInt32());

        // The body is raw DEFLATE (RFC 1951, no zlib header): DeflateStream reads it directly.
        var body = payload.AsSpan(2 + headerLength).ToArray();
        using var inflate = new DeflateStream(new MemoryStream(body), CompressionMode.Decompress);
        var inflated = new MemoryStream();
        inflate.CopyTo(inflated);
        Assert.Equal(raw, inflated.ToArray());

        var (h, data) = FrameCodec.DecodeData(payload);
        Assert.True(h.Compressed);
        Assert.Equal(1234, h.Offset);
        Assert.Equal(raw, data);
    }

    [Fact]
    public void IncompressibleDataIsSentRaw()
    {
        var raw = new byte[4096];
        new Random(42).NextBytes(raw);
        using var encoded = FrameCodec.EncodeData("output_log_x.txt", "id", 0, raw);
        Assert.False(encoded.Compressed);
        var bytes = encoded.ToArray();
        var (h, data) = FrameCodec.DecodeData(bytes.AsSpan(5));
        Assert.False(h.Compressed);
        Assert.Equal(raw.Length, h.Len);
        Assert.Equal(raw, data);
    }

    [Fact]
    public void CompressionCanBeDisabled()
    {
        var raw = Encoding.ASCII.GetBytes(new string('a', 1000));
        using var encoded = FrameCodec.EncodeData("f", "id", 0, raw, allowCompression: false);
        Assert.False(encoded.Compressed);
        Assert.Equal(raw, FrameCodec.DecodeData(encoded.ToArray().AsSpan(5)).Data);
    }

    [Fact]
    public void EmptyDataRoundTrip()
    {
        using var encoded = FrameCodec.EncodeData("f", "id", 7, ReadOnlySpan<byte>.Empty);
        var (h, data) = FrameCodec.DecodeData(encoded.ToArray().AsSpan(5));
        Assert.Equal(7, h.Offset);
        Assert.Empty(data);
    }

    [Fact]
    public void MaximumChunkFitsInOneFrame()
    {
        var raw = new byte[ProtocolConstants.MaxChunkBytes];
        new Random(1).NextBytes(raw);
        using var encoded = FrameCodec.EncodeData(new string('n', 200), new string('i', 40), long.MaxValue / 2, raw);
        Assert.True(encoded.Length - 4 <= ProtocolConstants.MaxFrameLength);
        Assert.Equal(raw, FrameCodec.DecodeData(encoded.ToArray().AsSpan(5)).Data);
    }

    [Fact]
    public async Task FrameOfExactlyTheLimitIsAccepted()
    {
        var payload = new byte[ProtocolConstants.MaxFrameLength - 1];
        var frame = new byte[4 + ProtocolConstants.MaxFrameLength];
        BinaryPrimitives.WriteUInt32BigEndian(frame, ProtocolConstants.MaxFrameLength);
        frame[4] = ProtocolConstants.FrameTypeControl;
        payload.CopyTo(frame, 5);
        var decoded = await FrameCodec.ReadFrameAsync(new MemoryStream(frame), CancellationToken.None);
        Assert.Equal(payload.Length, decoded!.Value.Payload.Length);
    }

    [Fact]
    public async Task FrameAboveTheLimitClosesTheConnection()
    {
        var frame = new byte[16];
        BinaryPrimitives.WriteUInt32BigEndian(frame, ProtocolConstants.MaxFrameLength + 1);
        frame[4] = ProtocolConstants.FrameTypeControl;
        await Assert.ThrowsAsync<ProtocolException>(async () => await FrameCodec.ReadFrameAsync(new MemoryStream(frame), CancellationToken.None));
    }

    [Fact]
    public void EncodingAboveTheLimitThrows()
    {
        Assert.Throws<ProtocolException>(() => FrameCodec.EncodeControl(new byte[ProtocolConstants.MaxFrameLength]));
        Assert.Throws<ProtocolException>(() =>
        {
            var raw = new byte[ProtocolConstants.MaxFrameLength];
            new Random(3).NextBytes(raw);
            FrameCodec.EncodeData("f", "id", 0, raw).Dispose();
        });
    }

    [Fact]
    public async Task ZeroLengthAndUnknownTypeAreRejected()
    {
        await Assert.ThrowsAsync<ProtocolException>(async () =>
            await FrameCodec.ReadFrameAsync(new MemoryStream(new byte[] { 0, 0, 0, 0 }), CancellationToken.None));
        await Assert.ThrowsAsync<ProtocolException>(async () =>
            await FrameCodec.ReadFrameAsync(new MemoryStream(new byte[] { 0, 0, 0, 2, 0x03, 0 }), CancellationToken.None));
    }

    [Fact]
    public async Task CleanEndOfStreamReturnsNullAndTruncatedFrameThrows()
    {
        Assert.Null(await FrameCodec.ReadFrameAsync(new MemoryStream(), CancellationToken.None));
        await Assert.ThrowsAsync<EndOfStreamException>(async () =>
            await FrameCodec.ReadFrameAsync(new MemoryStream(new byte[] { 0, 0 }), CancellationToken.None));
        await Assert.ThrowsAsync<EndOfStreamException>(async () =>
            await FrameCodec.ReadFrameAsync(new MemoryStream(new byte[] { 0, 0, 0, 10, 1, 1, 2 }), CancellationToken.None));
    }

    [Fact]
    public async Task SeveralFramesOnOneStream()
    {
        var ms = new MemoryStream();
        ms.Write(FrameCodec.EncodeControl(Encoding.UTF8.GetBytes("{\"t\":\"a\"}")));
        using (var d = FrameCodec.EncodeData("f", "id", 3, Encoding.ASCII.GetBytes("abc")))
            ms.Write(d.Memory.Span);
        ms.Write(FrameCodec.EncodeControl(Encoding.UTF8.GetBytes("{\"t\":\"b\"}")));
        ms.Position = 0;

        var a = await FrameCodec.ReadFrameAsync(ms, CancellationToken.None);
        var d2 = await FrameCodec.ReadFrameAsync(ms, CancellationToken.None);
        var b = await FrameCodec.ReadFrameAsync(ms, CancellationToken.None);
        Assert.True(a!.Value.IsControl);
        Assert.Equal("abc", Encoding.ASCII.GetString(FrameCodec.DecodeData(d2!.Value.Payload).Data));
        Assert.Equal("{\"t\":\"b\"}", Encoding.UTF8.GetString(b!.Value.Payload));
        Assert.Null(await FrameCodec.ReadFrameAsync(ms, CancellationToken.None));
    }

    [Fact]
    public void DeflateBodyWithWrongLengthIsRejected()
    {
        var raw = Encoding.ASCII.GetBytes(new string('x', 500));
        using var encoded = FrameCodec.EncodeData("f", "id", 0, raw);
        var payload = encoded.ToArray().AsSpan(5).ToArray();
        var headerLength = BinaryPrimitives.ReadUInt16BigEndian(payload);
        var header = Encoding.UTF8.GetString(payload, 2, headerLength).Replace("\"len\":500", "\"len\":501");
        var hb = Encoding.UTF8.GetBytes(header);
        var tampered = new byte[2 + hb.Length + payload.Length - 2 - headerLength];
        BinaryPrimitives.WriteUInt16BigEndian(tampered, (ushort)hb.Length);
        hb.CopyTo(tampered, 2);
        payload.AsSpan(2 + headerLength).CopyTo(tampered.AsSpan(2 + hb.Length));
        Assert.Throws<ProtocolException>(() => FrameCodec.DecodeData(tampered));
    }

    [Fact]
    public void ControlMessagesHaveTheDocumentedShape()
    {
        var now = DateTimeOffset.FromUnixTimeMilliseconds(1_700_000_000_123);
        using var hello = JsonDocument.Parse(ControlMessages.Hello("cid", "PC", "nonce", true));
        Assert.Equal("hello", hello.RootElement.GetProperty("t").GetString());
        Assert.Equal(1, hello.RootElement.GetProperty("v").GetInt32());
        Assert.True(hello.RootElement.GetProperty("pairing").GetBoolean());

        using var process = JsonDocument.Parse(ControlMessages.Process(true, false, now));
        Assert.True(process.RootElement.GetProperty("vrchatRunning").GetBoolean());
        Assert.False(process.RootElement.GetProperty("steamVrRunning").GetBoolean());
        Assert.Equal(1_700_000_000_123, process.RootElement.GetProperty("pcUtcNowMs").GetInt64());

        var tz = new TimeZoneSnapshot("W. Europe Standard Time", "Europe/Berlin", true, 60, 120);
        using var info = JsonDocument.Parse(ControlMessages.Info(new InfoSnapshot("1.0.0", "PC", tz, @"C:\logs", true), now));
        var t = info.RootElement.GetProperty("tz");
        Assert.Equal("Europe/Berlin", t.GetProperty("ianaId").GetString());
        Assert.Equal(60, t.GetProperty("baseUtcOffsetMin").GetInt32());
        Assert.Equal(120, t.GetProperty("currentUtcOffsetMin").GetInt32());
        Assert.Equal(@"C:\logs", info.RootElement.GetProperty("logDir").GetString());

        using var noIana = JsonDocument.Parse(ControlMessages.Info(new InfoSnapshot("1.0.0", "PC", tz with { IanaId = null }, "d", false), now));
        Assert.Equal(JsonValueKind.Null, noIana.RootElement.GetProperty("tz").GetProperty("ianaId").ValueKind);

        using var disc = JsonDocument.Parse(ControlMessages.DiscoveryReply("cid", "PC", 49460, "fp", false));
        Assert.Equal("vrcx-companion", disc.RootElement.GetProperty("t").GetString());
        Assert.Equal(49460, disc.RootElement.GetProperty("port").GetInt32());
    }

    [Fact]
    public void Base64UrlHasNoPaddingAndRoundTrips()
    {
        var bytes = new byte[] { 0xfb, 0xff, 0xbf, 0x00, 0x01 };
        var s = Base64Url.Encode(bytes);
        Assert.DoesNotContain('=', s);
        Assert.DoesNotContain('+', s);
        Assert.DoesNotContain('/', s);
        Assert.True(Base64Url.TryDecode(s, out var back));
        Assert.Equal(bytes, back);
        Assert.False(Base64Url.TryDecode("a+b", out _));
        Assert.Equal(43, Base64Url.RandomBytes(32).Length);
    }
}

using System.Buffers;
using System.Buffers.Binary;
using System.IO.Compression;
using System.Text.Json;

namespace VrcxCompanion.Core.Protocol;

/// <summary>A protocol violation that closes the connection.</summary>
public sealed class ProtocolException : Exception
{
    public ProtocolException(string message) : base(message) { }
}

/// <summary>One received frame. <see cref="Payload"/> excludes the length prefix and the type byte.</summary>
public readonly record struct Frame(byte Type, byte[] Payload)
{
    public bool IsControl => Type == ProtocolConstants.FrameTypeControl;
    public bool IsData => Type == ProtocolConstants.FrameTypeData;
}

/// <summary>Header of a file data frame (PROTOCOL.md §4).</summary>
public sealed record FileDataHeader(string Name, string FileId, long Offset, int Len, bool Compressed);

/// <summary>An encoded frame in a pooled buffer, ready to be written as-is (length prefix included).</summary>
public sealed class EncodedFrame : IDisposable
{
    private byte[]? _buffer;

    internal EncodedFrame(byte[] buffer, int length, bool pooled, bool compressed, int rawLength)
    {
        _buffer = buffer;
        Length = length;
        Pooled = pooled;
        Compressed = compressed;
        RawLength = rawLength;
    }

    public int Length { get; }
    public bool Compressed { get; }
    public int RawLength { get; }
    private bool Pooled { get; }

    public ReadOnlyMemory<byte> Memory => (_buffer ?? throw new ObjectDisposedException(nameof(EncodedFrame))).AsMemory(0, Length);

    public byte[] ToArray() => Memory.ToArray();

    public void Dispose()
    {
        var b = _buffer;
        _buffer = null;
        if (b != null && Pooled)
            ArrayPool<byte>.Shared.Return(b);
    }
}

/// <summary>Frame encoding and decoding (PROTOCOL.md §4).</summary>
public static class FrameCodec
{
    private const int PrefixLength = 4;

    public static byte[] EncodeControl(ReadOnlySpan<byte> json)
    {
        var length = 1 + json.Length;
        if (length > ProtocolConstants.MaxFrameLength)
            throw new ProtocolException($"control frame too large ({length} bytes)");
        var frame = new byte[PrefixLength + length];
        BinaryPrimitives.WriteUInt32BigEndian(frame, (uint)length);
        frame[PrefixLength] = ProtocolConstants.FrameTypeControl;
        json.CopyTo(frame.AsSpan(PrefixLength + 1));
        return frame;
    }

    /// <summary>
    /// Encodes a file data frame. The body is raw DEFLATE (RFC 1951) when that is smaller than the raw bytes and
    /// <paramref name="allowCompression"/> is set, otherwise the raw bytes.
    /// </summary>
    public static EncodedFrame EncodeData(string name, string fileId, long offset, ReadOnlySpan<byte> raw,
        bool allowCompression = true, CompressionLevel level = CompressionLevel.Optimal)
    {
        byte[]? compressed = null;
        var compressedLength = 0;
        try
        {
            if (allowCompression && raw.Length > 0)
            {
                compressed = ArrayPool<byte>.Shared.Rent(raw.Length);
                // The body is only used when strictly smaller than the raw bytes.
                var sink = new BoundedBufferStream(compressed, raw.Length - 1);
                using (var deflate = new DeflateStream(sink, level, leaveOpen: true))
                    deflate.Write(raw);
                if (!sink.Overflowed && sink.Position < raw.Length)
                    compressedLength = (int)sink.Position;
                else
                {
                    ArrayPool<byte>.Shared.Return(compressed);
                    compressed = null;
                }
            }

            var z = compressed != null;
            var header = EncodeDataHeader(name, fileId, offset, raw.Length, z);
            if (header.Length > ushort.MaxValue)
                throw new ProtocolException("data header too large");
            var bodyLength = z ? compressedLength : raw.Length;
            var length = 1 + 2 + header.Length + bodyLength;
            if (length > ProtocolConstants.MaxFrameLength)
                throw new ProtocolException($"data frame too large ({length} bytes)");

            var total = PrefixLength + length;
            var buffer = ArrayPool<byte>.Shared.Rent(total);
            var span = buffer.AsSpan();
            BinaryPrimitives.WriteUInt32BigEndian(span, (uint)length);
            span[PrefixLength] = ProtocolConstants.FrameTypeData;
            BinaryPrimitives.WriteUInt16BigEndian(span[(PrefixLength + 1)..], (ushort)header.Length);
            header.CopyTo(span[(PrefixLength + 3)..]);
            var bodyStart = PrefixLength + 3 + header.Length;
            if (z)
                compressed.AsSpan(0, compressedLength).CopyTo(span[bodyStart..]);
            else
                raw.CopyTo(span[bodyStart..]);
            return new EncodedFrame(buffer, total, pooled: true, compressed: z, rawLength: raw.Length);
        }
        finally
        {
            if (compressed != null)
                ArrayPool<byte>.Shared.Return(compressed);
        }
    }

    private static byte[] EncodeDataHeader(string name, string fileId, long offset, int len, bool z)
    {
        var buffer = new ArrayBufferWriter<byte>(128);
        using (var w = new Utf8JsonWriter(buffer, Json.WriterOptions))
        {
            w.WriteStartObject();
            w.WriteString("name", name);
            w.WriteString("fileId", fileId);
            w.WriteNumber("offset", offset);
            w.WriteNumber("len", len);
            w.WriteNumber("z", z ? 1 : 0);
            w.WriteEndObject();
        }
        return buffer.WrittenSpan.ToArray();
    }

    /// <summary>
    /// Reads one frame. Returns null on a clean end of stream before the first byte of a frame. Throws
    /// <see cref="ProtocolException"/> on a length of 0 or above the limit, or an unknown frame type, and
    /// <see cref="EndOfStreamException"/> when the stream ends inside a frame.
    /// </summary>
    public static async ValueTask<Frame?> ReadFrameAsync(Stream stream, CancellationToken cancellationToken)
    {
        var prefix = new byte[PrefixLength + 1];
        var read = 0;
        while (read < PrefixLength)
        {
            var n = await stream.ReadAsync(prefix.AsMemory(read, PrefixLength - read), cancellationToken).ConfigureAwait(false);
            if (n == 0)
            {
                if (read == 0)
                    return null;
                throw new EndOfStreamException("stream ended inside a frame header");
            }
            read += n;
        }
        var length = BinaryPrimitives.ReadUInt32BigEndian(prefix);
        if (length == 0 || length > ProtocolConstants.MaxFrameLength)
            throw new ProtocolException($"invalid frame length {length}");
        await stream.ReadExactlyAsync(prefix.AsMemory(PrefixLength, 1), cancellationToken).ConfigureAwait(false);
        var type = prefix[PrefixLength];
        if (type != ProtocolConstants.FrameTypeControl && type != ProtocolConstants.FrameTypeData)
            throw new ProtocolException($"unknown frame type {type}");
        var payload = new byte[length - 1];
        if (payload.Length > 0)
            await stream.ReadExactlyAsync(payload, cancellationToken).ConfigureAwait(false);
        return new Frame(type, payload);
    }

    /// <summary>Decodes the payload of a data frame, inflating the body when <c>z=1</c>.</summary>
    public static (FileDataHeader Header, byte[] Data) DecodeData(ReadOnlySpan<byte> payload)
    {
        if (payload.Length < 2)
            throw new ProtocolException("data frame too short");
        var headerLength = BinaryPrimitives.ReadUInt16BigEndian(payload);
        if (payload.Length < 2 + headerLength)
            throw new ProtocolException("data header truncated");
        FileDataHeader header;
        try
        {
            using var doc = JsonDocument.Parse(payload.Slice(2, headerLength).ToArray());
            var root = doc.RootElement;
            header = new FileDataHeader(
                root.GetProperty("name").GetString() ?? throw new ProtocolException("name missing"),
                root.GetProperty("fileId").GetString() ?? throw new ProtocolException("fileId missing"),
                root.GetProperty("offset").GetInt64(),
                root.GetProperty("len").GetInt32(),
                root.GetProperty("z").GetInt32() switch
                {
                    0 => false,
                    1 => true,
                    _ => throw new ProtocolException("invalid z"),
                });
        }
        catch (Exception e) when (e is JsonException or KeyNotFoundException or InvalidOperationException or FormatException)
        {
            throw new ProtocolException("invalid data header: " + e.GetType().Name);
        }
        if (header.Len < 0 || header.Offset < 0)
            throw new ProtocolException("invalid data header values");

        var body = payload[(2 + headerLength)..];
        if (!header.Compressed)
        {
            if (body.Length != header.Len)
                throw new ProtocolException("raw body length does not match len");
            return (header, body.ToArray());
        }

        var output = new byte[header.Len];
        using var input = new MemoryStream(body.ToArray(), writable: false);
        using var inflate = new DeflateStream(input, CompressionMode.Decompress);
        var total = 0;
        while (total < output.Length)
        {
            var n = inflate.Read(output, total, output.Length - total);
            if (n == 0)
                throw new ProtocolException("deflate body shorter than len");
            total += n;
        }
        Span<byte> probe = stackalloc byte[1];
        if (inflate.Read(probe) != 0)
            throw new ProtocolException("deflate body longer than len");
        return (header, output);
    }

    /// <summary>A write-only stream into a fixed buffer that records an overflow instead of throwing.</summary>
    private sealed class BoundedBufferStream : Stream
    {
        private readonly byte[] _buffer;
        private readonly int _limit;
        private int _position;

        public BoundedBufferStream(byte[] buffer, int limit)
        {
            _buffer = buffer;
            _limit = Math.Max(0, Math.Min(limit, buffer.Length));
        }

        public bool Overflowed { get; private set; }
        public override bool CanRead => false;
        public override bool CanSeek => false;
        public override bool CanWrite => true;
        public override long Length => _position;
        public override long Position { get => _position; set => throw new NotSupportedException(); }
        public override void Flush() { }
        public override int Read(byte[] buffer, int offset, int count) => throw new NotSupportedException();
        public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
        public override void SetLength(long value) => throw new NotSupportedException();

        public override void Write(byte[] buffer, int offset, int count) => Write(buffer.AsSpan(offset, count));

        public override void Write(ReadOnlySpan<byte> buffer)
        {
            if (Overflowed)
                return;
            if (_position + buffer.Length > _limit)
            {
                Overflowed = true;
                return;
            }
            buffer.CopyTo(_buffer.AsSpan(_position));
            _position += buffer.Length;
        }

        public override void WriteByte(byte value) => Write(new ReadOnlySpan<byte>(in value));
    }
}

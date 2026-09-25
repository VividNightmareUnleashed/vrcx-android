using System.Buffers.Binary;
using System.Text;
using System.Text.Json;
using VrcxCompanion.Core.Client;
using VrcxCompanion.Core.Processes;
using VrcxCompanion.Core.Protocol;
using VrcxCompanion.Core.Sync;

namespace VrcxCompanion.Tests;

internal sealed class TempDir : IDisposable
{
    public TempDir()
    {
        Path = System.IO.Path.Combine(System.IO.Path.GetTempPath(), "vrcxc-tests-" + Guid.NewGuid().ToString("N")[..12]);
        Directory.CreateDirectory(Path);
    }

    public string Path { get; }

    public string File(string name) => System.IO.Path.Combine(Path, name);

    public void Dispose()
    {
        for (var i = 0; i < 5; i++)
        {
            try
            {
                if (Directory.Exists(Path))
                    Directory.Delete(Path, recursive: true);
                return;
            }
            catch (IOException)
            {
                Thread.Sleep(50);
            }
            catch (UnauthorizedAccessException)
            {
                Thread.Sleep(50);
            }
        }
    }
}

internal sealed class FakeProcessProbe : IProcessProbe
{
    public volatile bool Vrchat;
    public volatile bool SteamVr;
    public int Polls;

    public ProcessState Poll()
    {
        Interlocked.Increment(ref Polls);
        return new ProcessState(Vrchat, SteamVr);
    }
}

internal sealed class ManualTime : TimeProvider
{
    private DateTimeOffset _now;

    public ManualTime(DateTimeOffset start) => _now = start;

    public override DateTimeOffset GetUtcNow() => _now;

    public void Advance(TimeSpan by) => _now += by;
}

/// <summary>Collects the frames a <see cref="StreamSession"/> writer produces.</summary>
internal sealed class RecordingSink : IFrameSink
{
    private readonly object _gate = new();
    private readonly List<ReceivedMessage> _messages = new();
    private readonly SemaphoreSlim _signal = new(0);

    public long DataWireBytes;

    public IReadOnlyList<ReceivedMessage> Messages
    {
        get
        {
            lock (_gate)
                return _messages.ToArray();
        }
    }

    public int Count
    {
        get
        {
            lock (_gate)
                return _messages.Count;
        }
    }

    public ValueTask WriteFrameAsync(ReadOnlyMemory<byte> frame, CancellationToken cancellationToken)
    {
        var span = frame.Span;
        var length = BinaryPrimitives.ReadUInt32BigEndian(span);
        Assert.Equal(frame.Length, (int)length + 4);
        var type = span[4];
        var payload = span[5..].ToArray();
        ReceivedMessage m;
        if (type == ProtocolConstants.FrameTypeData)
        {
            var (header, data) = FrameCodec.DecodeData(payload);
            Interlocked.Add(ref DataWireBytes, frame.Length);
            m = new ReceivedMessage { Type = "data", Header = header, Data = data, WireBytes = frame.Length };
        }
        else
        {
            var text = Encoding.UTF8.GetString(payload);
            using var doc = JsonDocument.Parse(text);
            m = new ReceivedMessage { Type = doc.RootElement.GetProperty("t").GetString()!, JsonText = text, WireBytes = frame.Length };
        }
        lock (_gate)
            _messages.Add(m);
        _signal.Release();
        return ValueTask.CompletedTask;
    }

    /// <summary>Waits until a message of <paramref name="type"/> arrives at or after index <paramref name="from"/>.</summary>
    public async Task<List<ReceivedMessage>> WaitForAsync(string type, int from = 0, int timeoutMs = 10000)
    {
        var deadline = Environment.TickCount64 + timeoutMs;
        while (true)
        {
            var all = Messages;
            for (var i = from; i < all.Count; i++)
            {
                if (all[i].Type == type)
                    return all.Skip(from).Take(i - from + 1).ToList();
            }
            var remaining = deadline - Environment.TickCount64;
            if (remaining <= 0)
                throw new TimeoutException($"no '{type}' after index {from}; got: {string.Join(" | ", all.Skip(from))}");
            await _signal.WaitAsync(TimeSpan.FromMilliseconds(Math.Min(remaining, 200)));
        }
    }

    /// <summary>Waits until no frame has arrived for <paramref name="quietMs"/>.</summary>
    public async Task WaitQuietAsync(int quietMs = 250, int timeoutMs = 10000)
    {
        var deadline = Environment.TickCount64 + timeoutMs;
        var last = Count;
        var lastChange = Environment.TickCount64;
        while (Environment.TickCount64 < deadline)
        {
            await Task.Delay(25);
            var c = Count;
            if (c != last)
            {
                last = c;
                lastChange = Environment.TickCount64;
            }
            else if (Environment.TickCount64 - lastChange >= quietMs)
            {
                return;
            }
        }
        throw new TimeoutException("sink never went quiet");
    }
}

internal static class LogFiles
{
    public static byte[] Line(string text) => Encoding.UTF8.GetBytes(text + "\r\n");

    public static void Append(string path, byte[] bytes)
    {
        using var fs = new FileStream(path, FileMode.Append, FileAccess.Write, FileShare.ReadWrite | FileShare.Delete);
        fs.Write(bytes);
    }

    /// <summary>Concatenates the data frames for one file, checking that offsets are contiguous from <paramref name="start"/>.</summary>
    public static byte[] Contiguous(IEnumerable<ReceivedMessage> messages, string name, long start = 0)
    {
        var ms = new MemoryStream();
        var expected = start;
        foreach (var m in messages.Where(m => m.Header?.Name == name))
        {
            Assert.Equal(expected, m.Header!.Offset);
            ms.Write(m.Data!);
            expected += m.Data!.Length;
        }
        return ms.ToArray();
    }
}

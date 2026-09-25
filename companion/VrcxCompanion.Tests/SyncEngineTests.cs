using System.Text;
using VrcxCompanion.Core.Client;
using VrcxCompanion.Core.Diagnostics;
using VrcxCompanion.Core.Logs;
using VrcxCompanion.Core.Sync;

namespace VrcxCompanion.Tests;

/// <summary>Subscription behaviour (PROTOCOL.md §5.5-5.9) on a temporary log directory.</summary>
public sealed class SyncEngineTests : IDisposable
{
    private const string A = "output_log_2024-01-01_09-59-00.txt";
    private const string B = "output_log_2024-01-02_10-00-00.txt";

    private readonly TempDir _dir = new();
    private readonly FakeProcessProbe _probe = new();
    private readonly SyncEngine _engine;
    private readonly CancellationTokenSource _cts = new();
    private readonly List<Task> _writers = new();

    public SyncEngineTests()
    {
        _engine = new SyncEngine(new LogDirectoryTailer(_dir.Path, NullLog.Instance, useWatcher: false), _probe, NullLog.Instance);
    }

    public void Dispose()
    {
        _cts.Cancel();
        try
        {
            Task.WaitAll(_writers.ToArray(), 5000);
        }
        catch (AggregateException)
        {
        }
        _engine.Dispose();
        _dir.Dispose();
    }

    private (StreamSession session, RecordingSink sink) Connect(string deviceId = "dev-1", long maxInFlight = 4L * 1024 * 1024,
        int maxChunk = 256 * 1024)
    {
        var session = new StreamSession(deviceId, "Phone", "127.0.0.1", NullLog.Instance, maxInFlight, maxChunk);
        var sink = new RecordingSink();
        _engine.AddSession(session);
        _writers.Add(Task.Run(() => session.RunWriterAsync(sink, _cts.Token)));
        return (session, sink);
    }

    private string Path(string name) => _dir.File(name);

    private void Write(string name, byte[] bytes, DateTime? created = null)
    {
        File.WriteAllBytes(Path(name), bytes);
        if (created != null)
            File.SetCreationTimeUtc(Path(name), created.Value);
    }

    private static string[] Types(IEnumerable<ReceivedMessage> messages) => messages.Select(m => m.Type).ToArray();

    [Fact]
    public async Task SubscribeSendsSnapshotProcessDataSyncCompleteOldestFileFirst()
    {
        var a = LogFiles.Line("2024.01.01 09:59:00 Log        -  first file");
        var b = LogFiles.Line("2024.01.02 10:00:00 Log        -  second file");
        Write(B, b, new DateTime(2024, 1, 2, 10, 0, 0, DateTimeKind.Utc));
        Write(A, a, new DateTime(2024, 1, 1, 9, 59, 0, DateTimeKind.Utc));
        _probe.Vrchat = true;

        var (session, sink) = Connect();
        _engine.Subscribe(session, 0, Array.Empty<HaveEntry>());
        var msgs = await sink.WaitForAsync("syncComplete");

        Assert.Equal(new[] { "snapshot", "process", "data", "data", "syncComplete" }, Types(msgs));
        var files = msgs[0].Json.GetProperty("files");
        Assert.Equal(2, files.GetArrayLength());
        Assert.Equal(A, files[0].GetProperty("name").GetString());
        Assert.Equal(new FileInfo(Path(A)).CreationTimeUtc.Ticks, files[0].GetProperty("creationTimeUtcTicks").GetInt64());
        Assert.Equal(a.Length, files[0].GetProperty("length").GetInt64());
        Assert.True(msgs[1].Json.GetProperty("vrchatRunning").GetBoolean());
        Assert.False(msgs[1].Json.GetProperty("steamVrRunning").GetBoolean());
        Assert.Equal(A, msgs[2].Header!.Name);
        Assert.Equal(B, msgs[3].Header!.Name);
        Assert.Equal(a, msgs[2].Data);
        Assert.Equal(b, msgs[3].Data);
        Assert.Equal(files[0].GetProperty("fileId").GetString(), msgs[2].Header!.FileId);
        Assert.False(session.Syncing);
    }

    [Fact]
    public async Task SinceTicksFiltersDataButNotTheSnapshot()
    {
        Write(A, LogFiles.Line("old"));
        File.SetLastWriteTimeUtc(Path(A), new DateTime(2020, 1, 1, 0, 0, 0, DateTimeKind.Utc));
        Write(B, LogFiles.Line("new"));
        var since = new DateTime(2023, 1, 1, 0, 0, 0, DateTimeKind.Utc).Ticks;

        var (session, sink) = Connect();
        _engine.Subscribe(session, since, Array.Empty<HaveEntry>());
        var msgs = await sink.WaitForAsync("syncComplete");
        Assert.Equal(new[] { "snapshot", "process", "data", "syncComplete" }, Types(msgs));
        Assert.Equal(2, msgs[0].Json.GetProperty("files").GetArrayLength());
        Assert.Equal(B, msgs[2].Header!.Name);

        // The old file's lastWrite later crosses since: it is streamed from 0.
        LogFiles.Append(Path(A), LogFiles.Line("appended"));
        _engine.Tick();
        var live = await sink.WaitForAsync("data", msgs.Count);
        Assert.Equal(new[] { "snapshot", "data" }, Types(live));
        Assert.Equal(A, live[1].Header!.Name);
        Assert.Equal(0, live[1].Header!.Offset);
        Assert.Equal(File.ReadAllBytes(Path(A)), live[1].Data);
    }

    [Fact]
    public async Task HaveOffsetsResumeWithoutResending()
    {
        var content = LogFiles.Line("line one").Concat(LogFiles.Line("line two")).ToArray();
        Write(A, content);
        var fileId = Scan().Single().FileId;

        var (session, sink) = Connect();
        _engine.Subscribe(session, 0, new[] { new HaveEntry(A, fileId, 10) });
        var msgs = await sink.WaitForAsync("syncComplete");
        var data = msgs.Single(m => m.Type == "data");
        Assert.Equal(10, data.Header!.Offset);
        Assert.Equal(content[10..], data.Data);

        // Complete have: nothing is sent.
        var from = sink.Count;
        _engine.Subscribe(session, 0, new[] { new HaveEntry(A, fileId, content.Length) });
        Assert.Equal(new[] { "snapshot", "process", "syncComplete" }, Types(await sink.WaitForAsync("syncComplete", from)));

        // A have entry with another fileId is ignored: everything from 0.
        from = sink.Count;
        _engine.Subscribe(session, 0, new[] { new HaveEntry(A, "0000000000000000deadbeef", content.Length) });
        var again = await sink.WaitForAsync("syncComplete", from);
        Assert.Equal(content, LogFiles.Contiguous(again, A));
    }

    [Fact]
    public async Task HaveBeyondTheEndSendsTruncate()
    {
        Write(A, LogFiles.Line("short"));
        var fileId = Scan().Single().FileId;
        var (session, sink) = Connect();
        _engine.Subscribe(session, 0, new[] { new HaveEntry(A, fileId, 1000) });
        var msgs = await sink.WaitForAsync("syncComplete");
        Assert.Equal(new[] { "snapshot", "process", "truncate", "syncComplete" }, Types(msgs));
        Assert.Equal(7, msgs[2].Json.GetProperty("newLength").GetInt64());
        Assert.Equal(fileId, msgs[2].Json.GetProperty("fileId").GetString());
    }

    [Fact]
    public async Task AppendsArriveContiguouslyAndPartialLinesAreUntouched()
    {
        Write(A, LogFiles.Line("2024.01.01 09:59:00 Log        -  start"));
        var (session, sink) = Connect();
        _engine.Subscribe(session, 0, Array.Empty<HaveEntry>());
        await sink.WaitForAsync("syncComplete");

        // Half a line, a split UTF-8 sequence, a BOM in the middle, bare CR and LF endings.
        var chunks = new[]
        {
            Encoding.UTF8.GetBytes("2024.01.01 10:00:00 Log        -  [Behaviour] Joining wrld_split:123~priv"),
            Encoding.UTF8.GetBytes("ate(usr_x)~region(us)\n2024.01.01 10:00:01 Log        -  日").Concat(new byte[] { 0xE6, 0x97 }).ToArray(),
            new byte[] { 0xA5 }.Concat(new byte[] { 0xEF, 0xBB, 0xBF }).Concat(Encoding.ASCII.GetBytes("bom\rcr only\r\ncrlf\n")).ToArray(),
        };
        foreach (var chunk in chunks)
        {
            LogFiles.Append(Path(A), chunk);
            var from = sink.Count;
            _engine.Tick();
            var msgs = await sink.WaitForAsync("data", from);
            Assert.Equal(chunk, msgs.Last().Data);
        }
        Assert.Equal(File.ReadAllBytes(Path(A)), LogFiles.Contiguous(sink.Messages, A));
    }

    [Fact]
    public async Task NoChangeMeansNoSnapshot()
    {
        Write(A, LogFiles.Line("x"));
        var (session, sink) = Connect();
        _engine.Subscribe(session, 0, Array.Empty<HaveEntry>());
        await sink.WaitForAsync("syncComplete");
        var count = sink.Count;
        _engine.Tick();
        _engine.Tick();
        await Task.Delay(100);
        Assert.Equal(count, sink.Count);

        // A metadata change (lastWrite only) produces exactly one snapshot and no data.
        File.SetLastWriteTimeUtc(Path(A), DateTime.UtcNow.AddMinutes(1));
        _engine.Tick();
        _engine.Tick();
        await sink.WaitQuietAsync(150);
        var added = sink.Messages.Skip(count).ToArray();
        Assert.Equal(new[] { "snapshot" }, Types(added));
    }

    [Fact]
    public async Task TruncationSendsTruncateThenResumesAtTheNewLength()
    {
        Write(A, Encoding.ASCII.GetBytes("0123456789abcdefghij"));
        var (session, sink) = Connect();
        _engine.Subscribe(session, 0, Array.Empty<HaveEntry>());
        await sink.WaitForAsync("syncComplete");

        using (var fs = new FileStream(Path(A), FileMode.Open, FileAccess.Write, FileShare.ReadWrite | FileShare.Delete))
            fs.SetLength(5);
        var from = sink.Count;
        _engine.Tick();
        var msgs = await sink.WaitForAsync("truncate", from);
        Assert.Equal(new[] { "snapshot", "truncate" }, Types(msgs));
        Assert.Equal(5, msgs[1].Json.GetProperty("newLength").GetInt64());
        Assert.Equal(A, msgs[1].Json.GetProperty("name").GetString());

        LogFiles.Append(Path(A), Encoding.ASCII.GetBytes("XYZ"));
        from = sink.Count;
        _engine.Tick();
        var data = (await sink.WaitForAsync("data", from)).Last();
        Assert.Equal(5, data.Header!.Offset);
        Assert.Equal("XYZ", Encoding.ASCII.GetString(data.Data!));
    }

    [Fact]
    public async Task DeletedFileDropsOutOfTheSnapshot()
    {
        Write(A, LogFiles.Line("a"));
        Write(B, LogFiles.Line("b"));
        var (session, sink) = Connect();
        _engine.Subscribe(session, 0, Array.Empty<HaveEntry>());
        await sink.WaitForAsync("syncComplete");

        File.Delete(Path(A));
        var from = sink.Count;
        _engine.Tick();
        var msgs = await sink.WaitForAsync("snapshot", from);
        var files = msgs.Last().Json.GetProperty("files");
        Assert.Equal(B, files.EnumerateArray().Single().GetProperty("name").GetString());
        await sink.WaitQuietAsync(100);
        Assert.Single(sink.Messages.Skip(from));
    }

    [Fact]
    public async Task ReplacedFileIsSentAsDeleteThenCreateWithANewFileId()
    {
        Write(A, LogFiles.Line("original content"));
        var (session, sink) = Connect();
        _engine.Subscribe(session, 0, Array.Empty<HaveEntry>());
        var initial = await sink.WaitForAsync("syncComplete");
        var oldId = initial.First(m => m.Type == "data").Header!.FileId;

        File.Delete(Path(A));
        var replacement = LogFiles.Line("new");
        Write(A, replacement);
        var from = sink.Count;
        _engine.Tick();
        var msgs = await sink.WaitForAsync("data", from);
        Assert.Equal(new[] { "snapshot", "snapshot", "data" }, Types(msgs));
        Assert.Equal(0, msgs[0].Json.GetProperty("files").GetArrayLength());
        var entry = msgs[1].Json.GetProperty("files")[0];
        var newId = entry.GetProperty("fileId").GetString();
        Assert.NotEqual(oldId, newId);
        Assert.Equal(newId, msgs[2].Header!.FileId);
        Assert.Equal(0, msgs[2].Header!.Offset);
        Assert.Equal(replacement, msgs[2].Data);
    }

    [Fact]
    public async Task ProcessChangeTravelsAfterTheDataWrittenBeforeIt()
    {
        Write(A, LogFiles.Line("2024.01.01 09:59:00 Log        -  start"));
        var (session, sink) = Connect();
        _engine.Subscribe(session, 0, Array.Empty<HaveEntry>());
        await sink.WaitForAsync("syncComplete");

        // VRChat writes its last lines, then exits; both are observed by the same poll.
        _probe.Vrchat = true;
        var from = sink.Count;
        _engine.Tick();
        Assert.Equal(new[] { "process" }, Types(await sink.WaitForAsync("process", from)));

        LogFiles.Append(Path(A), LogFiles.Line("2024.01.01 10:00:00 Log        -  VRCApplication: HandleApplicationQuit"));
        _probe.Vrchat = false;
        from = sink.Count;
        _engine.Tick();
        var msgs = await sink.WaitForAsync("process", from);
        Assert.Equal(new[] { "snapshot", "data", "process" }, Types(msgs));
        Assert.False(msgs[2].Json.GetProperty("vrchatRunning").GetBoolean());
    }

    [Fact]
    public async Task ProcessChangeWaitsBehindAFlowControlledBacklog()
    {
        var content = new byte[200 * 1024];
        new Random(7).NextBytes(content);
        Write(A, Array.Empty<byte>());
        var (session, sink) = Connect(maxInFlight: 64 * 1024, maxChunk: 16 * 1024);
        _engine.Subscribe(session, 0, Array.Empty<HaveEntry>());
        await sink.WaitForAsync("syncComplete");

        LogFiles.Append(Path(A), content);
        _probe.SteamVr = true;
        _engine.Tick();
        await sink.WaitQuietAsync(150);
        Assert.DoesNotContain(sink.Messages, m => m.Type == "process" && m.Json.GetProperty("steamVrRunning").GetBoolean());
        Assert.True(session.BytesInFlight <= 64 * 1024);

        // The phone acks everything it gets until the process message arrives.
        var deadline = Environment.TickCount64 + 10000;
        while (!sink.Messages.Any(m => m.Type == "process" && m.Json.GetProperty("steamVrRunning").GetBoolean()))
        {
            Assert.True(Environment.TickCount64 < deadline, "process never arrived");
            session.OnAck(Interlocked.Read(ref sink.DataWireBytes));
            await Task.Delay(10);
        }
        var all = sink.Messages;
        var processIndex = all.ToList().FindIndex(m => m.Type == "process" && m.Json.GetProperty("steamVrRunning").GetBoolean());
        var lastData = all.ToList().FindLastIndex(m => m.Type == "data");
        Assert.True(lastData < processIndex);
        Assert.Equal(content, LogFiles.Contiguous(all, A));
    }

    [Fact]
    public async Task FlowControlKeepsAtMostFourMiBInFlight()
    {
        var content = new byte[10 * 1024 * 1024];
        new Random(11).NextBytes(content); // incompressible: wire bytes ≈ raw bytes
        Write(A, content);
        var (session, sink) = Connect();
        _engine.Subscribe(session, 0, Array.Empty<HaveEntry>());

        await sink.WaitQuietAsync(200);
        Assert.DoesNotContain(sink.Messages, m => m.Type == "syncComplete");
        var inFlight = Interlocked.Read(ref sink.DataWireBytes);
        Assert.True(inFlight <= 4L * 1024 * 1024, $"in flight {inFlight}");
        Assert.True(inFlight >= 3L * 1024 * 1024, $"in flight {inFlight}");
        Assert.Equal(inFlight, session.BytesInFlight);

        // Acks at 1 MiB granularity, as the phone does, release the rest.
        var deadline = Environment.TickCount64 + 20000;
        while (!sink.Messages.Any(m => m.Type == "syncComplete"))
        {
            Assert.True(Environment.TickCount64 < deadline, "sync never completed");
            var received = Interlocked.Read(ref sink.DataWireBytes);
            if (received - session.AckedBytes >= 1024 * 1024)
                session.OnAck(received);
            Assert.True(session.BytesInFlight <= 4L * 1024 * 1024);
            await Task.Delay(5);
        }
        Assert.Equal(content, LogFiles.Contiguous(sink.Messages, A));
    }

    [Fact]
    public async Task LogFileIsNotHeldOpenWhileWaitingForAcks()
    {
        var content = new byte[1024 * 1024];
        new Random(5).NextBytes(content);
        Write(A, content);
        var (session, sink) = Connect(maxInFlight: 64 * 1024, maxChunk: 16 * 1024);
        _engine.Subscribe(session, 0, Array.Empty<HaveEntry>());
        await sink.WaitQuietAsync(200);
        Assert.True(session.BytesInFlight > 0);
        Assert.DoesNotContain(sink.Messages, m => m.Type == "syncComplete");

        // Exclusive open fails if any other handle is open.
        using (new FileStream(Path(A), FileMode.Open, FileAccess.ReadWrite, FileShare.None))
        {
        }

        var deadline = Environment.TickCount64 + 10000;
        while (!sink.Messages.Any(m => m.Type == "syncComplete"))
        {
            Assert.True(Environment.TickCount64 < deadline);
            session.OnAck(Interlocked.Read(ref sink.DataWireBytes));
            await Task.Delay(5);
        }
        Assert.Equal(content, LogFiles.Contiguous(sink.Messages, A));
    }

    [Fact]
    public void SessionThatStopsDrainingIsClosed()
    {
        var session = new StreamSession("dev", "Phone", "127.0.0.1", NullLog.Instance);
        for (var i = 0; i < StreamSession.MaxQueuedItems; i++)
            session.EnqueueControl("process", new byte[] { (byte)'{', (byte)'}' });
        Assert.False(session.Closed.IsCancellationRequested);
        session.EnqueueControl("process", new byte[] { (byte)'{', (byte)'}' });
        Assert.True(session.Closed.IsCancellationRequested);
        Assert.Equal("queue overflow", session.CloseReason);
    }

    [Fact]
    public async Task AcksAreClampedAndMonotonic()
    {
        Write(A, LogFiles.Line("x"));
        var (session, sink) = Connect();
        _engine.Subscribe(session, 0, Array.Empty<HaveEntry>());
        await sink.WaitForAsync("syncComplete");
        session.OnAck(long.MaxValue);
        Assert.Equal(session.DataWireBytesSent, session.AckedBytes);
        session.OnAck(1);
        Assert.Equal(session.DataWireBytesSent, session.AckedBytes);
        Assert.Equal(0, session.BytesInFlight);
    }

    [Fact]
    public async Task FetchResendsFromTheRequestedOffset()
    {
        var content = Encoding.ASCII.GetBytes("0123456789");
        Write(A, content);
        var (session, sink) = Connect();
        _engine.Subscribe(session, 0, Array.Empty<HaveEntry>());
        var initial = await sink.WaitForAsync("syncComplete");
        var fileId = initial.First(m => m.Type == "data").Header!.FileId;

        var from = sink.Count;
        _engine.Fetch(session, A, fileId, 4);
        var data = (await sink.WaitForAsync("data", from)).Last();
        Assert.Equal(4, data.Header!.Offset);
        Assert.Equal("456789", Encoding.ASCII.GetString(data.Data!));

        // Afterwards, live data stays contiguous.
        LogFiles.Append(Path(A), Encoding.ASCII.GetBytes("ab"));
        from = sink.Count;
        _engine.Tick();
        var live = (await sink.WaitForAsync("data", from)).Last();
        Assert.Equal(10, live.Header!.Offset);

        // Unknown file ids are ignored.
        from = sink.Count;
        _engine.Fetch(session, A, "nope", 0);
        await sink.WaitQuietAsync(100);
        Assert.Equal(from, sink.Count);
    }

    [Fact]
    public async Task NewSessionOfTheSameDeviceClosesTheOlderOne()
    {
        var (first, _) = Connect("dev-1");
        var (other, _) = Connect("dev-2");
        var (second, _) = Connect("dev-1");
        Assert.True(first.Closed.IsCancellationRequested);
        Assert.Equal("replaced", first.CloseReason);
        Assert.False(second.Closed.IsCancellationRequested);
        Assert.False(other.Closed.IsCancellationRequested);
        Assert.Equal(2, _engine.Sessions.Count);

        _engine.CloseDevice("dev-2", "revoked");
        Assert.True(other.Closed.IsCancellationRequested);
        await Task.CompletedTask;
    }

    [Fact]
    public async Task NoPollingWorkWhileNobodyIsSubscribed()
    {
        Write(A, LogFiles.Line("x"));
        var (session, _) = Connect();
        _engine.Tick();
        _engine.Tick();
        Assert.Equal(0, _probe.Polls);

        using (_engine.ObserveStatus())
        {
            _engine.Tick();
            Assert.Equal(1, _probe.Polls);
            Assert.True(_engine.LastDirectoryExists);
        }
        _engine.Tick();
        Assert.Equal(1, _probe.Polls);
        await Task.CompletedTask;
        Assert.False(session.Subscribed);
    }

    [Fact]
    public async Task RunLoopPollsWhileSubscribed()
    {
        using var engine = new SyncEngine(new LogDirectoryTailer(_dir.Path, NullLog.Instance, useWatcher: false), _probe, NullLog.Instance)
        {
            PollInterval = TimeSpan.FromMilliseconds(50),
        };
        Write(A, LogFiles.Line("x"));
        var session = new StreamSession("d", "P", "127.0.0.1", NullLog.Instance);
        var sink = new RecordingSink();
        engine.AddSession(session);
        _writers.Add(Task.Run(() => session.RunWriterAsync(sink, _cts.Token)));
        _writers.Add(Task.Run(() => engine.RunAsync(_cts.Token)));
        engine.Subscribe(session, 0, Array.Empty<HaveEntry>());
        await sink.WaitForAsync("syncComplete");

        LogFiles.Append(Path(A), LogFiles.Line("live"));
        var msgs = await sink.WaitForAsync("data", sink.Count);
        Assert.Equal(LogFiles.Line("live"), msgs.Last().Data);
    }

    [Fact]
    public async Task InfoIsDeliveredIndependentlyOfSubscriptions()
    {
        Write(A, LogFiles.Line("x"));
        var (session, sink) = Connect();
        _engine.Broadcast("info", () => Encoding.UTF8.GetBytes("{\"t\":\"info\"}"));
        _engine.Subscribe(session, 0, Array.Empty<HaveEntry>());
        var msgs = await sink.WaitForAsync("syncComplete");
        Assert.Equal("info", msgs[0].Type);
    }

    private IReadOnlyList<LogFileMeta> Scan()
    {
        using var t = new LogDirectoryTailer(_dir.Path, NullLog.Instance, useWatcher: false);
        return t.Scan()!.Files;
    }
}

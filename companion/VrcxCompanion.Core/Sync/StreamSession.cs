using System.Buffers;
using Microsoft.Win32.SafeHandles;
using VrcxCompanion.Core.Diagnostics;
using VrcxCompanion.Core.Logs;
using VrcxCompanion.Core.Processes;
using VrcxCompanion.Core.Protocol;

namespace VrcxCompanion.Core.Sync;

/// <summary>Writes complete frames to the connection. Implementations serialize concurrent writers.</summary>
public interface IFrameSink
{
    ValueTask WriteFrameAsync(ReadOnlyMemory<byte> frame, CancellationToken cancellationToken);
}

/// <summary>Send state of one file for one subscription.</summary>
internal sealed class FileSendState
{
    public FileSendState(string name, string fileId, string path, long start)
    {
        Name = name;
        FileId = fileId;
        Path = path;
        QueuedEnd = start;
        SentEnd = start;
    }

    public string Name { get; }
    public string FileId { get; }
    public string Path { get; }

    /// <summary>Whether data is streamed for this file (lastWrite reached <c>sinceUtcTicks</c>, or fetched).</summary>
    public bool Eligible { get; set; }

    /// <summary>End offset of the data queued so far. Owned by the engine (under its lock).</summary>
    public long QueuedEnd { get; set; }

    /// <summary>
    /// Bytes the phone has for this file, as far as the writer knows: the next data frame starts here. Owned by the
    /// writer after creation.
    /// </summary>
    public long SentEnd { get; set; }

    /// <summary>Consecutive attempts to read this file that failed while the file was still there. Owned by the writer.</summary>
    public int ReadFailures { get; set; }

    /// <summary>
    /// The file stayed unreadable for <see cref="StreamSession.MaxReadRetries"/> polls: its data no longer holds back
    /// the queue, and each later poll retries it until a read succeeds. Owned by the writer.
    /// </summary>
    public bool SetAside { get; set; }

    private int _retryRequested;

    /// <summary>Asks the engine to queue this file's unsent bytes again on its next poll (set by the writer).</summary>
    public void RequestRetry() => Volatile.Write(ref _retryRequested, 1);

    /// <summary>Consumes a pending <see cref="RequestRetry"/> (called by the engine under its lock).</summary>
    public bool TakeRetryRequest() => Interlocked.Exchange(ref _retryRequested, 0) == 1;
}

internal abstract class OutItem
{
    protected OutItem(int epoch) => Epoch = epoch;

    /// <summary>Subscription epoch; items of an older epoch are dropped. -1 = independent of the subscription.</summary>
    public int Epoch { get; }
}

internal sealed class ControlItem : OutItem
{
    public ControlItem(int epoch, string type, byte[] json) : base(epoch)
    {
        Type = type;
        Json = json;
    }

    public string Type { get; }
    public byte[] Json { get; }
}

/// <summary>Send the bytes from the file's <c>SentEnd</c> up to <see cref="End"/>.</summary>
internal sealed class DataItem : OutItem
{
    public DataItem(int epoch, FileSendState file, long end) : base(epoch)
    {
        File = file;
        End = end;
    }

    public FileSendState File { get; }
    public long End { get; set; }
}

/// <summary>The file shrank to <see cref="NewLength"/>: send <c>truncate</c> if the phone has more.</summary>
internal sealed class TruncateItem : OutItem
{
    public TruncateItem(int epoch, FileSendState file, long newLength) : base(epoch)
    {
        File = file;
        NewLength = newLength;
    }

    public FileSendState File { get; }
    public long NewLength { get; }
}

/// <summary>Answer to <c>fetch</c>: resend from <see cref="Offset"/>.</summary>
internal sealed class RewindItem : OutItem
{
    public RewindItem(int epoch, FileSendState file, long offset) : base(epoch)
    {
        File = file;
        Offset = offset;
    }

    public FileSendState File { get; }
    public long Offset { get; }
}

/// <summary>
/// One authenticated phone connection as seen by the sync engine: the single ordered writer queue (snapshot, process,
/// data, truncate, syncComplete, info), the writer that drains it, and ack-based flow control. Data items are
/// materialized lazily, so a <c>process</c> message queued after them is sent only after the bytes they cover.
/// </summary>
public sealed class StreamSession
{
    private readonly object _queueGate = new();
    private readonly LinkedList<OutItem> _queue = new();
    private readonly AsyncSignal _queueSignal = new();
    private readonly AsyncSignal _ackSignal = new();
    private readonly AsyncSignal _pollSignal = new();
    private readonly AsyncSignal _modeSignal = new();
    private readonly CancellationTokenSource _closed = new();
    private readonly ICompanionLog _log;
    private readonly long _maxInFlight;
    private readonly int _maxChunk;
    private int _epoch;
    private long _dataWireBytesSent;
    private long _ackedBytes;
    private long _bytesSent;
    private long _rawBytesSent;
    private long _polls;
    private int _idle;

    public StreamSession(string deviceId, string deviceName, string remoteAddress, ICompanionLog log,
        long maxInFlight = ProtocolConstants.MaxBytesInFlight, int maxChunk = ProtocolConstants.MaxChunkBytes)
    {
        DeviceId = deviceId;
        DeviceName = deviceName;
        RemoteAddress = remoteAddress;
        ConnectedAtUtc = DateTimeOffset.UtcNow;
        _log = log;
        _maxInFlight = maxInFlight;
        _maxChunk = maxChunk;
    }

    public string DeviceId { get; }
    public string DeviceName { get; }
    public string RemoteAddress { get; }
    public DateTimeOffset ConnectedAtUtc { get; }

    /// <summary>Total bytes of all frames written by <see cref="RecordWrite"/> (all frame types).</summary>
    public long BytesSent => Interlocked.Read(ref _bytesSent);

    /// <summary>Raw log bytes delivered in data frames (before compression).</summary>
    public long RawLogBytesSent => Interlocked.Read(ref _rawBytesSent);

    /// <summary>Wire bytes of data frames (length prefix included); what <c>ack.bytes</c> is compared against.</summary>
    public long DataWireBytesSent => Interlocked.Read(ref _dataWireBytesSent);

    public long AckedBytes => Interlocked.Read(ref _ackedBytes);

    /// <summary>True between a <c>subscribe</c> and the matching <c>syncComplete</c> being written.</summary>
    public bool Syncing { get; private set; }

    /// <summary>Cancelled when the session must close (replaced by a newer session of the device, revoked, ...).</summary>
    public CancellationToken Closed => _closed.Token;

    public string? CloseReason { get; private set; }

    /// <summary>
    /// The phone said its app is in the background (<c>{"t":"idle","on":true}</c>, PROTOCOL.md §5.11): heartbeats
    /// stretch, the receive timeout grows and log growth is sent in batches.
    /// </summary>
    public bool Idle => Volatile.Read(ref _idle) == 1;

    /// <summary>Sets <see cref="Idle"/>; returns whether it changed. Use <see cref="SyncEngine.SetIdle"/>.</summary>
    internal bool SetIdle(bool idle)
    {
        var value = idle ? 1 : 0;
        if (Interlocked.Exchange(ref _idle, value) == value)
            return false;
        _modeSignal.Set();
        return true;
    }

    /// <summary>Completes when <see cref="Idle"/> changes (one waiter: the connection's heartbeat loop).</summary>
    internal Task WaitForModeChangeAsync(CancellationToken cancellationToken) => _modeSignal.WaitAsync(cancellationToken);

    private int _reportedDirExists = -1;

    /// <summary>The <c>dirExists</c> value of the last <c>info</c> sent or queued to this session (null: none yet).</summary>
    public bool? ReportedDirExists
    {
        get => Volatile.Read(ref _reportedDirExists) switch
        {
            0 => false,
            1 => true,
            _ => null,
        };
        set => Volatile.Write(ref _reportedDirExists, value switch
        {
            false => 0,
            true => 1,
            null => -1,
        });
    }

    // ---- subscription state, owned by SyncEngine under its lock ----
    internal bool Subscribed { get; set; }
    internal (long since, IReadOnlyList<HaveEntry> have)? PendingSubscribe { get; set; }
    internal long SinceTicks { get; set; }
    internal Dictionary<string, FileSendState> Files { get; } = new(StringComparer.Ordinal);
    internal IReadOnlyList<LogFileMeta>? LastSnapshot { get; set; }
    internal ProcessState? LastProcess { get; set; }

    /// <summary>While <see cref="Idle"/>: the poll from which log growth is queued again.</summary>
    internal DateTimeOffset NextIdleFlush { get; set; }

    internal int Epoch => Volatile.Read(ref _epoch);

    internal int NextEpoch()
    {
        Syncing = true;
        var epoch = Interlocked.Increment(ref _epoch);
        _pollSignal.Set(); // a writer holding a stale item for a retry drops it now
        return epoch;
    }

    /// <summary>Called by the engine after each poll: a data item waiting for a retry is attempted again.</summary>
    internal void OnPoll()
    {
        Interlocked.Increment(ref _polls);
        _pollSignal.Set();
    }

    public void RequestClose(string reason)
    {
        CloseReason ??= reason;
        try
        {
            _closed.Cancel();
        }
        catch (ObjectDisposedException)
        {
        }
    }

    // ---- queue ----

    /// <summary>
    /// Polls for which a data item whose file exists but cannot be opened or read (another process holds it without
    /// sharing, for example) stays at the head of the queue, so the <c>process</c> and <c>syncComplete</c> messages
    /// queued after it keep waiting behind its bytes (§5.5, §5.8). After that the file is set aside: the rest of the
    /// queue flows, and the engine queues the file's unsent bytes again on every poll until a read succeeds.
    /// </summary>
    public const int MaxReadRetries = 5;

    /// <summary>
    /// Upper bound on queued items. A phone that stops acknowledging while the log keeps changing would otherwise grow
    /// the queue without limit; such a session is closed and resumes through <c>subscribe.have</c> after reconnecting.
    /// </summary>
    public const int MaxQueuedItems = 10_000;

    internal void Enqueue(OutItem item)
    {
        bool overflow;
        lock (_queueGate)
        {
            _queue.AddLast(item);
            overflow = _queue.Count > MaxQueuedItems;
        }
        _queueSignal.Set();
        if (overflow)
        {
            _log.Warn("send queue overflow (the phone stopped acknowledging); closing the session");
            RequestClose("queue overflow");
        }
    }

    internal void EnqueueControl(string type, byte[] json, bool independentOfSubscription = false) =>
        Enqueue(new ControlItem(independentOfSubscription ? -1 : Epoch, type, json));

    /// <summary>Queues "send up to <paramref name="end"/>", extending the last queued item when it is for the same file.</summary>
    internal void EnqueueData(FileSendState file, long end)
    {
        var epoch = Epoch;
        lock (_queueGate)
        {
            if (_queue.Last?.Value is DataItem tail && tail.File == file && tail.Epoch == epoch)
            {
                tail.End = Math.Max(tail.End, end);
                return;
            }
            _queue.AddLast(new DataItem(epoch, file, end));
        }
        _queueSignal.Set();
    }

    internal int QueueLength
    {
        get
        {
            lock (_queueGate)
                return _queue.Count;
        }
    }

    // ---- flow control ----

    /// <summary>
    /// Handles <c>ack.bytes</c>. The companion counts data-frame wire bytes (length prefix included). A phone that counts
    /// fewer bytes per frame (without the prefix) or more (inflated bytes) cannot deadlock: acks are clamped, and the
    /// 4 MiB window is well above the phone's 512 KiB ack interval.
    /// </summary>
    public void OnAck(long bytes)
    {
        if (bytes < 0)
            return;
        var clamped = Math.Min(bytes, DataWireBytesSent);
        long current;
        do
        {
            current = Interlocked.Read(ref _ackedBytes);
            if (clamped <= current)
                return;
        } while (Interlocked.CompareExchange(ref _ackedBytes, clamped, current) != current);
        _ackSignal.Set();
    }

    public long BytesInFlight => DataWireBytesSent - AckedBytes;

    /// <summary>Records a frame written to the connection (called by the connection for every frame).</summary>
    public void RecordWrite(int frameBytes) => Interlocked.Add(ref _bytesSent, frameBytes);

    // ---- writer ----

    /// <summary>Drains the queue into <paramref name="sink"/> until cancelled or the sink fails.</summary>
    public async Task RunWriterAsync(IFrameSink sink, CancellationToken cancellationToken)
    {
        while (!cancellationToken.IsCancellationRequested)
        {
            OutItem? item;
            lock (_queueGate)
            {
                item = _queue.First?.Value;
                if (item != null)
                    _queue.RemoveFirst();
            }
            if (item == null)
            {
                await _queueSignal.WaitAsync(cancellationToken).ConfigureAwait(false);
                continue;
            }
            if (item.Epoch != -1 && item.Epoch != Epoch)
                continue;

            switch (item)
            {
                case ControlItem c:
                    await WriteAsync(sink, FrameCodec.EncodeControl(c.Json), cancellationToken).ConfigureAwait(false);
                    if (c.Type == "syncComplete" && item.Epoch == Epoch)
                        Syncing = false;
                    break;
                case TruncateItem t:
                    if (t.File.SentEnd > t.NewLength)
                    {
                        var json = ControlMessages.Truncate(t.File.Name, t.File.FileId, t.NewLength);
                        await WriteAsync(sink, FrameCodec.EncodeControl(json), cancellationToken).ConfigureAwait(false);
                        t.File.SentEnd = t.NewLength;
                    }
                    break;
                case RewindItem r:
                    r.File.SentEnd = r.Offset;
                    break;
                case DataItem d:
                    var result = await SendDataAsync(sink, d, cancellationToken).ConfigureAwait(false);
                    if (result == SendResult.Failed && d.Epoch == Epoch)
                        await OnReadFailedAsync(d, cancellationToken).ConfigureAwait(false);
                    break;
            }
        }
    }

    /// <summary>
    /// A data item could not be opened or read, but its file is still there. The item goes back to the head of the
    /// queue and is retried after the next poll, for up to <see cref="MaxReadRetries"/> polls. After that the file is
    /// set aside and the engine queues its unsent bytes again on each poll.
    /// </summary>
    private async Task OnReadFailedAsync(DataItem item, CancellationToken cancellationToken)
    {
        var file = item.File;
        file.ReadFailures++;
        if (!file.SetAside && file.ReadFailures <= MaxReadRetries)
        {
            if (file.ReadFailures == 1)
                _log.Info($"{file.Name} cannot be read right now; retrying after the next poll");
            var seen = Interlocked.Read(ref _polls);
            lock (_queueGate)
                _queue.AddFirst(item);
            while (Interlocked.Read(ref _polls) == seen && item.Epoch == Epoch)
                await _pollSignal.WaitAsync(cancellationToken).ConfigureAwait(false);
            return;
        }
        if (!file.SetAside)
        {
            file.SetAside = true;
            _log.Warn($"{file.Name} stayed unreadable for {MaxReadRetries} polls; other updates are sent meanwhile and it is retried on every poll");
        }
        file.RequestRetry();
    }

    private async ValueTask WriteAsync(IFrameSink sink, byte[] frame, CancellationToken cancellationToken)
    {
        await sink.WriteFrameAsync(frame, cancellationToken).ConfigureAwait(false);
    }

    private enum SendResult
    {
        /// <summary>Sent up to the item's end, or nothing left to send (stale epoch, the file shrank).</summary>
        Done,

        /// <summary>The file was deleted or replaced; the next snapshot tells the phone.</summary>
        Gone,

        /// <summary>The file is still there but could not be opened or read right now.</summary>
        Failed,
    }

    private async Task<SendResult> SendDataAsync(IFrameSink sink, DataItem item, CancellationToken cancellationToken)
    {
        var file = item.File;
        SafeFileHandle? handle = null;
        var buffer = ArrayPool<byte>.Shared.Rent(_maxChunk);
        try
        {
            while (file.SentEnd < item.End && item.Epoch == Epoch)
            {
                var want = (int)Math.Min(_maxChunk, item.End - file.SentEnd);
                if (!HasCredit(want + 512))
                {
                    // Never hold VRChat's log open while waiting for the phone.
                    handle?.Dispose();
                    handle = null;
                    await WaitForCreditAsync(want + 512, cancellationToken).ConfigureAwait(false);
                }
                if (item.Epoch != Epoch)
                    return SendResult.Done;

                if (handle == null)
                {
                    var opened = OpenVerified(file, out handle);
                    if (opened != SendResult.Done)
                        return opened;
                }

                int read;
                try
                {
                    read = RandomAccess.Read(handle!, buffer.AsSpan(0, want), file.SentEnd);
                }
                catch (IOException e)
                {
                    if (file.ReadFailures == 0)
                        _log.Warn($"reading {file.Name} failed", e);
                    return SendResult.Failed;
                }
                if (read <= 0)
                    return SendResult.Done; // the file shrank; the next poll sends truncate

                using var frame = FrameCodec.EncodeData(file.Name, file.FileId, file.SentEnd, buffer.AsSpan(0, read));
                await sink.WriteFrameAsync(frame.Memory, cancellationToken).ConfigureAwait(false);
                Interlocked.Add(ref _dataWireBytesSent, frame.Length);
                Interlocked.Add(ref _rawBytesSent, read);
                file.SentEnd += read;
                if (file.ReadFailures > 0)
                {
                    if (file.SetAside)
                        _log.Info($"{file.Name} can be read again");
                    file.ReadFailures = 0;
                    file.SetAside = false;
                }
            }
            return SendResult.Done;
        }
        finally
        {
            handle?.Dispose();
            ArrayPool<byte>.Shared.Return(buffer);
        }
    }

    private bool HasCredit(int nextFrameBytes) => BytesInFlight + nextFrameBytes <= _maxInFlight || BytesInFlight <= 0;

    private async Task WaitForCreditAsync(int nextFrameBytes, CancellationToken cancellationToken)
    {
        while (!HasCredit(nextFrameBytes))
            await _ackSignal.WaitAsync(cancellationToken).ConfigureAwait(false);
    }

    /// <summary>
    /// Opens the file and checks that it is still the file this state belongs to. Returns <see cref="SendResult.Done"/>
    /// with an open handle, <see cref="SendResult.Gone"/> when the file was deleted or replaced, and
    /// <see cref="SendResult.Failed"/> when it cannot be opened right now (sharing violation, access denied while a
    /// delete is pending, I/O error).
    /// </summary>
    private static SendResult OpenVerified(FileSendState file, out SafeFileHandle? handle)
    {
        handle = null;
        SafeFileHandle opened;
        try
        {
            opened = LogFileAccess.OpenShared(file.Path);
        }
        catch (Exception e) when (e is FileNotFoundException or DirectoryNotFoundException)
        {
            return SendResult.Gone;
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            return SendResult.Failed;
        }
        try
        {
            if (LogFileAccess.GetFileId(opened, file.Path) != file.FileId)
            {
                opened.Dispose();
                return SendResult.Gone;
            }
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            opened.Dispose();
            return SendResult.Failed;
        }
        handle = opened;
        return SendResult.Done;
    }
}

/// <summary>An entry of <c>subscribe.have</c>.</summary>
public sealed record HaveEntry(string Name, string FileId, long Length);

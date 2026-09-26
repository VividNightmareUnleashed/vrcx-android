using VrcxCompanion.Core.Diagnostics;
using VrcxCompanion.Core.Logs;
using VrcxCompanion.Core.Processes;
using VrcxCompanion.Core.Protocol;

namespace VrcxCompanion.Core.Sync;

/// <summary>
/// Drives the subscriptions (PROTOCOL.md §5.5-5.8). One poll every 1000 ms while at least one phone is subscribed
/// (or a status observer is registered); completely idle otherwise. Each poll observes the process state first, then
/// lists the log directory, then queues, per session and in this order: snapshots (only on change), truncates, data
/// up to the observed end of each file, and the process state if it changed. Because data is queued before the
/// process message, the phone applies a process change after every byte written before it was observed.
/// <para>
/// For a session whose phone is idle (§5.11) the snapshot, truncate and data part is queued only every
/// <see cref="IdleFlushInterval"/>, or at once when the process state changed (the change still travels behind the
/// bytes written before it). Each such queueing covers everything since the previous one, so batching changes when
/// bytes travel, never which bytes or in which order.
/// </para>
/// </summary>
public sealed class SyncEngine : IDisposable
{
    private readonly object _gate = new();
    private readonly List<StreamSession> _sessions = new();
    private readonly LogDirectoryTailer _tailer;
    private readonly IProcessProbe _probe;
    private readonly ICompanionLog _log;
    private readonly TimeProvider _time;
    private readonly AsyncSignal _activity = new();
    private int _observers;
    private bool _tailerActive;
    private ProcessState? _lastProcess;
    private bool? _lastDirExists;

    public SyncEngine(LogDirectoryTailer tailer, IProcessProbe probe, ICompanionLog log, TimeProvider? time = null)
    {
        _tailer = tailer;
        _probe = probe;
        _log = log;
        _time = time ?? TimeProvider.System;
    }

    public TimeSpan PollInterval { get; init; } = ProtocolConstants.PollInterval;

    /// <summary>How often log growth is queued for an idle phone (PROTOCOL.md §5.11).</summary>
    public TimeSpan IdleFlushInterval { get; init; } = ProtocolConstants.IdleFlushInterval;

    /// <summary>
    /// Whether a device is still paired. <see cref="AddSession"/> refuses a session whose device was removed while
    /// its connection was still in the handshake (the revocation closed the sessions registered at that moment only).
    /// </summary>
    public Func<string, bool> IsDevicePaired { get; init; } = _ => true;

    /// <summary>
    /// Raised (outside the engine lock) when the log directory appears or disappears, and with the first observation
    /// after an idle period (when nothing was watched).
    /// </summary>
    public event Action<bool>? DirectoryExistsChanged;

    /// <summary>Raised (outside the engine lock) when the observed process state changes.</summary>
    public event Action<ProcessState>? ProcessStateChanged;

    /// <summary>Raised (outside the engine lock) when a session is added or removed.</summary>
    public event Action? SessionsChanged;

    public ProcessState? LastProcessState
    {
        get
        {
            lock (_gate)
                return _lastProcess;
        }
    }

    public bool? LastDirectoryExists
    {
        get
        {
            lock (_gate)
                return _lastDirExists;
        }
    }

    public string LogDirectory => _tailer.DirectoryPath;

    public IReadOnlyList<StreamSession> Sessions
    {
        get
        {
            lock (_gate)
                return _sessions.ToArray();
        }
    }

    /// <summary>
    /// Registers an authenticated session. An older session of the same device is closed. Returns false, and closes
    /// the session with reason "revoked", when the device is no longer paired: a revocation removes the device before
    /// it closes the registered sessions, and this check runs under the same lock as that close, so a session is
    /// either refused here or closed there.
    /// </summary>
    public bool AddSession(StreamSession session)
    {
        List<StreamSession> replaced;
        lock (_gate)
        {
            if (!IsDevicePaired(session.DeviceId))
            {
                session.RequestClose("revoked");
                return false;
            }
            replaced = _sessions.Where(s => s.DeviceId == session.DeviceId).ToList();
            foreach (var old in replaced)
                _sessions.Remove(old);
            _sessions.Add(session);
        }
        foreach (var old in replaced)
        {
            _log.Info("closing the older session of a device that connected again");
            old.RequestClose("replaced");
        }
        SessionsChanged?.Invoke();
        return true;
    }

    /// <summary>
    /// Handles the phone's <c>idle</c> message (PROTOCOL.md §5.11). Going idle holds log growth back until
    /// <see cref="IdleFlushInterval"/> has passed; leaving it queues the growth at the next poll.
    /// </summary>
    public void SetIdle(StreamSession session, bool idle)
    {
        lock (_gate)
        {
            if (!session.SetIdle(idle))
                return;
            session.NextIdleFlush = idle ? _time.GetUtcNow() + IdleFlushInterval : DateTimeOffset.MinValue;
        }
        _log.Debug(idle ? "phone went idle" : "phone is active again");
    }

    public void RemoveSession(StreamSession session)
    {
        bool removed;
        lock (_gate)
            removed = _sessions.Remove(session);
        if (removed)
            SessionsChanged?.Invoke();
    }

    /// <summary>Closes the sessions of a device (it was revoked).</summary>
    public void CloseDevice(string deviceId, string reason)
    {
        List<StreamSession> matching;
        lock (_gate)
        {
            matching = _sessions.Where(s => s.DeviceId == deviceId).ToList();
            foreach (var s in matching)
                _sessions.Remove(s);
        }
        foreach (var s in matching)
            s.RequestClose(reason);
        if (matching.Count > 0)
            SessionsChanged?.Invoke();
    }

    /// <summary>Queues a control message on every registered session (e.g. <c>info</c>), independent of subscriptions.</summary>
    public void Broadcast(string type, Func<byte[]> build)
    {
        StreamSession[] sessions;
        lock (_gate)
            sessions = _sessions.ToArray();
        if (sessions.Length == 0)
            return;
        var json = build();
        foreach (var s in sessions)
            s.EnqueueControl(type, json, independentOfSubscription: true);
    }

    /// <summary>
    /// Keeps the process monitor (and a directory check) running while the returned object is alive, for the status
    /// window.
    /// </summary>
    public IDisposable ObserveStatus()
    {
        lock (_gate)
            _observers++;
        _activity.Set();
        return new Observer(this);
    }

    /// <summary>
    /// Handles <c>subscribe</c>: restarts the sequence snapshot, process, data from the have offsets, syncComplete.
    /// </summary>
    public void Subscribe(StreamSession session, long sinceUtcTicks, IReadOnlyList<HaveEntry> have)
    {
        Action? after;
        lock (_gate)
        {
            if (!_sessions.Contains(session))
                return;
            session.NextEpoch();
            session.Subscribed = false;
            session.PendingSubscribe = (sinceUtcTicks, have);
            after = TrySubscribeLocked(session);
        }
        after?.Invoke();
        _activity.Set();
    }

    /// <summary>Handles <c>fetch</c>: resend a file from an offset.</summary>
    public void Fetch(StreamSession session, string name, string fileId, long fromOffset)
    {
        lock (_gate)
        {
            if (!session.Subscribed || !session.Files.TryGetValue(name, out var st) || st.FileId != fileId)
            {
                _log.Debug("fetch for an unknown file ignored");
                return;
            }
            if (!LogFileAccess.TryProbe(st.Path, out var id, out var length) || id != fileId)
                return;
            var from = Math.Clamp(fromOffset, 0, length);
            st.Eligible = true;
            session.Enqueue(new RewindItem(session.Epoch, st, from));
            st.QueuedEnd = Math.Max(st.QueuedEnd, length);
            session.EnqueueData(st, length);
        }
    }

    /// <summary>One poll. Called every <see cref="PollInterval"/> by <see cref="RunAsync"/>, or directly by tests.</summary>
    public void Tick()
    {
        var events = new List<Action>();
        lock (_gate)
        {
            TickLocked(events);
        }
        foreach (var e in events)
            e();
    }

    /// <summary>Runs the poll loop until cancelled. Idle (no timer at all) while nobody is subscribed or observing.</summary>
    public async Task RunAsync(CancellationToken cancellationToken)
    {
        while (!cancellationToken.IsCancellationRequested)
        {
            bool active;
            lock (_gate)
            {
                active = _observers > 0 || _sessions.Any(s => s.Subscribed || s.PendingSubscribe != null);
                if (!active)
                    GoIdleLocked();
            }
            try
            {
                // Idle: no timer at all until someone subscribes or observes; then poll right away.
                if (!active)
                    await _activity.WaitAsync(cancellationToken).ConfigureAwait(false);
                else
                    await Task.Delay(PollInterval, _time, cancellationToken).ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
                return;
            }
            try
            {
                Tick();
            }
            catch (Exception e)
            {
                _log.Error("poll failed", e);
            }
        }
    }

    /// <summary>
    /// Stops the tailer and forgets the observed process and directory state: nothing is watched while idle, so the
    /// first poll after becoming active again reports both afresh (<see cref="DirectoryExistsChanged"/> included).
    /// </summary>
    private void GoIdleLocked()
    {
        if (_tailerActive)
        {
            _tailer.Suspend();
            _tailerActive = false;
        }
        _lastProcess = null;
        _lastDirExists = null;
    }

    private void TickLocked(List<Action> events)
    {
        var subscribed = _sessions.Where(s => s.Subscribed).ToList();
        var pending = _sessions.Where(s => s.PendingSubscribe != null).ToList();
        if (subscribed.Count == 0 && pending.Count == 0)
        {
            if (_observers == 0)
                return;
            ObserveProcess(events);
            var exists = _tailer.DirectoryExists();
            ObserveDirectory(exists, events);
            return;
        }

        // 1. Process state first: every byte written before this observation is covered by the scan below.
        var process = ObserveProcess(events);

        // 2. Directory.
        _tailerActive = true;
        var scan = _tailer.Scan((name, lastWrite) => subscribed.Any(s => lastWrite >= s.SinceTicks ||
                                                                        (s.Files.TryGetValue(name, out var st) && st.Eligible)));
        if (scan == null)
        {
            // Listing failed; try again next poll (the process change waits for its data).
            foreach (var s in subscribed)
                s.OnPoll();
            return;
        }
        ObserveDirectory(scan.DirectoryExists, events);

        var now = _time.GetUtcNow();
        foreach (var s in subscribed)
        {
            var processChanged = s.LastProcess != process;
            // An idle phone gets log growth in batches; a process change flushes the batch so that it still travels
            // behind every byte written before it was observed.
            if (s.Idle && !processChanged && now < s.NextIdleFlush)
            {
                s.OnPoll();
                continue;
            }
            ApplyScan(s, scan);
            if (processChanged)
            {
                s.EnqueueControl("process", ControlMessages.Process(process.VrchatRunning, process.SteamVrRunning, now));
                s.LastProcess = process;
            }
            if (s.Idle)
                s.NextIdleFlush = now + IdleFlushInterval;
            s.OnPoll();
        }

        foreach (var s in pending)
        {
            var after = TrySubscribeLocked(s, scan, process);
            if (after != null)
                events.Add(after);
        }
    }

    private ProcessState ObserveProcess(List<Action> events)
    {
        ProcessState state;
        try
        {
            state = _probe.Poll();
        }
        catch (Exception e)
        {
            _log.Warn("process poll failed", e);
            state = _lastProcess ?? default;
        }
        if (_lastProcess != state)
        {
            if (_lastProcess != null)
                _log.Info($"process state: vrchat={state.VrchatRunning} steamvr={state.SteamVrRunning}");
            _lastProcess = state;
            events.Add(() => ProcessStateChanged?.Invoke(state));
        }
        return state;
    }

    private void ObserveDirectory(bool exists, List<Action> events)
    {
        if (_lastDirExists == exists)
            return;
        var first = _lastDirExists == null;
        _lastDirExists = exists;
        if (!first || !exists)
            _log.Info(exists ? "log directory found" : "log directory missing");
        // Raised for the first observation after an idle period too: the value the sessions were last told may be
        // older than that period (the host compares it per session and only then sends info).
        events.Add(() => DirectoryExistsChanged?.Invoke(exists));
    }

    /// <summary>Runs a pending subscribe. Returns an action to run outside the lock, or null.</summary>
    private Action? TrySubscribeLocked(StreamSession session, DirectoryScan? scan = null, ProcessState? process = null)
    {
        if (session.PendingSubscribe is not { } request)
            return null;
        var events = new List<Action>();
        process ??= ObserveProcess(events);
        _tailerActive = true;
        scan ??= _tailer.Scan((_, lastWrite) => lastWrite >= request.since);
        if (scan == null)
            return events.Count == 0 ? null : () => events.ForEach(e => e());
        ObserveDirectory(scan.DirectoryExists, events);

        session.PendingSubscribe = null;
        session.Subscribed = true;
        session.SinceTicks = request.since;
        session.Files.Clear();

        // 1. snapshot
        session.EnqueueControl("snapshot", ControlMessages.Snapshot(scan.Files));
        session.LastSnapshot = scan.Files;

        // 2. process
        var p = process.Value;
        session.EnqueueControl("process", ControlMessages.Process(p.VrchatRunning, p.SteamVrRunning, _time.GetUtcNow()));
        session.LastProcess = p;

        // 3. data from the have offsets, oldest file first
        var have = new Dictionary<(string, string), long>();
        foreach (var h in request.have)
            have[(h.Name, h.FileId)] = Math.Max(0, h.Length);
        foreach (var f in scan.Files)
        {
            var start = have.GetValueOrDefault((f.Name, f.FileId), 0);
            var st = new FileSendState(f.Name, f.FileId, f.FullPath, start);
            session.Files[f.Name] = st;
            QueueGrowth(session, st, f);
        }

        // 4. syncComplete
        session.EnqueueControl("syncComplete", ControlMessages.SyncComplete());
        if (session.Idle)
            session.NextIdleFlush = _time.GetUtcNow() + IdleFlushInterval;
        _log.Info($"subscription started ({scan.Files.Count} files, {session.Files.Values.Count(f => f.Eligible)} streamed)");
        return events.Count == 0 ? null : () => events.ForEach(e => e());
    }

    private void ApplyScan(StreamSession s, DirectoryScan scan)
    {
        var current = scan.Files;
        var byName = current.ToDictionary(f => f.Name, StringComparer.Ordinal);
        var last = s.LastSnapshot ?? Array.Empty<LogFileMeta>();

        // Replaced files (same name, new fileId): one snapshot without the entry, then one with it.
        var replaced = last.Where(o => byName.TryGetValue(o.Name, out var n) && n.FileId != o.FileId)
            .Select(o => o.Name).ToHashSet(StringComparer.Ordinal);
        if (replaced.Count > 0)
        {
            s.EnqueueControl("snapshot", ControlMessages.Snapshot(current.Where(f => !replaced.Contains(f.Name))));
            foreach (var name in replaced)
                s.Files.Remove(name);
        }
        if (replaced.Count > 0 || !SameSnapshot(last, current))
        {
            s.EnqueueControl("snapshot", ControlMessages.Snapshot(current));
            s.LastSnapshot = current;
        }

        foreach (var gone in s.Files.Keys.Where(k => !byName.ContainsKey(k)).ToList())
            s.Files.Remove(gone);

        foreach (var f in current)
        {
            if (!s.Files.TryGetValue(f.Name, out var st) || st.FileId != f.FileId)
            {
                st = new FileSendState(f.Name, f.FileId, f.FullPath, 0);
                s.Files[f.Name] = st;
            }
            QueueGrowth(s, st, f);
        }
    }

    private static void QueueGrowth(StreamSession s, FileSendState st, LogFileMeta f)
    {
        if (!st.Eligible && f.LastWriteTimeUtcTicks >= s.SinceTicks)
            st.Eligible = true;
        if (!st.Eligible || f.HandleLength < 0)
            return;
        // The writer set this file aside after reads kept failing: queue its unsent bytes again. A data item sends
        // from the writer's position, so this covers everything up to QueuedEnd.
        var retry = st.TakeRetryRequest();
        if (f.HandleLength < st.QueuedEnd)
        {
            s.Enqueue(new TruncateItem(s.Epoch, st, f.HandleLength));
            st.QueuedEnd = f.HandleLength;
            s.EnqueueData(st, f.HandleLength);
        }
        else if (f.HandleLength > st.QueuedEnd)
        {
            st.QueuedEnd = f.HandleLength;
            s.EnqueueData(st, f.HandleLength);
        }
        else if (retry)
        {
            s.EnqueueData(st, st.QueuedEnd);
        }
    }

    private static bool SameSnapshot(IReadOnlyList<LogFileMeta> a, IReadOnlyList<LogFileMeta> b)
    {
        if (a.Count != b.Count)
            return false;
        for (var i = 0; i < a.Count; i++)
        {
            if (!a[i].SameSnapshotValues(b[i]))
                return false;
        }
        return true;
    }

    public void Dispose()
    {
        lock (_gate)
        {
            _tailer.Dispose();
            if (_probe is IDisposable d)
                d.Dispose();
        }
    }

    private sealed class Observer : IDisposable
    {
        private SyncEngine? _engine;

        public Observer(SyncEngine engine) => _engine = engine;

        public void Dispose()
        {
            var e = Interlocked.Exchange(ref _engine, null);
            if (e == null)
                return;
            lock (e._gate)
                e._observers--;
        }
    }
}

using VrcxCompanion.Core.Diagnostics;

namespace VrcxCompanion.Core.Logs;

/// <summary>
/// One VRChat log file as observed in one poll. <see cref="CreationTimeUtcTicks"/>, <see cref="LastWriteTimeUtcTicks"/>
/// and <see cref="Length"/> are exactly what <c>FileInfo.Refresh()</c> reported (what upstream LogWatcher reads,
/// staleness included). <see cref="HandleLength"/> is the real end of file of an open handle, which is what data is
/// read up to.
/// </summary>
public sealed record LogFileMeta(
    string Name,
    string FullPath,
    string FileId,
    long CreationTimeUtcTicks,
    long LastWriteTimeUtcTicks,
    long Length,
    long HandleLength)
{
    /// <summary>Equality of the values carried by a snapshot entry.</summary>
    public bool SameSnapshotValues(LogFileMeta other) =>
        Name == other.Name && FileId == other.FileId && CreationTimeUtcTicks == other.CreationTimeUtcTicks &&
        LastWriteTimeUtcTicks == other.LastWriteTimeUtcTicks && Length == other.Length;
}

/// <summary>Result of one poll of the log directory. Files are ordered by creation time (oldest first).</summary>
public sealed record DirectoryScan(bool DirectoryExists, IReadOnlyList<LogFileMeta> Files);

/// <summary>Locates the VRChat log directory.</summary>
public static class LogDirectoryLocator
{
    /// <summary><c>Environment.GetFolderPath(LocalApplicationData) + @"Low\VRChat\VRChat"</c>, as upstream.</summary>
    public static string DefaultPath =>
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData) + @"Low\VRChat\VRChat";

    /// <summary>
    /// Upstream's check (<c>LogWatcher.cs:122-124</c>): a plain directory must exist; a symbolic link or junction
    /// must point at an existing directory. A relative link target is resolved against the link's parent directory.
    /// <paramref name="directory"/> must have been refreshed.
    /// </summary>
    public static bool IsUsable(DirectoryInfo directory)
    {
        string? target;
        try
        {
            target = directory.LinkTarget;
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            return false;
        }
        if (target == null)
            return directory.Exists;
        if (!Path.IsPathRooted(target))
        {
            var parent = Path.GetDirectoryName(directory.FullName.TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar));
            if (parent == null)
                return false;
            target = Path.GetFullPath(Path.Combine(parent, target));
        }
        return Directory.Exists(target);
    }
}

/// <summary>
/// Polls the log directory the way upstream <c>LogWatcher.Update()</c> does (<c>GetFiles("output_log_*.txt")</c>,
/// <c>FileInfo.Refresh()</c>, sort by creation time) and adds each file's id and real length. It is driven by the
/// caller's 1000 ms poll. Handles are opened only when a file is new, its metadata changed, a
/// <see cref="FileSystemWatcher"/> hint named it, or the caller wants a fresh length and the last check is older
/// than <see cref="FreshLengthInterval"/>. It never writes anything.
/// </summary>
public sealed class LogDirectoryTailer : IDisposable
{
    public const string FilePattern = "output_log_*.txt";
    public static readonly TimeSpan FreshLengthInterval = TimeSpan.FromSeconds(10);

    private readonly object _hintGate = new();
    private readonly HashSet<string> _hinted = new(StringComparer.OrdinalIgnoreCase);
    private readonly Dictionary<string, CacheEntry> _cache = new(StringComparer.Ordinal);
    private readonly ICompanionLog _log;
    private readonly TimeProvider _time;
    private readonly bool _useWatcher;
    private readonly DirectoryInfo _directory;
    private FileSystemWatcher? _watcher;
    private bool _lastScanFailed;

    public LogDirectoryTailer(string directoryPath, ICompanionLog log, TimeProvider? time = null, bool useWatcher = true)
    {
        DirectoryPath = directoryPath;
        _directory = new DirectoryInfo(directoryPath);
        _log = log;
        _time = time ?? TimeProvider.System;
        _useWatcher = useWatcher;
    }

    public string DirectoryPath { get; }

    /// <summary>Number of handles opened by <see cref="Scan"/> so far (diagnostics and tests).</summary>
    public long ProbeCount { get; private set; }

    /// <summary>Whether the directory is usable right now (cheap check for status displays).</summary>
    public bool DirectoryExists()
    {
        var di = new DirectoryInfo(DirectoryPath);
        di.Refresh();
        return LogDirectoryLocator.IsUsable(di);
    }

    /// <summary>
    /// Polls the directory. Returns null when listing failed transiently; the caller then skips this poll (sending
    /// an empty snapshot would make the phone delete its mirror).
    /// </summary>
    /// <param name="wantsFreshLength">Called with (name, lastWriteTimeUtcTicks); true when the caller streams this file.</param>
    public DirectoryScan? Scan(Func<string, long, bool>? wantsFreshLength = null)
    {
        _directory.Refresh();
        if (!LogDirectoryLocator.IsUsable(_directory))
        {
            _cache.Clear();
            DisposeWatcher();
            return new DirectoryScan(false, Array.Empty<LogFileMeta>());
        }
        EnsureWatcher();

        FileInfo[] infos;
        try
        {
            infos = _directory.GetFiles(FilePattern, SearchOption.TopDirectoryOnly);
            if (_lastScanFailed)
            {
                _lastScanFailed = false;
                _log.Info("log directory listing works again");
            }
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            if (!_lastScanFailed)
                _log.Warn("log directory could not be listed", e);
            _lastScanFailed = true;
            return null;
        }

        HashSet<string> hinted;
        lock (_hintGate)
        {
            hinted = new HashSet<string>(_hinted, StringComparer.OrdinalIgnoreCase);
            _hinted.Clear();
        }

        var now = _time.GetUtcNow();
        var entries = new List<(FileInfo info, long creation)>(infos.Length);
        foreach (var fi in infos)
        {
            try
            {
                entries.Add((fi, fi.CreationTimeUtc.Ticks));
            }
            catch (Exception e) when (e is IOException or UnauthorizedAccessException)
            {
                entries.Add((fi, long.MaxValue));
            }
        }
        // Upstream sorts by CreationTimeUtc (unstable). Ties are broken by name so the order is deterministic.
        entries.Sort((a, b) =>
        {
            var c = a.creation.CompareTo(b.creation);
            return c != 0 ? c : string.CompareOrdinal(a.info.Name, b.info.Name);
        });

        var files = new List<LogFileMeta>(entries.Count);
        var seen = new HashSet<string>(StringComparer.Ordinal);
        foreach (var (fi, _) in entries)
        {
            long creation, lastWrite, length;
            try
            {
                fi.Refresh();
                if (!fi.Exists)
                    continue;
                creation = fi.CreationTimeUtc.Ticks;
                lastWrite = fi.LastWriteTimeUtc.Ticks;
                length = fi.Length;
            }
            catch (Exception e) when (e is IOException or UnauthorizedAccessException)
            {
                continue;
            }

            var name = fi.Name;
            _cache.TryGetValue(name, out var cached);
            var metadataChanged = cached == null || cached.CreationTicks != creation || cached.LastWriteTicks != lastWrite ||
                                  cached.Length != length;
            var needProbe = metadataChanged || hinted.Contains(name) ||
                            (wantsFreshLength?.Invoke(name, lastWrite) == true && now - cached!.ProbedAt >= FreshLengthInterval);

            string fileId;
            long handleLength;
            if (needProbe)
            {
                ProbeCount++;
                if (LogFileAccess.TryProbe(fi.FullName, out var probedId, out var probedLength))
                {
                    fileId = probedId;
                    handleLength = probedLength;
                    cached = new CacheEntry(creation, lastWrite, length, fileId, handleLength, now);
                }
                else if (cached != null)
                {
                    // Keep the last known id and length; try again on the next poll.
                    fileId = cached.FileId;
                    handleLength = cached.HandleLength;
                    cached = cached with { CreationTicks = creation, LastWriteTicks = lastWrite, Length = length, ProbedAt = DateTimeOffset.MinValue };
                }
                else
                {
                    continue; // new file that cannot be opened yet: list it on a later poll
                }
            }
            else
            {
                fileId = cached!.FileId;
                handleLength = cached.HandleLength;
            }

            _cache[name] = cached;
            seen.Add(name);
            files.Add(new LogFileMeta(name, fi.FullName, fileId, creation, lastWrite, length, handleLength));
        }

        foreach (var stale in _cache.Keys.Where(k => !seen.Contains(k)).ToList())
            _cache.Remove(stale);

        return new DirectoryScan(true, files);
    }

    /// <summary>Stops the watcher and forgets cached ids (called when nobody is subscribed).</summary>
    public void Suspend()
    {
        DisposeWatcher();
        _cache.Clear();
        lock (_hintGate)
            _hinted.Clear();
    }

    private void EnsureWatcher()
    {
        if (!_useWatcher || _watcher != null)
            return;
        try
        {
            var w = new FileSystemWatcher(DirectoryPath, FilePattern)
            {
                NotifyFilter = NotifyFilters.FileName,
                IncludeSubdirectories = false,
                InternalBufferSize = 8192,
            };
            w.Created += OnHint;
            w.Deleted += OnHint;
            w.Renamed += (_, e) =>
            {
                lock (_hintGate)
                {
                    _hinted.Add(e.Name ?? "");
                    _hinted.Add(e.OldName ?? "");
                }
            };
            w.Error += (_, _) => { };
            w.EnableRaisingEvents = true;
            _watcher = w;
        }
        catch (Exception e) when (e is IOException or ArgumentException or UnauthorizedAccessException or PlatformNotSupportedException)
        {
            // The watcher is only a hint; polling works without it.
            _log.Debug("file system watcher unavailable: " + e.GetType().Name);
        }
    }

    private void OnHint(object sender, FileSystemEventArgs e)
    {
        lock (_hintGate)
            _hinted.Add(e.Name ?? "");
    }

    private void DisposeWatcher()
    {
        _watcher?.Dispose();
        _watcher = null;
    }

    public void Dispose() => DisposeWatcher();

    private sealed record CacheEntry(long CreationTicks, long LastWriteTicks, long Length, string FileId, long HandleLength, DateTimeOffset ProbedAt);
}

using System.Globalization;
using System.Text;

namespace VrcxCompanion.Core.Diagnostics;

/// <summary>
/// Diagnostic log. Only metadata is ever logged: never log-line contents, tokens, pairing codes or proofs
/// (log lines contain instance nonces and API keys).
/// </summary>
public interface ICompanionLog
{
    void Write(LogLevel level, string message, Exception? exception = null);
}

public enum LogLevel
{
    Debug,
    Info,
    Warn,
    Error,
}

public static class CompanionLogExtensions
{
    public static void Debug(this ICompanionLog log, string message) => log.Write(LogLevel.Debug, message);
    public static void Info(this ICompanionLog log, string message) => log.Write(LogLevel.Info, message);
    public static void Warn(this ICompanionLog log, string message, Exception? e = null) => log.Write(LogLevel.Warn, message, e);
    public static void Error(this ICompanionLog log, string message, Exception? e = null) => log.Write(LogLevel.Error, message, e);
}

public sealed class NullLog : ICompanionLog
{
    public static readonly NullLog Instance = new();
    public void Write(LogLevel level, string message, Exception? exception = null) { }
}

/// <summary>Keeps the last entries in memory (tests, self test).</summary>
public sealed class MemoryLog : ICompanionLog
{
    private readonly List<string> _entries = new();

    public IReadOnlyList<string> Entries
    {
        get
        {
            lock (_entries)
                return _entries.ToArray();
        }
    }

    public void Write(LogLevel level, string message, Exception? exception = null)
    {
        lock (_entries)
        {
            _entries.Add($"{level} {message}{(exception is null ? "" : " " + exception.GetType().Name + ": " + exception.Message)}");
            if (_entries.Count > 2000)
                _entries.RemoveRange(0, 1000);
        }
    }
}

/// <summary>
/// Appends to <c>companion.log</c> and rotates it at <see cref="MaxBytes"/> into <c>companion.1.log</c> ...
/// <c>companion.N.log</c>.
/// </summary>
public sealed class FileLog : ICompanionLog, IDisposable
{
    private readonly object _gate = new();
    private readonly string _path;
    private readonly int _keep;
    private StreamWriter? _writer;
    private bool _disabled;

    public FileLog(string path, long maxBytes = 1024 * 1024, int keep = 3, LogLevel minimumLevel = LogLevel.Info)
    {
        _path = path;
        MaxBytes = maxBytes;
        _keep = Math.Max(1, keep);
        MinimumLevel = minimumLevel;
    }

    public long MaxBytes { get; }
    public LogLevel MinimumLevel { get; set; }

    public void Write(LogLevel level, string message, Exception? exception = null)
    {
        if (level < MinimumLevel)
            return;
        var sb = new StringBuilder(128);
        sb.Append(DateTime.UtcNow.ToString("yyyy-MM-dd'T'HH:mm:ss.fff'Z'", CultureInfo.InvariantCulture));
        sb.Append(' ').Append(level.ToString().ToUpperInvariant().PadRight(5)).Append(' ').Append(message);
        if (exception != null)
        {
            sb.Append(" | ").Append(exception.GetType().FullName).Append(": ").Append(exception.Message);
            if (level >= LogLevel.Error && exception.StackTrace != null)
                sb.Append(Environment.NewLine).Append(exception.StackTrace);
        }
        var line = sb.ToString();
        lock (_gate)
        {
            if (_disabled)
                return;
            try
            {
                _writer ??= Open();
                _writer.WriteLine(line);
                _writer.Flush();
                if (_writer.BaseStream.Length >= MaxBytes)
                    Rotate();
            }
            catch (IOException)
            {
                // Logging must never break the companion. Try again on the next entry.
                _writer?.Dispose();
                _writer = null;
            }
            catch (UnauthorizedAccessException)
            {
                _disabled = true;
            }
        }
    }

    private StreamWriter Open()
    {
        Directory.CreateDirectory(Path.GetDirectoryName(Path.GetFullPath(_path))!);
        var fs = new FileStream(_path, FileMode.Append, FileAccess.Write, FileShare.ReadWrite | FileShare.Delete);
        return new StreamWriter(fs, new UTF8Encoding(false));
    }

    private void Rotate()
    {
        _writer?.Dispose();
        _writer = null;
        var dir = Path.GetDirectoryName(Path.GetFullPath(_path))!;
        var stem = Path.GetFileNameWithoutExtension(_path);
        var ext = Path.GetExtension(_path);
        string Numbered(int i) => Path.Combine(dir, $"{stem}.{i}{ext}");
        var oldest = Numbered(_keep);
        if (File.Exists(oldest))
            File.Delete(oldest);
        for (var i = _keep - 1; i >= 1; i--)
        {
            if (File.Exists(Numbered(i)))
                File.Move(Numbered(i), Numbered(i + 1));
        }
        File.Move(_path, Numbered(1));
    }

    public void Dispose()
    {
        lock (_gate)
        {
            _writer?.Dispose();
            _writer = null;
            _disabled = true;
        }
    }
}

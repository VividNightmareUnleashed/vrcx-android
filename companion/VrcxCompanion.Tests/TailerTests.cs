using System.Diagnostics;
using VrcxCompanion.Core.Diagnostics;
using VrcxCompanion.Core.Logs;

namespace VrcxCompanion.Tests;

public class TailerTests
{
    [Fact]
    public void ReportsMetadataExactlyAsFileInfoRefresh()
    {
        using var dir = new TempDir();
        var a = dir.File("output_log_2024-01-01_09-59-00.txt");
        var b = dir.File("output_log_2024-01-02_10-00-00.txt");
        File.WriteAllBytes(a, LogFiles.Line("first"));
        File.WriteAllBytes(b, LogFiles.Line("second file"));
        File.WriteAllText(dir.File("other.txt"), "not a log");
        File.WriteAllText(dir.File("output_log_x.log"), "wrong extension");
        File.SetCreationTimeUtc(a, new DateTime(2024, 1, 1, 9, 59, 0, DateTimeKind.Utc));
        File.SetCreationTimeUtc(b, new DateTime(2024, 1, 2, 10, 0, 0, DateTimeKind.Utc));

        using var tailer = new LogDirectoryTailer(dir.Path, NullLog.Instance, useWatcher: false);
        var scan = tailer.Scan()!;

        Assert.True(scan.DirectoryExists);
        Assert.Equal(new[] { Path.GetFileName(a), Path.GetFileName(b) }, scan.Files.Select(f => f.Name));
        foreach (var f in scan.Files)
        {
            var fi = new FileInfo(f.FullPath);
            fi.Refresh();
            Assert.Equal(fi.CreationTimeUtc.Ticks, f.CreationTimeUtcTicks);
            Assert.Equal(fi.LastWriteTimeUtc.Ticks, f.LastWriteTimeUtcTicks);
            Assert.Equal(fi.Length, f.Length);
            Assert.Equal(fi.Length, f.HandleLength);
            Assert.Matches("^[0-9a-f]{24}$", f.FileId);
        }
        Assert.NotEqual(scan.Files[0].FileId, scan.Files[1].FileId);
    }

    [Fact]
    public void PatternIsCaseInsensitiveAndTopLevelOnly()
    {
        using var dir = new TempDir();
        File.WriteAllText(dir.File("OUTPUT_LOG_12-00-00.TXT"), "x");
        Directory.CreateDirectory(dir.File("sub"));
        File.WriteAllText(Path.Combine(dir.File("sub"), "output_log_nested.txt"), "x");
        using var tailer = new LogDirectoryTailer(dir.Path, NullLog.Instance, useWatcher: false);
        Assert.Equal("OUTPUT_LOG_12-00-00.TXT", Assert.Single(tailer.Scan()!.Files).Name);
    }

    [Fact]
    public void FileIdIsStableAcrossAppendsAndChangesWhenReplaced()
    {
        using var dir = new TempDir();
        var path = dir.File("output_log_1.txt");
        File.WriteAllBytes(path, LogFiles.Line("a"));
        using var tailer = new LogDirectoryTailer(dir.Path, NullLog.Instance, useWatcher: false);
        var id1 = tailer.Scan()!.Files.Single().FileId;

        LogFiles.Append(path, LogFiles.Line("b"));
        var s2 = tailer.Scan()!.Files.Single();
        Assert.Equal(id1, s2.FileId);
        Assert.Equal(6, s2.HandleLength);

        File.Delete(path);
        File.WriteAllBytes(path, LogFiles.Line("replacement file"));
        var s3 = tailer.Scan()!.Files.Single();
        Assert.NotEqual(id1, s3.FileId);
    }

    [Fact]
    public void OnlyOpensFilesWhoseMetadataChanged()
    {
        using var dir = new TempDir();
        for (var i = 0; i < 5; i++)
            File.WriteAllBytes(dir.File($"output_log_{i}.txt"), LogFiles.Line("line " + i));
        using var tailer = new LogDirectoryTailer(dir.Path, NullLog.Instance, useWatcher: false);
        tailer.Scan();
        Assert.Equal(5, tailer.ProbeCount);
        tailer.Scan();
        tailer.Scan();
        Assert.Equal(5, tailer.ProbeCount);
        LogFiles.Append(dir.File("output_log_3.txt"), LogFiles.Line("more"));
        tailer.Scan();
        Assert.Equal(6, tailer.ProbeCount);
    }

    [Fact]
    public void MissingDirectoryReportsNoFiles()
    {
        using var dir = new TempDir();
        var logs = Path.Combine(dir.Path, "VRChat");
        using var tailer = new LogDirectoryTailer(logs, NullLog.Instance, useWatcher: false);
        var scan = tailer.Scan()!;
        Assert.False(scan.DirectoryExists);
        Assert.Empty(scan.Files);
        Assert.False(tailer.DirectoryExists());

        Directory.CreateDirectory(logs);
        File.WriteAllText(Path.Combine(logs, "output_log_1.txt"), "x");
        Assert.Single(tailer.Scan()!.Files);
        Assert.True(tailer.DirectoryExists());
    }

    [Fact]
    public void JunctionToAnExistingDirectoryIsFollowed()
    {
        using var dir = new TempDir();
        var target = Path.Combine(dir.Path, "real");
        var link = Path.Combine(dir.Path, "link");
        Directory.CreateDirectory(target);
        File.WriteAllText(Path.Combine(target, "output_log_1.txt"), "x");
        if (!CreateJunction(link, target))
            return; // junctions unavailable on this machine

        using (var tailer = new LogDirectoryTailer(link, NullLog.Instance, useWatcher: false))
        {
            var scan = tailer.Scan()!;
            Assert.True(scan.DirectoryExists);
            Assert.Single(scan.Files);
        }

        // Dangling link: treated as missing, like upstream.
        Directory.Delete(target, recursive: true);
        using (var tailer = new LogDirectoryTailer(link, NullLog.Instance, useWatcher: false))
        {
            var scan = tailer.Scan()!;
            Assert.False(scan.DirectoryExists);
            Assert.Empty(scan.Files);
        }
        Directory.Delete(link);
    }

    [Fact]
    public void RelativeSymlinkTargetResolvesAgainstTheLinkDirectory()
    {
        using var dir = new TempDir();
        var target = Path.Combine(dir.Path, "real");
        Directory.CreateDirectory(target);
        var link = Path.Combine(dir.Path, "link");
        try
        {
            Directory.CreateSymbolicLink(link, "real");
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            return; // creating symbolic links needs developer mode or elevation
        }
        var di = new DirectoryInfo(link);
        di.Refresh();
        Assert.True(LogDirectoryLocator.IsUsable(di));
        Directory.Delete(link);
    }

    [Fact]
    public void FilesOpenForWritingByAnotherProcessCanBeRead()
    {
        using var dir = new TempDir();
        var path = dir.File("output_log_1.txt");
        // VRChat keeps its log open for writing; the companion must share it.
        using var writer = new FileStream(path, FileMode.Create, FileAccess.Write, FileShare.Read | FileShare.Delete);
        writer.Write(LogFiles.Line("hello"));
        writer.Flush();
        using var tailer = new LogDirectoryTailer(dir.Path, NullLog.Instance, useWatcher: false);
        var f = tailer.Scan()!.Files.Single();
        Assert.Equal(7, f.HandleLength);
    }

    [Fact]
    public void DefaultPathIsLocalLow()
    {
        Assert.EndsWith(@"AppData\LocalLow\VRChat\VRChat", LogDirectoryLocator.DefaultPath, StringComparison.OrdinalIgnoreCase);
    }

    private static bool CreateJunction(string link, string target)
    {
        try
        {
            var psi = new ProcessStartInfo("cmd.exe", $"/c mklink /J \"{link}\" \"{target}\"")
            {
                UseShellExecute = false,
                CreateNoWindow = true,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
            };
            using var p = Process.Start(psi)!;
            p.WaitForExit(10000);
            return p.ExitCode == 0 && Directory.Exists(link);
        }
        catch (Exception)
        {
            return false;
        }
    }
}

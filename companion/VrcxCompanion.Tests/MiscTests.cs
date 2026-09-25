using System.Text;
using VrcxCompanion.Core;
using VrcxCompanion.Core.Diagnostics;
using VrcxCompanion.Core.Settings;

namespace VrcxCompanion.Tests;

public class FileLogTests
{
    [Fact]
    public void RotatesAndKeepsALimitedNumberOfFiles()
    {
        using var dir = new TempDir();
        var path = dir.File("companion.log");
        using (var log = new FileLog(path, maxBytes: 300, keep: 3))
        {
            for (var i = 0; i < 100; i++)
                log.Info($"entry {i:000} with some padding to fill the file");
        }
        var files = Directory.GetFiles(dir.Path).Select(Path.GetFileName).OrderBy(n => n).ToArray();
        Assert.Equal(new[] { "companion.1.log", "companion.2.log", "companion.3.log", "companion.log" }, files);
        Assert.All(Directory.GetFiles(dir.Path), f => Assert.True(new FileInfo(f).Length < 600));
        Assert.Contains("entry 099", File.ReadAllText(path) + File.ReadAllText(dir.File("companion.1.log")));
    }

    [Fact]
    public void WritesTimestampLevelAndMessageOnly()
    {
        using var dir = new TempDir();
        var path = dir.File("companion.log");
        using (var log = new FileLog(path))
        {
            log.Debug("hidden at the default level");
            log.Warn("something", new IOException("disk"));
        }
        var text = File.ReadAllText(path);
        Assert.DoesNotContain("hidden", text);
        Assert.Matches(@"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z WARN  something \| System.IO.IOException: disk", text);
    }
}

public class SettingsTests
{
    [Fact]
    public void CreatesDefaultsAndReadsOverrides()
    {
        using var dir = new TempDir();
        var path = dir.File("settings.json");
        var defaults = CompanionSettings.LoadOrCreate(path, NullLog.Instance);
        Assert.Equal(49460, defaults.TcpPort);
        Assert.Equal(49461, defaults.DiscoveryPort);
        Assert.Contains("\"tcpPort\": 49460", File.ReadAllText(path));

        File.WriteAllText(path, "{ \"tcpPort\": 50000, \"discoveryPort\": 50001, // comment\n }");
        var custom = CompanionSettings.LoadOrCreate(path, NullLog.Instance);
        Assert.Equal(50000, custom.TcpPort);
        Assert.Equal(50001, custom.DiscoveryPort);

        File.WriteAllText(path, "{ \"tcpPort\": 0, \"discoveryPort\": 70000 }");
        var invalid = CompanionSettings.LoadOrCreate(path, NullLog.Instance);
        Assert.Equal(49460, invalid.TcpPort);
        Assert.Equal(49461, invalid.DiscoveryPort);

        File.WriteAllText(path, "not json");
        Assert.Equal(49460, CompanionSettings.LoadOrCreate(path, NullLog.Instance).TcpPort);
    }

    [Fact]
    public void PathsLiveUnderAppDataVrcxCompanion()
    {
        var paths = AppPaths.Default;
        Assert.EndsWith(@"\VRCX-Companion", paths.DataDirectory, StringComparison.OrdinalIgnoreCase);
        Assert.EndsWith(@"VRCX-Companion\identity.bin", paths.IdentityFile, StringComparison.OrdinalIgnoreCase);
        Assert.EndsWith(@"VRCX-Companion\devices.bin", paths.DevicesFile, StringComparison.OrdinalIgnoreCase);
        Assert.EndsWith(@"VRCX-Companion\companion.log", paths.LogFile, StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public void FirewallCommandAddsPrivateLocalSubnetRules()
    {
        var args = FirewallCommand.BuildCmdArguments(49460, 49461);
        Assert.StartsWith("/c \"", args);
        Assert.EndsWith("\"", args);
        Assert.Contains("add rule name=\"VRCX Companion (TCP)\" dir=in action=allow protocol=TCP localport=49460 profile=private remoteip=localsubnet", args);
        Assert.Contains("add rule name=\"VRCX Companion (UDP discovery)\" dir=in action=allow protocol=UDP localport=49461 profile=private remoteip=localsubnet", args);
        Assert.Contains("delete rule name=\"VRCX Companion (TCP)\"", args);
        Assert.DoesNotContain("profile=public", args);
    }
}

public class TimeZoneTests
{
    [Fact]
    public void CapturesWindowsZoneWithIanaIdAndOffsets()
    {
        var zone = TimeZoneInfo.FindSystemTimeZoneById("W. Europe Standard Time");
        var summer = TimeZoneReader.Capture(zone, new DateTimeOffset(2024, 7, 1, 12, 0, 0, TimeSpan.Zero));
        Assert.Equal("W. Europe Standard Time", summer.WindowsId);
        Assert.Equal("Europe/Berlin", summer.IanaId);
        Assert.True(summer.SupportsDst);
        Assert.Equal(60, summer.BaseUtcOffsetMin);
        Assert.Equal(120, summer.CurrentUtcOffsetMin);
        var winter = TimeZoneReader.Capture(zone, new DateTimeOffset(2024, 1, 15, 12, 0, 0, TimeSpan.Zero));
        Assert.Equal(60, winter.CurrentUtcOffsetMin);
    }

    [Fact]
    public void FixedOffsetZoneWithoutIana()
    {
        var zone = TimeZoneInfo.CreateCustomTimeZone("Custom Fixed", TimeSpan.FromMinutes(330), "Custom", "Custom");
        var tz = TimeZoneReader.Capture(zone, DateTimeOffset.UtcNow);
        Assert.Null(tz.IanaId);
        Assert.False(tz.SupportsDst);
        Assert.Equal(330, tz.BaseUtcOffsetMin);
        Assert.Equal(330, tz.CurrentUtcOffsetMin);
    }

    [Fact]
    public void LocalZoneIsReadable()
    {
        var tz = TimeZoneReader.CaptureLocal(DateTimeOffset.UtcNow);
        Assert.Equal(TimeZoneInfo.Local.Id, tz.WindowsId);
        Assert.Equal((int)TimeZoneInfo.Local.GetUtcOffset(DateTimeOffset.UtcNow).TotalMinutes, tz.CurrentUtcOffsetMin);
        Assert.False(string.IsNullOrEmpty(Encoding.UTF8.GetString(Encoding.UTF8.GetBytes(tz.WindowsId))));
    }
}

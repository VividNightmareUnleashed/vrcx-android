using System.Text.Json;
using VrcxCompanion.Core.Diagnostics;
using VrcxCompanion.Core.Protocol;

namespace VrcxCompanion.Core.Settings;

/// <summary>Locations of the companion's own files (never inside the VRChat folder).</summary>
public sealed class AppPaths
{
    public AppPaths(string dataDirectory)
    {
        DataDirectory = dataDirectory;
    }

    /// <summary><c>%APPDATA%\VRCX-Companion</c>.</summary>
    public static AppPaths Default =>
        new(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "VRCX-Companion"));

    public string DataDirectory { get; }
    public string IdentityFile => Path.Combine(DataDirectory, "identity.bin");
    public string DevicesFile => Path.Combine(DataDirectory, "devices.bin");
    public string SettingsFile => Path.Combine(DataDirectory, "settings.json");
    public string LogFile => Path.Combine(DataDirectory, "companion.log");
}

/// <summary>User-editable settings (<c>settings.json</c>).</summary>
public sealed class CompanionSettings
{
    public int TcpPort { get; set; } = ProtocolConstants.DefaultTcpPort;
    public int DiscoveryPort { get; set; } = ProtocolConstants.DefaultDiscoveryPort;

    private static readonly JsonSerializerOptions Options = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        WriteIndented = true,
        ReadCommentHandling = JsonCommentHandling.Skip,
        AllowTrailingCommas = true,
    };

    /// <summary>Loads the settings, writing the defaults when the file does not exist. Invalid values fall back to the defaults.</summary>
    public static CompanionSettings LoadOrCreate(string path, ICompanionLog log)
    {
        CompanionSettings settings;
        if (File.Exists(path))
        {
            try
            {
                settings = JsonSerializer.Deserialize<CompanionSettings>(File.ReadAllText(path), Options) ?? new CompanionSettings();
            }
            catch (Exception e) when (e is JsonException or IOException or UnauthorizedAccessException)
            {
                log.Warn("settings.json could not be read, using defaults", e);
                settings = new CompanionSettings();
            }
        }
        else
        {
            settings = new CompanionSettings();
            try
            {
                Directory.CreateDirectory(Path.GetDirectoryName(Path.GetFullPath(path))!);
                File.WriteAllText(path, JsonSerializer.Serialize(settings, Options));
            }
            catch (Exception e) when (e is IOException or UnauthorizedAccessException)
            {
                log.Warn("settings.json could not be written", e);
            }
        }
        settings.Validate(log);
        return settings;
    }

    private void Validate(ICompanionLog log)
    {
        if (TcpPort is < 1 or > 65535)
        {
            log.Warn($"invalid tcpPort {TcpPort}, using {ProtocolConstants.DefaultTcpPort}");
            TcpPort = ProtocolConstants.DefaultTcpPort;
        }
        if (DiscoveryPort is < 1 or > 65535)
        {
            log.Warn($"invalid discoveryPort {DiscoveryPort}, using {ProtocolConstants.DefaultDiscoveryPort}");
            DiscoveryPort = ProtocolConstants.DefaultDiscoveryPort;
        }
    }
}

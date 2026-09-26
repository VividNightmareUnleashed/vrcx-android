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

    /// <summary>
    /// Ids of Windows network profiles categorized as Public on which the user chose "Allow on this network"
    /// (PROTOCOL.md §1). Entries that are not GUIDs are ignored.
    /// </summary>
    public List<string> AllowedPublicNetworks { get; set; } = new();

    /// <summary>The valid entries of <see cref="AllowedPublicNetworks"/>.</summary>
    public IReadOnlyCollection<Guid> AllowedPublicNetworkIds() =>
        (AllowedPublicNetworks ?? new List<string>())
        .Select(s => Guid.TryParse(s, out var g) ? g : Guid.Empty)
        .Where(g => g != Guid.Empty)
        .Distinct()
        .ToArray();

    /// <summary>Replaces <see cref="AllowedPublicNetworks"/> and writes the file.</summary>
    public void SetAllowedPublicNetworks(IEnumerable<Guid> ids, string path, ICompanionLog log)
    {
        AllowedPublicNetworks = ids.Select(g => g.ToString("D")).OrderBy(s => s, StringComparer.Ordinal).ToList();
        Save(path, log);
    }

    /// <summary>Writes the settings; failures are logged, not thrown.</summary>
    public void Save(string path, ICompanionLog log)
    {
        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(Path.GetFullPath(path))!);
            File.WriteAllText(path, JsonSerializer.Serialize(this, Options));
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            log.Warn("settings.json could not be written", e);
        }
    }

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
            settings.Save(path, log);
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
        AllowedPublicNetworks ??= new List<string>();
    }
}

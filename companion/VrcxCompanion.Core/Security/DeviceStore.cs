using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using VrcxCompanion.Core.Diagnostics;
using VrcxCompanion.Core.Protocol;

namespace VrcxCompanion.Core.Security;

public sealed record PairedDevice(string DeviceId, string DeviceName, string TokenSha256, DateTime PairedAtUtc, DateTime LastSeenUtc);

/// <summary>
/// Paired phones, stored DPAPI-protected in <c>devices.bin</c> as
/// <c>{deviceId, deviceName, tokenSha256, pairedAtUtc, lastSeenUtc}</c>. Only the SHA-256 of the token is kept.
/// </summary>
public sealed class DeviceStore
{
    private readonly object _gate = new();
    private readonly string? _path;
    private readonly ISecretProtector _protector;
    private readonly ICompanionLog _log;
    private readonly TimeProvider _time;
    private readonly Dictionary<string, PairedDevice> _devices = new(StringComparer.Ordinal);

    /// <param name="path">File to persist to, or null for an in-memory store.</param>
    public DeviceStore(string? path, ISecretProtector protector, ICompanionLog log, TimeProvider? time = null)
    {
        _path = path;
        _protector = protector;
        _log = log;
        _time = time ?? TimeProvider.System;
        Load();
    }

    /// <summary>Raised after any change (on the thread that made it).</summary>
    public event Action? Changed;

    public IReadOnlyList<PairedDevice> List()
    {
        lock (_gate)
            return _devices.Values.OrderBy(d => d.PairedAtUtc).ToArray();
    }

    public PairedDevice? Find(string deviceId)
    {
        lock (_gate)
            return _devices.GetValueOrDefault(deviceId);
    }

    /// <summary>Adds a device or replaces an earlier pairing of the same device id.</summary>
    public PairedDevice AddOrReplace(string deviceId, string deviceName, string token)
    {
        var now = _time.GetUtcNow().UtcDateTime;
        var device = new PairedDevice(deviceId, deviceName, PairingCrypto.TokenHash(token), now, now);
        lock (_gate)
        {
            _devices[deviceId] = device;
            Save();
        }
        Changed?.Invoke();
        return device;
    }

    /// <summary>Checks a token in constant time and updates <c>lastSeenUtc</c> on success.</summary>
    public bool Verify(string? deviceId, string? token)
    {
        if (string.IsNullOrEmpty(deviceId) || string.IsNullOrEmpty(token))
            return false;
        bool ok;
        lock (_gate)
        {
            if (!_devices.TryGetValue(deviceId, out var device))
                return false;
            var expected = Encoding.ASCII.GetBytes(device.TokenSha256);
            var actual = Encoding.ASCII.GetBytes(PairingCrypto.TokenHash(token));
            ok = CryptographicOperations.FixedTimeEquals(expected, actual);
            if (ok)
            {
                _devices[deviceId] = device with { LastSeenUtc = _time.GetUtcNow().UtcDateTime };
                Save();
            }
        }
        if (ok)
            Changed?.Invoke();
        return ok;
    }

    public void Touch(string deviceId)
    {
        lock (_gate)
        {
            if (!_devices.TryGetValue(deviceId, out var device))
                return;
            _devices[deviceId] = device with { LastSeenUtc = _time.GetUtcNow().UtcDateTime };
            Save();
        }
        Changed?.Invoke();
    }

    public bool Remove(string deviceId)
    {
        bool removed;
        lock (_gate)
        {
            removed = _devices.Remove(deviceId);
            if (removed)
                Save();
        }
        if (removed)
            Changed?.Invoke();
        return removed;
    }

    private void Load()
    {
        if (_path is null || !File.Exists(_path))
            return;
        try
        {
            var json = _protector.Unprotect(File.ReadAllBytes(_path));
            using var doc = JsonDocument.Parse(json);
            foreach (var e in doc.RootElement.GetProperty("devices").EnumerateArray())
            {
                var id = e.GetProperty("deviceId").GetString();
                var hash = e.GetProperty("tokenSha256").GetString();
                if (string.IsNullOrEmpty(id) || string.IsNullOrEmpty(hash))
                    continue;
                _devices[id] = new PairedDevice(
                    id,
                    e.GetProperty("deviceName").GetString() ?? "",
                    hash,
                    DateTime.SpecifyKind(e.GetProperty("pairedAtUtc").GetDateTime(), DateTimeKind.Utc),
                    DateTime.SpecifyKind(e.GetProperty("lastSeenUtc").GetDateTime(), DateTimeKind.Utc));
            }
        }
        catch (Exception e) when (e is CryptographicException or JsonException or IOException or KeyNotFoundException
                                      or InvalidOperationException or FormatException)
        {
            _log.Error("devices.bin could not be read; starting with no paired devices", e);
            try
            {
                File.Move(_path, _path + ".bad", overwrite: true);
            }
            catch (Exception moveError) when (moveError is IOException or UnauthorizedAccessException)
            {
            }
        }
    }

    private void Save()
    {
        if (_path is null)
            return;
        var json = Json.Build(w =>
        {
            w.WriteNumber("v", 1);
            w.WriteStartArray("devices");
            foreach (var d in _devices.Values)
            {
                w.WriteStartObject();
                w.WriteString("deviceId", d.DeviceId);
                w.WriteString("deviceName", d.DeviceName);
                w.WriteString("tokenSha256", d.TokenSha256);
                w.WriteString("pairedAtUtc", d.PairedAtUtc);
                w.WriteString("lastSeenUtc", d.LastSeenUtc);
                w.WriteEndObject();
            }
            w.WriteEndArray();
        });
        try
        {
            AtomicFile.WriteAllBytes(_path, _protector.Protect(json));
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException or CryptographicException)
        {
            _log.Error("devices.bin could not be written", e);
        }
    }
}

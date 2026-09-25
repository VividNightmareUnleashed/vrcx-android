using System.Text.Json;
using VrcxCompanion.Core.Diagnostics;
using VrcxCompanion.Core.Protocol;

namespace VrcxCompanion.Core.Security;

public enum PairingWindowState
{
    Open,
    Paired,
    Expired,
    TooManyAttempts,
    Cancelled,
}

/// <summary>A pairing window as shown by the UI.</summary>
public sealed record PairingWindowInfo(
    string Code,
    DateTimeOffset ExpiresAtUtc,
    PairingWindowState State,
    int FailedAttempts,
    string? PairedDeviceName)
{
    public string DisplayCode => CrockfordCode.Format(Code);
}

public sealed record PairAttemptResult(bool Success, string? FailReason, string? Token, string? ServerProof, string? DeviceId, string? DeviceName)
{
    public static PairAttemptResult Fail(string reason) => new(false, reason, null, null, null, null);
}

/// <summary>
/// The pairing window (PROTOCOL.md §5.2): valid for 5 minutes or until one device pairs, closed after 5 failed
/// attempts.
/// </summary>
public sealed class PairingManager
{
    public const int MaxDeviceIdLength = 128;
    public const int MaxDeviceNameLength = 64;
    public const int MaxNonceLength = 256;

    private readonly object _gate = new();
    private readonly DeviceStore _devices;
    private readonly ICompanionLog _log;
    private readonly TimeProvider _time;
    private readonly TimeSpan _duration;
    private readonly int _maxFailures;
    private Window? _window;

    public PairingManager(DeviceStore devices, ICompanionLog log, TimeProvider? time = null, TimeSpan? duration = null,
        int maxFailures = ProtocolConstants.PairingMaxFailedAttempts)
    {
        _devices = devices;
        _log = log;
        _time = time ?? TimeProvider.System;
        _duration = duration ?? ProtocolConstants.PairingWindowDuration;
        _maxFailures = maxFailures;
    }

    /// <summary>Raised when a window opens, a device pairs or the window closes (on the thread that caused it).</summary>
    public event Action<PairingWindowInfo>? StateChanged;

    /// <summary>Whether a pairing window is open right now (for <c>hello.pairing</c> and discovery).</summary>
    public bool IsOpen => Current?.State == PairingWindowState.Open;

    /// <summary>The current or last window, with expiry applied.</summary>
    public PairingWindowInfo? Current
    {
        get
        {
            PairingWindowInfo? changed = null;
            PairingWindowInfo? info;
            lock (_gate)
            {
                if (_window is null)
                    return null;
                if (ExpireIfDue(_window))
                    changed = _window.ToInfo();
                info = _window.ToInfo();
            }
            if (changed != null)
                Raise(changed);
            return info;
        }
    }

    /// <summary>Opens a new window with a fresh code, replacing any previous one.</summary>
    public PairingWindowInfo Open()
    {
        PairingWindowInfo info;
        lock (_gate)
        {
            _window = new Window(CrockfordCode.Generate(), _time.GetUtcNow() + _duration);
            info = _window.ToInfo();
        }
        _log.Info("pairing window opened");
        Raise(info);
        return info;
    }

    /// <summary>Closes the open window (the user closed the pairing dialog).</summary>
    public void Cancel()
    {
        PairingWindowInfo? info = null;
        lock (_gate)
        {
            if (_window is { State: PairingWindowState.Open })
            {
                _window.State = PairingWindowState.Cancelled;
                info = _window.ToInfo();
            }
        }
        if (info != null)
        {
            _log.Info("pairing window closed by the user");
            Raise(info);
        }
    }

    /// <summary>Handles a <c>pair</c> message received on a connection that pinned <paramref name="fingerprint"/>.</summary>
    public PairAttemptResult TryPair(string fingerprint, string helloNonce, JsonElement message)
    {
        var deviceId = message.GetString("deviceId");
        var deviceName = SanitizeName(message.GetString("deviceName"));
        var clientNonce = message.GetString("nonce");
        var proof = message.GetString("proof");

        PairingWindowInfo? changed;
        PairAttemptResult result;
        lock (_gate)
        {
            (result, changed) = TryPairLocked(fingerprint, helloNonce, deviceId, deviceName, clientNonce, proof);
        }
        if (changed != null)
            Raise(changed);
        return result;
    }

    private (PairAttemptResult, PairingWindowInfo?) TryPairLocked(string fingerprint, string helloNonce, string? deviceId,
        string deviceName, string? clientNonce, string? proof)
    {
        var w = _window;
        if (w is null)
            return (PairAttemptResult.Fail("closed"), null);
        if (ExpireIfDue(w))
            return (PairAttemptResult.Fail("expired"), w.ToInfo());
        if (w.State == PairingWindowState.Expired)
            return (PairAttemptResult.Fail("expired"), null);
        if (w.State != PairingWindowState.Open)
            return (PairAttemptResult.Fail("closed"), null);

        if (string.IsNullOrEmpty(deviceId) || deviceId.Length > MaxDeviceIdLength ||
            string.IsNullOrEmpty(clientNonce) || clientNonce.Length > MaxNonceLength ||
            string.IsNullOrEmpty(proof) || proof.Length > MaxNonceLength)
        {
            _log.Warn("pair message with missing or invalid fields");
            return (PairAttemptResult.Fail("code"), null);
        }

        var expected = PairingCrypto.ClientProof(w.Code, fingerprint, helloNonce, clientNonce);
        if (!PairingCrypto.ProofEquals(expected, proof))
        {
            w.FailedAttempts++;
            _log.Warn($"pairing attempt with a wrong code ({w.FailedAttempts}/{_maxFailures})");
            if (w.FailedAttempts >= _maxFailures)
            {
                w.State = PairingWindowState.TooManyAttempts;
                _log.Warn("pairing window closed after too many failed attempts");
            }
            return (PairAttemptResult.Fail("code"), w.ToInfo());
        }

        var token = Base64Url.RandomBytes(32);
        _devices.AddOrReplace(deviceId, deviceName, token);
        var serverProof = PairingCrypto.ServerProof(w.Code, fingerprint, clientNonce, helloNonce);
        w.State = PairingWindowState.Paired;
        w.PairedDeviceName = deviceName;
        _log.Info("device paired");
        return (new PairAttemptResult(true, null, token, serverProof, deviceId, deviceName), w.ToInfo());
    }

    private bool ExpireIfDue(Window w)
    {
        if (w.State != PairingWindowState.Open || _time.GetUtcNow() < w.ExpiresAt)
            return false;
        w.State = PairingWindowState.Expired;
        _log.Info("pairing window expired");
        return true;
    }

    private void Raise(PairingWindowInfo info)
    {
        try
        {
            StateChanged?.Invoke(info);
        }
        catch (Exception e)
        {
            _log.Error("pairing state handler failed", e);
        }
    }

    internal static string SanitizeName(string? name)
    {
        if (string.IsNullOrWhiteSpace(name))
            return "Android device";
        var chars = name.Where(c => !char.IsControl(c)).Take(MaxDeviceNameLength).ToArray();
        var s = new string(chars).Trim();
        return s.Length == 0 ? "Android device" : s;
    }

    private sealed class Window
    {
        public Window(string code, DateTimeOffset expiresAt)
        {
            Code = code;
            ExpiresAt = expiresAt;
        }

        public string Code { get; }
        public DateTimeOffset ExpiresAt { get; }
        public PairingWindowState State { get; set; } = PairingWindowState.Open;
        public int FailedAttempts { get; set; }
        public string? PairedDeviceName { get; set; }

        public PairingWindowInfo ToInfo() => new(Code, ExpiresAt, State, FailedAttempts, PairedDeviceName);
    }
}

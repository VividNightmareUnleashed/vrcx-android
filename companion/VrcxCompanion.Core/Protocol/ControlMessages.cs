using System.Text.Json;
using VrcxCompanion.Core.Logs;

namespace VrcxCompanion.Core.Protocol;

/// <summary>Time zone of the PC as sent in <c>info.tz</c>.</summary>
public sealed record TimeZoneSnapshot(string WindowsId, string? IanaId, bool SupportsDst, int BaseUtcOffsetMin, int CurrentUtcOffsetMin);

/// <summary>The values of an <c>info</c> message except <c>pcUtcNowMs</c>, which is taken when it is sent.</summary>
public sealed record InfoSnapshot(string CompanionVersion, string MachineName, TimeZoneSnapshot Tz, string LogDir, bool DirExists);

/// <summary>Builders for the control messages the companion sends (UTF-8 JSON, PROTOCOL.md §2 and §5).</summary>
public static class ControlMessages
{
    public static long UnixMs(DateTimeOffset now) => now.ToUnixTimeMilliseconds();

    public static byte[] Hello(string companionId, string machineName, string nonce, bool pairing) => Json.Build(w =>
    {
        w.WriteString("t", "hello");
        w.WriteNumber("v", ProtocolConstants.Version);
        w.WriteString("id", companionId);
        w.WriteString("name", machineName);
        w.WriteString("nonce", nonce);
        w.WriteBoolean("pairing", pairing);
    });

    public static byte[] Paired(string token, string proof) => Json.Build(w =>
    {
        w.WriteString("t", "paired");
        w.WriteString("token", token);
        w.WriteString("proof", proof);
    });

    public static byte[] PairFail(string reason) => Json.Build(w =>
    {
        w.WriteString("t", "pairFail");
        w.WriteString("reason", reason);
    });

    public static byte[] AuthOk() => Json.Build(w => w.WriteString("t", "authOk"));

    public static byte[] AuthFail() => Json.Build(w => w.WriteString("t", "authFail"));

    public static byte[] SyncComplete() => Json.Build(w => w.WriteString("t", "syncComplete"));

    public static byte[] Heartbeat(DateTimeOffset now) => Json.Build(w =>
    {
        w.WriteString("t", "heartbeat");
        w.WriteNumber("pcUtcNowMs", UnixMs(now));
    });

    /// <summary>Confirms the phone's <c>idle</c> message (PROTOCOL.md §5.11).</summary>
    public static byte[] Idle(bool on) => Json.Build(w =>
    {
        w.WriteString("t", "idle");
        w.WriteBoolean("on", on);
    });

    public static byte[] Info(InfoSnapshot info, DateTimeOffset now) => Json.Build(w =>
    {
        w.WriteString("t", "info");
        w.WriteString("companionVersion", info.CompanionVersion);
        w.WriteString("machineName", info.MachineName);
        w.WriteNumber("pcUtcNowMs", UnixMs(now));
        w.WriteStartObject("tz");
        w.WriteString("windowsId", info.Tz.WindowsId);
        if (info.Tz.IanaId is null)
            w.WriteNull("ianaId");
        else
            w.WriteString("ianaId", info.Tz.IanaId);
        w.WriteBoolean("supportsDst", info.Tz.SupportsDst);
        w.WriteNumber("baseUtcOffsetMin", info.Tz.BaseUtcOffsetMin);
        w.WriteNumber("currentUtcOffsetMin", info.Tz.CurrentUtcOffsetMin);
        w.WriteEndObject();
        w.WriteString("logDir", info.LogDir);
        w.WriteBoolean("dirExists", info.DirExists);
    });

    public static byte[] Snapshot(IEnumerable<LogFileMeta> files) => Json.Build(w =>
    {
        w.WriteString("t", "snapshot");
        w.WriteStartArray("files");
        foreach (var f in files)
        {
            w.WriteStartObject();
            w.WriteString("name", f.Name);
            w.WriteString("fileId", f.FileId);
            w.WriteNumber("creationTimeUtcTicks", f.CreationTimeUtcTicks);
            w.WriteNumber("lastWriteTimeUtcTicks", f.LastWriteTimeUtcTicks);
            w.WriteNumber("length", f.Length);
            w.WriteEndObject();
        }
        w.WriteEndArray();
    });

    public static byte[] Process(bool vrchatRunning, bool steamVrRunning, DateTimeOffset now) => Json.Build(w =>
    {
        w.WriteString("t", "process");
        w.WriteBoolean("vrchatRunning", vrchatRunning);
        w.WriteBoolean("steamVrRunning", steamVrRunning);
        w.WriteNumber("pcUtcNowMs", UnixMs(now));
    });

    public static byte[] Truncate(string name, string fileId, long newLength) => Json.Build(w =>
    {
        w.WriteString("t", "truncate");
        w.WriteString("name", name);
        w.WriteString("fileId", fileId);
        w.WriteNumber("newLength", newLength);
    });

    public static byte[] DiscoveryReply(string companionId, string machineName, int port, string fingerprint, bool pairing) =>
        Json.Build(w =>
        {
            w.WriteString("t", ProtocolConstants.DiscoverReplyType);
            w.WriteNumber("v", ProtocolConstants.Version);
            w.WriteString("id", companionId);
            w.WriteString("name", machineName);
            w.WriteNumber("port", port);
            w.WriteString("fp", fingerprint);
            w.WriteBoolean("pairing", pairing);
        });

    /// <summary>Parses a control payload; returns false when it is not a JSON object with a string <c>t</c>.</summary>
    public static bool TryParse(ReadOnlySpan<byte> payload, out JsonDocument? document, out string? type)
    {
        document = null;
        type = null;
        try
        {
            var doc = JsonDocument.Parse(payload.ToArray(), Json.ReaderOptions);
            var t = doc.RootElement.GetString("t");
            if (t is null)
            {
                doc.Dispose();
                return false;
            }
            document = doc;
            type = t;
            return true;
        }
        catch (JsonException)
        {
            return false;
        }
    }
}

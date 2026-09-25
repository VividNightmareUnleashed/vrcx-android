using VrcxCompanion.Core.Protocol;

namespace VrcxCompanion.Core;

/// <summary>Reads the PC time zone for <c>info.tz</c> (PROTOCOL.md §5.4).</summary>
public static class TimeZoneReader
{
    public static TimeZoneSnapshot Capture(TimeZoneInfo zone, DateTimeOffset now)
    {
        string? iana;
        if (zone.HasIanaId)
            iana = zone.Id;
        else if (!TimeZoneInfo.TryConvertWindowsIdToIanaId(zone.Id, out iana))
            iana = null;
        return new TimeZoneSnapshot(
            zone.Id,
            iana,
            zone.SupportsDaylightSavingTime,
            (int)Math.Round(zone.BaseUtcOffset.TotalMinutes),
            (int)Math.Round(zone.GetUtcOffset(now).TotalMinutes));
    }

    /// <summary>Re-reads the local zone after <see cref="TimeZoneInfo.ClearCachedData"/>.</summary>
    public static TimeZoneSnapshot CaptureLocal(DateTimeOffset now)
    {
        TimeZoneInfo.ClearCachedData();
        return Capture(TimeZoneInfo.Local, now);
    }
}

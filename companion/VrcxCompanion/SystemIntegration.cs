using System.ComponentModel;
using System.Diagnostics;
using Microsoft.Win32;
using VrcxCompanion.Core.Settings;

namespace VrcxCompanion;

/// <summary><c>HKCU\Software\Microsoft\Windows\CurrentVersion\Run</c> entry. Off unless the user turns it on.</summary>
internal static class Autostart
{
    private const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
    private const string ValueName = "VRCX Companion";

    private static string Command => "\"" + (Environment.ProcessPath ?? Application.ExecutablePath) + "\"";

    public static bool IsEnabled()
    {
        using var key = Registry.CurrentUser.OpenSubKey(RunKey, writable: false);
        return key?.GetValue(ValueName) is string value && string.Equals(value, Command, StringComparison.OrdinalIgnoreCase);
    }

    public static void SetEnabled(bool enabled)
    {
        using var key = Registry.CurrentUser.CreateSubKey(RunKey, writable: true);
        if (enabled)
            key.SetValue(ValueName, Command, RegistryValueKind.String);
        else
            key.DeleteValue(ValueName, throwOnMissingValue: false);
    }
}

/// <summary>Adds the inbound firewall rules through one elevated <c>netsh</c> run.</summary>
internal static class Firewall
{
    /// <summary>Returns the exit code, or null when the user declined the elevation prompt.</summary>
    public static async Task<int?> AddRulesAsync(int tcpPort, int udpPort)
    {
        var psi = new ProcessStartInfo
        {
            FileName = Path.Combine(Environment.SystemDirectory, "cmd.exe"),
            Arguments = FirewallCommand.BuildCmdArguments(tcpPort, udpPort),
            UseShellExecute = true,
            Verb = "runas",
            WindowStyle = ProcessWindowStyle.Hidden,
        };
        try
        {
            using var process = Process.Start(psi);
            if (process == null)
                return null;
            await process.WaitForExitAsync().ConfigureAwait(false);
            return process.ExitCode;
        }
        catch (Win32Exception e) when (e.NativeErrorCode == 1223) // ERROR_CANCELLED
        {
            return null;
        }
    }
}

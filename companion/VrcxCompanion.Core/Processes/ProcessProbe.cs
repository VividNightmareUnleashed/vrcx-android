using System.Diagnostics;
using System.Runtime.InteropServices;
using Microsoft.Win32.SafeHandles;

namespace VrcxCompanion.Core.Processes;

/// <summary>The two process flags of PROTOCOL.md §5.8.</summary>
public readonly record struct ProcessState(bool VrchatRunning, bool SteamVrRunning);

public interface IProcessProbe
{
    /// <summary>Returns the current state. Called at most once per second, from one thread at a time.</summary>
    ProcessState Poll();
}

/// <summary>
/// <c>vrchatRunning</c>: any non-exited process named <c>VRChat</c> (case-insensitive); <c>steamVrRunning</c>: any
/// process named <c>vrserver</c>. Like upstream <c>ProcessMonitor</c>, a found process is tracked through a handle and
/// the process list is only enumerated while a flag is false or its tracked process has exited; unlike upstream,
/// another running process with the same name keeps the flag true.
/// </summary>
public sealed class SystemProcessProbe : IProcessProbe, IDisposable
{
    private readonly Target _vrchat = new("VRChat");
    private readonly Target _vrserver = new("vrserver");

    public ProcessState Poll()
    {
        var needEnumeration = !_vrchat.CheckTracked() | !_vrserver.CheckTracked();
        if (needEnumeration)
        {
            var processes = Enumerate();
            _vrchat.Find(processes);
            _vrserver.Find(processes);
        }
        return new ProcessState(_vrchat.Running, _vrserver.Running);
    }

    /// <summary>Process names as .NET reports <c>Process.ProcessName</c> (image name without ".exe").</summary>
    internal static string ShortName(string exeFile)
    {
        var name = Path.GetFileName(exeFile);
        return name.EndsWith(".exe", StringComparison.OrdinalIgnoreCase) ? name[..^4] : name;
    }

    private static List<(int pid, string name)> Enumerate()
    {
        var result = new List<(int, string)>(256);
        if (OperatingSystem.IsWindows())
        {
            var snapshot = Native.CreateToolhelp32Snapshot(Native.Th32csSnapprocess, 0);
            if (snapshot.IsInvalid)
                return result;
            using (snapshot)
            {
                var entry = new Native.ProcessEntry32 { Size = (uint)Marshal.SizeOf<Native.ProcessEntry32>() };
                if (!Native.Process32First(snapshot, ref entry))
                    return result;
                do
                {
                    result.Add(((int)entry.ProcessId, ShortName(entry.ExeFile)));
                } while (Native.Process32Next(snapshot, ref entry));
            }
            return result;
        }

        foreach (var p in Process.GetProcesses())
        {
            using (p)
            {
                try
                {
                    result.Add((p.Id, p.ProcessName));
                }
                catch (InvalidOperationException)
                {
                }
            }
        }
        return result;
    }

    public void Dispose()
    {
        _vrchat.Dispose();
        _vrserver.Dispose();
    }

    private sealed class Target : IDisposable
    {
        private readonly string _name;
        private SafeProcessHandle? _handle;

        public Target(string name) => _name = name;

        public bool Running { get; private set; }

        /// <summary>True when a tracked process is still alive (no enumeration needed).</summary>
        public bool CheckTracked()
        {
            if (_handle == null)
                return false;
            if (!HasExited(_handle))
                return true;
            _handle.Dispose();
            _handle = null;
            Running = false;
            return false;
        }

        public void Find(List<(int pid, string name)> processes)
        {
            if (_handle != null)
                return;
            var unopenable = false;
            foreach (var (pid, name) in processes)
            {
                if (!string.Equals(name, _name, StringComparison.OrdinalIgnoreCase))
                    continue;
                var handle = Open(pid);
                if (handle == null)
                {
                    // Listed but cannot be opened: counts as running; enumerate again next time.
                    unopenable = true;
                    continue;
                }
                if (HasExited(handle))
                {
                    handle.Dispose();
                    continue;
                }
                _handle = handle;
                break;
            }
            Running = _handle != null || unopenable;
        }

        private static SafeProcessHandle? Open(int pid)
        {
            if (!OperatingSystem.IsWindows())
                return null;
            var h = Native.OpenProcess(Native.Synchronize | Native.ProcessQueryLimitedInformation, false, (uint)pid);
            if (h.IsInvalid)
            {
                h.Dispose();
                return null;
            }
            return h;
        }

        private static bool HasExited(SafeProcessHandle handle) =>
            OperatingSystem.IsWindows() && Native.WaitForSingleObject(handle, 0) == Native.WaitObject0;

        public void Dispose()
        {
            _handle?.Dispose();
            _handle = null;
        }
    }

    private static class Native
    {
        public const uint Th32csSnapprocess = 0x00000002;
        public const uint Synchronize = 0x00100000;
        public const uint ProcessQueryLimitedInformation = 0x00001000;
        public const uint WaitObject0 = 0;

        [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
        public struct ProcessEntry32
        {
            public uint Size;
            public uint Usage;
            public uint ProcessId;
            public IntPtr DefaultHeapId;
            public uint ModuleId;
            public uint Threads;
            public uint ParentProcessId;
            public int PriClassBase;
            public uint Flags;

            [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 260)]
            public string ExeFile;
        }

        [DllImport("kernel32.dll", SetLastError = true)]
        public static extern SafeFileHandle CreateToolhelp32Snapshot(uint flags, uint processId);

        [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode, EntryPoint = "Process32FirstW")]
        [return: MarshalAs(UnmanagedType.Bool)]
        public static extern bool Process32First(SafeFileHandle snapshot, ref ProcessEntry32 entry);

        [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode, EntryPoint = "Process32NextW")]
        [return: MarshalAs(UnmanagedType.Bool)]
        public static extern bool Process32Next(SafeFileHandle snapshot, ref ProcessEntry32 entry);

        [DllImport("kernel32.dll", SetLastError = true)]
        public static extern SafeProcessHandle OpenProcess(uint access, [MarshalAs(UnmanagedType.Bool)] bool inherit, uint processId);

        [DllImport("kernel32.dll", SetLastError = true)]
        public static extern uint WaitForSingleObject(SafeProcessHandle handle, uint milliseconds);
    }
}

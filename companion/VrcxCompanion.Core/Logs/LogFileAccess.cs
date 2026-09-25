using System.Runtime.InteropServices;
using Microsoft.Win32.SafeHandles;

namespace VrcxCompanion.Core.Logs;

/// <summary>
/// Read-only access to log files. Files are always opened with <c>FileShare.ReadWrite | FileShare.Delete</c> so
/// VRChat's writes and deletes are never blocked, and handles are only held for the duration of one operation.
/// </summary>
public static class LogFileAccess
{
    public static SafeFileHandle OpenShared(string path) =>
        File.OpenHandle(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete, FileOptions.None);

    /// <summary>
    /// The file id of an open handle: NTFS volume serial number (8 hex digits) followed by the 64-bit file index
    /// (16 hex digits), lowercase.
    /// </summary>
    public static string GetFileId(SafeFileHandle handle, string path)
    {
        if (OperatingSystem.IsWindows() && GetFileInformationByHandle(handle, out var info))
        {
            var index = ((ulong)info.FileIndexHigh << 32) | info.FileIndexLow;
            return info.VolumeSerialNumber.ToString("x8") + index.ToString("x16");
        }
        // Not Windows (or the call failed): a stable-enough stand-in derived from the path and creation time.
        var creation = File.GetCreationTimeUtc(path).Ticks;
        var hash = System.Security.Cryptography.SHA256.HashData(System.Text.Encoding.UTF8.GetBytes(Path.GetFullPath(path) + "|" + creation));
        return "ffffffff" + Convert.ToHexString(hash, 0, 8).ToLowerInvariant();
    }

    /// <summary>Opens the file and reports its id and its real length (the handle's end of file).</summary>
    public static bool TryProbe(string path, out string fileId, out long length)
    {
        fileId = "";
        length = -1;
        try
        {
            using var handle = OpenShared(path);
            fileId = GetFileId(handle, path);
            length = RandomAccess.GetLength(handle);
            return true;
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            return false;
        }
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct ByHandleFileInformation
    {
        public uint FileAttributes;
        public uint CreationTimeLow;
        public uint CreationTimeHigh;
        public uint LastAccessTimeLow;
        public uint LastAccessTimeHigh;
        public uint LastWriteTimeLow;
        public uint LastWriteTimeHigh;
        public uint VolumeSerialNumber;
        public uint FileSizeHigh;
        public uint FileSizeLow;
        public uint NumberOfLinks;
        public uint FileIndexHigh;
        public uint FileIndexLow;
    }

    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool GetFileInformationByHandle(SafeFileHandle file, out ByHandleFileInformation information);
}

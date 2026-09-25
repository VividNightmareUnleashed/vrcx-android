using System.Security.Cryptography;
using System.Text;

namespace VrcxCompanion.Core.Security;

/// <summary>Protects secrets at rest.</summary>
public interface ISecretProtector
{
    byte[] Protect(byte[] data);
    byte[] Unprotect(byte[] data);
}

/// <summary>DPAPI, CurrentUser scope, with fixed application entropy.</summary>
public sealed class DpapiProtector : ISecretProtector
{
    private readonly byte[] _entropy;

    public DpapiProtector(string purpose)
    {
        _entropy = Encoding.UTF8.GetBytes("VRCX-Companion|" + purpose);
    }

    public byte[] Protect(byte[] data)
    {
        if (!OperatingSystem.IsWindows())
            throw new PlatformNotSupportedException("DPAPI requires Windows");
        return ProtectedData.Protect(data, _entropy, DataProtectionScope.CurrentUser);
    }

    public byte[] Unprotect(byte[] data)
    {
        if (!OperatingSystem.IsWindows())
            throw new PlatformNotSupportedException("DPAPI requires Windows");
        return ProtectedData.Unprotect(data, _entropy, DataProtectionScope.CurrentUser);
    }
}

/// <summary>No protection. Only for tests.</summary>
public sealed class PlainProtector : ISecretProtector
{
    public byte[] Protect(byte[] data) => (byte[])data.Clone();
    public byte[] Unprotect(byte[] data) => (byte[])data.Clone();
}

internal static class AtomicFile
{
    /// <summary>Writes to a temporary file next to <paramref name="path"/> and moves it into place.</summary>
    public static void WriteAllBytes(string path, byte[] data)
    {
        var dir = Path.GetDirectoryName(Path.GetFullPath(path))!;
        Directory.CreateDirectory(dir);
        var tmp = Path.Combine(dir, Path.GetFileName(path) + ".tmp");
        using (var fs = new FileStream(tmp, FileMode.Create, FileAccess.Write, FileShare.None))
        {
            fs.Write(data);
            fs.Flush(flushToDisk: true);
        }
        File.Move(tmp, path, overwrite: true);
    }
}

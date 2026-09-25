using System.Security.Cryptography;
using System.Text;
using VrcxCompanion.Core.Protocol;

namespace VrcxCompanion.Core.Security;

/// <summary>Pairing proofs (PROTOCOL.md §5.2). All inputs are the strings exactly as transmitted.</summary>
public static class PairingCrypto
{
    private const string Prefix = "vrcxc-pair-v1|";

    /// <summary>HMAC-SHA256(codeKey, "vrcxc-pair-v1|client|" + fp + "|" + helloNonce + "|" + clientNonce), base64url.</summary>
    public static string ClientProof(string normalizedCode, string fingerprint, string helloNonce, string clientNonce) =>
        Mac(normalizedCode, Prefix + "client|" + fingerprint + "|" + helloNonce + "|" + clientNonce);

    /// <summary>HMAC-SHA256(codeKey, "vrcxc-pair-v1|server|" + fp + "|" + clientNonce + "|" + helloNonce), base64url.</summary>
    public static string ServerProof(string normalizedCode, string fingerprint, string clientNonce, string helloNonce) =>
        Mac(normalizedCode, Prefix + "server|" + fingerprint + "|" + clientNonce + "|" + helloNonce);

    /// <summary>Constant-time comparison of two base64url proofs.</summary>
    public static bool ProofEquals(string expected, string? actual)
    {
        if (actual is null)
            return false;
        var a = Encoding.ASCII.GetBytes(expected);
        var b = Encoding.UTF8.GetBytes(actual);
        return a.Length == b.Length && CryptographicOperations.FixedTimeEquals(a, b);
    }

    /// <summary>SHA-256 of the token string (UTF-8), base64url. Only this hash is stored on the PC.</summary>
    public static string TokenHash(string token) => Base64Url.Encode(SHA256.HashData(Encoding.UTF8.GetBytes(token)));

    private static string Mac(string normalizedCode, string message)
    {
        var key = Encoding.ASCII.GetBytes(normalizedCode);
        var data = Encoding.UTF8.GetBytes(message);
        return Base64Url.Encode(HMACSHA256.HashData(key, data));
    }
}

namespace VrcxCompanion.Core.Protocol;

/// <summary>base64url without padding (RFC 4648 §5), as used for nonces, tokens, proofs and fingerprints.</summary>
public static class Base64Url
{
    public static string Encode(ReadOnlySpan<byte> data)
    {
        var s = Convert.ToBase64String(data);
        return s.TrimEnd('=').Replace('+', '-').Replace('/', '_');
    }

    public static bool TryDecode(string? text, out byte[] bytes)
    {
        bytes = Array.Empty<byte>();
        if (text is null)
            return false;
        foreach (var c in text)
        {
            if (!(c is >= 'A' and <= 'Z' or >= 'a' and <= 'z' or >= '0' and <= '9' or '-' or '_'))
                return false;
        }
        var s = text.Replace('-', '+').Replace('_', '/');
        switch (s.Length % 4)
        {
            case 1:
                return false;
            case 2:
                s += "==";
                break;
            case 3:
                s += "=";
                break;
        }
        try
        {
            bytes = Convert.FromBase64String(s);
            return true;
        }
        catch (FormatException)
        {
            return false;
        }
    }

    public static string RandomBytes(int count)
    {
        Span<byte> buffer = stackalloc byte[count];
        System.Security.Cryptography.RandomNumberGenerator.Fill(buffer);
        return Encode(buffer);
    }
}

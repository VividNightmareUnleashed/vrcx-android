using System.Security.Cryptography;
using System.Text;

namespace VrcxCompanion.Core.Security;

/// <summary>Pairing codes: 10 characters of Crockford base32 (50 random bits), shown as XXXXX-XXXXX.</summary>
public static class CrockfordCode
{
    public const string Alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    public const int Length = 10;

    /// <summary>Generates a new code from 50 cryptographically random bits (normalized form, no dash).</summary>
    public static string Generate()
    {
        Span<byte> bytes = stackalloc byte[8];
        RandomNumberGenerator.Fill(bytes);
        var bits = BitConverter.ToUInt64(bytes) & ((1UL << 50) - 1);
        var chars = new char[Length];
        for (var i = Length - 1; i >= 0; i--)
        {
            chars[i] = Alphabet[(int)(bits & 31)];
            bits >>= 5;
        }
        return new string(chars);
    }

    /// <summary>
    /// Normalizes user input: uppercase, drops '-' and whitespace, maps O to 0 and I/L to 1. Returns null when the
    /// result is not exactly 10 Crockford characters.
    /// </summary>
    public static string? Normalize(string? input)
    {
        if (input is null)
            return null;
        var sb = new StringBuilder(Length);
        foreach (var raw in input)
        {
            if (raw == '-' || char.IsWhiteSpace(raw))
                continue;
            var c = char.ToUpperInvariant(raw);
            c = c switch
            {
                'O' => '0',
                'I' or 'L' => '1',
                _ => c,
            };
            if (Alphabet.IndexOf(c) < 0)
                return null;
            sb.Append(c);
        }
        return sb.Length == Length ? sb.ToString() : null;
    }

    /// <summary>Formats a normalized code for display as XXXXX-XXXXX.</summary>
    public static string Format(string normalizedCode)
    {
        if (normalizedCode.Length != Length)
            throw new ArgumentException("code must have 10 characters", nameof(normalizedCode));
        return normalizedCode[..5] + "-" + normalizedCode[5..];
    }
}

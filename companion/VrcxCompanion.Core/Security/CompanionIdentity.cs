using System.Runtime.Versioning;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Text.Json;
using VrcxCompanion.Core.Diagnostics;
using VrcxCompanion.Core.Protocol;

namespace VrcxCompanion.Core.Security;

/// <summary>
/// The companion's identity: a random companion id and a self-signed ECDSA P-256 certificate
/// (CN = "VRCX Companion &lt;machine&gt;", valid 20 years).
/// <para>
/// <c>identity.bin</c> (DPAPI-protected) holds the id, the certificate and the name of its private key. The key is a
/// non-exportable, persisted key in the user's CNG key store (Microsoft Software Key Storage Provider, which keeps it
/// DPAPI-protected for the user). It is created once under a fixed name derived from the path of <c>identity.bin</c>
/// and opened by that name on every start. Nothing is imported per start: loading a PKCS#12 would put a fresh key
/// container in the key store each time, deleted only when the certificate is disposed, so every crash, kill or
/// logoff would leave a copy of the private key behind. SChannel cannot use an in-memory key, which rules out an
/// ephemeral one.
/// </para>
/// </summary>
public sealed class CompanionIdentity : IDisposable
{
    private const int FormatVersion = 2;

    private CompanionIdentity(string companionId, X509Certificate2 certificate, string? keyName)
    {
        CompanionId = companionId;
        Certificate = certificate;
        KeyName = keyName;
        Fingerprint = ComputeFingerprint(certificate);
    }

    public string CompanionId { get; }
    public X509Certificate2 Certificate { get; }

    /// <summary>Name of the persisted key in the user's CNG key store (null for an ephemeral identity).</summary>
    public string? KeyName { get; }

    /// <summary>base64url (no padding) of SHA-256 over the DER SubjectPublicKeyInfo.</summary>
    public string Fingerprint { get; }

    public static string ComputeFingerprint(X509Certificate2 certificate) =>
        Base64Url.Encode(SHA256.HashData(certificate.PublicKey.ExportSubjectPublicKeyInfo()));

    /// <summary>
    /// The name of the persisted key that belongs to <paramref name="identityPath"/>. A new identity for the same path
    /// overwrites it, so replacing an identity never leaves the old key behind.
    /// </summary>
    public static string KeyNameFor(string identityPath)
    {
        var full = Path.GetFullPath(identityPath).ToUpperInvariant();
        var hash = SHA256.HashData(Encoding.UTF8.GetBytes(full));
        return "VRCX-Companion-identity-" + Convert.ToHexString(hash, 0, 8).ToLowerInvariant();
    }

    public static CompanionIdentity LoadOrCreate(string path, ISecretProtector protector, string machineName, ICompanionLog log)
    {
        if (!OperatingSystem.IsWindows())
            throw new PlatformNotSupportedException("the companion identity is kept in the Windows key store");

        if (File.Exists(path))
        {
            StoredIdentity? stored = null;
            try
            {
                stored = Read(path, protector);
                var identity = stored.Pfx != null ? Migrate(path, protector, stored, log) : Open(stored);
                log.Info($"identity loaded (fingerprint {identity.Fingerprint})");
                return identity;
            }
            catch (Exception e) when (e is CryptographicException or JsonException or InvalidDataException or FormatException
                                          or KeyNotFoundException or InvalidOperationException or IOException)
            {
                log.Error("identity.bin or its key could not be read; creating a new identity (phones must pair again)", e);
                TryMoveAside(path);
                if (stored?.KeyName is { } oldKey && oldKey != KeyNameFor(path))
                    TryDeleteKey(oldKey, stored.Provider);
            }
        }

        var id = Guid.NewGuid().ToString();
        var keyName = KeyNameFor(path);
        var (der, provider) = CreatePersisted(keyName, machineName);
        Save(path, protector, id, der, keyName, provider);
        var result = new CompanionIdentity(id, Bind(der, keyName, provider), keyName);
        log.Info($"identity created (fingerprint {result.Fingerprint})");
        return result;
    }

    /// <summary>
    /// Removes <c>identity.bin</c> and its key from the user's key store (the self test and the tests clean up with
    /// it). Missing pieces are ignored.
    /// </summary>
    public static void Delete(string path, ISecretProtector protector)
    {
        if (OperatingSystem.IsWindows())
        {
            try
            {
                var stored = Read(path, protector);
                if (stored.KeyName != null)
                    TryDeleteKey(stored.KeyName, stored.Provider);
            }
            catch (Exception e) when (e is CryptographicException or JsonException or InvalidDataException or FormatException
                                          or KeyNotFoundException or InvalidOperationException or IOException)
            {
            }
            TryDeleteKey(KeyNameFor(path), CngProvider.MicrosoftSoftwareKeyStorageProvider.Provider);
        }
        try
        {
            File.Delete(path);
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
        }
    }

    /// <summary>
    /// Creates an identity for tests: its key lives in a temporary key container that is deleted when the identity is
    /// disposed.
    /// </summary>
    public static CompanionIdentity CreateEphemeral(string machineName)
    {
        using var key = ECDsa.Create(ECCurve.NamedCurves.nistP256);
        using var cert = CreateSelfSigned(machineName, key);
        var pfx = cert.Export(X509ContentType.Pkcs12);
        return new CompanionIdentity(Guid.NewGuid().ToString(), new X509Certificate2(pfx, (string?)null, X509KeyStorageFlags.UserKeySet), null);
    }

    private sealed record StoredIdentity(string Id, byte[]? Certificate, string? KeyName, string Provider, byte[]? Pfx);

    private static StoredIdentity Read(string path, ISecretProtector protector)
    {
        var json = protector.Unprotect(File.ReadAllBytes(path));
        using var doc = JsonDocument.Parse(json);
        var root = doc.RootElement;
        var id = root.GetProperty("id").GetString();
        if (string.IsNullOrEmpty(id))
            throw new InvalidDataException("identity without id");
        if (root.TryGetProperty("pfx", out var pfx))
            return new StoredIdentity(id, null, null, CngProvider.MicrosoftSoftwareKeyStorageProvider.Provider,
                Convert.FromBase64String(pfx.GetString() ?? ""));
        var cert = Convert.FromBase64String(root.GetProperty("cert").GetString() ?? "");
        var keyName = root.GetProperty("key").GetString();
        var provider = root.GetProperty("provider").GetString();
        if (string.IsNullOrEmpty(keyName) || string.IsNullOrEmpty(provider))
            throw new InvalidDataException("identity without key name");
        return new StoredIdentity(id, cert, keyName, provider, null);
    }

    [SupportedOSPlatform("windows")]
    private static CompanionIdentity Open(StoredIdentity stored) =>
        new(stored.Id, Bind(stored.Certificate!, stored.KeyName!, stored.Provider), stored.KeyName);

    /// <summary>
    /// Format 1 kept a PKCS#12 and imported it on every start. It is imported once more, persisted, and the file is
    /// rewritten to point at that key, so the identity (and every pairing) survives the upgrade.
    /// </summary>
    [SupportedOSPlatform("windows")]
    private static CompanionIdentity Migrate(string path, ISecretProtector protector, StoredIdentity stored, ICompanionLog log)
    {
        string keyName, provider;
        byte[] der;
        using (var imported = new X509Certificate2(stored.Pfx!, (string?)null, X509KeyStorageFlags.UserKeySet | X509KeyStorageFlags.PersistKeySet))
        {
            using var key = imported.GetECDsaPrivateKey() as ECDsaCng
                            ?? throw new InvalidDataException("identity key is not a CNG ECDSA key");
            keyName = key.Key.KeyName ?? throw new InvalidDataException("imported identity key has no name");
            provider = key.Key.Provider?.Provider ?? CngProvider.MicrosoftSoftwareKeyStorageProvider.Provider;
            der = imported.RawData;
        }
        Save(path, protector, stored.Id, der, keyName, provider);
        log.Info("identity.bin upgraded to a persisted key");
        return new CompanionIdentity(stored.Id, Bind(der, keyName, provider), keyName);
    }

    [SupportedOSPlatform("windows")]
    private static (byte[] der, string provider) CreatePersisted(string keyName, string machineName)
    {
        var parameters = new CngKeyCreationParameters
        {
            Provider = CngProvider.MicrosoftSoftwareKeyStorageProvider,
            KeyCreationOptions = CngKeyCreationOptions.OverwriteExistingKey,
            ExportPolicy = CngExportPolicies.None,
            KeyUsage = CngKeyUsages.Signing,
        };
        using var key = CngKey.Create(CngAlgorithm.ECDsaP256, keyName, parameters);
        using var ecdsa = new ECDsaCng(key);
        using var cert = CreateSelfSigned(machineName, ecdsa);
        return (cert.RawData, parameters.Provider.Provider);
    }

    /// <summary>The stored certificate joined with its persisted key (opened by name; nothing is copied).</summary>
    [SupportedOSPlatform("windows")]
    private static X509Certificate2 Bind(byte[] der, string keyName, string provider)
    {
        using var certificate = new X509Certificate2(der);
        using var key = CngKey.Open(keyName, new CngProvider(provider));
        using var ecdsa = new ECDsaCng(key);
        if (!ecdsa.ExportSubjectPublicKeyInfo().AsSpan().SequenceEqual(certificate.PublicKey.ExportSubjectPublicKeyInfo()))
            throw new InvalidDataException("the stored key does not belong to the certificate");
        return certificate.CopyWithPrivateKey(ecdsa);
    }

    private static X509Certificate2 CreateSelfSigned(string machineName, ECDsa key)
    {
        var name = new X500DistinguishedNameBuilder();
        name.AddCommonName("VRCX Companion " + machineName);
        var request = new CertificateRequest(name.Build(), key, HashAlgorithmName.SHA256);
        request.CertificateExtensions.Add(new X509BasicConstraintsExtension(false, false, 0, true));
        request.CertificateExtensions.Add(new X509KeyUsageExtension(X509KeyUsageFlags.DigitalSignature, true));
        request.CertificateExtensions.Add(new X509EnhancedKeyUsageExtension(
            new OidCollection { new Oid("1.3.6.1.5.5.7.3.1", "Server Authentication") }, false));
        var notBefore = DateTimeOffset.UtcNow.AddDays(-1);
        return request.CreateSelfSigned(notBefore, notBefore.AddYears(20));
    }

    private static void Save(string path, ISecretProtector protector, string id, byte[] certificate, string keyName, string provider)
    {
        var json = Json.Build(w =>
        {
            w.WriteNumber("v", FormatVersion);
            w.WriteString("id", id);
            w.WriteString("cert", Convert.ToBase64String(certificate));
            w.WriteString("key", keyName);
            w.WriteString("provider", provider);
        });
        AtomicFile.WriteAllBytes(path, protector.Protect(json));
    }

    [SupportedOSPlatform("windows")]
    private static void TryDeleteKey(string keyName, string provider)
    {
        try
        {
            var p = new CngProvider(provider);
            if (!CngKey.Exists(keyName, p))
                return;
            using var key = CngKey.Open(keyName, p);
            key.Delete();
        }
        catch (CryptographicException)
        {
        }
    }

    private static void TryMoveAside(string path)
    {
        try
        {
            File.Move(path, path + ".bad", overwrite: true);
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
        }
    }

    public void Dispose() => Certificate.Dispose();
}

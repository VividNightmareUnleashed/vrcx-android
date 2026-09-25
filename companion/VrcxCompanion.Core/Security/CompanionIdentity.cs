using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text.Json;
using VrcxCompanion.Core.Diagnostics;
using VrcxCompanion.Core.Protocol;

namespace VrcxCompanion.Core.Security;

/// <summary>
/// The companion's identity: a random companion id and a self-signed ECDSA P-256 certificate
/// (CN = "VRCX Companion &lt;machine&gt;", valid 20 years), stored DPAPI-protected in <c>identity.bin</c>.
/// </summary>
public sealed class CompanionIdentity : IDisposable
{
    private CompanionIdentity(string companionId, X509Certificate2 certificate)
    {
        CompanionId = companionId;
        Certificate = certificate;
        Fingerprint = ComputeFingerprint(certificate);
    }

    public string CompanionId { get; }
    public X509Certificate2 Certificate { get; }

    /// <summary>base64url (no padding) of SHA-256 over the DER SubjectPublicKeyInfo.</summary>
    public string Fingerprint { get; }

    public static string ComputeFingerprint(X509Certificate2 certificate) =>
        Base64Url.Encode(SHA256.HashData(certificate.PublicKey.ExportSubjectPublicKeyInfo()));

    public static CompanionIdentity LoadOrCreate(string path, ISecretProtector protector, string machineName, ICompanionLog log)
    {
        if (File.Exists(path))
        {
            try
            {
                var json = protector.Unprotect(File.ReadAllBytes(path));
                using var doc = JsonDocument.Parse(json);
                var id = doc.RootElement.GetProperty("id").GetString();
                var pfx = Convert.FromBase64String(doc.RootElement.GetProperty("pfx").GetString() ?? "");
                if (string.IsNullOrEmpty(id))
                    throw new InvalidDataException("identity without id");
                var identity = new CompanionIdentity(id, ImportPfx(pfx));
                log.Info($"identity loaded (fingerprint {identity.Fingerprint})");
                return identity;
            }
            catch (Exception e) when (e is CryptographicException or JsonException or InvalidDataException or FormatException
                                          or KeyNotFoundException or InvalidOperationException or IOException)
            {
                log.Error("identity.bin could not be read; creating a new identity (phones must pair again)", e);
                TryMoveAside(path);
            }
        }

        var created = CreateNew(machineName);
        Save(path, protector, created.id, created.pfx);
        var result = new CompanionIdentity(created.id, ImportPfx(created.pfx));
        log.Info($"identity created (fingerprint {result.Fingerprint})");
        return result;
    }

    /// <summary>Creates an identity in memory only (tests, self test).</summary>
    public static CompanionIdentity CreateEphemeral(string machineName)
    {
        var created = CreateNew(machineName);
        return new CompanionIdentity(created.id, ImportPfx(created.pfx));
    }

    private static (string id, byte[] pfx) CreateNew(string machineName)
    {
        using var key = ECDsa.Create(ECCurve.NamedCurves.nistP256);
        var name = new X500DistinguishedNameBuilder();
        name.AddCommonName("VRCX Companion " + machineName);
        var request = new CertificateRequest(name.Build(), key, HashAlgorithmName.SHA256);
        request.CertificateExtensions.Add(new X509BasicConstraintsExtension(false, false, 0, true));
        request.CertificateExtensions.Add(new X509KeyUsageExtension(X509KeyUsageFlags.DigitalSignature, true));
        request.CertificateExtensions.Add(new X509EnhancedKeyUsageExtension(
            new OidCollection { new Oid("1.3.6.1.5.5.7.3.1", "Server Authentication") }, false));
        var notBefore = DateTimeOffset.UtcNow.AddDays(-1);
        using var cert = request.CreateSelfSigned(notBefore, notBefore.AddYears(20));
        return (Guid.NewGuid().ToString(), cert.Export(X509ContentType.Pkcs12));
    }

    private static void Save(string path, ISecretProtector protector, string id, byte[] pfx)
    {
        var json = Json.Build(w =>
        {
            w.WriteNumber("v", 1);
            w.WriteString("id", id);
            w.WriteString("pfx", Convert.ToBase64String(pfx));
        });
        AtomicFile.WriteAllBytes(path, protector.Protect(json));
    }

    /// <summary>
    /// SChannel cannot use an ephemeral in-memory key, so the certificate is re-imported from PKCS#12, which gives it
    /// a key in the user key store for the lifetime of the object.
    /// </summary>
    private static X509Certificate2 ImportPfx(byte[] pfx) =>
        new(pfx, (string?)null, X509KeyStorageFlags.UserKeySet);

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

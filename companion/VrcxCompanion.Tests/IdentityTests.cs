using System.Net;
using System.Runtime.Versioning;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Text.Json;
using VrcxCompanion.Core;
using VrcxCompanion.Core.Client;
using VrcxCompanion.Core.Diagnostics;
using VrcxCompanion.Core.Protocol;
using VrcxCompanion.Core.Security;
using VrcxCompanion.Core.Settings;

namespace VrcxCompanion.Tests;

/// <summary>
/// The identity keeps one persisted, named key in the user's CNG key store and reuses it on every start. Every test
/// removes its key again with <see cref="CompanionIdentity.Delete"/>.
/// </summary>
[SupportedOSPlatform("windows")]
public class IdentityTests
{
    private static readonly DpapiProtector Protector = new("identity");

    private static CompanionIdentity Load(string path) => CompanionIdentity.LoadOrCreate(path, Protector, "TESTPC", NullLog.Instance);

    private static string? PrivateKeyName(X509Certificate2 certificate)
    {
        using var key = certificate.GetECDsaPrivateKey() as ECDsaCng;
        return key?.Key.KeyName;
    }

    private static JsonElement Stored(string path) =>
        JsonDocument.Parse(Protector.Unprotect(File.ReadAllBytes(path))).RootElement.Clone();

    [Fact]
    public void CreatedOnceAndReloadedFromTheSamePersistedKey()
    {
        using var dir = new TempDir();
        var path = dir.File("identity.bin");
        var keyName = CompanionIdentity.KeyNameFor(path);
        try
        {
            string id, fp;
            using (var first = Load(path))
            {
                id = first.CompanionId;
                fp = first.Fingerprint;
                Assert.True(Guid.TryParse(id, out _));
                Assert.Equal("CN=VRCX Companion TESTPC", first.Certificate.Subject);
                Assert.True(first.Certificate.HasPrivateKey);
                Assert.Equal("1.2.840.10045.2.1", first.Certificate.PublicKey.Oid.Value); // id-ecPublicKey
                Assert.True(first.Certificate.NotAfter - first.Certificate.NotBefore >= TimeSpan.FromDays(365 * 20 - 1));
                var spki = first.Certificate.PublicKey.ExportSubjectPublicKeyInfo();
                Assert.Equal(Base64Url.Encode(SHA256.HashData(spki)), fp);
                Assert.Equal(43, fp.Length);
                Assert.Equal(keyName, first.KeyName);
                Assert.Equal(keyName, PrivateKeyName(first.Certificate));
            }

            // Disposing leaves the key in place: it is the identity's one key, not a temporary copy.
            Assert.True(CngKey.Exists(keyName));
            using (var second = Load(path))
            {
                Assert.Equal(id, second.CompanionId);
                Assert.Equal(fp, second.Fingerprint);
                Assert.Equal(keyName, PrivateKeyName(second.Certificate));
                using var ecdsa = second.Certificate.GetECDsaPrivateKey()!;
                var signature = ecdsa.SignData(Encoding.ASCII.GetBytes("vrcx"), HashAlgorithmName.SHA256);
                using var pub = second.Certificate.GetECDsaPublicKey()!;
                Assert.True(pub.VerifyData(Encoding.ASCII.GetBytes("vrcx"), signature, HashAlgorithmName.SHA256));
            }

            // identity.bin names the key; the private key itself is not in it, and it is not exportable.
            var stored = Stored(path);
            Assert.Equal(keyName, stored.GetProperty("key").GetString());
            Assert.False(stored.TryGetProperty("pfx", out _));
            using (var key = CngKey.Open(keyName))
                Assert.Equal(CngExportPolicies.None, key.ExportPolicy);

            // Not readable without DPAPI for this user and purpose.
            Assert.ThrowsAny<CryptographicException>(() => new DpapiProtector("other").Unprotect(File.ReadAllBytes(path)));
        }
        finally
        {
            CompanionIdentity.Delete(path, Protector);
        }
        Assert.False(CngKey.Exists(keyName));
        Assert.False(File.Exists(path));
    }

    [Fact]
    public async Task PersistedKeyServesTls()
    {
        using var data = new TempDir();
        using var logs = new TempDir();
        var paths = new AppPaths(data.Path);
        try
        {
            await using var host = CompanionHost.Create(new CompanionHostOptions
            {
                Paths = paths,
                LogDirectory = logs.Path,
                BindAddress = IPAddress.Loopback,
                TcpPort = 0,
                EnableDiscovery = false,
                InMemoryDevices = true,
                ProcessProbe = new FakeProcessProbe(),
                UseFileSystemWatcher = false,
            });
            host.Start();
            Assert.Equal(CompanionIdentity.KeyNameFor(paths.IdentityFile), host.Identity.KeyName);
            using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(20));
            await using var client = await CompanionClient.ConnectAsync(IPAddress.Loopback, host.TcpPort, host.Identity.Fingerprint, cts.Token);
            Assert.Equal(host.Identity.CompanionId, client.Hello.GetProperty("id").GetString());
        }
        finally
        {
            CompanionIdentity.Delete(paths.IdentityFile, Protector);
        }
        Assert.False(CngKey.Exists(CompanionIdentity.KeyNameFor(paths.IdentityFile)));
    }

    [Fact]
    public void FormatOneIsMigratedToAPersistedKey()
    {
        using var dir = new TempDir();
        var path = dir.File("identity.bin");
        var id = Guid.NewGuid().ToString();
        string fingerprint;
        using (var ecdsa = ECDsa.Create(ECCurve.NamedCurves.nistP256))
        {
            var request = new CertificateRequest("CN=VRCX Companion OLDPC", ecdsa, HashAlgorithmName.SHA256);
            using var cert = request.CreateSelfSigned(DateTimeOffset.UtcNow.AddDays(-1), DateTimeOffset.UtcNow.AddYears(20));
            fingerprint = CompanionIdentity.ComputeFingerprint(cert);
            var v1 = Encoding.UTF8.GetBytes(JsonSerializer.Serialize(new
            {
                v = 1,
                id,
                pfx = Convert.ToBase64String(cert.Export(X509ContentType.Pkcs12)),
            }));
            File.WriteAllBytes(path, Protector.Protect(v1));
        }

        string? migratedKey = null;
        try
        {
            using (var identity = Load(path))
            {
                Assert.Equal(id, identity.CompanionId);
                Assert.Equal(fingerprint, identity.Fingerprint);
                Assert.True(identity.Certificate.HasPrivateKey);
                migratedKey = identity.KeyName;
                Assert.NotNull(migratedKey);
                Assert.Equal(migratedKey, PrivateKeyName(identity.Certificate));
            }
            var stored = Stored(path);
            Assert.False(stored.TryGetProperty("pfx", out _));
            Assert.Equal(migratedKey, stored.GetProperty("key").GetString());

            using var again = Load(path);
            Assert.Equal(id, again.CompanionId);
            Assert.Equal(fingerprint, again.Fingerprint);
            Assert.Equal(migratedKey, again.KeyName);
        }
        finally
        {
            CompanionIdentity.Delete(path, Protector);
        }
        Assert.False(CngKey.Exists(migratedKey!));
    }

    [Fact]
    public void MissingKeyOrUnreadableFileGivesANewIdentityUnderTheSameKeyName()
    {
        using var dir = new TempDir();
        var path = dir.File("identity.bin");
        var keyName = CompanionIdentity.KeyNameFor(path);
        try
        {
            string fp;
            using (var first = Load(path))
                fp = first.Fingerprint;

            // The key was removed from the key store: a new identity (phones pair again).
            using (var key = CngKey.Open(keyName))
                key.Delete();
            string fp2;
            using (var second = Load(path))
            {
                fp2 = second.Fingerprint;
                Assert.NotEqual(fp, fp2);
                Assert.Equal(keyName, PrivateKeyName(second.Certificate));
            }
            Assert.True(File.Exists(path + ".bad"));

            // identity.bin cannot be read: the new identity overwrites the old key instead of adding one.
            File.WriteAllBytes(path, new byte[] { 1, 2, 3 });
            using (var third = Load(path))
            {
                Assert.NotEqual(fp2, third.Fingerprint);
                Assert.Equal(keyName, PrivateKeyName(third.Certificate));
            }
        }
        finally
        {
            CompanionIdentity.Delete(path, Protector);
        }
        Assert.False(CngKey.Exists(keyName));
    }

    [Fact]
    public void KeyNameDependsOnlyOnTheFullPath()
    {
        Assert.Equal(CompanionIdentity.KeyNameFor(@"C:\A\identity.bin"), CompanionIdentity.KeyNameFor(@"c:\a\IDENTITY.BIN"));
        Assert.NotEqual(CompanionIdentity.KeyNameFor(@"C:\A\identity.bin"), CompanionIdentity.KeyNameFor(@"C:\B\identity.bin"));
        Assert.StartsWith("VRCX-Companion-identity-", CompanionIdentity.KeyNameFor(@"C:\A\identity.bin"));
    }
}

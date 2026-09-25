using System.Net;
using System.Text.Json;
using VrcxCompanion.Core.Diagnostics;
using VrcxCompanion.Core.Protocol;
using VrcxCompanion.Core.Security;

namespace VrcxCompanion.Tests;

public class PairingCryptoTests
{
    // PROTOCOL.md §7
    [Fact]
    public void ClientProofMatchesTheTestVector() =>
        Assert.Equal("lminVMWbovCkRt0N33ED9SLaV7-Nqw-Tcinojo8YL3M", PairingCrypto.ClientProof("ABCDE12345", "fp-test", "hn", "cn"));

    [Fact]
    public void ServerProofMatchesTheTestVector() =>
        Assert.Equal("Y1SCIkysnwsJGNruwoktsQix-sFYpCeiexDg-x56VvI", PairingCrypto.ServerProof("ABCDE12345", "fp-test", "cn", "hn"));

    [Fact]
    public void ProofComparison()
    {
        Assert.True(PairingCrypto.ProofEquals("abc", "abc"));
        Assert.False(PairingCrypto.ProofEquals("abc", "abd"));
        Assert.False(PairingCrypto.ProofEquals("abc", "ab"));
        Assert.False(PairingCrypto.ProofEquals("abc", null));
    }
}

public class CrockfordCodeTests
{
    [Theory]
    [InlineData("ABCDE-12345", "ABCDE12345")]
    [InlineData("abcde12345", "ABCDE12345")]
    [InlineData(" abcde 12345 ", "ABCDE12345")]
    [InlineData("oOiIl-0z9Y1", "001110Z9Y1")]
    [InlineData("0O1IL-VWXYZ", "00111VWXYZ")]
    [InlineData("oOiIlL-0z9Y1", null)] // 11 characters
    [InlineData("ABCDE-1234U", null)] // U is not in the alphabet
    [InlineData("ABCDE-1234", null)]
    [InlineData("", null)]
    [InlineData(null, null)]
    public void Normalize(string? input, string? expected) => Assert.Equal(expected, CrockfordCode.Normalize(input));

    [Fact]
    public void GeneratedCodesAreTenCrockfordCharacters()
    {
        var seen = new HashSet<string>();
        for (var i = 0; i < 200; i++)
        {
            var code = CrockfordCode.Generate();
            Assert.Equal(10, code.Length);
            Assert.All(code, c => Assert.Contains(c, CrockfordCode.Alphabet));
            Assert.Equal(code, CrockfordCode.Normalize(code));
            seen.Add(code);
        }
        Assert.True(seen.Count > 190);
    }

    [Fact]
    public void FormatsAsTwoGroupsOfFive()
    {
        Assert.Equal("ABCDE-12345", CrockfordCode.Format("ABCDE12345"));
        Assert.Throws<ArgumentException>(() => CrockfordCode.Format("ABC"));
    }
}

public class PairingManagerTests
{
    private const string Fp = "fingerprint";
    private const string HelloNonce = "hello-nonce";

    private static JsonElement PairMessage(string code, string deviceId = "dev-1", string clientNonce = "client-nonce", string name = "Pixel 8")
    {
        var json = $"{{\"t\":\"pair\",\"deviceId\":\"{deviceId}\",\"deviceName\":\"{name}\",\"nonce\":\"{clientNonce}\"," +
                   $"\"proof\":\"{PairingCrypto.ClientProof(code, Fp, HelloNonce, clientNonce)}\"}}";
        return JsonDocument.Parse(json).RootElement.Clone();
    }

    private static (PairingManager manager, DeviceStore store, ManualTime time) Create()
    {
        var time = new ManualTime(new DateTimeOffset(2026, 1, 1, 12, 0, 0, TimeSpan.Zero));
        var store = new DeviceStore(null, new PlainProtector(), NullLog.Instance, time);
        return (new PairingManager(store, NullLog.Instance, time), store, time);
    }

    [Fact]
    public void NoWindowMeansClosed()
    {
        var (m, _, _) = Create();
        Assert.False(m.IsOpen);
        Assert.Equal("closed", m.TryPair(Fp, HelloNonce, PairMessage("ABCDE12345")).FailReason);
    }

    [Fact]
    public void CorrectCodePairsOneDeviceAndClosesTheWindow()
    {
        var (m, store, _) = Create();
        var w = m.Open();
        Assert.True(m.IsOpen);
        Assert.Matches("^[0-9A-Z]{5}-[0-9A-Z]{5}$", w.DisplayCode);

        var r = m.TryPair(Fp, HelloNonce, PairMessage(w.Code));
        Assert.True(r.Success);
        Assert.Equal(PairingCrypto.ServerProof(w.Code, Fp, "client-nonce", HelloNonce), r.ServerProof);
        Assert.True(Base64Url.TryDecode(r.Token, out var tokenBytes));
        Assert.Equal(32, tokenBytes.Length);

        var device = Assert.Single(store.List());
        Assert.Equal("dev-1", device.DeviceId);
        Assert.Equal("Pixel 8", device.DeviceName);
        Assert.Equal(PairingCrypto.TokenHash(r.Token!), device.TokenSha256);
        Assert.NotEqual(r.Token, device.TokenSha256);
        Assert.True(store.Verify("dev-1", r.Token));
        Assert.False(store.Verify("dev-1", "wrong"));

        Assert.False(m.IsOpen);
        Assert.Equal(PairingWindowState.Paired, m.Current!.State);
        Assert.Equal("closed", m.TryPair(Fp, HelloNonce, PairMessage(w.Code, "dev-2")).FailReason);
    }

    [Fact]
    public void ProofBoundToTheFingerprint()
    {
        var (m, _, _) = Create();
        var w = m.Open();
        // A man in the middle presents another certificate: the phone's proof uses the fingerprint it saw.
        var r = m.TryPair("other-fingerprint", HelloNonce, PairMessage(w.Code));
        Assert.False(r.Success);
        Assert.Equal("code", r.FailReason);
    }

    [Fact]
    public void WindowExpiresAfterFiveMinutes()
    {
        var (m, _, time) = Create();
        var w = m.Open();
        time.Advance(TimeSpan.FromMinutes(5) - TimeSpan.FromSeconds(1));
        Assert.True(m.IsOpen);
        time.Advance(TimeSpan.FromSeconds(1));
        Assert.Equal("expired", m.TryPair(Fp, HelloNonce, PairMessage(w.Code)).FailReason);
        Assert.False(m.IsOpen);
        Assert.Equal(PairingWindowState.Expired, m.Current!.State);
    }

    [Fact]
    public void FiveFailedAttemptsCloseTheWindow()
    {
        var (m, store, _) = Create();
        var w = m.Open();
        var wrong = w.Code == "0000000000" ? "1111111111" : "0000000000";
        for (var i = 1; i <= 5; i++)
        {
            Assert.Equal("code", m.TryPair(Fp, HelloNonce, PairMessage(wrong)).FailReason);
            Assert.Equal(i < 5, m.IsOpen);
        }
        Assert.Equal(PairingWindowState.TooManyAttempts, m.Current!.State);
        Assert.Equal("closed", m.TryPair(Fp, HelloNonce, PairMessage(w.Code)).FailReason);
        Assert.Empty(store.List());
    }

    [Fact]
    public void CancelClosesAndReopenGivesANewCode()
    {
        var (m, _, _) = Create();
        var first = m.Open();
        m.Cancel();
        Assert.False(m.IsOpen);
        Assert.Equal("closed", m.TryPair(Fp, HelloNonce, PairMessage(first.Code)).FailReason);
        var second = m.Open();
        Assert.True(m.IsOpen);
        Assert.True(m.TryPair(Fp, HelloNonce, PairMessage(second.Code)).Success);
    }

    [Fact]
    public void MissingFieldsAreRefusedWithoutCountingAsAttempts()
    {
        var (m, _, _) = Create();
        m.Open();
        var bad = JsonDocument.Parse("{\"t\":\"pair\"}").RootElement.Clone();
        Assert.Equal("code", m.TryPair(Fp, HelloNonce, bad).FailReason);
        Assert.Equal(0, m.Current!.FailedAttempts);
        Assert.True(m.IsOpen);
    }

    [Fact]
    public void DeviceNamesAreSanitized()
    {
        Assert.Equal("Android device", PairingManager.SanitizeName(null));
        Assert.Equal("Android device", PairingManager.SanitizeName("  \u0001 "));
        Assert.Equal("Pixel", PairingManager.SanitizeName("Pi\u0000xel"));
        Assert.Equal(64, PairingManager.SanitizeName(new string('x', 300)).Length);
    }

    [Fact]
    public void QrPayloadMatchesTheProtocol()
    {
        var uri = PairingPayload.Build("3f1c-id", "My PC", new[]
        {
            IPAddress.Parse("192.168.1.20"), IPAddress.Parse("fd00::1234"), IPAddress.Parse("::ffff:10.0.0.5"),
        }, 49460, "abc_DEF-123", "ABCDE12345");
        Assert.Equal("vrcxc://pair?v=1&id=3f1c-id&n=My%20PC&h=192.168.1.20,fd00::1234,10.0.0.5&p=49460&fp=abc_DEF-123&c=ABCDE12345", uri);
        Assert.Equal("fe80::1", PairingPayload.FormatHost(IPAddress.Parse("fe80::1%12")));
    }
}

public class DeviceStoreTests
{
    [Fact]
    public void PersistsWithDpapiAndNeverStoresTheToken()
    {
        using var dir = new TempDir();
        var path = dir.File("devices.bin");
        var store = new DeviceStore(path, new DpapiProtector("devices"), NullLog.Instance);
        store.AddOrReplace("dev-1", "Pixel", "secret-token-value");
        store.AddOrReplace("dev-2", "Galaxy", "another-token");

        var bytes = File.ReadAllBytes(path);
        var text = System.Text.Encoding.UTF8.GetString(bytes);
        Assert.DoesNotContain("secret-token-value", text);
        Assert.DoesNotContain("Pixel", text); // DPAPI-protected

        var reloaded = new DeviceStore(path, new DpapiProtector("devices"), NullLog.Instance);
        Assert.Equal(2, reloaded.List().Count);
        Assert.True(reloaded.Verify("dev-1", "secret-token-value"));
        Assert.False(reloaded.Verify("dev-1", "another-token"));
        Assert.True(reloaded.Remove("dev-1"));

        var again = new DeviceStore(path, new DpapiProtector("devices"), NullLog.Instance);
        Assert.Null(again.Find("dev-1"));
        Assert.NotNull(again.Find("dev-2"));
    }

    [Fact]
    public void PlainJsonContainsOnlyTheHash()
    {
        using var dir = new TempDir();
        var path = dir.File("devices.bin");
        var store = new DeviceStore(path, new PlainProtector(), NullLog.Instance);
        store.AddOrReplace("dev-1", "Pixel", "secret-token-value");
        var text = File.ReadAllText(path);
        Assert.DoesNotContain("secret-token-value", text);
        Assert.Contains(PairingCrypto.TokenHash("secret-token-value"), text);
        using var doc = JsonDocument.Parse(text);
        var d = doc.RootElement.GetProperty("devices")[0];
        foreach (var field in new[] { "deviceId", "deviceName", "tokenSha256", "pairedAtUtc", "lastSeenUtc" })
            Assert.True(d.TryGetProperty(field, out _), field);
    }

    [Fact]
    public void CorruptFileStartsEmpty()
    {
        using var dir = new TempDir();
        var path = dir.File("devices.bin");
        File.WriteAllBytes(path, new byte[] { 1, 2, 3 });
        var store = new DeviceStore(path, new DpapiProtector("devices"), NullLog.Instance);
        Assert.Empty(store.List());
        Assert.True(File.Exists(path + ".bad"));
    }
}

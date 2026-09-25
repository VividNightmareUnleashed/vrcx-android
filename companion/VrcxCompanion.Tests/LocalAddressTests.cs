using System.Net;
using VrcxCompanion.Core.Net;

namespace VrcxCompanion.Tests;

public class LocalAddressTests
{
    [Theory]
    // IPv4 10/8
    [InlineData("10.0.0.0", true)]
    [InlineData("10.255.255.255", true)]
    [InlineData("11.0.0.1", false)]
    [InlineData("9.255.255.255", false)]
    // 172.16/12
    [InlineData("172.15.255.255", false)]
    [InlineData("172.16.0.0", true)]
    [InlineData("172.31.255.255", true)]
    [InlineData("172.32.0.0", false)]
    // 192.168/16
    [InlineData("192.168.0.1", true)]
    [InlineData("192.168.255.255", true)]
    [InlineData("192.169.0.1", false)]
    [InlineData("192.167.255.255", false)]
    // 169.254/16
    [InlineData("169.254.1.1", true)]
    [InlineData("169.253.1.1", false)]
    [InlineData("169.255.1.1", false)]
    // 127/8
    [InlineData("127.0.0.1", true)]
    [InlineData("127.255.255.254", true)]
    // 100.64/10
    [InlineData("100.63.255.255", false)]
    [InlineData("100.64.0.0", true)]
    [InlineData("100.127.255.255", true)]
    [InlineData("100.128.0.0", false)]
    // public and special
    [InlineData("8.8.8.8", false)]
    [InlineData("1.1.1.1", false)]
    [InlineData("0.0.0.0", false)]
    [InlineData("255.255.255.255", false)]
    [InlineData("224.0.0.251", false)]
    // IPv6
    [InlineData("::1", true)]
    [InlineData("::", false)]
    [InlineData("::2", false)]
    [InlineData("fe80::1", true)]
    [InlineData("fe80::1%12", true)]
    [InlineData("febf:ffff::1", true)]
    [InlineData("fec0::1", false)]
    [InlineData("fc00::1", true)]
    [InlineData("fdff:ffff::1", true)]
    [InlineData("fe00::1", false)]
    [InlineData("fb00::1", false)]
    [InlineData("2001:db8::1", false)]
    [InlineData("2a00:1450:4001::200e", false)]
    [InlineData("ff02::1", false)]
    // IPv4-mapped IPv6
    [InlineData("::ffff:192.168.1.10", true)]
    [InlineData("::ffff:10.1.2.3", true)]
    [InlineData("::ffff:100.64.0.1", true)]
    [InlineData("::ffff:127.0.0.1", true)]
    [InlineData("::ffff:8.8.8.8", false)]
    [InlineData("::ffff:172.32.0.1", false)]
    public void Classifies(string address, bool expected) =>
        Assert.Equal(expected, LocalAddress.IsLocal(IPAddress.Parse(address)));

    [Fact]
    public void NullIsNotLocal() => Assert.False(LocalAddress.IsLocal(null));

    [Fact]
    public void AdvertisedAddressesAreLocalAndUsable()
    {
        foreach (var a in LocalAddress.GetAdvertisedAddresses())
        {
            Assert.True(LocalAddress.IsLocal(a));
            Assert.False(IPAddress.IsLoopback(a));
            Assert.False(a.IsIPv6LinkLocal);
        }
    }
}

package io.github.laiwf94.payflow.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Which addresses a webhook may go to. */
class AddressPolicyTest {

  private static final AddressPolicy STRICT = AddressPolicy.strict();

  private static InetAddress ip(String literal) {
    return InetAddress.ofLiteral(literal);
  }

  /** An IPv6 address from its 16 bytes, kept as IPv6 even when it is IPv4-mapped. */
  private static InetAddress v6(int... bytes) throws UnknownHostException {
    byte[] b = new byte[16];
    for (int i = 0; i < 16; i++) {
      b[i] = (byte) bytes[i];
    }
    return Inet6Address.getByAddress(null, b, -1);
  }

  @Test
  void refusesEveryNonPublicIpv4Range() {
    Map<String, String> refused =
        Map.ofEntries(
            Map.entry("0.0.0.0", "an unspecified address"),
            Map.entry("0.255.1.2", "an unspecified address"),
            Map.entry("10.0.0.1", "a private address"),
            Map.entry("10.255.255.255", "a private address"),
            Map.entry("100.64.0.1", "a shared (CGNAT) address"),
            Map.entry("100.127.255.254", "a shared (CGNAT) address"),
            Map.entry("127.0.0.1", "a loopback address"),
            Map.entry("127.255.0.9", "a loopback address"),
            Map.entry("169.254.169.254", "the cloud metadata address"),
            Map.entry("169.254.0.1", "a link-local address"),
            Map.entry("172.16.0.1", "a private address"),
            Map.entry("172.31.255.255", "a private address"),
            Map.entry("192.0.0.170", "a special-purpose address"),
            Map.entry("192.0.2.1", "a documentation address"),
            Map.entry("192.88.99.1", "a reserved address"),
            Map.entry("192.168.1.1", "a private address"),
            Map.entry("198.18.0.1", "a benchmarking address"),
            Map.entry("198.19.255.255", "a benchmarking address"),
            Map.entry("198.51.100.7", "a documentation address"),
            Map.entry("203.0.113.9", "a documentation address"),
            Map.entry("224.0.0.1", "a multicast address"),
            Map.entry("239.255.255.250", "a multicast address"),
            Map.entry("240.0.0.1", "a reserved address"),
            Map.entry("255.255.255.255", "the broadcast address"));
    refused.forEach((a, why) -> assertThat(STRICT.refusal(ip(a))).as(a).contains(why));

    for (String ok :
        List.of(
            "1.1.1.1",
            "8.8.8.8",
            "93.184.215.14",
            "100.63.255.255",
            "100.128.0.0",
            "172.15.255.255",
            "172.32.0.0",
            "169.253.255.255",
            "192.167.255.255",
            "198.17.255.255",
            "198.20.0.0",
            "223.255.255.255")) {
      assertThat(STRICT.refusal(ip(ok))).as(ok).isEmpty();
    }
  }

  @Test
  void refusesEveryNonPublicIpv6Range() {
    Map<String, String> refused =
        Map.ofEntries(
            Map.entry("::", "an unspecified address"),
            Map.entry("::1", "a loopback address"),
            Map.entry("64:ff9b:1::5", "a local NAT64 address"),
            Map.entry("100::1", "a discard address"),
            Map.entry("2001::1", "a special-purpose address"),
            Map.entry("2001:1ff::1", "a special-purpose address"),
            Map.entry("2001:db8::1", "a documentation address"),
            Map.entry("3fff::1", "a documentation address"),
            Map.entry("fc00::1", "a private (unique local) address"),
            Map.entry("fd12:3456::1", "a private (unique local) address"),
            Map.entry("fe80::1", "a link-local address"),
            Map.entry("febf::1", "a link-local address"),
            Map.entry("fec0::1", "a site-local address"),
            Map.entry("ff02::1", "a multicast address"),
            Map.entry("4000::1", "not a global unicast address"),
            Map.entry("1::1", "not a global unicast address"));
    refused.forEach((a, why) -> assertThat(STRICT.refusal(ip(a))).as(a).contains(why));

    for (String ok : List.of("2606:4700::1111", "2a00:1450:4001::200e", "2001:4860::8888")) {
      assertThat(STRICT.refusal(ip(ok))).as(ok).isEmpty();
    }
  }

  @Test
  void judgesAnIpv6AddressThatCarriesAnIpv4AddressByTheIpv4Address() throws Exception {
    // IPv4-mapped, kept as IPv6 the way a raw resolver answer or a socket could hand it over.
    assertThat(STRICT.refusal(v6(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xff, 0xff, 127, 0, 0, 1)))
        .contains("an IPv6 address wrapping 127.0.0.1, a loopback address");
    assertThat(STRICT.refusal(v6(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xff, 0xff, 169, 254, 169, 254)))
        .contains("an IPv6 address wrapping 169.254.169.254, the cloud metadata address");
    // Java turns a mapped literal into the IPv4 address, which is refused just the same.
    assertThat(STRICT.refusal(ip("::ffff:10.0.0.1"))).contains("a private address");
    // IPv4-translated, NAT64, IPv4-compatible and 6to4.
    assertThat(STRICT.refusal(ip("::ffff:0:192.168.0.1")))
        .contains("an IPv6 address wrapping 192.168.0.1, a private address");
    assertThat(STRICT.refusal(ip("64:ff9b::7f00:1")))
        .contains("an IPv6 address wrapping 127.0.0.1, a loopback address");
    assertThat(STRICT.refusal(ip("::a9fe:a9fe")))
        .contains("an IPv6 address wrapping 169.254.169.254, the cloud metadata address");
    assertThat(STRICT.refusal(ip("2002:0a00:0001::1")))
        .contains("an IPv6 address wrapping 10.0.0.1, a private address");
    assertThat(STRICT.refusal(ip("2002:6440:0001::")))
        .contains("an IPv6 address wrapping 100.64.0.1, a shared (CGNAT) address");

    // The same forms around a public address are as public as it is.
    assertThat(STRICT.refusal(v6(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xff, 0xff, 8, 8, 8, 8))).isEmpty();
    assertThat(STRICT.refusal(ip("64:ff9b::808:808"))).isEmpty();
    assertThat(STRICT.refusal(ip("2002:0808:0808::1"))).isEmpty();
  }

  @Test
  void allowedNetworksAreLetThroughAndNothingElse() throws Exception {
    AddressPolicy p = AddressPolicy.allowing(List.of("172.18.0.0/16", " ::1 ", "", "127.0.0.1"));
    assertThat(p.allowed()).hasToString("[172.18.0.0/16, 0:0:0:0:0:0:0:1/128, 127.0.0.1/32]");
    assertThat(p.refusal(ip("172.18.5.4"))).isEmpty();
    assertThat(p.refusal(ip("::1"))).isEmpty();
    assertThat(p.refusal(ip("127.0.0.1"))).isEmpty();
    assertThat(p.refusal(v6(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xff, 0xff, 127, 0, 0, 1)))
        .as("the mapped form of an allowed address")
        .isEmpty();
    assertThat(p.refusal(ip("127.0.0.2"))).contains("a loopback address");
    assertThat(p.refusal(ip("172.19.0.1"))).contains("a private address");
    assertThat(p.refusal(ip("169.254.169.254"))).contains("the cloud metadata address");

    assertThat(AddressPolicy.allowing(List.of("10.0.0.0/7")).refusal(ip("11.1.1.1")))
        .as("a prefix that isn't on a byte boundary")
        .isEmpty();
    assertThat(AddressPolicy.allowing(List.of("10.1.2.3/8")).refusal(ip("10.200.0.1")))
        .as("host bits in the network are ignored")
        .isEmpty();

    for (String bad : List.of("10.0.0.0/33", "::/129", "10.0.0.0/-1", "x/8", "1/a")) {
      assertThatThrownBy(() -> AddressPolicy.allowing(List.of(bad)))
          .as(bad)
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThat(AddressPolicy.Cidr.parse("10.0.0.0/8"))
        .isEqualTo(AddressPolicy.Cidr.parse("10.0.0.0/8"));
    assertThat(AddressPolicy.Cidr.parse("10.0.0.0/8").hashCode())
        .isEqualTo(AddressPolicy.Cidr.parse("10.0.0.0/8").hashCode());
    assertThat(AddressPolicy.Cidr.parse("10.0.0.0/8"))
        .isNotEqualTo(AddressPolicy.Cidr.parse("10.0.0.0/9"));
  }
}

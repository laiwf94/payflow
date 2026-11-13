package io.github.laiwf94.payflow.webhook;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Which addresses a webhook may be sent to: public unicast only. Loopback, private, link-local (and
 * so the cloud metadata address 169.254.169.254), shared CGNAT space, multicast, broadcast,
 * documentation, benchmarking and reserved ranges are refused, in IPv4 and IPv6. An IPv6 address
 * that carries an IPv4 address (IPv4-mapped, IPv4-compatible, IPv4-translated, NAT64 and 6to4) is
 * judged by the IPv4 address inside it, so {@code ::ffff:127.0.0.1} is loopback. Other IPv6
 * addresses must be global unicast ({@code 2000::/3}) outside its special-purpose blocks.
 *
 * <p>{@code allowed} names networks that are let through anyway, for a development stack or a test
 * whose merchant server is on a private network. It is empty unless configured.
 */
public final class AddressPolicy {

  /** A network in CIDR form. */
  public static final class Cidr {

    private final byte[] network;
    private final int prefix;

    public Cidr(byte[] network, int prefix) {
      if (network.length != 4 && network.length != 16) {
        throw new IllegalArgumentException("not an IP address");
      }
      if (prefix < 0 || prefix > network.length * 8) {
        throw new IllegalArgumentException("prefix must be 0-" + network.length * 8);
      }
      this.network = network.clone();
      this.prefix = prefix;
    }

    public int prefix() {
      return prefix;
    }

    /**
     * Parses {@code a.b.c.d/n}, {@code x::y/n}, or an address alone (a single host).
     *
     * @throws IllegalArgumentException when it is neither
     */
    public static Cidr parse(String s) {
      String t = s.strip();
      int slash = t.indexOf('/');
      String addr = slash < 0 ? t : t.substring(0, slash);
      byte[] b;
      try {
        b = InetAddress.ofLiteral(addr).getAddress();
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException("not a network: " + s, e);
      }
      int prefix;
      try {
        prefix = slash < 0 ? b.length * 8 : Integer.parseInt(t.substring(slash + 1));
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException("not a network: " + s, e);
      }
      return new Cidr(b, prefix);
    }

    boolean contains(byte[] a) {
      if (a.length != network.length) {
        return false;
      }
      return prefixMatches(a, network, prefix);
    }

    @Override
    public boolean equals(Object o) {
      return o instanceof Cidr c && prefix == c.prefix && Arrays.equals(network, c.network);
    }

    @Override
    public int hashCode() {
      return 31 * Arrays.hashCode(network) + prefix;
    }

    @Override
    public String toString() {
      try {
        return InetAddress.getByAddress(network).getHostAddress() + "/" + prefix;
      } catch (java.net.UnknownHostException e) {
        throw new IllegalStateException(e);
      }
    }
  }

  private record Block(Cidr net, String why) {}

  private static final List<Block> V4 =
      List.of(
          block("0.0.0.0/8", "an unspecified address"),
          block("10.0.0.0/8", "a private address"),
          block("100.64.0.0/10", "a shared (CGNAT) address"),
          block("127.0.0.0/8", "a loopback address"),
          block("169.254.169.254/32", "the cloud metadata address"),
          block("169.254.0.0/16", "a link-local address"),
          block("172.16.0.0/12", "a private address"),
          block("192.0.0.0/24", "a special-purpose address"),
          block("192.0.2.0/24", "a documentation address"),
          block("192.88.99.0/24", "a reserved address"),
          block("192.168.0.0/16", "a private address"),
          block("198.18.0.0/15", "a benchmarking address"),
          block("198.51.100.0/24", "a documentation address"),
          block("203.0.113.0/24", "a documentation address"),
          block("224.0.0.0/4", "a multicast address"),
          block("255.255.255.255/32", "the broadcast address"),
          block("240.0.0.0/4", "a reserved address"));

  private static final List<Block> V6 =
      List.of(
          block("::/128", "an unspecified address"),
          block("::1/128", "a loopback address"),
          block("64:ff9b:1::/48", "a local NAT64 address"),
          block("100::/64", "a discard address"),
          block("2001::/23", "a special-purpose address"),
          block("2001:db8::/32", "a documentation address"),
          block("3fff::/20", "a documentation address"),
          block("fc00::/7", "a private (unique local) address"),
          block("fe80::/10", "a link-local address"),
          block("fec0::/10", "a site-local address"),
          block("ff00::/8", "a multicast address"));

  private static final Cidr GLOBAL_UNICAST = Cidr.parse("2000::/3");

  /** IPv6 forms that carry an IPv4 address in their last 32 bits. */
  private static final List<Cidr> EMBEDS_V4_AT_END =
      List.of(
          // IPv4-mapped. Built from bytes: Java reads ::ffff:0:0 as the IPv4 address 0.0.0.0.
          new Cidr(
              new byte[] {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, (byte) 0xff, (byte) 0xff, 0, 0, 0, 0}, 96),
          Cidr.parse("::ffff:0:0:0/96"), // IPv4-translated
          Cidr.parse("64:ff9b::/96"), // NAT64
          Cidr.parse("::/96")); // IPv4-compatible, deprecated

  private static final Cidr SIX_TO_FOUR = Cidr.parse("2002::/16");

  private final List<Cidr> allowed;

  public AddressPolicy(List<Cidr> allowed) {
    this.allowed = List.copyOf(allowed);
  }

  /** The policy with nothing let through. */
  public static AddressPolicy strict() {
    return new AddressPolicy(List.of());
  }

  /**
   * Parses a list of networks for {@code allowed}.
   *
   * @throws IllegalArgumentException when one isn't a network
   */
  public static AddressPolicy allowing(List<String> networks) {
    List<Cidr> nets = new ArrayList<>();
    for (String n : networks) {
      if (!n.isBlank()) {
        nets.add(Cidr.parse(n));
      }
    }
    return new AddressPolicy(nets);
  }

  public List<Cidr> allowed() {
    return allowed;
  }

  /** Why a webhook may not be sent to {@code address}, or empty when it may. */
  public Optional<String> refusal(InetAddress address) {
    byte[] a = address.getAddress();
    if (isAllowed(a)) {
      return Optional.empty();
    }
    if (address instanceof Inet4Address) {
      return v4(a);
    }
    if (!(address instanceof Inet6Address)) {
      return Optional.of("not an IP address");
    }
    for (Block b : V6) {
      // ::/128 and ::1/128 first, so they aren't read as IPv4-compatible 0.0.0.0 and 0.0.0.1.
      if (b.net().prefix() == 128 && b.net().contains(a)) {
        return Optional.of(b.why());
      }
    }
    byte[] inner = embeddedV4(a);
    if (inner != null) {
      if (isAllowed(inner)) {
        return Optional.empty();
      }
      return v4(inner).map(why -> "an IPv6 address wrapping " + text(inner) + ", " + why);
    }
    for (Block b : V6) {
      if (b.net().contains(a)) {
        return Optional.of(b.why());
      }
    }
    if (!GLOBAL_UNICAST.contains(a)) {
      return Optional.of("not a global unicast address");
    }
    return Optional.empty();
  }

  private boolean isAllowed(byte[] a) {
    for (Cidr c : allowed) {
      if (c.contains(a)) {
        return true;
      }
    }
    return false;
  }

  private static Optional<String> v4(byte[] a) {
    for (Block b : V4) {
      if (b.net().contains(a)) {
        return Optional.of(b.why());
      }
    }
    return Optional.empty();
  }

  /** The IPv4 address an IPv6 address carries, or null when it carries none. */
  private static byte[] embeddedV4(byte[] a) {
    for (Cidr c : EMBEDS_V4_AT_END) {
      if (c.contains(a)) {
        return Arrays.copyOfRange(a, 12, 16);
      }
    }
    if (SIX_TO_FOUR.contains(a)) {
      return Arrays.copyOfRange(a, 2, 6);
    }
    return null;
  }

  private static String text(byte[] v4) {
    return (v4[0] & 0xff) + "." + (v4[1] & 0xff) + "." + (v4[2] & 0xff) + "." + (v4[3] & 0xff);
  }

  private static Block block(String cidr, String why) {
    return new Block(Cidr.parse(cidr), why);
  }

  private static boolean prefixMatches(byte[] a, byte[] net, int prefix) {
    int full = prefix / 8;
    for (int i = 0; i < full; i++) {
      if (a[i] != net[i]) {
        return false;
      }
    }
    int rest = prefix % 8;
    if (rest == 0) {
      return true;
    }
    int mask = (0xff << (8 - rest)) & 0xff;
    return (a[full] & mask) == (net[full] & mask);
  }
}

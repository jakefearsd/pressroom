package org.apache.roller.weblogger.business.fetch;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;

/**
 * Whether an address is on the public internet. Everything else (loopback,
 * private, CGNAT and Tailscale, link-local and cloud metadata, Docker
 * networks, documentation, benchmarking, multicast, reserved) is refused. An
 * IPv4-mapped or NAT64 IPv6 address is judged by the IPv4 address inside
 * it. Beyond the spec's list, IPv4-compatible (::/96), discard-only
 * (100::/64), Teredo (2001::/32) and 6to4 (2002::/16) are refused too: each
 * can carry an internal IPv4 address, and no image host needs them.
 */
public final class PublicAddresses {

    private static final List<Cidr> REFUSED_V4 = Cidr.all("0.0.0.0/8", "10.0.0.0/8", "100.64.0.0/10",
            "127.0.0.0/8", "169.254.0.0/16", "172.16.0.0/12", "192.0.0.0/24", "192.0.2.0/24",
            "192.168.0.0/16", "198.18.0.0/15", "198.51.100.0/24", "203.0.113.0/24", "224.0.0.0/4",
            "240.0.0.0/4");
    private static final List<Cidr> REFUSED_V6 = Cidr.all("::/96", "::1/128", "fc00::/7", "fe80::/10",
            "ff00::/8", "2001:db8::/32", "100::/64", "2001::/32", "2002::/16");
    /**
     * {@code ::ffff:0:0/96}, built from its bytes: Java parses that literal to
     * the four-byte 0.0.0.0, which would make this a block of IPv4 addresses.
     */
    private static final Cidr MAPPED = new Cidr(
            new byte[] {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, (byte) 0xFF, (byte) 0xFF, 0, 0, 0, 0}, 96);
    private static final Cidr NAT64 = Cidr.of("64:ff9b::/96");

    private PublicAddresses() {
    }

    public static boolean isPublic(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            return REFUSED_V4.stream().noneMatch(c -> c.contains(bytes));
        }
        if (MAPPED.contains(bytes) || NAT64.contains(bytes)) {
            byte[] inner = Arrays.copyOfRange(bytes, 12, 16);
            return REFUSED_V4.stream().noneMatch(c -> c.contains(inner));
        }
        return REFUSED_V6.stream().noneMatch(c -> c.contains(bytes));
    }

    /** An address block: the first {@code bits} bits of {@code prefix}. */
    private record Cidr(byte[] prefix, int bits) {

        static List<Cidr> all(String... blocks) {
            return Arrays.stream(blocks).map(Cidr::of).toList();
        }

        static Cidr of(String block) {
            int slash = block.indexOf('/');
            try {
                // Literals only: getByName does no lookup for an address literal.
                byte[] prefix = InetAddress.getByName(block.substring(0, slash)).getAddress();
                return new Cidr(prefix, Integer.parseInt(block.substring(slash + 1)));
            } catch (UnknownHostException e) {
                throw new IllegalArgumentException(block, e);
            }
        }

        boolean contains(byte[] address) {
            if (address.length != prefix.length) {
                return false;
            }
            int whole = bits / 8;
            for (int i = 0; i < whole; i++) {
                if (address[i] != prefix[i]) {
                    return false;
                }
            }
            int rest = bits % 8;
            if (rest == 0) {
                return true;
            }
            int mask = 0xFF << (8 - rest) & 0xFF;
            return (address[whole] & mask) == (prefix[whole] & mask);
        }
    }
}

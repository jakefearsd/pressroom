package org.apache.roller.weblogger.business.fetch;

import java.net.Inet6Address;
import java.net.InetAddress;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every refused range at its edges, and public neighbours just outside them. */
class PublicAddressesTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "0.0.0.0", "0.255.255.255", "10.0.0.0", "10.255.255.255", "100.64.0.0", "100.127.255.255",
            "127.0.0.1", "127.255.255.255", "169.254.0.0", "169.254.169.254", "169.254.255.255",
            "172.16.0.0", "172.31.255.255", "192.0.0.0", "192.0.0.255", "192.0.2.0", "192.0.2.255",
            "192.168.0.0", "192.168.255.255", "198.18.0.0", "198.19.255.255", "198.51.100.7", "203.0.113.9",
            "224.0.0.1", "239.255.255.255", "240.0.0.0", "255.255.255.255",
            "::", "::1", "::127.0.0.1", "fc00::", "fdff:ffff:ffff:ffff:ffff:ffff:ffff:ffff", "fe80::1",
            "febf:ffff:ffff:ffff:ffff:ffff:ffff:ffff", "ff02::1", "2001:db8::1", "2001:0:4136:e378::1",
            "2002:7f00:1::", "100::1", "::ffff:127.0.0.1", "::ffff:10.1.2.3", "64:ff9b::a9fe:a9fe"})
    void refused(String literal) throws Exception {
        assertFalse(PublicAddresses.isPublic(InetAddress.getByName(literal)), literal);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "1.1.1.1", "9.255.255.255", "11.0.0.0", "100.63.255.255", "100.128.0.0", "126.255.255.255",
            "128.0.0.0", "169.253.255.255", "169.255.0.0", "172.15.255.255", "172.32.0.0", "192.0.1.0",
            "192.167.255.255", "192.169.0.0", "198.17.255.255", "198.20.0.0", "223.255.255.255",
            "2606:4700:4700::1111", "2001:4860:4860::8888", "fec0::1", "::ffff:8.8.8.8", "64:ff9b::808:808"})
    void allowed(String literal) throws Exception {
        assertTrue(PublicAddresses.isPublic(InetAddress.getByName(literal)), literal);
    }

    /**
     * Java parses an IPv4-mapped literal to an {@code Inet4Address}, but a
     * resolver can hand back the sixteen-byte form; that form is judged by the
     * IPv4 address inside it too.
     */
    @Test
    void aMappedAddressInItsSixteenByteFormIsJudgedByItsIpv4Part() throws Exception {
        InetAddress loopback = mapped(127, 0, 0, 1);
        InetAddress google = mapped(8, 8, 8, 8);
        assertInstanceOf(Inet6Address.class, loopback);
        assertFalse(PublicAddresses.isPublic(loopback));
        assertTrue(PublicAddresses.isPublic(google));
    }

    private static InetAddress mapped(int a, int b, int c, int d) throws Exception {
        byte[] bytes = new byte[16];
        bytes[10] = (byte) 0xFF;
        bytes[11] = (byte) 0xFF;
        bytes[12] = (byte) a;
        bytes[13] = (byte) b;
        bytes[14] = (byte) c;
        bytes[15] = (byte) d;
        return Inet6Address.getByAddress(null, bytes, -1);
    }
}

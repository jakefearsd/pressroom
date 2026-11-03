/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.wikantik.pressroom.boot;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import org.apache.catalina.connector.Connector;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.RemoteIpValve;
import org.apache.catalina.valves.ValveBase;
import org.apache.tomcat.util.http.MimeHeaders;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Pins how the client's address, scheme and host are read behind Caddy, and
 * proves the result against Tomcat's own {@code RemoteIpValve} configured
 * from the same properties.
 *
 * <p>{@code server.forward-headers-strategy} must be {@code native}. Under
 * {@code framework} Spring Boot installs Spring's {@code ForwardedHeaderFilter}
 * as well as the valve, and that filter (spring-web 7) overrides
 * {@code getRemoteAddr()} from an RFC 7239 {@code Forwarded: for=} header or
 * the leftmost {@code X-Forwarded-For} entry, both written by the visitor. That
 * undid every per-IP throttle (password reset, contact, newsletter, API basic
 * auth) and let {@code Forwarded: host=} rewrite {@code getRequestURL()} (code
 * review, 2026-11-03). The valve walks {@code X-Forwarded-For} from the right
 * and stops at the first address that is not an internal proxy, so only
 * Caddy's (and cloudflared's) own hops are trusted.
 *
 * <p>The host is never taken from a header: Caddy passes the reader's own
 * {@code Host} through, which is what custom domains route on.
 */
class RemoteIpValveConfigTest {

    private static Properties properties() throws IOException {
        Properties properties = new Properties();
        try (InputStream in = RemoteIpValveConfigTest.class
                .getResourceAsStream("/application.properties")) {
            assertNotNull(in, "application.properties must be on the test classpath");
            properties.load(in);
        }
        return properties;
    }

    @Test
    void applicationPropertiesConfiguresTheRemoteIpValveFromCaddysForwardedHeaders() throws IOException {
        Properties properties = properties();
        assertEquals("x-forwarded-for",
                properties.getProperty("server.tomcat.remoteip.remote-ip-header"),
                "getRemoteAddr() must be rewritten from Caddy's X-Forwarded-For, or every "
                        + "per-client throttle (contact, newsletter subscribe, password reset) "
                        + "collapses onto Caddy's own container IP in production");
        assertEquals("x-forwarded-proto",
                properties.getProperty("server.tomcat.remoteip.protocol-header"),
                "RemoteIpValve's own scheme rewrite must agree with the header Caddy actually sets");
    }

    @Test
    void theValveAloneReadsForwardedHeaders() throws IOException {
        Properties properties = properties();
        assertEquals("native", properties.getProperty("server.forward-headers-strategy"),
                "framework adds Spring's ForwardedHeaderFilter, which trusts the visitor's own headers");
        assertEquals("", properties.getProperty("server.tomcat.remoteip.host-header"),
                "the host comes from Host, never from X-Forwarded-Host");
    }

    @Test
    void aVisitorsOwnForwardedForEntriesAreIgnored() throws Exception {
        Seen seen = throughTheValve("172.18.0.5", "6.6.6.6, 203.0.113.9, 172.18.0.7", null, null, null);
        assertEquals("203.0.113.9", seen.remoteAddr, "the rightmost address that is not an internal proxy");
    }

    @Test
    void anRfc7239ForwardedHeaderIsIgnored() throws Exception {
        Seen seen = throughTheValve("172.18.0.5", "203.0.113.9", null, null,
                "for=1.2.3.4;host=evil.example;proto=https");
        assertEquals("203.0.113.9", seen.remoteAddr);
        assertEquals("blog.example.com", seen.serverName);
    }

    @Test
    void anXForwardedHostIsIgnored() throws Exception {
        Seen seen = throughTheValve("172.18.0.5", "203.0.113.9", "https", "evil.example", null);
        assertEquals("blog.example.com", seen.serverName);
    }

    @Test
    void caddysForwardedProtoMakesTheRequestSecure() throws Exception {
        Seen seen = throughTheValve("172.18.0.5", "203.0.113.9", "https", null, null);
        assertEquals("https", seen.scheme);
        assertEquals(true, seen.secure);
        assertEquals(443, seen.serverPort);
    }

    @Test
    void aPeerThatIsNotAProxyCannotForgeItsAddress() throws Exception {
        Seen seen = throughTheValve("198.51.100.7", "1.2.3.4", "https", null, null);
        assertEquals("198.51.100.7", seen.remoteAddr);
        assertEquals("http", seen.scheme, "nor its scheme");
    }

    /** What the next valve saw: the valve restores the original values once the request returns. */
    private static final class Seen {
        String remoteAddr;
        String scheme;
        String serverName;
        int serverPort;
        boolean secure;
    }

    /**
     * One request through a {@code RemoteIpValve} set up the way Spring Boot
     * sets it up from {@code server.tomcat.remoteip.*} (Tomcat's own default
     * internal proxies: loopback and the private ranges).
     */
    private static Seen throughTheValve(String peer, String forwardedFor, String proto, String forwardedHost,
            String forwarded) throws Exception {
        Properties properties = properties();
        RemoteIpValve valve = new RemoteIpValve();
        valve.setRemoteIpHeader(properties.getProperty("server.tomcat.remoteip.remote-ip-header"));
        valve.setProtocolHeader(properties.getProperty("server.tomcat.remoteip.protocol-header"));
        valve.setHostHeader(properties.getProperty("server.tomcat.remoteip.host-header", "X-Forwarded-Host"));
        Seen seen = new Seen();
        valve.setNext(new ValveBase() {
            @Override
            public void invoke(Request request, Response response) {
                seen.remoteAddr = request.getRemoteAddr();
                seen.scheme = request.getScheme();
                seen.serverName = request.getServerName();
                seen.serverPort = request.getServerPort();
                seen.secure = request.isSecure();
            }
        });

        org.apache.coyote.Request coyote = new org.apache.coyote.Request();
        coyote.scheme().setString("http");
        coyote.serverName().setString("blog.example.com");
        coyote.setServerPort(80);
        MimeHeaders headers = coyote.getMimeHeaders();
        headers.addValue("X-Forwarded-For").setString(forwardedFor);
        if (proto != null) {
            headers.addValue("X-Forwarded-Proto").setString(proto);
        }
        if (forwardedHost != null) {
            headers.addValue("X-Forwarded-Host").setString(forwardedHost);
        }
        if (forwarded != null) {
            headers.addValue("Forwarded").setString(forwarded);
        }
        Request request = new Request(new Connector(), coyote);
        request.setRemoteAddr(peer);
        request.setRemoteHost(peer);
        valve.invoke(request, new Response(new org.apache.coyote.Response()));
        return seen;
    }
}

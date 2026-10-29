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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Pins the two {@code server.tomcat.remoteip.*} keys in {@code
 * application.properties} that install Tomcat's {@code RemoteIpValve}.
 *
 * <p>There is no {@code @Bean} for this, unlike {@link WebContainerConfig}'s
 * error pages and welcome files -- Spring Boot's {@code ServerProperties}
 * auto-configuration wires the valve straight from these two keys, so there
 * is no Java-config seam to unit test against directly (an end-to-end proof
 * would need a real embedded Tomcat behind a proxy sending X-Forwarded-For,
 * which is what {@code docker_deployment.md}'s TLS section and the property
 * comments describe instead). This test instead pins the properties file
 * itself, on the {@code WebContainerConfigTest} family's "prose is not proof"
 * principle: a key silently dropped or renamed during a future edit would
 * otherwise collapse every per-client throttle onto Caddy's own address in
 * production with nothing failing red locally.
 *
 * <p>{@code server.forward-headers-strategy=framework} (Spring's own {@code
 * ForwardedHeaderFilter}, tested end-to-end in {@code InitFilterTest}'s
 * forwarded-proto chain) is a DIFFERENT mechanism: it rewrites {@code
 * getScheme()}/{@code isSecure()}/{@code getRequestURL()}, never {@code
 * getRemoteAddr()}. The RemoteIpValve keys pinned here are what makes
 * {@code getRemoteAddr()} -- and therefore every throttle keyed on it --
 * proxy-aware.
 */
class RemoteIpValveConfigTest {

    @Test
    void applicationPropertiesConfiguresTheRemoteIpValveFromCaddysForwardedHeaders() throws IOException {
        Properties properties = new Properties();
        try (InputStream in = RemoteIpValveConfigTest.class
                .getResourceAsStream("/application.properties")) {
            assertNotNull(in, "application.properties must be on the test classpath");
            properties.load(in);
        }

        assertEquals("x-forwarded-for",
                properties.getProperty("server.tomcat.remoteip.remote-ip-header"),
                "getRemoteAddr() must be rewritten from Caddy's X-Forwarded-For, or every "
                        + "per-client throttle (contact, newsletter subscribe, password reset) "
                        + "collapses onto Caddy's own container IP in production");
        assertEquals("x-forwarded-proto",
                properties.getProperty("server.tomcat.remoteip.protocol-header"),
                "RemoteIpValve's own scheme rewrite must agree with the header Caddy actually sets");
    }
}

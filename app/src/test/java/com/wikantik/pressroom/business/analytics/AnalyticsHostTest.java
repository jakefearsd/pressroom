/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  The ASF licenses this file to You
 * under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.  For additional information regarding
 * copyright in this work, please see the NOTICE file in the top level
 * directory of this distribution.
 */
package com.wikantik.pressroom.business.analytics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The host the tracker counts: the custom domain, else the site URL's host; lower case, no port. */
class AnalyticsHostTest {

    @Test
    void customDomainWinsAndIsNormalised() {
        assertEquals("blog.example.com", AnalyticsHost.of(" Blog.Example.COM:8443. ", "https://site.example.org"));
    }

    @Test
    void siteUrlHostWithoutPortOtherwise() {
        assertEquals("localhost", AnalyticsHost.of(null, "http://LocalHost:8083/pressroom"));
        assertEquals("localhost", AnalyticsHost.of("  ", "http://localhost:8083"));
    }

    @Test
    void ipLiteralsStayTheRealHost() {
        assertEquals("192.168.1.5", AnalyticsHost.of(null, "http://192.168.1.5:8083"));
        assertEquals("[::1]", AnalyticsHost.of(null, "http://[::1]:8083/"));
    }

    @Test
    void noHostMeansNull() {
        assertNull(AnalyticsHost.of(null, null));
        assertNull(AnalyticsHost.of(null, ""));
        assertNull(AnalyticsHost.of(null, "not a url"));
        assertNull(AnalyticsHost.of(":8080", null));
        assertNull(AnalyticsHost.of(".", null));
    }
}

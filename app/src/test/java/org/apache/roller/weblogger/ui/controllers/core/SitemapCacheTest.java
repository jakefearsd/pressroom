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
package org.apache.roller.weblogger.ui.controllers.core;

import org.apache.roller.weblogger.config.StaticConfigOverride;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link SitemapCache}: one slot per weblog and url, valid only for the
 * {@code lastModified} it was built at, bounded by the configured size.
 */
class SitemapCacheTest {

    private static final String URL = "https://example.com/blog/";

    @Test
    void aDocumentIsServedOnlyForTheStampItWasBuiltAt() {
        SitemapCache cache = SitemapCache.fromConfig();
        cache.put("blog", URL, 1000L, "<v1/>");

        assertEquals("<v1/>", cache.get("blog", URL, 1000L));
        assertNull(cache.get("blog", URL, 2000L), "a moved lastModified is a miss");
        assertNull(cache.get("blog", "https://elsewhere.example.com/", 1000L),
                "another url is another document");
        assertNull(cache.get("other", URL, 1000L), "another weblog is another document");
    }

    @Test
    void aNewVersionReplacesTheOldInItsSlot() {
        SitemapCache cache = SitemapCache.fromConfig();
        cache.put("blog", URL, 1000L, "<v1/>");
        cache.put("blog", URL, 2000L, "<v2/>");

        assertEquals("<v2/>", cache.get("blog", URL, 2000L));
        assertNull(cache.get("blog", URL, 1000L), "the superseded version is gone, not kept alongside");
    }

    @Test
    void theConfiguredSizeBoundsHowManyWeblogsAreHeld() {
        try (StaticConfigOverride _ = StaticConfigOverride.set("cache.sitemap.size", "2")) {
            SitemapCache cache = SitemapCache.fromConfig();
            cache.put("one", URL, 1L, "<one/>");
            cache.put("two", URL, 1L, "<two/>");
            cache.put("three", URL, 1L, "<three/>");

            assertNull(cache.get("one", URL, 1L), "the least recently used document is evicted");
            assertEquals("<three/>", cache.get("three", URL, 1L));
        }
    }

    @Test
    void aDisabledCacheHoldsNothing() {
        try (StaticConfigOverride _ = StaticConfigOverride.set("cache.sitemap.enabled", "false")) {
            SitemapCache cache = SitemapCache.fromConfig();
            cache.put("blog", URL, 1000L, "<v1/>");

            assertNull(cache.get("blog", URL, 1000L));
        }
    }
}

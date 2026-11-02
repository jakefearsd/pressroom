/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  The ASF licenses this file to You
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

import com.wikantik.pressroom.business.MultiWeblogURLStrategy;
import com.wikantik.pressroom.config.WebloggerRuntimeConfig;
import com.wikantik.pressroom.pojos.Weblog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Which recorded paths are which entries: the shapes come from the real URL
 * strategy, so a custom domain and a context path are covered the way pages
 * link to entries.
 */
class EntryPathsTest {

    private final MultiWeblogURLStrategy urls = new MultiWeblogURLStrategy();
    private String previousContext;

    /** The strategy reads the relative context URL, a per-JVM static; pinned here and restored. */
    @BeforeEach
    void pinContext() {
        previousContext = WebloggerRuntimeConfig.getRelativeContextURL();
        WebloggerRuntimeConfig.setRelativeContextURL("");
    }

    @AfterEach
    void restoreContext() {
        WebloggerRuntimeConfig.setRelativeContextURL(previousContext);
    }

    private static Weblog weblog(String handle, String customDomain) {
        Weblog weblog = new Weblog();
        weblog.setHandle(handle);
        weblog.setCustomDomain(customDomain);
        return weblog;
    }

    @Test
    void pathMappingCoversEveryUrlShape() {
        EntryPaths root = EntryPaths.forWeblog(weblog("sandiego", null), urls, "");
        assertEquals("tide-pools", root.anchorOf("/sandiego/entry/tide-pools"));
        assertEquals("tide-pools", root.anchorOf("/sandiego/entry/tide-pools/"), "a trailing slash");
        assertEquals("tide-pools", root.anchorOf("/sandiego/entry/tide-pools?utm_source=x#top"),
                "a query string and a fragment");
        assertEquals("café", root.anchorOf("/sandiego/entry/caf%C3%A9"), "the path is percent-encoded");
        assertNull(root.anchorOf("/sandiego/"), "the blog's front page is not an entry");
        assertNull(root.anchorOf("/sandiego/category/x"));
        assertNull(root.anchorOf("/sandiego/tags/y"));
        assertNull(root.anchorOf("/sandiego/entry/"), "no anchor at all");
        assertNull(root.anchorOf("/sandiego/entry/a/b"), "an anchor has no slash in it");
        assertNull(root.anchorOf("/otherblog/entry/tide-pools"), "another blog's entry");
        assertNull(root.anchorOf(null));
        assertNull(root.anchorOf("/sandiego/entry/%zz"), "a path that does not decode names no anchor");

        WebloggerRuntimeConfig.setRelativeContextURL("/pressroom");
        EntryPaths prefixed = EntryPaths.forWeblog(weblog("sandiego", null), urls, "/pressroom");
        assertEquals("tide-pools", prefixed.anchorOf("/pressroom/sandiego/entry/tide-pools"),
                "under a context path");
        assertNull(prefixed.anchorOf("/pressroomsandiego/entry/tide-pools"), "the context path is a whole segment");

        EntryPaths vhost = EntryPaths.forWeblog(weblog("sandiego", "sandiego.example.com"), urls, "/pressroom");
        assertEquals("tide-pools", vhost.anchorOf("/pressroom/entry/tide-pools"),
                "on a custom domain, under a context path");
        WebloggerRuntimeConfig.setRelativeContextURL("");
        vhost = EntryPaths.forWeblog(weblog("sandiego", "sandiego.example.com"), urls, "");
        assertEquals("tide-pools", vhost.anchorOf("/entry/tide-pools"), "on a custom domain");
        assertEquals("tide-pools", vhost.anchorOf("/sandiego/entry/tide-pools"),
                "the site-host form a custom-domain blog was reached at before");

        WebloggerRuntimeConfig.setRelativeContextURL(null);
        EntryPaths unlatched = EntryPaths.forWeblog(weblog("sandiego", null), urls, null);
        assertEquals("tide-pools", unlatched.anchorOf("/sandiego/entry/tide-pools"),
                "before the first request has latched a context path, the root's shapes still map");

        List<Set<String>> lookups = new ArrayList<>();
        Map<String, String> ids = root.entryIds(List.of("/sandiego/entry/tide-pools", "/sandiego/entry/tide-pools/",
                "/sandiego/entry/unknown", "/sandiego/category/x"), anchors -> {
                    lookups.add(anchors);
                    return Map.of("tide-pools", "entry-1");
                });
        assertEquals(List.of(Set.of("tide-pools", "unknown")), lookups, "one lookup for every anchor at once");
        assertEquals(Map.of("/sandiego/entry/tide-pools", "entry-1", "/sandiego/entry/tide-pools/", "entry-1"), ids,
                "an unknown anchor and a non-entry path map to nothing");

        lookups.clear();
        assertEquals(Map.of(), root.entryIds(List.of("/sandiego/"), anchors -> {
            lookups.add(anchors);
            return Map.of();
        }));
        assertEquals(List.of(), lookups, "no anchors, no query");
    }
}

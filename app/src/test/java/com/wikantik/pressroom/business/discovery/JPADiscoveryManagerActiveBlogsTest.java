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
package com.wikantik.pressroom.business.discovery;

import com.wikantik.pressroom.business.MockWeblogger;
import com.wikantik.pressroom.pojos.Weblog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * The front page sidebar's "recently updated" blogs
 * ({@link FrontPageSelection#activeBlogs()}): at most
 * {@link JPADiscoveryManagerImpl#ACTIVE_BLOGS}, the blog with the newest
 * eligible post first, so the sticky sidebar stays short however many blogs
 * the site has (spec 2026-11-03-front-page-redesign-design.md, item 6).
 */
class JPADiscoveryManagerActiveBlogsTest {

    private static final Instant NOW = Instant.parse("2026-11-06T12:00:00Z");

    private MockWeblogger tier;
    private final Map<String, Weblog> weblogs = new HashMap<>();
    private final List<DiscoveryCandidate> candidates = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        tier = MockWeblogger.attached();
        when(tier.weblogManager().getWeblog(anyString()))
                .thenAnswer(call -> weblogs.get(call.<String>getArgument(0)));
        when(tier.weblogManager().getWeblogs(any(), any(), any(), any(), anyInt(), anyInt()))
                .thenAnswer(call -> new ArrayList<>(weblogs.values()));
        when(tier.weblogEntryManager().getDiscoveryCandidates()).thenReturn(candidates);
    }

    @AfterEach
    void tearDown() {
        tier.detach();
    }

    private JPADiscoveryManagerImpl manager() {
        return new JPADiscoveryManagerImpl(tier.weblogger(), new Unconfigured(),
                Clock.fixed(NOW, ZoneOffset.UTC), Runnable::run);
    }

    private FrontPageSelection page() {
        return manager().selectFrontPage();
    }

    private Weblog blog(String id) {
        Weblog weblog = new Weblog();
        weblog.setId(id);
        weblog.setHandle(id);
        weblog.setName(id);
        weblogs.put(id, weblog);
        return weblog;
    }

    /** A post on {@code weblog}, {@code daysAgo} old. */
    private void post(Weblog weblog, String entryId, int daysAgo) {
        candidates.add(new DiscoveryCandidate(entryId, weblog.getId(), entryId, NOW.minusSeconds(86_400L * daysAgo)));
    }

    private static List<String> ids(List<Weblog> list) {
        return list.stream().map(Weblog::getId).toList();
    }

    @Test
    void newestPostFirstAndCappedAtFive() {
        Weblog[] blogs = new Weblog[7];
        for (int i = 0; i < blogs.length; i++) {
            blogs[i] = blog("blog-" + i);
            // blog-i's newest post is i days old; each also has an older one
            post(blogs[i], "new-" + i, i);
            post(blogs[i], "old-" + i, 100 + i);
        }

        FrontPageSelection page = page();

        assertEquals(5, JPADiscoveryManagerImpl.ACTIVE_BLOGS);
        assertEquals(List.of("blog-0", "blog-1", "blog-2", "blog-3", "blog-4"), ids(page.activeBlogs()));
        assertEquals(7, page.allBlogs().size(), "the full list is unchanged");
    }

    @Test
    void anOldBlogWithOneFreshPostOutranksABusyStaleOne() {
        Weblog busy = blog("busy");
        for (int i = 0; i < 10; i++) {
            post(busy, "busy-" + i, 30 + i);
        }
        Weblog fresh = blog("fresh");
        post(fresh, "fresh-0", 1);

        assertEquals(List.of("fresh", "busy"), ids(page().activeBlogs()));
    }

    @Test
    void aBlogWithNoPostsIsNotActive() {
        Weblog empty = blog("empty");
        Weblog writing = blog("writing");
        post(writing, "w-0", 3);

        FrontPageSelection page = page();

        assertEquals(List.of("writing"), ids(page.activeBlogs()));
        assertTrue(ids(page.allBlogs()).contains(empty.getId()), "the directory still lists it");
    }

    @Test
    void nothingEligibleMeansNoActiveBlogs() {
        assertTrue(page().activeBlogs().isEmpty());
        assertTrue(FrontPageSelection.empty().activeBlogs().isEmpty());
    }

    @Test
    void aBlogDeletedAfterThePickIsLeftOut() {
        Weblog gone = blog("gone");
        post(gone, "g-0", 0);
        Weblog kept = blog("kept");
        post(kept, "k-0", 1);
        JPADiscoveryManagerImpl manager = manager();
        assertEquals(List.of("gone", "kept"), ids(manager.selectFrontPage().activeBlogs()));

        // the snapshot still names it; the render-time lookup finds nothing
        weblogs.remove(gone.getId());

        assertEquals(List.of("kept"), ids(manager.selectFrontPage().activeBlogs()));
    }

    private static final class Unconfigured implements TrafficSource {
        @Override
        public boolean isConfigured() {
            return false;
        }

        @Override
        public Map<String, Long> viewsByEntry(int windowDays) {
            return Map.of();
        }
    }
}

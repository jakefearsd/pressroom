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

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.MockWeblogger;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogEntry.PubStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * The picker's failure and edge paths, on a mocked tier: what happens when
 * Pressroom itself cannot be read, when an entity cannot be loaded at
 * display time, and when a whole section's picks stop being eligible.
 */
class JPADiscoveryManagerImplEdgeTest {

    private static final Instant NOW = Instant.parse("2026-11-01T12:00:00Z");

    private MockWeblogger tier;
    private final Map<String, WeblogEntry> entries = new HashMap<>();
    private final Weblog blog = weblog("blog-1");

    @BeforeEach
    void setUp() throws Exception {
        tier = MockWeblogger.attached();
        when(tier.weblogEntryManager().getWeblogEntry(anyString()))
                .thenAnswer(call -> entries.get(call.<String>getArgument(0)));
        when(tier.weblogManager().getWeblog(blog.getId())).thenReturn(blog);
        when(tier.weblogManager().getWeblogs(any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of(blog));
    }

    @AfterEach
    void tearDown() {
        tier.detach();
    }

    private JPADiscoveryManagerImpl manager(TrafficSource source) {
        return new JPADiscoveryManagerImpl(tier.weblogger(), source, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static Weblog weblog(String id) {
        Weblog weblog = new Weblog();
        weblog.setId(id);
        weblog.setHandle(id.replace("-", ""));
        weblog.setName(id);
        return weblog;
    }

    /** {@code count} published entries on the blog, the first one newest. */
    private List<DiscoveryCandidate> candidates(int count) throws WebloggerException {
        List<DiscoveryCandidate> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            WeblogEntry entry = new WeblogEntry();
            entry.setId("entry-" + i);
            entry.setAnchor("post-" + i);
            entry.setStatus(PubStatus.PUBLISHED);
            entry.setWebsite(blog);
            entries.put(entry.getId(), entry);
            list.add(new DiscoveryCandidate(entry.getId(), blog.getId(), entry.getAnchor(),
                    NOW.minusSeconds(60L * i)));
        }
        when(tier.weblogEntryManager().getDiscoveryCandidates()).thenReturn(list);
        return list;
    }

    @Test
    void aCandidateReadFailureIsRecordedAndShowsNothing() throws Exception {
        when(tier.weblogEntryManager().getDiscoveryCandidates()).thenThrow(new WebloggerException("db down"));
        JPADiscoveryManagerImpl manager = manager(new Unconfigured());

        FrontPageSelection page = manager.selectFrontPage();

        assertNull(page.headline());
        assertTrue(page.popular().isEmpty());
        assertTrue(page.allBlogs().isEmpty());
        assertTrue(manager.selectMoreFromSite(weblog("other"), 3).isEmpty());
        assertEquals(DiscoveryStatus.State.FAILED, manager.getStatus().state());
        assertEquals("db down", manager.getStatus().lastError());
    }

    @Test
    void aCandidateReadFailureKeepsThePreviousSnapshot() throws Exception {
        candidates(3);
        JPADiscoveryManagerImpl manager = manager(new Unconfigured());
        manager.refresh();
        when(tier.weblogEntryManager().getDiscoveryCandidates()).thenThrow(new IllegalStateException());

        manager.refresh();

        assertEquals(DiscoveryStatus.State.FAILED, manager.getStatus().state());
        assertEquals("IllegalStateException", manager.getStatus().lastError(), "no message: the type stands in");
        assertEquals(3, manager.getStatus().candidates());
        assertEquals("entry-0", manager.selectFrontPage().headline().getId());
    }

    @Test
    void aRuntimeFailureReadingTrafficIsAFailedRefresh() throws Exception {
        candidates(3);
        JPADiscoveryManagerImpl manager = manager(new TrafficSource() {
            @Override
            public boolean isConfigured() {
                return true;
            }

            @Override
            public List<TrafficRow> viewsSince(LocalDate firstDay) {
                throw new IllegalStateException("driver blew up");
            }
        });

        manager.refresh();

        assertEquals(DiscoveryStatus.State.FAILED, manager.getStatus().state());
        assertNull(manager.getStatus().lastSuccess());
        assertEquals("entry-0", manager.selectFrontPage().headline().getId(), "no scores: newest leads");
    }

    @Test
    void nothingEligibleMeansNoHeadline() throws Exception {
        candidates(0);
        JPADiscoveryManagerImpl manager = manager(new Unconfigured());

        FrontPageSelection page = manager.selectFrontPage();

        assertNull(page.headline());
        assertTrue(page.blogs().isEmpty());
        assertEquals(List.of(blog), page.allBlogs(), "a blog with no posts is still listed");
    }

    @Test
    void anEntryThatCannotBeLoadedIsLeftOut() throws Exception {
        candidates(2);
        when(tier.weblogEntryManager().getWeblogEntry(anyString())).thenThrow(new WebloggerException("gone"));
        when(tier.weblogManager().getWeblog(blog.getId())).thenThrow(new WebloggerException("gone"));
        JPADiscoveryManagerImpl manager = manager(new Unconfigured());

        FrontPageSelection page = manager.selectFrontPage();

        assertNull(page.headline());
        assertTrue(page.allBlogs().isEmpty());
        assertTrue(manager.selectMoreFromSite(null, 3).isEmpty());
    }

    @Test
    void theHeadlineFallsBackToSomethingDifferent() throws Exception {
        // 9 on one blog: headline 1, popular 4, the one card 3, different 1
        candidates(9);
        JPADiscoveryManagerImpl manager = manager(new Unconfigured());
        FrontPageSelection before = manager.selectFrontPage();
        assertEquals(1, before.different().size());
        entries.get(before.headline().getId()).setStatus(PubStatus.DRAFT);
        for (WeblogEntry entry : before.popular()) {
            entry.setStatus(PubStatus.DRAFT);
        }

        FrontPageSelection after = manager.selectFrontPage();

        assertNotNull(after.headline());
        assertEquals(before.different().get(0).getId(), after.headline().getId());
        assertTrue(after.popular().isEmpty());
        assertTrue(after.different().isEmpty());
    }

    @Test
    void moreFromSiteForNoCountIsEmptyAndForNoBlogDrawsFromAll() throws Exception {
        candidates(4);
        JPADiscoveryManagerImpl manager = manager(new Unconfigured());

        assertTrue(manager.selectMoreFromSite(blog, 0).isEmpty());
        assertEquals(3, manager.selectMoreFromSite(null, 3).size());
        assertTrue(manager.selectMoreFromSite(blog, 3).isEmpty(), "the only blog has no other blogs to show");
    }

    @Test
    void anIneligibleWeblogDropsItsCardAndListing() throws Exception {
        candidates(9);
        JPADiscoveryManagerImpl manager = manager(new Unconfigured());
        assertEquals(1, manager.selectFrontPage().blogs().size());
        blog.setActive(Boolean.FALSE);

        FrontPageSelection page = manager.selectFrontPage();

        assertTrue(page.blogs().isEmpty());
        assertTrue(page.allBlogs().isEmpty());
        assertNull(page.headline(), "its entries are no longer eligible either");
    }

    private static final class Unconfigured implements TrafficSource {
        @Override
        public boolean isConfigured() {
            return false;
        }

        @Override
        public List<TrafficRow> viewsSince(LocalDate firstDay) {
            return List.of();
        }
    }
}

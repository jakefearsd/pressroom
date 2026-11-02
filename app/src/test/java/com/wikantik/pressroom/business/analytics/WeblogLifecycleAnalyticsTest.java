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

import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What creating, saving and deleting a blog does to analytics: the weblog
 * manager asks for a sync once its change has committed, never waits for
 * it, and never asks on delete (AC 1, 2, 4, 5).
 */
class WeblogLifecycleAnalyticsTest {

    private AnalyticsTier tier;
    private User user;

    @BeforeEach
    void setUp() throws Exception {
        tier = new AnalyticsTier();
        user = TestUtils.setupUser("analyticslife");
        TestUtils.endSession(true);
        // everything already in the database is registered first, so the
        // assertions below see only what the lifecycle under test sends
        tier.manager.sync();
        TestUtils.endSession(false);
        tier.service.ensured.clear();
        tier.service.calls.set(0);
    }

    @AfterEach
    void tearDown() throws Exception {
        tier.tearDown();
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    @Test
    void newBlogIsRegisteredOnNextSync() throws Exception {
        Weblog weblog = tier.addWeblog("lifenew", user);
        TestUtils.endSession(false);
        tier.awaitQuiet();

        assertTrue(tier.background.submitted.get() >= 1, "creating the blog asked for a sync");
        assertEquals(new SiteSpec(weblog.getId(), "Test Weblog lifenew", "blogs.example.com"),
                tier.service.sites.get(weblog.getId()));
        assertTrue(tier.manager.isRegistered(weblog.getId()));
    }

    @Test
    void createBlogWithUmamiDownSucceedsFastAndRegistersLater() throws Exception {
        tier.service.down = true;
        tier.service.hang = new CountDownLatch(1);

        long start = System.nanoTime();
        Weblog weblog = tier.addWeblog("lifedown", user);
        long tookMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        TestUtils.endSession(false);
        assertTrue(tookMillis < 200, "creating a blog never waits on Umami: " + tookMillis + " ms");

        tier.service.release();
        tier.awaitQuiet();
        assertFalse(tier.manager.isRegistered(weblog.getId()));
        AnalyticsStatus down = tier.manager.getStatus();
        assertEquals(Boolean.FALSE, down.reachable());
        assertTrue(down.failures().stream().anyMatch(f -> "lifedown".equals(f.handle())
                        && f.error() != null && !f.error().isBlank() && f.at() != null),
                "the failure is on Maintenance: " + down.failures());

        tier.service.down = false;
        tier.manager.requestSync();
        tier.awaitQuiet();
        assertTrue(tier.manager.isRegistered(weblog.getId()), "registered once Umami is back");
        assertTrue(tier.manager.getStatus().failures().stream().noneMatch(f -> "lifedown".equals(f.handle())));
    }

    @Test
    void renameAndDomainChangeUpdateTheSite() throws Exception {
        Weblog weblog = tier.addWeblog("liferename", user);
        TestUtils.endSession(false);
        tier.awaitQuiet();

        Weblog renamed = tier.weblogs.getWeblog(weblog.getId());
        renamed.setName("Renamed Weblog");
        tier.weblogs.saveWeblog(renamed);
        tier.weblogger.flush();
        TestUtils.endSession(false);
        tier.awaitQuiet();
        assertEquals(new SiteSpec(weblog.getId(), "Renamed Weblog", "blogs.example.com"),
                tier.service.sites.get(weblog.getId()));

        Weblog moved = tier.weblogs.getWeblog(weblog.getId());
        moved.setCustomDomain("liferename.example.org");
        tier.weblogs.saveWeblog(moved);
        tier.weblogger.flush();
        TestUtils.endSession(false);
        tier.awaitQuiet();
        assertEquals(new SiteSpec(weblog.getId(), "Renamed Weblog", "liferename.example.org"),
                tier.service.sites.get(weblog.getId()));
        assertEquals(weblog.getId(), tier.manager.websiteIdFor(weblog.getId()), "same website throughout");
    }

    @Test
    void aSaveThatNeverCommitsAsksForNothing() throws Exception {
        Weblog weblog = tier.addWeblog("liferollback", user);
        TestUtils.endSession(false);
        tier.awaitQuiet();
        int before = tier.background.submitted.get();

        Weblog edited = tier.weblogs.getWeblog(weblog.getId());
        edited.setName("Never Committed");
        tier.weblogs.saveWeblog(edited);
        TestUtils.endSession(false);
        tier.awaitQuiet();

        assertEquals(before, tier.background.submitted.get(), "a rolled-back save requests no sync");
    }

    @Test
    void deletingABlogNeverCallsUmami() throws Exception {
        Weblog weblog = tier.addWeblog("lifedelete", user);
        TestUtils.endSession(false);
        tier.awaitQuiet();
        assertTrue(tier.manager.isRegistered(weblog.getId()));
        int submitted = tier.background.submitted.get();
        tier.service.calls.set(0);

        tier.weblogs.removeWeblog(tier.weblogs.getWeblog(weblog.getId()));
        tier.weblogger.flush();
        TestUtils.endSession(false);
        tier.awaitQuiet();

        assertEquals(submitted, tier.background.submitted.get(), "deleting asks for no sync");
        assertEquals(0, tier.service.calls.get(), "and nothing reaches Umami");
        assertTrue(tier.service.sites.containsKey(weblog.getId()), "the website stays in Umami");
    }
}

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
import org.mockito.Mockito;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The analytics manager against the real database and a fake service:
 * the backfill, what is (not) re-sent, coalescing, the render-cache bump
 * and the dev-host domain.
 *
 * <p>The suite shares one database, so other classes' weblogs are active
 * too and get registered with the fake as well; assertions name this
 * class's own weblogs, or count calls made after everything was registered.
 */
class JPAAnalyticsManagerImplTest {

    private AnalyticsTier tier;
    private User user;
    private String createdSiteSpace;

    @BeforeEach
    void setUp() throws Exception {
        tier = new AnalyticsTier();
        user = TestUtils.setupUser("analyticsmgr");
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        tier.tearDown();
        if (createdSiteSpace != null) {
            TestUtils.teardownWeblog(createdSiteSpace);
        }
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    @Test
    void backfillRegistersEveryExistingBlog() throws Exception {
        List<Weblog> existing = new ArrayList<>();
        for (String handle : List.of("backfilla", "backfillb", "backfillc")) {
            existing.add(tier.addWeblogUnseen(handle, user));
        }
        assertEquals(0, tier.service.calls.get(), "nothing reached the fake before startup");

        tier.manager.initialize();
        tier.awaitQuiet();

        for (Weblog weblog : existing) {
            assertEquals(new SiteSpec(weblog.getId(), weblog.getName(), "blogs.example.com"),
                    tier.service.sites.get(weblog.getId()), weblog.getHandle());
            assertTrue(tier.manager.isRegistered(weblog.getId()), weblog.getHandle());
            assertEquals(weblog.getId(), tier.manager.websiteIdFor(weblog.getId()));
        }
        AnalyticsStatus status = tier.manager.getStatus();
        assertTrue(status.configured());
        assertEquals(Boolean.TRUE, status.reachable());
        assertTrue(status.registered() >= 3, "registered: " + status);
        assertEquals(status.total(), status.registered(), "every active blog: " + status);
        assertTrue(status.failures().isEmpty(), status.failures().toString());
    }

    @Test
    void oneBlogFailingDoesNotRegisterItOrStopTheOthers() throws Exception {
        Weblog failing = tier.addWeblogUnseen("failsalone", user);
        Weblog before = tier.addWeblogUnseen("failsbefore", user);
        Weblog after = tier.addWeblogUnseen("failsafter", user);
        tier.service.failIds.add(failing.getId());

        tier.manager.sync();
        TestUtils.endSession(false);

        assertFalse(tier.manager.isRegistered(failing.getId()), "a refused blog is not recorded as registered");
        assertNull(tier.manager.websiteIdFor(failing.getId()));
        assertTrue(tier.manager.getStatus().failures().stream().anyMatch(f -> "failsalone".equals(f.handle())
                        && f.error().contains("HTTP 400")),
                "its failure is on Maintenance: " + tier.manager.getStatus().failures());
        assertEquals(Boolean.TRUE, tier.manager.getStatus().reachable(), "the service itself answered");
        assertTrue(tier.manager.isRegistered(before.getId()), "the others in the same run still register");
        assertTrue(tier.manager.isRegistered(after.getId()));

        tier.service.failIds.clear();
        tier.manager.sync();
        TestUtils.endSession(false);
        assertTrue(tier.manager.isRegistered(failing.getId()), "and it is retried on the next run");
    }

    /**
     * An unexpected exception's message is not ours to show (AC 15, defence
     * in depth): Maintenance gets its class name only, as a failed read does.
     */
    @Test
    void anUnexpectedRegistrationFailureRecordsOnlyItsClassName() throws Exception {
        Weblog crashing = tier.addWeblogUnseen("crashes", user);
        tier.service.crashIds.add(crashing.getId());

        tier.manager.sync();
        TestUtils.endSession(false);

        AnalyticsStatus.SiteFailure failure = tier.manager.getStatus().failures().stream()
                .filter(f -> "crashes".equals(f.handle())).findFirst().orElseThrow();
        assertEquals("IllegalStateException", failure.error());
        assertFalse(failure.error().contains(FakeAnalyticsService.CRASH_SECRET));
    }

    @Test
    void everyWeblogRegistersIncludingInactiveOnesAndTheSiteSpace() throws Exception {
        Weblog inactive = tier.addWeblogUnseen("inactiveblog", user);
        Weblog managed = TestUtils.weblogger().getWeblogManager().getWeblog(inactive.getId());
        managed.setActive(Boolean.FALSE);
        managed.setVisible(Boolean.FALSE);
        TestUtils.weblogger().getWeblogManager().saveWeblog(managed);
        TestUtils.endSession(true);
        Weblog site = siteSpace();

        tier.manager.sync();
        TestUtils.endSession(false);

        assertTrue(tier.manager.isRegistered(inactive.getId()), "inactive blogs are still served (R9)");
        assertTrue(tier.manager.isRegistered(site.getId()), "the site space carries the front page's traffic");
        assertEquals(new SiteSpec(site.getId(), site.getName(), "blogs.example.com"),
                tier.service.sites.get(site.getId()));
    }

    @Test
    void aRunWithNothingToSendMakesNoPing() throws Exception {
        tier.addWeblogUnseen("nothingtosend", user);
        tier.manager.sync();
        TestUtils.endSession(false);
        tier.service.pings.set(0);
        tier.service.ensured.clear();

        tier.manager.sync();
        TestUtils.endSession(false);

        assertEquals(0, tier.service.pings.get(), "every blog is as last confirmed: Umami is not asked (R10)");
        assertTrue(tier.service.ensured.isEmpty(), "nor sent anything; the run only reads");
    }

    @Test
    void unchangedBlogsAreNotResent() throws Exception {
        Weblog weblog = tier.addWeblogUnseen("unchanged", user);
        tier.manager.sync();
        TestUtils.endSession(false);
        assertTrue(tier.service.sites.containsKey(weblog.getId()));
        tier.service.ensured.clear();

        tier.manager.sync();
        TestUtils.endSession(false);

        assertTrue(tier.service.ensured.isEmpty(), "nothing changed, nothing sent: " + tier.service.ensured);
        assertTrue(tier.manager.isRegistered(weblog.getId()));
    }

    @Test
    void concurrentRequestsCoalesceIntoOneRun() throws Exception {
        tier.addWeblogUnseen("coalesce", user);
        tier.service.hang = new CountDownLatch(1);
        tier.manager.requestSync();
        awaitPings(1);

        ExecutorService callers = Executors.newFixedThreadPool(8);
        try {
            for (int i = 0; i < 40; i++) {
                callers.execute(tier.manager::requestSync);
            }
        } finally {
            callers.shutdown();
            assertTrue(callers.awaitTermination(10, TimeUnit.SECONDS));
        }
        tier.service.release();
        tier.awaitQuiet();

        assertEquals(2, backgroundRuns(), "the run in flight, then exactly one queued behind it");
        assertEquals(1, tier.background.submitted.get(), "the queued run rides on the same background task");
    }

    @Test
    void registrationExpiresTheBlogsRenderedPages() throws Exception {
        Weblog weblog = tier.addWeblogUnseen("expires", user);
        Date before = tier.committed(weblog.getId()).getLastModified();

        tier.manager.sync();
        Date registered = tier.committed(weblog.getId()).getLastModified();
        assertTrue(registered.after(before), "the flip to registered moves lastModified: " + before
                + " -> " + registered);
        assertEquals(0, tier.background.submitted.get(),
                "the bump's own save must not ask for another sync (R1)");

        tier.manager.sync();
        assertEquals(registered, tier.committed(weblog.getId()).getLastModified(),
                "no flip, no bump");
    }

    @Test
    void devHostStillRegisters() throws Exception {
        Weblog weblog = tier.addWeblogUnseen("devhost", user);
        tier.siteUrl = "http://LOCALHOST:8083";
        tier.manager.sync();
        TestUtils.endSession(false);
        assertEquals("localhost", tier.service.sites.get(weblog.getId()).domain(),
                "Umami accepts localhost; case and port are dropped");
        assertTrue(tier.manager.isRegistered(weblog.getId()));

        tier.siteUrl = "http://127.0.0.1:8083";
        tier.manager.sync();
        TestUtils.endSession(false);
        assertEquals("devhost.localhost.invalid", tier.service.sites.get(weblog.getId()).domain(),
                "Umami rejects an IP literal, so the blog registers under a placeholder");
    }

    @Test
    void unconfiguredManagerNeverCallsTheService() throws Exception {
        Weblog weblog = tier.addWeblogUnseen("unconfigured", user);
        tier.service.configured = false;

        tier.manager.initialize();
        tier.manager.requestSync();
        tier.manager.sync();
        tier.manager.reconcile();
        TestUtils.endSession(false);
        tier.awaitQuiet();

        assertEquals(0, tier.service.calls.get(), "an unconfigured service is never called");
        assertEquals(0, tier.background.submitted.get(), "nor is anything scheduled");
        assertFalse(tier.manager.isRegistered(weblog.getId()));
        assertNull(tier.manager.websiteIdFor(weblog.getId()));
        assertFalse(tier.manager.getStatus().configured());
    }

    @Test
    void requestSyncNeverRunsOnTheCallingThread() throws Exception {
        tier.addWeblogUnseen("offthread", user);
        tier.service.hang = new CountDownLatch(1);

        long start = System.nanoTime();
        tier.manager.requestSync();
        long tookMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        awaitPings(1);
        tier.service.release();
        tier.awaitQuiet();

        assertTrue(tookMillis < 1000, "requestSync returned while the service hung: " + tookMillis + " ms");
        assertFalse(tier.service.callers.isEmpty(), "the sync ran");
        assertFalse(tier.service.callers.contains(Thread.currentThread()),
                "and never on the calling thread");
    }

    @Test
    void domainForEveryHostShape() {
        Weblog weblog = AnalyticsTier.newWeblog("Dev_Blog.1", user);
        assertEquals("blogs.example.com", JPAAnalyticsManagerImpl.domainFor(weblog, "https://Blogs.Example.com:8443/"));
        assertEquals("blogs.example.com", JPAAnalyticsManagerImpl.domainFor(weblog, "https://blogs.example.com./x"));
        assertEquals("dev_blog-1.localhost.invalid", JPAAnalyticsManagerImpl.domainFor(weblog, "http://10.0.0.25"),
                "every IP literal, even one Umami's pattern lets through, gets the placeholder");
        assertEquals("dev_blog-1.localhost.invalid", JPAAnalyticsManagerImpl.domainFor(weblog, "http://[::1]:8083"));
        assertEquals("dev_blog-1.localhost.invalid", JPAAnalyticsManagerImpl.domainFor(weblog, null),
                "no site URL latched yet");
        assertEquals("dev_blog-1.localhost.invalid", JPAAnalyticsManagerImpl.domainFor(weblog, "not a url"));

        weblog.setCustomDomain("Shop.Example.org:443");
        assertEquals("shop.example.org", JPAAnalyticsManagerImpl.domainFor(weblog, "http://127.0.0.1"),
                "the custom domain wins over the site URL");

        // degenerate custom domains: no host, so the placeholder here and no data-domains on the page
        for (String degenerate : new String[] {":8080", ".", " . "}) {
            weblog.setCustomDomain(degenerate);
            assertEquals("dev_blog-1.localhost.invalid", JPAAnalyticsManagerImpl.domainFor(weblog, null), degenerate);
            assertNull(AnalyticsHost.of(weblog.getCustomDomain(), null), degenerate);
        }
    }

    @Test
    void aSyncThatCannotBeScheduledLeavesTheNextRequestFree() {
        AtomicInteger attempts = new AtomicInteger();
        JPAAnalyticsManagerImpl rejecting = new JPAAnalyticsManagerImpl(tier.weblogger, tier.strategy, tier.service,
                task -> {
                    attempts.incrementAndGet();
                    throw new RejectedExecutionException("pool shut down");
                }, Clock.systemUTC(), () -> AnalyticsTier.SITE_URL, () -> 30);

        rejecting.requestSync();
        rejecting.requestSync();

        assertEquals(2, attempts.get(), "a rejected schedule must not leave a run marked in flight");
        assertEquals(0, tier.service.calls.get());
    }

    /** Background syncs run so far: the drain releases the unit of work once after each. */
    private long backgroundRuns() {
        return Mockito.mockingDetails(tier.weblogger).getInvocations().stream()
                .filter(i -> "release".equals(i.getMethod().getName()))
                .count();
    }

    /** The site space, created here (and removed afterwards) if the suite has none yet. */
    private Weblog siteSpace() throws Exception {
        Weblog found = TestUtils.weblogger().getWeblogManager().getSiteSpace();
        if (found == null) {
            found = TestUtils.weblogger().getWeblogManager().getOrCreateSiteSpace(user);
            TestUtils.endSession(true);
            createdSiteSpace = found.getId();
        }
        return found;
    }

    private void awaitPings(int n) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (tier.service.pings.get() < n) {
            assertTrue(System.nanoTime() < deadline, "no sync reached the service");
            Thread.sleep(10);
        }
    }
}

package com.wikantik.pressroom.business.analytics;

import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.business.WeblogEntryManager;
import com.wikantik.pressroom.business.jpa.JPAPersistenceStrategy;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The four cache entities persist and read back through the real persistence unit. */
class AnalyticsEntitiesTest {

    private User user;
    private Weblog weblog;
    private EntityManagerFactory emf;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.setupWeblogger();
        user = TestUtils.setupUser("analyticsuser");
        weblog = TestUtils.setupWeblog("analyticsblog", user);
        TestUtils.endSession(true);
        WeblogEntryManager entries = TestUtils.weblogger().getWeblogEntryManager();
        Field f = entries.getClass().getDeclaredField("strategy");
        f.setAccessible(true);
        emf = ((JPAPersistenceStrategy) f.get(entries)).getEntityManager(false).getEntityManagerFactory();
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    @Test
    void entitiesRoundTrip() {
        String id = weblog.getId();
        Instant asOf = Instant.parse("2026-11-01T10:00:00Z");

        AnalyticsSite site = new AnalyticsSite();
        site.setWeblogId(id);
        site.setWebsiteId("umami-1");
        site.setSyncedName("Name");
        site.setSyncedDomain("a.example.com");
        site.setRegisteredAt(asOf);

        AnalyticsSummary summary = new AnalyticsSummary();
        summary.setWeblogId(id);
        summary.setPeriod("7d");
        summary.setAsOf(asOf);
        summary.setVisitors(1);
        summary.setVisits(2);
        summary.setViews(3);
        summary.setBounces(4);
        summary.setTotalTime(5);
        summary.setPrevVisitors(6);
        summary.setPrevVisits(7);
        summary.setPrevViews(8);
        summary.setPrevBounces(9);
        summary.setPrevTotalTime(10);

        AnalyticsPathViews path = new AnalyticsPathViews();
        path.setWeblogId(id);
        path.setPeriod("7d");
        path.setPath("/a");
        path.setViews(11);
        path.setVisitors(12);

        AnalyticsBreakdown breakdown = new AnalyticsBreakdown();
        breakdown.setWeblogId(id);
        breakdown.setPeriod("7d");
        breakdown.setKind("referrer");
        breakdown.setPayload("[{\"x\":\"google\",\"y\":3}]");

        EntityManager em = emf.createEntityManager();
        try {
            em.getTransaction().begin();
            em.persist(site);
            em.persist(summary);
            em.persist(path);
            em.persist(breakdown);
            em.getTransaction().commit();
        } finally {
            em.close();
        }

        EntityManager read = emf.createEntityManager();
        try {
            AnalyticsSite s = read.find(AnalyticsSite.class, id);
            assertEquals("umami-1", s.getWebsiteId());
            assertEquals(asOf, s.getRegisteredAt());
            assertNull(s.getLastError());

            AnalyticsSummary m = read.find(AnalyticsSummary.class, new AnalyticsSummary.Key(id, "7d"));
            assertEquals(asOf, m.getAsOf());
            assertEquals(10, m.getPrevTotalTime());
            assertEquals(3, m.getViews());

            AnalyticsPathViews p = read.find(AnalyticsPathViews.class, new AnalyticsPathViews.Key(id, "7d", "/a"));
            assertEquals(11, p.getViews());
            assertNull(p.getEntryId());

            AnalyticsBreakdown b = read.find(AnalyticsBreakdown.class, new AnalyticsBreakdown.Key(id, "7d", "referrer"));
            assertNotNull(b);
            assertEquals("[{\"x\":\"google\",\"y\":3}]", b.getPayload());
        } finally {
            read.close();
        }
    }
}

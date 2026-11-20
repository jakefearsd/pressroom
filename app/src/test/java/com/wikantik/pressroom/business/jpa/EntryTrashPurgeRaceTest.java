package com.wikantik.pressroom.business.jpa;

import java.lang.reflect.Field;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogEntry.PubStatus;
import jakarta.persistence.Query;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The entry purge must not delete an entry that left the trash after the
 * sweep listed it (as {@code PageTrashPurgeRaceTest} does for pages): each
 * candidate is read again under a write lock and removed only if it is still
 * trashed and still past the cutoff.
 */
class EntryTrashPurgeRaceTest {

    private User user;
    private Weblog weblog;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.setupWeblogger();
        user = TestUtils.setupUser("entrypurgeraceuser");
        weblog = TestUtils.setupWeblog("entrypurgeraceblog", user);
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    private static JPAWeblogEntryManagerImpl manager() {
        return (JPAWeblogEntryManagerImpl) TestUtils.weblogger().getWeblogEntryManager();
    }

    private static JPAPersistenceStrategy strategy() throws Exception {
        Field f = JPAWeblogEntryManagerImpl.class.getDeclaredField("strategy");
        f.setAccessible(true);
        return (JPAPersistenceStrategy) f.get(manager());
    }

    private WeblogEntry trashedEntry(String anchor, Instant trashedAt) throws Exception {
        WeblogEntry e = TestUtils.setupWeblogEntry(anchor, weblog, user, PubStatus.DRAFT);
        TestUtils.endSession(true);
        WeblogEntry managed = manager().getWeblogEntry(e.getId());
        managed.setStatus(PubStatus.TRASHED);
        managed.setTrashedAt(Timestamp.from(trashedAt));
        manager().saveWeblogEntry(managed);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
        return manager().getTrashedEntries(TestUtils.getManagedWebsite(weblog)).get(0);
    }

    private static Timestamp cutoff() {
        return Timestamp.from(Instant.now().minus(1, ChronoUnit.DAYS));
    }

    @Test
    void anEntryRestoredAfterTheSweepListedItIsNotPurged() throws Exception {
        WeblogEntry candidate = trashedEntry("restored-race", Instant.now().minus(10, ChronoUnit.DAYS));
        assertEquals(PubStatus.TRASHED, candidate.getStatus());

        Query restore = strategy().getEntityManager(true).createNativeQuery(
                "UPDATE weblogentry SET status = 'DRAFT', trashed_at = NULL WHERE id = ?");
        restore.setParameter(1, candidate.getId());
        assertEquals(1, restore.executeUpdate());

        assertFalse(manager().removeIfStillTrashed(candidate, cutoff()));
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
        strategy().getEntityManager(false).getEntityManagerFactory().getCache().evictAll();
        TestUtils.endSession(true);

        WeblogEntry kept = manager().getWeblogEntry(candidate.getId());
        assertNotNull(kept, "an entry that left the trash must survive the purge");
        assertEquals(PubStatus.DRAFT, kept.getStatus());
    }

    @Test
    void anEntryReTrashedAfterTheSweepListedItIsNotPurged() throws Exception {
        WeblogEntry candidate = trashedEntry("retrashed-race", Instant.now().minus(10, ChronoUnit.DAYS));

        Query retrash = strategy().getEntityManager(true).createNativeQuery(
                "UPDATE weblogentry SET trashed_at = now() WHERE id = ?");
        retrash.setParameter(1, candidate.getId());
        assertEquals(1, retrash.executeUpdate());

        assertFalse(manager().removeIfStillTrashed(candidate, cutoff()));
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
        strategy().getEntityManager(false).getEntityManagerFactory().getCache().evictAll();
        TestUtils.endSession(true);

        assertNotNull(manager().getWeblogEntry(candidate.getId()));
    }

    @Test
    void anEntryStillInTheTrashPastTheCutoffIsRemoved() throws Exception {
        WeblogEntry candidate = trashedEntry("still-trashed", Instant.now().minus(10, ChronoUnit.DAYS));
        String id = candidate.getId();

        assertTrue(manager().removeIfStillTrashed(candidate, cutoff()));
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);

        assertNull(manager().getWeblogEntry(id));
    }
}

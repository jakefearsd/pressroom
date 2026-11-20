package com.wikantik.pressroom.business.jpa;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.EntryCacheInvalidator;
import com.wikantik.pressroom.business.EntryChangeListener;
import com.wikantik.pressroom.business.EntryChanged;
import com.wikantik.pressroom.business.EntryIndexer;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.search.IndexManager;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogEntry.PubStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The entry manager publishes one {@link EntryChanged} per committed write
 * (save, trash, restore, removal) and nothing for a write that never commits.
 * Against the real database; the manager under test is a second instance on
 * the suite's persistence strategy, carrying recording listeners.
 */
class EntryChangePublicationTest {

    private User user;
    private Weblog weblog;
    private final List<EntryChanged> seen = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.setupWeblogger();
        user = TestUtils.setupUser("entrychangeuser");
        weblog = TestUtils.setupWeblog("entrychangeblog", user);
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.endSession(false);
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    private static JPAPersistenceStrategy strategy() throws Exception {
        Field f = JPAWeblogEntryManagerImpl.class.getDeclaredField("strategy");
        f.setAccessible(true);
        return (JPAPersistenceStrategy) f.get(TestUtils.weblogger().getWeblogEntryManager());
    }

    private JPAWeblogEntryManagerImpl manager(EntryChangeListener... listeners) throws Exception {
        return new JPAWeblogEntryManagerImpl(TestUtils.weblogger(), strategy(), List.of(listeners));
    }

    private WeblogEntry stored(String anchor, PubStatus status) throws Exception {
        WeblogEntry e = TestUtils.setupWeblogEntry(anchor, weblog, user, status);
        TestUtils.endSession(true);
        return TestUtils.weblogger().getWeblogEntryManager().getWeblogEntry(e.getId());
    }

    @Test
    void aSavePublishesOnceAndOnlyAfterTheCommit() throws Exception {
        JPAWeblogEntryManagerImpl mgr = manager(seen::add);
        WeblogEntry entry = stored("published-once", PubStatus.PUBLISHED);

        entry.setTitle("edited");
        mgr.saveWeblogEntry(entry);
        assertTrue(seen.isEmpty(), "nothing is published before the unit of work commits");

        TestUtils.weblogger().flush();
        assertEquals(1, seen.size());
        EntryChanged change = seen.get(0);
        assertEquals(PubStatus.PUBLISHED, change.previousStatus());
        assertEquals(PubStatus.PUBLISHED, change.status());
        assertTrue(change.isPublished());
        assertFalse(change.leftPublished());
    }

    @Test
    void aSaveThatNeverCommitsPublishesNothing() throws Exception {
        JPAWeblogEntryManagerImpl mgr = manager(seen::add);
        WeblogEntry entry = stored("rolled-back", PubStatus.PUBLISHED);

        entry.setStatus(PubStatus.DRAFT);
        mgr.saveWeblogEntry(entry);
        TestUtils.weblogger().release();
        TestUtils.weblogger().flush();

        assertTrue(seen.isEmpty());
    }

    @Test
    void unpublishingReportsThatTheEntryLeftPublished() throws Exception {
        JPAWeblogEntryManagerImpl mgr = manager(seen::add);
        WeblogEntry entry = stored("unpublished", PubStatus.PUBLISHED);

        entry.setStatus(PubStatus.DRAFT);
        mgr.saveWeblogEntry(entry);
        TestUtils.weblogger().flush();

        assertEquals(1, seen.size());
        assertTrue(seen.get(0).leftPublished());
    }

    @Test
    void trashThenRestoreEachPublishOnce() throws Exception {
        JPAWeblogEntryManagerImpl mgr = manager(seen::add);
        WeblogEntry entry = stored("trash-restore", PubStatus.PUBLISHED);

        mgr.trashWeblogEntry(entry);
        TestUtils.weblogger().flush();
        assertEquals(1, seen.size());
        assertEquals(PubStatus.TRASHED, seen.get(0).status());
        assertTrue(seen.get(0).leftPublished());

        mgr.restoreWeblogEntry(entry);
        TestUtils.weblogger().flush();
        assertEquals(2, seen.size());
        assertEquals(PubStatus.TRASHED, seen.get(1).previousStatus());
        assertEquals(PubStatus.DRAFT, seen.get(1).status());
        assertFalse(seen.get(1).leftPublished());
    }

    @Test
    void aRemovalPublishesARemovedChange() throws Exception {
        JPAWeblogEntryManagerImpl mgr = manager(seen::add);
        WeblogEntry entry = stored("removed", PubStatus.DRAFT);

        mgr.removeWeblogEntry(entry);
        TestUtils.weblogger().flush();

        assertEquals(1, seen.size());
        assertTrue(seen.get(0).removed());
        assertNull(seen.get(0).status());
        assertEquals(PubStatus.DRAFT, seen.get(0).previousStatus());
    }

    @Test
    void aListenerThatFailsDoesNotStopTheOthers() throws Exception {
        JPAWeblogEntryManagerImpl mgr = manager(change -> {
            throw new WebloggerException("index down");
        }, seen::add);
        WeblogEntry entry = stored("listener-fails", PubStatus.PUBLISHED);

        entry.setTitle("again");
        mgr.saveWeblogEntry(entry);
        TestUtils.weblogger().flush();

        assertEquals(1, seen.size());
    }

    /** One save is one index operation, never two. */
    @Test
    void oneSaveIsIndexedOnce() throws Exception {
        IndexManager index = mock(IndexManager.class);
        Weblogger weblogger = mock(Weblogger.class);
        when(weblogger.getIndexManager()).thenReturn(index);
        JPAWeblogEntryManagerImpl mgr = manager(new EntryIndexer(weblogger));
        WeblogEntry entry = stored("indexed-once", PubStatus.PUBLISHED);

        entry.setTitle("one more edit");
        mgr.saveWeblogEntry(entry);
        TestUtils.weblogger().flush();

        verify(index, times(1)).addEntryReIndexOperation(entry);
        verify(index, never()).removeEntryIndexOperation(any());
    }

    /** The suite's real manager carries the two subscribers the bean config registers. */
    @Test
    void theWiredManagerCarriesTheIndexerAndTheCacheInvalidator() throws Exception {
        Field f = JPAWeblogEntryManagerImpl.class.getDeclaredField("listeners");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<EntryChangeListener> wired =
                (List<EntryChangeListener>) f.get(TestUtils.weblogger().getWeblogEntryManager());
        assertTrue(wired.stream().anyMatch(EntryIndexer.class::isInstance));
        assertTrue(wired.stream().anyMatch(EntryCacheInvalidator.class::isInstance));
    }
}

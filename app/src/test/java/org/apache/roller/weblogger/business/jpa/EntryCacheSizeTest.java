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
package org.apache.roller.weblogger.business.jpa;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import org.apache.roller.weblogger.TestUtils;
import org.apache.roller.weblogger.business.WeblogEntryManager;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogCategory;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.apache.roller.weblogger.pojos.WeblogEntry.PubStatus;
import org.eclipse.persistence.descriptors.ClassDescriptor;
import org.eclipse.persistence.internal.databaseaccess.DatabaseCall;
import org.eclipse.persistence.internal.identitymaps.SoftCacheWeakIdentityMap;
import org.eclipse.persistence.jpa.JpaHelper;
import org.eclipse.persistence.sessions.SessionEvent;
import org.eclipse.persistence.sessions.SessionEventAdapter;
import org.eclipse.persistence.sessions.server.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The size of EclipseLink's shared cache for entries, and that a bigger one
 * is never stale.
 *
 * <p>A permalink resolves its anchor through {@code
 * JPAWeblogEntryManagerImpl}'s anchor-to-id map and then finds the entry by
 * id, which costs no SQL while the entry is in the shared cache. Until
 * 2026-10-22 that cache was EclipseLink's default for every entity: a
 * soft-weak identity map of 100. Only the 100 most recently cached entries
 * were held softly; the rest were weak, so every garbage collection dropped
 * them and the next permalink of each read its row again ({@code SELECT ...
 * FROM weblogentry WHERE (ID = ?)}). A profiling run over 10 weblogs of
 * ~3,000 entries made that query 207,012 times for 207,005 permalinks.
 *
 * <p>The freshness tests are characterisation, passing with the old size too
 * (the anchor rename excepted, see there): a cache that keeps entries longer
 * must still show every edit, unpublish, delete and new entry at once, and
 * never one that rolled back.
 */
class EntryCacheSizeTest {

    private static final String HANDLE = "entrycachesizeblog";

    /** Comfortably past the old 100, well inside the new size. */
    private static final int HOT = 300;

    /** A by-id read of one entry: what em.find runs on a shared-cache miss. */
    private static final Pattern BY_ID = Pattern.compile(
            "(?is)^\\s*SELECT\\b.*\\bFROM\\s+weblogentry\\s+WHERE\\s+\\(\\s*ID\\s*=\\s*\\?\\s*\\)\\s*$");

    private User user;
    private Weblog weblog;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.setupWeblogger();
        user = TestUtils.setupUser("entrycachesizeuser");
        weblog = TestUtils.setupWeblog(HANDLE, user);
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (weblog != null) {
            TestUtils.teardownWeblog(weblog.getId());
        }
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    // ------------------------------------------------------------ the size

    @Test
    void aHotSetOfEntriesLargerThanOneHundredSurvivesAGarbageCollection() throws Exception {
        List<String> anchors = createEntries(HOT);
        for (String anchor : anchors) {
            assertNotNull(byAnchor(anchor)); // as a permalink: anchor map, then find
        }
        TestUtils.endSession(false);

        collectGarbage();

        AtomicInteger reads = new AtomicInteger();
        try (ByIdCounter _ = new ByIdCounter(reads)) {
            for (String anchor : anchors) {
                assertNotNull(byAnchor(anchor), anchor);
                TestUtils.endSession(false); // one request each
            }
        }
        assertEquals(0, reads.get(), "permalinks of " + HOT + " recently read entries re-read rows");
    }

    @Test
    void entriesHaveABoundedSoftCacheOfFiveThousand() throws Exception {
        ClassDescriptor entries = server().getDescriptor(WeblogEntry.class);
        assertEquals(SoftCacheWeakIdentityMap.class, entries.getIdentityMapClass());
        assertEquals(5000, entries.getIdentityMapSize());
    }

    @Test
    void theSmallHotEntitiesKeepAThousand() throws Exception {
        for (Class<?> type : List.of(Weblog.class, User.class, WeblogCategory.class)) {
            ClassDescriptor descriptor = server().getDescriptor(type);
            assertEquals(SoftCacheWeakIdentityMap.class, descriptor.getIdentityMapClass(), type.getName());
            assertEquals(1000, descriptor.getIdentityMapSize(), type.getName());
        }
    }

    // ------------------------------------------------------- never stale

    @Test
    void anEditShowsOnTheNextLookup() throws Exception {
        String anchor = createEntries(1).getFirst();
        assertEquals("text of " + anchor, byAnchor(anchor).getText());
        TestUtils.endSession(false);

        WeblogEntry managed = byAnchor(anchor);
        managed.setText("edited");
        entries().saveWeblogEntry(managed);
        TestUtils.endSession(true);

        assertEquals("edited", byAnchor(anchor).getText());
    }

    @Test
    void anEditThatRolledBackNeverShows() throws Exception {
        String anchor = createEntries(1).getFirst();
        byAnchor(anchor);
        TestUtils.endSession(false);

        WeblogEntry managed = byAnchor(anchor);
        managed.setText("never committed");
        entries().saveWeblogEntry(managed);
        TestUtils.endSession(false);

        assertEquals("text of " + anchor, byAnchor(anchor).getText());
    }

    @Test
    void anUnpublishShowsOnTheNextLookup() throws Exception {
        String anchor = createEntries(1).getFirst();
        assertEquals(PubStatus.PUBLISHED, byAnchor(anchor).getStatus());
        TestUtils.endSession(false);

        WeblogEntry managed = byAnchor(anchor);
        managed.setStatus(PubStatus.DRAFT);
        entries().saveWeblogEntry(managed);
        TestUtils.endSession(true);

        assertEquals(PubStatus.DRAFT, byAnchor(anchor).getStatus());
    }

    @Test
    void aDeleteShowsOnTheNextLookup() throws Exception {
        String anchor = createEntries(1).getFirst();
        String id = byAnchor(anchor).getId();
        TestUtils.endSession(false);

        entries().removeWeblogEntry(entries().getWeblogEntry(id));
        TestUtils.endSession(true);

        assertNull(byAnchor(anchor));
        assertNull(entries().getWeblogEntry(id));
    }

    @Test
    void aNewEntryShowsOnTheNextLookup() throws Exception {
        createEntries(1);
        assertNull(byAnchor("not-yet"));
        TestUtils.endSession(false);

        WeblogEntry entry = newEntry("not-yet");
        entries().saveWeblogEntry(entry);
        TestUtils.endSession(true);

        assertNotNull(byAnchor("not-yet"));
    }

    /**
     * Red before the fix, and not because of the cache size: the manager's
     * anchor-to-id map kept the old anchor pointing at the entry, so the old
     * permalink kept serving it after the rename. No UI or API path renames
     * an anchor today, but {@code saveWeblogEntry} accepts one ({@code
     * claimAnchor} checks the new anchor).
     */
    @Test
    void anAnchorRenameShowsOnTheNextLookupAndFreesTheOldAnchor() throws Exception {
        String anchor = createEntries(1).getFirst();
        String id = byAnchor(anchor).getId();
        TestUtils.endSession(false);

        WeblogEntry managed = entries().getWeblogEntry(id);
        managed.setAnchor("renamed");
        entries().saveWeblogEntry(managed);
        TestUtils.endSession(true);

        assertEquals(id, byAnchor("renamed").getId());
        assertNull(byAnchor(anchor), "the old anchor no longer resolves");
    }

    @Test
    void aDeletedWeblogsEntriesAreGoneFromTheCache() throws Exception {
        String anchor = createEntries(1).getFirst();
        String id = byAnchor(anchor).getId();
        TestUtils.endSession(false);

        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.endSession(true);
        weblog = null;

        assertNull(entries().getWeblogEntry(id));
    }

    // ------------------------------------------------------------- helpers

    private List<String> createEntries(int count) throws Exception {
        List<String> anchors = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String anchor = "cached-" + i;
            entries().saveWeblogEntry(newEntry(anchor));
            anchors.add(anchor);
        }
        TestUtils.endSession(true);
        return anchors;
    }

    private WeblogEntry newEntry(String anchor) throws Exception {
        Weblog managed = TestUtils.getManagedWebsite(weblog);
        WeblogEntry entry = new WeblogEntry();
        entry.setTitle(anchor);
        entry.setText("text of " + anchor);
        entry.setAnchor(anchor);
        entry.setPubTime(new java.sql.Timestamp(System.currentTimeMillis()));
        entry.setUpdateTime(new java.sql.Timestamp(System.currentTimeMillis()));
        entry.setStatus(PubStatus.PUBLISHED);
        entry.setWebsite(managed);
        entry.setCreatorUserName(user.getUserName());
        entry.setCategory(managed.getWeblogCategories().iterator().next());
        return entry;
    }

    private WeblogEntry byAnchor(String anchor) throws Exception {
        return entries().getWeblogEntryByAnchor(TestUtils.getManagedWebsite(weblog), anchor);
    }

    private static WeblogEntryManager entries() {
        return TestUtils.weblogger().getWeblogEntryManager();
    }

    /** Until a weakly held sentinel is gone, so the weak part of the cache is too. */
    private static void collectGarbage() throws InterruptedException {
        WeakReference<Object> sentinel = new WeakReference<>(new Object());
        for (int i = 0; i < 50 && sentinel.get() != null; i++) {
            System.gc();
            Thread.sleep(20);
        }
        assertNull(sentinel.get(), "a garbage collection ran");
    }

    private static Server server() throws Exception {
        Field f = entries().getClass().getDeclaredField("strategy");
        f.setAccessible(true);
        JPAPersistenceStrategy strategy = (JPAPersistenceStrategy) f.get(entries());
        return JpaHelper.getServerSession(strategy.getEntityManager(false).getEntityManagerFactory());
    }

    /** Counts by-id reads of an entry on every session of the persistence unit. */
    private static final class ByIdCounter extends SessionEventAdapter implements AutoCloseable {
        private final AtomicInteger reads;
        private final Server server;

        ByIdCounter(AtomicInteger reads) throws Exception {
            this.reads = reads;
            this.server = server();
            server.getEventManager().addListener(this);
        }

        @Override
        public void preExecuteCall(SessionEvent event) {
            if (event.getCall() instanceof DatabaseCall call && BY_ID.matcher(call.getSQLString()).find()) {
                reads.incrementAndGet();
            }
        }

        @Override
        public void close() {
            server.getEventManager().removeListener(this);
        }
    }
}

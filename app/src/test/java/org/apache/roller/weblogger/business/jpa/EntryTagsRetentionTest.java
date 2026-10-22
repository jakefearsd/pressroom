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

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import org.apache.roller.weblogger.TestUtils;
import org.apache.roller.weblogger.business.WeblogEntryManager;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.apache.roller.weblogger.pojos.WeblogEntrySearchCriteria;
import org.eclipse.persistence.descriptors.ClassDescriptor;
import org.eclipse.persistence.indirection.IndirectContainer;
import org.eclipse.persistence.internal.databaseaccess.DatabaseCall;
import org.eclipse.persistence.internal.sessions.AbstractSession;
import org.eclipse.persistence.jpa.JpaHelper;
import org.eclipse.persistence.mappings.DatabaseMapping;
import org.eclipse.persistence.sessions.SessionEvent;
import org.eclipse.persistence.sessions.SessionEventAdapter;
import org.eclipse.persistence.sessions.server.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What an entry left in EclipseLink's shared cache by a list query keeps
 * alive through its (lazy) tags, and what its tags cost a later permalink.
 *
 * <p>An IN batch fetch declared on the mapping gave every entry built by any
 * query a value holder pointing back at that query, and the query holds every
 * row it read, text included, until the entry's tags are touched. A search
 * reindex reads every published entry and never touches a tag, so each of
 * the ~100 entries the cache keeps afterwards pinned the whole site's rows;
 * and a permalink of one of them read the tags of the entire list in one IN.
 * Batch fetching is now asked for only by bounded (paged) lists, which load
 * the tags at once, so no holder is left referring to the query.
 *
 * <p>Retention is measured by walking the object graph from the cached
 * entry's tags collection and counting the entry texts reachable from it
 * (each is unique and {@value #TEXT_LENGTH} characters long). Its own row may
 * be there (a lazy holder keeps the row it was built from); no other may.
 */
class EntryTagsRetentionTest {

    private static final int ENTRIES = 60;
    private static final int TEXT_LENGTH = 20_000;

    private static final Pattern TAG_READ = Pattern.compile(
            "(?is)^\\s*SELECT\\b.*\\bFROM\\s+roller_weblogentrytag\\b");
    private static final Pattern IN_LIST = Pattern.compile("(?i)\\bentryid\\s+IN\\s*\\(");

    private User user;
    private Weblog weblog;
    private final List<String> ids = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.setupWeblogger();
        user = TestUtils.setupUser("tagretainuser");
        weblog = TestUtils.setupWeblog("tagretainblog", user);
        TestUtils.endSession(true);
        WeblogEntryManager mgr = entries();
        for (int i = 0; i < ENTRIES; i++) {
            WeblogEntry entry = TestUtils.setupWeblogEntry("retain-" + i,
                    TestUtils.getManagedWebsite(weblog), TestUtils.getManagedUser(user));
            String prefix = "entry " + i + " ";
            entry.setText(prefix + "x".repeat(TEXT_LENGTH - prefix.length()));
            entry.setTagsAsString("t" + i);
            mgr.saveWeblogEntry(entry);
            ids.add(entry.getId());
            TestUtils.endSession(true);
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    // ------------------------------------------------------------- retention

    @Test
    void anEntryCachedByAnUnboundedListKeepsNoOtherEntrysRow() throws Exception {
        loadList(0); // a reindex, a weblog delete: every entry, tags never touched
        int texts = entryTextsReachableFromTags(ids.get(ENTRIES / 2));
        assertTrue(texts <= 1, "the cached entry's lazy tags keep its own row at most, not the list's: "
                + texts + " entry texts reachable");
    }

    @Test
    void anEntryCachedByAPagedListKeepsNoOtherEntrysRow() throws Exception {
        loadList(ENTRIES);
        int texts = entryTextsReachableFromTags(ids.get(ENTRIES / 2));
        assertTrue(texts <= 1, "the cached entry's tags keep no other entry's row: "
                + texts + " entry texts reachable");
    }

    // -------------------------------------------------------------- permalink

    @Test
    void aPermalinkAfterAnUnboundedListReadsOnlyItsOwnTags() throws Exception {
        loadList(0);
        List<String> reads = tagReadsWhile(() -> {
            WeblogEntry entry = entries().getWeblogEntry(ids.get(ENTRIES / 2));
            assertEquals("t" + ENTRIES / 2, entry.getTagsAsString());
        });
        assertEquals(1, reads.size(), reads.toString());
        assertFalse(IN_LIST.matcher(reads.get(0)).find(),
                "one entry's tags, not the whole list's: " + reads.get(0));
    }

    @Test
    void aPermalinkAfterAPagedListReadsNoTags() throws Exception {
        loadList(ENTRIES);
        List<String> reads = tagReadsWhile(() -> {
            WeblogEntry entry = entries().getWeblogEntry(ids.get(ENTRIES / 2));
            assertEquals("t" + ENTRIES / 2, entry.getTagsAsString());
        });
        assertEquals(List.of(), reads, "the paged list loaded them already");
    }

    // ---------------------------------------------------------------- helpers

    private static WeblogEntryManager entries() {
        return TestUtils.weblogger().getWeblogEntryManager();
    }

    /** Empties the entry cache, then reads the weblog's entries (all of them when max is 0). */
    private void loadList(int max) throws Exception {
        server().getIdentityMapAccessor().initializeIdentityMap(WeblogEntry.class);
        WeblogEntrySearchCriteria criteria = new WeblogEntrySearchCriteria();
        criteria.setWeblog(TestUtils.getManagedWebsite(weblog));
        if (max > 0) {
            criteria.setMaxResults(max);
        }
        assertEquals(ENTRIES, entries().getWeblogEntries(criteria).size());
        TestUtils.endSession(false);
    }

    private interface Body {
        void run() throws Exception;
    }

    private static List<String> tagReadsWhile(Body body) throws Exception {
        Server server = server();
        TestUtils.endSession(false); // the body's sessions are created after the listener
        List<String> reads = new ArrayList<>();
        SessionEventAdapter counter = new SessionEventAdapter() {
            @Override
            public void preExecuteCall(SessionEvent event) {
                if (event.getCall() instanceof DatabaseCall call && TAG_READ.matcher(call.getSQLString()).find()) {
                    synchronized (reads) {
                        reads.add(call.getSQLString());
                    }
                }
            }
        };
        server.getEventManager().addListener(counter);
        try {
            body.run();
        } finally {
            server.getEventManager().removeListener(counter);
            TestUtils.endSession(false);
        }
        return reads;
    }

    private static Server server() throws Exception {
        Field f = entries().getClass().getDeclaredField("strategy");
        f.setAccessible(true);
        JPAPersistenceStrategy strategy = (JPAPersistenceStrategy) f.get(entries());
        return JpaHelper.getServerSession(strategy.getEntityManager(false).getEntityManagerFactory());
    }

    /**
     * How many entry texts the cached original's tags collection reaches.
     * Walks fields, arrays, collections and maps, without loading anything
     * (a lazy collection is walked by its fields, never iterated), and stops
     * at sessions, mapping metadata and other entities.
     */
    private int entryTextsReachableFromTags(String id) throws Exception {
        WeblogEntry original = (WeblogEntry) server().getIdentityMapAccessor()
                .getFromIdentityMap(id, WeblogEntry.class);
        assertNotNull(original, "the entry is in the shared cache");
        Object tags = original.getTags();
        assertTrue(tags instanceof IndirectContainer<?>, tags.getClass().getName());

        AtomicInteger texts = new AtomicInteger();
        Map<Object, Boolean> seen = new IdentityHashMap<>();
        Deque<Object> todo = new ArrayDeque<>();
        todo.push(tags);
        while (!todo.isEmpty()) {
            Object o = todo.pop();
            if (seen.put(o, Boolean.TRUE) != null || isBoundary(o, tags)) {
                continue;
            }
            assertTrue(seen.size() < 2_000_000, "the walk is bounded");
            if (o instanceof String s) {
                if (s.length() == TEXT_LENGTH && s.startsWith("entry ")) {
                    texts.incrementAndGet();
                }
            } else if (o.getClass().isArray()) {
                if (!o.getClass().getComponentType().isPrimitive()) {
                    for (int i = 0; i < Array.getLength(o); i++) {
                        push(todo, Array.get(o, i));
                    }
                }
            } else if (o instanceof IndirectContainer<?>) {
                pushFields(o, todo); // never iterate: that would load it
            } else if (o instanceof Collection<?> c && o.getClass().getName().startsWith("java.")) {
                c.forEach(e -> push(todo, e));
            } else if (o instanceof Map<?, ?> m && o.getClass().getName().startsWith("java.")) {
                m.forEach((k, v) -> {
                    push(todo, k);
                    push(todo, v);
                });
            } else if (!o.getClass().getName().startsWith("java.")) {
                pushFields(o, todo);
            }
        }
        return texts.get();
    }

    private static boolean isBoundary(Object o, Object root) {
        return o != root && (o instanceof AbstractSession
                || o instanceof ClassDescriptor
                || o instanceof DatabaseMapping
                || o instanceof Class<?>
                || o instanceof ClassLoader
                || o instanceof Thread
                || o instanceof java.lang.ref.Reference<?>
                || o.getClass().getName().startsWith("org.apache.roller.weblogger.pojos."));
    }

    private static void push(Deque<Object> todo, Object o) {
        if (o != null) {
            todo.push(o);
        }
    }

    private static void pushFields(Object o, Deque<Object> todo) {
        for (Class<?> c = o.getClass(); c != null && !c.getName().startsWith("java."); c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    push(todo, field.get(o));
                } catch (ReflectiveOperationException | RuntimeException e) {
                    // an inaccessible field is skipped: the walk may under-count, never over-count
                }
            }
        }
    }
}

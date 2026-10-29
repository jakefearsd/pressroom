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
package com.wikantik.pressroom.business;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.persistence.EntityManager;

import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.business.jpa.JPAPersistenceStrategy;
import com.wikantik.pressroom.pojos.ReaderComment;
import com.wikantik.pressroom.pojos.ReaderComment.Status;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;
import org.eclipse.persistence.jpa.JpaEntityManager;
import org.eclipse.persistence.sessions.Session;
import org.eclipse.persistence.sessions.SessionEvent;
import org.eclipse.persistence.sessions.SessionEventAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The reader-comment store: round trip, filters, counts, trust, and the one-query entry read. */
class CommentManagerTest {

    private User owner;
    private User reader;
    private User other;
    private Weblog a;
    private Weblog b;
    private WeblogEntry ea;
    private WeblogEntry ea2;
    private WeblogEntry eb;
    private long clock = 1_700_000_000_000L;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.setupWeblogger();
        owner = TestUtils.setupUser("cmowner");
        reader = TestUtils.setupUser("cmreader");
        other = TestUtils.setupUser("cmother");
        a = TestUtils.setupWeblog("cmbloga", owner);
        b = TestUtils.setupWeblog("cmblogb", owner);
        TestUtils.endSession(true);
        ea = TestUtils.setupWeblogEntry("cmea", TestUtils.getManagedWebsite(a), TestUtils.getManagedUser(owner));
        ea2 = TestUtils.setupWeblogEntry("cmea2", TestUtils.getManagedWebsite(a), TestUtils.getManagedUser(owner));
        eb = TestUtils.setupWeblogEntry("cmeb", TestUtils.getManagedWebsite(b), TestUtils.getManagedUser(owner));
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        // Comments go with their entries, then the weblogs and users (DB cascade).
        TestUtils.teardownWeblog(a.getId());
        TestUtils.teardownWeblog(b.getId());
        TestUtils.teardownUser(owner.getUserName());
        TestUtils.teardownUser(reader.getUserName());
        if (TestUtils.weblogger().getUserManager().getUserByUserName(other.getUserName(), null) != null) {
            TestUtils.teardownUser(other.getUserName());
        }
        TestUtils.endSession(true);
    }

    private CommentManager mgr() throws Exception {
        return TestUtils.weblogger().getCommentManager();
    }

    private ReaderComment comment(WeblogEntry entry, User author, Status status, ReaderComment replyTo)
            throws Exception {
        ReaderComment c = new ReaderComment();
        c.setEntry(TestUtils.getManagedWeblogEntry(entry));
        c.setAuthor(TestUtils.getManagedUser(author));
        c.setReplyTo(replyTo);
        c.setBody("body " + clock);
        c.setStatus(status);
        c.setAuthorIsOwner(replyTo != null);
        c.setCreated(new Date(clock));
        c.setStatusChanged(new Date(clock));
        clock += 1000;
        mgr().saveComment(c);
        return c;
    }

    @Test
    void saveGetRemoveRoundTrip() throws Exception {
        ReaderComment c = comment(ea, reader, Status.PENDING, null);
        ReaderComment r = comment(ea, owner, Status.PUBLISHED, c);
        TestUtils.endSession(true);

        ReaderComment got = mgr().getComment(c.getId());
        assertNotNull(got);
        assertEquals("body " + (clock - 2000), got.getBody());
        assertEquals(Status.PENDING, got.getStatus());
        assertFalse(got.isAuthorIsOwner());
        assertEquals(reader.getId(), got.getAuthor().getId());
        assertEquals(ea.getId(), got.getEntry().getId());
        assertNull(got.getReplyTo());
        assertEquals(r.getId(), got.getReply().getId());
        assertTrue(mgr().getComment(r.getId()).isAuthorIsOwner());
        assertNull(mgr().getComment("no-such-id"));

        got.setStatus(Status.HIDDEN);
        mgr().saveComment(got);
        TestUtils.endSession(true);
        assertEquals(Status.HIDDEN, mgr().getComment(c.getId()).getStatus());

        mgr().removeComment(mgr().getComment(c.getId()));
        TestUtils.endSession(true);
        assertNull(mgr().getComment(c.getId()));
        assertNull(mgr().getComment(r.getId()), "the reply goes with its parent");
    }

    @Test
    void publishedForEntryIsOneQuery() throws Exception {
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            User author = (i % 2 == 0) ? reader : other;
            ReaderComment c = comment(ea, author, Status.PUBLISHED, null);
            expected.add(c.getId());
            if (i < 20) {
                comment(ea, owner, Status.PUBLISHED, c);
            }
        }
        // Not to be listed: held, hidden, another entry, an unpublished reply.
        comment(ea, reader, Status.PENDING, null);
        comment(ea, reader, Status.HIDDEN, null);
        comment(ea2, reader, Status.PUBLISHED, null);
        ReaderComment withHiddenReply = comment(ea, reader, Status.PUBLISHED, null);
        expected.add(withHiddenReply.getId());
        comment(ea, owner, Status.HIDDEN, withHiddenReply);
        TestUtils.endSession(true);

        WeblogEntry entry = TestUtils.getManagedWeblogEntry(ea);
        EntityManager em = strategy().getEntityManager(false);
        Session session = em.unwrap(JpaEntityManager.class).getActiveSession();
        AtomicInteger queries = new AtomicInteger();
        SessionEventAdapter counter = new SessionEventAdapter() {
            @Override
            public void preExecuteQuery(SessionEvent event) {
                queries.incrementAndGet();
            }
        };
        session.getEventManager().addListener(counter);
        List<ReaderComment> list;
        try {
            list = mgr().getPublishedForEntry(entry);
            // Touch everything a renderer touches: no further query may fire.
            for (ReaderComment c : list) {
                assertNotNull(c.getAuthor().getScreenName());
                if (c.getReply() != null) {
                    assertNotNull(c.getReply().getAuthor().getScreenName());
                }
            }
        } finally {
            session.getEventManager().removeListener(counter);
        }
        assertEquals(1, queries.get(), "the entry's comments, authors and replies load in one query");

        assertEquals(expected, list.stream().map(ReaderComment::getId).toList(), "oldest first, top-level only");
        for (int i = 0; i < 20; i++) {
            assertNotNull(list.get(i).getReply());
            assertEquals(Status.PUBLISHED, list.get(i).getReply().getStatus());
        }
        assertNull(list.get(20).getReply());
        assertEquals(Status.HIDDEN, list.get(40).getReply().getStatus(),
                "getReply() is the true inverse: the reply in any status, the consumer filters");
    }

    @Test
    void getReplyStaysTheTrueInverseAfterAStatusChangeAndARender() throws Exception {
        ReaderComment parent = comment(ea, reader, Status.PUBLISHED, null);
        ReaderComment reply = comment(ea, owner, Status.PUBLISHED, parent);
        TestUtils.endSession(true);

        ReaderComment r = mgr().getComment(reply.getId());
        r.setStatus(Status.HIDDEN);
        mgr().saveComment(r);
        TestUtils.endSession(true);

        mgr().getPublishedForEntry(TestUtils.getManagedWeblogEntry(ea));
        TestUtils.endSession(true);

        ReaderComment again = mgr().getComment(parent.getId());
        assertNotNull(again.getReply());
        assertEquals(reply.getId(), again.getReply().getId());
        assertEquals(Status.HIDDEN, again.getReply().getStatus());
    }

    @Test
    void removingAParentTakesItsReplyOutOfEveryView() throws Exception {
        ReaderComment parent = comment(ea, reader, Status.PUBLISHED, null);
        ReaderComment reply = comment(ea, owner, Status.HIDDEN, parent);
        TestUtils.endSession(true);
        mgr().getPublishedForEntry(TestUtils.getManagedWeblogEntry(ea));   // render fills the cache

        mgr().removeComment(mgr().getComment(parent.getId()));
        TestUtils.endSession(true);

        assertNull(mgr().getComment(parent.getId()));
        assertNull(mgr().getComment(reply.getId()), "no ghost reply");
        assertTrue(mgr().getCommentsByAuthor(TestUtils.getManagedUser(owner)).isEmpty());
    }

    @Test
    void removingAnEntryRemovesItsComments() throws Exception {
        ReaderComment parent = comment(ea, reader, Status.PUBLISHED, null);
        ReaderComment reply = comment(ea, owner, Status.PUBLISHED, parent);
        ReaderComment elsewhere = comment(eb, reader, Status.PUBLISHED, null);
        TestUtils.endSession(true);
        mgr().getPublishedForEntry(TestUtils.getManagedWeblogEntry(ea));

        TestUtils.weblogger().getWeblogEntryManager()
                .removeWeblogEntry(TestUtils.getManagedWeblogEntry(ea));
        TestUtils.endSession(true);

        assertNull(mgr().getComment(parent.getId()));
        assertNull(mgr().getComment(reply.getId()));
        assertEquals(List.of(elsewhere.getId()),
                ids(mgr().getCommentsByAuthor(TestUtils.getManagedUser(reader))));
    }

    @Test
    void removingAUserRemovesTheirCommentsAndTheRepliesToThem() throws Exception {
        ReaderComment mine = comment(ea, other, Status.PUBLISHED, null);
        ReaderComment ownerReply = comment(ea, owner, Status.PUBLISHED, mine);
        ReaderComment kept = comment(ea, reader, Status.PUBLISHED, null);
        TestUtils.endSession(true);
        mgr().getPublishedForEntry(TestUtils.getManagedWeblogEntry(ea));

        TestUtils.weblogger().getUserManager().removeUser(TestUtils.getManagedUser(other));
        TestUtils.endSession(true);

        assertNull(mgr().getComment(mine.getId()));
        assertNull(mgr().getComment(ownerReply.getId()));
        assertNotNull(mgr().getComment(kept.getId()));
        assertEquals(List.of(kept.getId()), ids(mgr().getComments(
                TestUtils.getManagedWebsite(a), null, null, 0, 50)));
        assertTrue(mgr().getCommentsByAuthor(TestUtils.getManagedUser(owner)).isEmpty());
    }

    private JPAPersistenceStrategy strategy() throws Exception {
        Field f = mgr().getClass().getDeclaredField("strategy");
        f.setAccessible(true);
        return (JPAPersistenceStrategy) f.get(mgr());
    }

    @Test
    void getCommentsFiltersByStatusAndEntryAndPages() throws Exception {
        ReaderComment p1 = comment(ea, reader, Status.PENDING, null);
        ReaderComment u1 = comment(ea, reader, Status.PUBLISHED, null);
        ReaderComment u2 = comment(ea2, other, Status.PUBLISHED, null);
        ReaderComment h1 = comment(ea, reader, Status.HIDDEN, null);
        comment(eb, reader, Status.PUBLISHED, null);
        TestUtils.endSession(true);

        WeblogEntry entry = TestUtils.getManagedWeblogEntry(ea);
        Weblog wa = TestUtils.getManagedWebsite(a);
        assertEquals(List.of(h1.getId(), u2.getId(), u1.getId(), p1.getId()), ids(mgr().getComments(wa, null, null, 0, 50)));
        assertEquals(List.of(u2.getId(), u1.getId()), ids(mgr().getComments(wa, Status.PUBLISHED, null, 0, 50)));
        assertEquals(List.of(h1.getId(), u1.getId(), p1.getId()), ids(mgr().getComments(wa, null, entry, 0, 50)));
        assertEquals(List.of(u1.getId()), ids(mgr().getComments(wa, Status.PUBLISHED, entry, 0, 50)));
        assertEquals(List.of(u2.getId(), u1.getId()), ids(mgr().getComments(wa, null, null, 1, 2)));
    }

    private List<String> ids(List<ReaderComment> list) {
        return list.stream().map(ReaderComment::getId).toList();
    }

    @Test
    void countsPerWeblogAndEntry() throws Exception {
        comment(ea, reader, Status.PENDING, null);
        comment(ea, reader, Status.PENDING, null);
        comment(ea2, reader, Status.PUBLISHED, null);
        comment(eb, reader, Status.PENDING, null);
        TestUtils.endSession(true);

        Weblog wa = TestUtils.getManagedWebsite(a);
        WeblogEntry entry = TestUtils.getManagedWeblogEntry(ea);
        assertEquals(2, mgr().countComments(wa, Status.PENDING));
        assertEquals(1, mgr().countComments(wa, Status.PUBLISHED));
        assertEquals(3, mgr().countComments(wa, null));
        assertEquals(0, mgr().countComments(wa, Status.HIDDEN));
        assertEquals(2, mgr().countForEntry(entry, Status.PENDING));
        assertEquals(2, mgr().countForEntry(entry, null));
    }

    /**
     * M6: the moderation pager counts what its list shows. With an entry filter
     * the count is that entry's, replies included (the list shows them as rows),
     * in the status asked for (null = all).
     */
    @Test
    void theWeblogCountTakesTheListsEntryFilter() throws Exception {
        ReaderComment parent = comment(ea, reader, Status.PUBLISHED, null);
        comment(ea, owner, Status.PUBLISHED, parent);
        comment(ea, reader, Status.PENDING, null);
        comment(ea2, reader, Status.PUBLISHED, null);
        comment(eb, reader, Status.PUBLISHED, null);
        TestUtils.endSession(true);

        Weblog wa = TestUtils.getManagedWebsite(a);
        WeblogEntry entry = TestUtils.getManagedWeblogEntry(ea);
        assertEquals(2, mgr().countComments(wa, Status.PUBLISHED, entry));
        assertEquals(1, mgr().countComments(wa, Status.PENDING, entry));
        assertEquals(3, mgr().countComments(wa, null, entry));
        assertEquals(3, mgr().countComments(wa, Status.PUBLISHED, null), "no filter: the weblog's");
        assertEquals(mgr().getComments(wa, Status.PUBLISHED, entry, 0, 50).size(),
                mgr().countComments(wa, Status.PUBLISHED, entry), "the count is the list's length");
    }

    /**
     * M10: the editor's "N published" is the number the entry page's heading
     * shows, which counts top-level comments: an author's reply is not counted.
     */
    @Test
    void theEntryCountIsOfTopLevelCommentsOnly() throws Exception {
        ReaderComment parent = comment(ea, reader, Status.PUBLISHED, null);
        comment(ea, owner, Status.PUBLISHED, parent);
        comment(ea, reader, Status.PUBLISHED, null);
        TestUtils.endSession(true);

        WeblogEntry entry = TestUtils.getManagedWeblogEntry(ea);
        assertEquals(2, mgr().countForEntry(entry, Status.PUBLISHED));
        assertEquals(2, mgr().countForEntry(entry, null));
        assertEquals(mgr().getPublishedForEntry(entry).size(), mgr().countForEntry(entry, Status.PUBLISHED),
                "the editor's count is the page's");
    }

    @Test
    void pendingForEntryAndAuthor() throws Exception {
        ReaderComment mine = comment(ea, reader, Status.PENDING, null);
        comment(ea, reader, Status.PUBLISHED, null);
        comment(ea, other, Status.PENDING, null);
        comment(ea2, reader, Status.PENDING, null);
        TestUtils.endSession(true);

        assertEquals(List.of(mine.getId()), ids(mgr().getPendingForEntryAndAuthor(
                TestUtils.getManagedWeblogEntry(ea), TestUtils.getManagedUser(reader))));
    }

    /**
     * What the session answer hands the page script so it can offer Delete on
     * the caller's own comments (ruling R7: the cached page carries no
     * usernames, so the script matches these ids against id="comment-...").
     */
    @Test
    void publishedIdsForEntryAndAuthorAreTheirOwnPublishedCommentsAndReplies() throws Exception {
        ReaderComment mine = comment(ea, reader, Status.PUBLISHED, null);
        ReaderComment ownerReply = comment(ea, owner, Status.PUBLISHED, mine);
        comment(ea, reader, Status.PENDING, null);
        comment(ea, reader, Status.HIDDEN, null);
        comment(ea, other, Status.PUBLISHED, null);
        comment(ea2, reader, Status.PUBLISHED, null);
        ReaderComment mineLater = comment(ea, reader, Status.PUBLISHED, null);
        comment(ea, owner, Status.HIDDEN, mineLater);
        ReaderComment ownerTopLevel = comment(ea, owner, Status.PUBLISHED, null);
        TestUtils.endSession(true);

        WeblogEntry entry = TestUtils.getManagedWeblogEntry(ea);
        assertEquals(List.of(mine.getId(), mineLater.getId()),
                mgr().getPublishedIdsForEntryAndAuthor(entry, TestUtils.getManagedUser(reader)),
                "published only, this entry only, oldest first");
        assertEquals(List.of(ownerReply.getId(), ownerTopLevel.getId()),
                mgr().getPublishedIdsForEntryAndAuthor(entry, TestUtils.getManagedUser(owner)),
                "a published reply counts; a hidden one does not");
        assertEquals(List.of(), mgr().getPublishedIdsForEntryAndAuthor(
                TestUtils.getManagedWeblogEntry(eb), TestUtils.getManagedUser(reader)));
    }

    @Test
    void byAuthorAcrossWeblogs() throws Exception {
        ReaderComment c1 = comment(ea, reader, Status.PUBLISHED, null);
        comment(ea, other, Status.PUBLISHED, null);
        ReaderComment c2 = comment(eb, reader, Status.PENDING, null);
        TestUtils.endSession(true);

        assertEquals(List.of(c2.getId(), c1.getId()),
                ids(mgr().getCommentsByAuthor(TestUtils.getManagedUser(reader))));
    }

    @Test
    void trustNeedsAPublishedAndNoHidden() throws Exception {
        Weblog wa = TestUtils.getManagedWebsite(a);
        User u = TestUtils.getManagedUser(reader);
        assertFalse(mgr().isTrusted(u, wa));

        comment(ea, reader, Status.PENDING, null);
        TestUtils.endSession(true);
        assertFalse(mgr().isTrusted(TestUtils.getManagedUser(reader), TestUtils.getManagedWebsite(a)));

        comment(ea, reader, Status.PUBLISHED, null);
        TestUtils.endSession(true);
        assertTrue(mgr().isTrusted(TestUtils.getManagedUser(reader), TestUtils.getManagedWebsite(a)));

        comment(ea2, reader, Status.HIDDEN, null);
        TestUtils.endSession(true);
        assertFalse(mgr().isTrusted(TestUtils.getManagedUser(reader), TestUtils.getManagedWebsite(a)));

        comment(eb, other, Status.PUBLISHED, null);
        TestUtils.endSession(true);
        assertFalse(mgr().isTrusted(TestUtils.getManagedUser(other), TestUtils.getManagedWebsite(a)),
                "a published comment on another weblog earns no trust here");
        assertTrue(mgr().isTrusted(TestUtils.getManagedUser(other), TestUtils.getManagedWebsite(b)));
    }

    @Test
    void weblogsWithPublishedCommentsBy() throws Exception {
        comment(ea, reader, Status.PUBLISHED, null);
        comment(ea2, reader, Status.PUBLISHED, null);
        comment(eb, reader, Status.PENDING, null);
        comment(eb, other, Status.PUBLISHED, null);
        TestUtils.endSession(true);

        List<Weblog> weblogs = mgr().getWeblogsWithPublishedCommentsBy(TestUtils.getManagedUser(reader));
        assertEquals(List.of(a.getId()), weblogs.stream().map(Weblog::getId).toList());
    }

    @Test
    void theNewColumnsMapAndDefault() throws Exception {
        Weblog wa = TestUtils.getManagedWebsite(a);
        assertEquals(Boolean.FALSE, wa.getCommentsEnabled());
        assertEquals(Boolean.TRUE, wa.getCommentNotify());
        assertEquals(Boolean.FALSE, TestUtils.getManagedWeblogEntry(ea).getCommentsClosed());

        wa.setCommentsEnabled(true);
        wa.setCommentNotify(false);
        TestUtils.weblogger().getWeblogManager().saveWeblog(wa);
        WeblogEntry e = TestUtils.getManagedWeblogEntry(ea);
        e.setCommentsClosed(true);
        TestUtils.weblogger().getWeblogEntryManager().saveWeblogEntry(e);
        TestUtils.endSession(true);

        wa = TestUtils.getManagedWebsite(a);
        assertEquals(Boolean.TRUE, wa.getCommentsEnabled());
        assertEquals(Boolean.FALSE, wa.getCommentNotify());
        e = TestUtils.getManagedWeblogEntry(ea);
        assertEquals(Boolean.TRUE, e.getCommentsClosed());

        wa.setCommentsEnabled(false);
        wa.setCommentNotify(true);
        TestUtils.weblogger().getWeblogManager().saveWeblog(wa);
        e.setCommentsClosed(false);
        TestUtils.weblogger().getWeblogEntryManager().saveWeblogEntry(e);
        TestUtils.endSession(true);
        assertEquals(Boolean.FALSE, TestUtils.getManagedWebsite(a).getCommentsEnabled());
        assertEquals(Boolean.TRUE, TestUtils.getManagedWebsite(a).getCommentNotify());
        assertEquals(Boolean.FALSE, TestUtils.getManagedWeblogEntry(ea).getCommentsClosed());
    }
}

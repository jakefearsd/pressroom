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
package org.apache.roller.weblogger.business.comments;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.xml.parsers.DocumentBuilderFactory;

import org.apache.roller.weblogger.TestUtils;
import org.apache.roller.weblogger.business.CommentManager;
import org.apache.roller.weblogger.business.UserManager;
import org.apache.roller.weblogger.pojos.ReaderComment;
import org.apache.roller.weblogger.pojos.ReaderComment.Status;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.apache.roller.weblogger.pojos.WeblogEntry.PubStatus;
import org.apache.roller.weblogger.pojos.WeblogPermission;
import org.apache.roller.weblogger.ui.core.security.sso.SsoLogCapture;
import org.apache.roller.weblogger.util.GenericThrottle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every posting, trust and moderation rule, against the database: spec
 * criteria 4-13 and 18 at the service level, 31 for the log, and Review
 * Focus 1 and 5.
 */
class CommentServiceTest {

    private static final String ISSUER = "https://issuer.test/comments";
    private static final String BODY = "A thoughtful comment.";
    private static final String THREE_LINKS = "see http://a.example https://b.example HTTP://c.example";

    private final List<String> userNames = new ArrayList<>();
    private User owner;
    private User reader;
    private Weblog a;
    private Weblog b;
    private WeblogEntry ea;
    private WeblogEntry ea2;
    private WeblogEntry eb;
    private CommentNotifier notifier;
    private CommentService service;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.setupWeblogger();
        owner = TestUtils.setupUser("csowner");
        userNames.add(owner.getUserName());
        reader = newReader("reader-csreader1", "csreader1@example.com");
        a = TestUtils.setupWeblog("csbloga", owner);
        b = TestUtils.setupWeblog("csblogb", owner);
        TestUtils.endSession(true);
        setCommentsEnabled(a, true);
        setCommentsEnabled(b, true);
        ea = TestUtils.setupWeblogEntry("csea", TestUtils.getManagedWebsite(a), TestUtils.getManagedUser(owner));
        ea2 = TestUtils.setupWeblogEntry("csea2", TestUtils.getManagedWebsite(a), TestUtils.getManagedUser(owner));
        eb = TestUtils.setupWeblogEntry("cseb", TestUtils.getManagedWebsite(b), TestUtils.getManagedUser(owner));
        TestUtils.endSession(true);
        notifier = Mockito.spy(new NoOpCommentNotifier());
        service = new CommentService(TestUtils.weblogger(), notifier, unlimited(), unlimited());
    }

    @AfterEach
    void tearDown() throws Exception {
        // Comments go with their entries (DB cascade), then the weblogs and users.
        TestUtils.teardownWeblog(a.getId());
        TestUtils.teardownWeblog(b.getId());
        for (String name : userNames) {
            TestUtils.teardownUser(name);
        }
        TestUtils.endSession(true);
    }

    // ------------------------------------------------------------- criteria 4-8

    @Test
    void aReadersFirstCommentIsHeld() throws Exception {
        CommentOutcome out = post(reader, ea, BODY);

        assertTrue(out.ok());
        ReaderComment c = fetch(out.comment());
        assertEquals(Status.PENDING, c.getStatus());
        assertFalse(c.isAuthorIsOwner());
        assertEquals(BODY, c.getBody());
        assertEquals(reader.getId(), c.getAuthor().getId());
        assertEquals(ea.getId(), c.getEntry().getId());
        assertNull(c.getReplyTo());
        assertNotNull(c.getCreated());
        assertEquals(c.getCreated(), c.getStatusChanged());
    }

    @Test
    void afterApprovalTheNextIsPublishedButNotOnAnotherWeblog() throws Exception {
        ReaderComment first = post(reader, ea, BODY).comment();
        setStatus(first, Status.PUBLISHED);

        assertEquals(Status.PUBLISHED, post(reader, ea2, BODY).comment().getStatus(),
                "trusted on this weblog, on any of its entries");
        assertEquals(Status.PENDING, post(reader, eb, BODY).comment().getStatus(),
                "trust is earned per weblog");
    }

    @Test
    void threeLinksAreHeldEvenWhenTrusted() throws Exception {
        trust(reader, ea);

        assertEquals(Status.PENDING, post(reader, ea, THREE_LINKS).comment().getStatus());
        assertEquals(Status.PUBLISHED, post(reader, ea, "http://a.example https://b.example").comment().getStatus());
    }

    @Test
    void hidingOneSendsTheReaderBackToHeld() throws Exception {
        ReaderComment first = trust(reader, ea);
        assertEquals(Status.PUBLISHED, post(reader, ea, BODY).comment().getStatus());

        setStatus(first, Status.HIDDEN);

        assertEquals(Status.PENDING, post(reader, ea, BODY).comment().getStatus());
    }

    @Test
    void ownerCommentsAndRepliesArePublishedAndMarked() throws Exception {
        ReaderComment own = post(owner, ea, THREE_LINKS).comment();
        assertEquals(Status.PUBLISHED, fetch(own).getStatus(), "an owner is never held, links or not");
        assertTrue(fetch(own).isAuthorIsOwner());

        ReaderComment parent = trust(reader, ea);
        CommentOutcome viaPost = post(owner, ea.getId(), "Thanks", parent.getId());
        assertTrue(viaPost.ok());
        ReaderComment reply = fetch(viaPost.comment());
        assertEquals(Status.PUBLISHED, reply.getStatus());
        assertTrue(reply.isAuthorIsOwner());
        assertEquals(parent.getId(), reply.getReplyTo().getId());

        ReaderComment second = post(reader, ea, BODY).comment();
        CommentOutcome viaReply = service.reply(TestUtils.getManagedUser(owner), fetch(second), THREE_LINKS);
        TestUtils.endSession(true);
        assertTrue(viaReply.ok());
        ReaderComment reply2 = fetch(viaReply.comment());
        assertEquals(Status.PUBLISHED, reply2.getStatus());
        assertTrue(reply2.isAuthorIsOwner());
        assertEquals(second.getId(), reply2.getReplyTo().getId());

        List<ReaderComment> page = mgr().getPublishedForEntry(TestUtils.getManagedWeblogEntry(ea));
        assertEquals(Set.of(own.getId(), parent.getId(), second.getId()),
                page.stream().map(ReaderComment::getId).collect(Collectors.toSet()),
                "the replies sit under their parents, not in the list");
        ReaderComment shownParent = page.stream().filter(c -> c.getId().equals(parent.getId())).findFirst().get();
        assertEquals(reply.getId(), shownParent.getReply().getId());
        assertFalse(shownParent.isAuthorIsOwner(), "a reader's comment carries no badge");
    }

    // ------------------------------------------------------------- criteria 9-13

    @Test
    void badRepliesAreRefused() throws Exception {
        ReaderComment parent = trust(reader, ea);
        ReaderComment held = post(reader, ea, THREE_LINKS).comment();
        ReaderComment onOtherEntry = post(owner, ea2, BODY).comment();
        long before = countOn(a);

        assertSame(CommentRefusal.BAD_REPLY, post(reader, ea.getId(), "me too", parent.getId()).refusal(),
                "a non-owner reply");
        assertSame(CommentRefusal.BAD_REPLY,
                service.reply(TestUtils.getManagedUser(reader), fetch(parent), "me too").refusal(),
                "a non-owner reply, through reply()");
        assertSame(CommentRefusal.BAD_REPLY, post(owner, ea.getId(), "reply", held.getId()).refusal(),
                "a reply to a PENDING comment");
        assertSame(CommentRefusal.BAD_REPLY, post(owner, ea.getId(), "reply", onOtherEntry.getId()).refusal(),
                "a reply to another entry's comment");
        assertSame(CommentRefusal.BAD_REPLY, post(owner, ea.getId(), "reply", "no-such-comment").refusal(),
                "a reply to nothing");
        assertSame(CommentRefusal.NOT_FOUND, service.reply(TestUtils.getManagedUser(owner), null, "reply").refusal());
        TestUtils.endSession(true);
        assertEquals(before, countOn(a), "a refused reply stores nothing");

        ReaderComment reply = post(owner, ea.getId(), "reply", parent.getId()).comment();
        assertNotNull(reply, "the first reply is fine");

        assertSame(CommentRefusal.BAD_REPLY, post(owner, ea.getId(), "reply", reply.getId()).refusal(),
                "a reply to a reply");
        assertSame(CommentRefusal.BAD_REPLY, post(owner, ea.getId(), "again", parent.getId()).refusal(),
                "a second reply to the same comment");
        assertSame(CommentRefusal.BAD_REPLY,
                service.reply(TestUtils.getManagedUser(owner), fetch(parent), "again").refusal(),
                "a second reply, through reply()");
        TestUtils.endSession(true);

        // One reply per comment, ever: a hidden reply still counts, even after a
        // render has re-read the parent with only its PUBLISHED reply.
        setStatus(reply, Status.HIDDEN);
        assertNull(mgr().getPublishedForEntry(TestUtils.getManagedWeblogEntry(ea)).get(0).getReply());
        TestUtils.endSession(true);
        assertSame(CommentRefusal.BAD_REPLY, post(owner, ea.getId(), "again", parent.getId()).refusal(),
                "a second reply while the first is hidden");
        assertEquals(before + 1, countOn(a));
    }

    /**
     * A render re-reads a parent with only its PUBLISHED reply, so a cached
     * parent can stop knowing about a hidden one. Deleting the parent must
     * still take the reply, in the database and in the cache.
     */
    @Test
    void deletingACommentTakesItsHiddenReplyEvenAfterARender() throws Exception {
        ReaderComment parent = trust(reader, ea);
        ReaderComment reply = service.reply(TestUtils.getManagedUser(owner), fetch(parent), "Thanks").comment();
        TestUtils.endSession(true);
        setStatus(reply, Status.HIDDEN);
        mgr().getPublishedForEntry(TestUtils.getManagedWeblogEntry(ea));
        TestUtils.endSession(true);

        delete(parent);

        assertNull(mgr().getComment(parent.getId()));
        assertNull(mgr().getComment(reply.getId()), "no reply is left behind");
        assertEquals(0, countOn(a));
    }

    @Test
    void bodyLimits() throws Exception {
        for (String empty : new String[] {null, "", "   \n\t "}) {
            assertSame(CommentRefusal.EMPTY_BODY, post(reader, ea, empty).refusal(), String.valueOf(empty));
        }
        assertSame(CommentRefusal.BODY_TOO_LONG, post(reader, ea, "x".repeat(CommentRules.MAX_BODY + 1)).refusal());
        ReaderComment mine = post(owner, ea, BODY).comment();
        assertSame(CommentRefusal.EMPTY_BODY,
                service.reply(TestUtils.getManagedUser(owner), fetch(mine), " ").refusal());
        assertSame(CommentRefusal.BODY_TOO_LONG, service.reply(TestUtils.getManagedUser(owner), fetch(mine),
                "x".repeat(CommentRules.MAX_BODY + 1)).refusal());
        TestUtils.endSession(true);
        assertEquals(1, countOn(a), "nothing but the owner's own comment");

        CommentOutcome longest = post(reader, ea, "  " + "x".repeat(CommentRules.MAX_BODY) + "\n");
        assertTrue(longest.ok());
        assertEquals("x".repeat(CommentRules.MAX_BODY), fetch(longest.comment()).getBody(), "stored trimmed");
    }

    @Test
    void notFoundClosedOffAndCustomDomainAreRefusedAndStoreNothing() throws Exception {
        Weblog wa = TestUtils.getManagedWebsite(a);
        User o = TestUtils.getManagedUser(owner);
        WeblogEntry draft = TestUtils.setupWeblogEntry("csdraft", wa, o, PubStatus.DRAFT);
        WeblogEntry scheduled = TestUtils.setupWeblogEntry("csscheduled", wa, o, PubStatus.SCHEDULED);
        WeblogEntry pending = TestUtils.setupWeblogEntry("cspending", wa, o, PubStatus.PENDING);
        WeblogEntry trashed = TestUtils.setupWeblogEntry("cstrashed", wa, o);
        TestUtils.endSession(true);
        TestUtils.weblogger().getWeblogEntryManager().trashWeblogEntry(TestUtils.getManagedWeblogEntry(trashed));
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);

        for (WeblogEntry e : List.of(draft, scheduled, pending, trashed)) {
            assertSame(CommentRefusal.NOT_FOUND, post(reader, e, BODY).refusal(), e.getAnchor());
            assertSame(CommentRefusal.NOT_FOUND, post(owner, e, BODY).refusal(), e.getAnchor() + " (owner)");
        }
        assertSame(CommentRefusal.NOT_FOUND, post(reader, "no-such-entry", BODY, null).refusal());
        assertSame(CommentRefusal.NOT_FOUND, post(reader, null, BODY, null).refusal());
        assertSame(CommentRefusal.NOT_FOUND, service.whyNot(reader, null));

        setCommentsClosed(ea, true);
        assertSame(CommentRefusal.CLOSED, post(reader, ea, BODY).refusal());
        assertSame(CommentRefusal.CLOSED, post(owner, ea, BODY).refusal(), "closed is closed for owners too");
        assertSame(CommentRefusal.CLOSED, service.whyNot(reader, TestUtils.getManagedWeblogEntry(ea)));

        setCommentsEnabled(b, false);
        assertSame(CommentRefusal.COMMENTS_OFF, post(reader, eb, BODY).refusal());
        assertSame(CommentRefusal.COMMENTS_OFF, service.whyNot(reader, TestUtils.getManagedWeblogEntry(eb)));

        setCustomDomain(a, "comments.example.test");
        assertSame(CommentRefusal.CUSTOM_DOMAIN, post(reader, ea2, BODY).refusal());
        assertSame(CommentRefusal.CUSTOM_DOMAIN, service.whyNot(reader, TestUtils.getManagedWeblogEntry(ea2)));

        // The order of the checks: the entry, then comments on, then the domain, then closed.
        assertSame(CommentRefusal.CUSTOM_DOMAIN, post(reader, ea, BODY).refusal(), "custom domain before closed");
        setCommentsEnabled(a, false);
        assertSame(CommentRefusal.COMMENTS_OFF, post(reader, ea, BODY).refusal(), "comments off before the rest");
        assertSame(CommentRefusal.NOT_FOUND, post(reader, draft, BODY).refusal(), "an unpublished entry first");

        assertEquals(0, countOn(a));
        assertEquals(0, countOn(b));
        Mockito.verifyNoInteractions(notifier);
    }

    @Test
    void disabledAndPermissionlessAccountsAreRefused() throws Exception {
        assertSame(CommentRefusal.NOT_SIGNED_IN, service.whyNot(null, TestUtils.getManagedWeblogEntry(ea)));
        assertSame(CommentRefusal.NOT_SIGNED_IN, post(null, ea, BODY).refusal());
        assertNull(service.whyNot(reader, TestUtils.getManagedWeblogEntry(ea)), "a reader may post");

        // The session holds the account as it was at sign-in; it is disabled since.
        User inSession = reader;
        UserManager users = TestUtils.weblogger().getUserManager();
        User current = users.getUserByUserName(reader.getUserName(), null);
        current.setEnabled(Boolean.FALSE);
        users.saveUser(current);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
        assertEquals(Boolean.TRUE, inSession.getEnabled(), "fixture: the session's copy still says enabled");
        assertSame(CommentRefusal.DISABLED, service.whyNot(inSession, TestUtils.getManagedWeblogEntry(ea)));
        assertSame(CommentRefusal.DISABLED, post(inSession, ea, BODY).refusal());
        assertSame(CommentRefusal.DISABLED, post(inSession, ea, "").refusal(), "disabled before the body");
        assertSame(CommentRefusal.DISABLED, service.reply(inSession, null, BODY).refusal(), "replies re-read too");
        assertSame(CommentRefusal.NOT_SIGNED_IN, service.reply(null, null, BODY).refusal());

        User gone = new User();
        gone.setUserName("reader-csnoaccount");
        assertSame(CommentRefusal.DISABLED, post(gone, ea, BODY).refusal(), "an account that no longer exists");
        assertFalse(deleteOwn(gone, "any-comment"));
        assertSame(CommentRefusal.DISABLED, post(new User(), ea, BODY).refusal(), "an account with no name");
        assertFalse(service.isOwner(null, TestUtils.getManagedWebsite(a)));

        User roleless = newReader("reader-csroleless", "csroleless@example.com");
        users.revokeRole("reader", TestUtils.getManagedUser(roleless));
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
        assertSame(CommentRefusal.NO_PERMISSION, service.whyNot(roleless, TestUtils.getManagedWeblogEntry(ea)));
        assertSame(CommentRefusal.NO_PERMISSION, post(roleless, ea, BODY).refusal());
        assertSame(CommentRefusal.NO_PERMISSION, post(roleless, "no-such-entry", BODY, null).refusal(),
                "permission before the entry");

        assertEquals(0, countOn(a));
    }

    @Test
    void theSixthIn10MinutesAndThe31stInADayAreRateLimited() throws Exception {
        CommentService limited = new CommentService(TestUtils.weblogger(), new NoOpCommentNotifier());

        for (int i = 0; i < 6; i++) {
            assertSame(CommentRefusal.EMPTY_BODY, post(limited, reader, ea, " ").refusal());
        }
        for (int i = 1; i <= 5; i++) {
            assertTrue(post(limited, reader, ea, BODY + " " + i).ok(), "comment " + i + ", refusals never count");
        }
        assertSame(CommentRefusal.RATE_LIMITED, post(limited, reader, ea, BODY).refusal(), "the sixth");
        assertEquals(5, countOn(a));

        // Owners count too, replies included.
        ReaderComment parent = null;
        for (int i = 1; i <= 4; i++) {
            parent = post(limited, owner, ea2, BODY + " " + i).comment();
        }
        assertTrue(limited.reply(TestUtils.getManagedUser(owner), fetch(parent), "a reply is the fifth").ok());
        TestUtils.endSession(true);
        assertSame(CommentRefusal.RATE_LIMITED, post(limited, owner, ea2, BODY).refusal());
        assertEquals(10, countOn(a));

        // The day, with the ten-minute window out of the way.
        CommentService daily = new CommentService(TestUtils.weblogger(), new NoOpCommentNotifier(),
                unlimited(), CommentService.dayThrottle());
        for (int i = 1; i <= 30; i++) {
            assertTrue(post(daily, reader, eb, BODY + " " + i).ok(), "comment " + i);
        }
        assertSame(CommentRefusal.RATE_LIMITED, post(daily, reader, eb, BODY).refusal(), "the 31st");
        assertEquals(30, countOn(b));
    }

    // ------------------------------------------------------------- criterion 18

    @Test
    void publishedChangesExpireTheWeblogPendingDoesNot() throws Exception {
        ReaderComment held = keeps("storing a held comment", () -> post(reader, ea, BODY).comment());
        expires("approving it", () -> setStatus(held, Status.PUBLISHED));
        Date approved = fetch(held).getStatusChanged();
        Thread.sleep(5);
        keeps("approving it again", () -> setStatus(held, Status.PUBLISHED));
        assertEquals(approved, fetch(held).getStatusChanged(), "a move to the same status is no move");
        ReaderComment reply = expires("an owner's reply", () -> {
            CommentOutcome out = service.reply(TestUtils.getManagedUser(owner), fetch(held), "Thanks");
            TestUtils.endSession(true);
            return out.comment();
        });
        expires("deleting the reply", () -> delete(reply));
        expires("a reply through post", () -> post(owner, ea.getId(), "Thanks", held.getId()).comment());
        expires("hiding a published comment", () -> setStatus(held, Status.HIDDEN));
        keeps("hidden back to held", () -> setStatus(held, Status.PENDING));
        keeps("deleting a held comment", () -> delete(held));
        ReaderComment own = expires("an owner's comment", () -> post(owner, ea, BODY).comment());
        expires("deleting a published comment", () -> delete(own));
        ReaderComment hidden = keeps("another held comment", () -> post(reader, ea, BODY).comment());
        keeps("held to hidden", () -> setStatus(hidden, Status.HIDDEN));
        keeps("deleting a hidden comment", () -> delete(hidden));
    }

    // ------------------------------------------------------------- Review Focus 1 and 5

    @Test
    void anAuthorOfAnotherWeblogIsHeldLikeAReader() throws Exception {
        User stranger = TestUtils.setupUser("csstranger");
        userNames.add(stranger.getUserName());
        TestUtils.endSession(true);
        assertFalse(service.isOwner(stranger, TestUtils.getManagedWebsite(a)));
        assertTrue(service.isOwner(owner, TestUtils.getManagedWebsite(a)));

        ReaderComment first = fetch(post(stranger, ea, BODY).comment());
        assertEquals(Status.PENDING, first.getStatus(), "an account is not an owner here");
        assertFalse(first.isAuthorIsOwner(), "and gets no badge");

        TestUtils.weblogger().getUserManager().grantWeblogPermission(TestUtils.getManagedWebsite(a),
                TestUtils.getManagedUser(stranger), List.of(WeblogPermission.POST));
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
        assertTrue(service.isOwner(stranger, TestUtils.getManagedWebsite(a)));

        ReaderComment second = fetch(post(stranger, ea, BODY).comment());
        assertEquals(Status.PUBLISHED, second.getStatus());
        assertTrue(second.isAuthorIsOwner());
        assertEquals(Status.PENDING, post(stranger, eb, BODY).comment().getStatus(),
                "still a reader on a weblog it is not a member of");
    }

    @Test
    void commentsOffStillAllowsModeration() throws Exception {
        ReaderComment first = post(reader, ea, BODY).comment();
        ReaderComment second = post(reader, ea, "second").comment();
        setCommentsEnabled(a, false);

        setStatus(first, Status.PUBLISHED);
        assertEquals(Status.PUBLISHED, fetch(first).getStatus());
        CommentOutcome reply = service.reply(TestUtils.getManagedUser(owner), fetch(first), "Thanks");
        TestUtils.endSession(true);
        assertTrue(reply.ok());
        service.delete(fetch(second), null);
        TestUtils.endSession(true);
        assertNull(mgr().getComment(second.getId()), "an unattributed delete (no actor) works too");

        assertSame(CommentRefusal.COMMENTS_OFF, post(reader, ea, BODY).refusal());
        assertSame(CommentRefusal.COMMENTS_OFF, post(owner, ea.getId(), "again", first.getId()).refusal(),
                "the public form stays shut, replies included");
        assertSame(CommentRefusal.COMMENTS_OFF, service.whyNot(reader, TestUtils.getManagedWeblogEntry(ea)));
    }

    @Test
    void heldCommentsCallTheNotifierPublishedOnesDoNot() throws Exception {
        ReaderComment held = post(reader, ea, BODY).comment();
        ArgumentCaptor<ReaderComment> told = ArgumentCaptor.forClass(ReaderComment.class);
        Mockito.verify(notifier).notifyHeld(told.capture());
        assertEquals(held.getId(), told.getValue().getId());

        ReaderComment own = post(owner, ea, BODY).comment();
        service.reply(TestUtils.getManagedUser(owner), fetch(own), "a reply");
        TestUtils.endSession(true);
        setStatus(held, Status.PUBLISHED);
        post(reader, ea, BODY);
        post(reader, ea, "");
        Mockito.verifyNoMoreInteractions(notifier);
    }

    @Test
    void aReaderDeletesOnlyTheirOwn() throws Exception {
        ReaderComment mine = trust(reader, ea);
        CommentOutcome reply = service.reply(TestUtils.getManagedUser(owner), fetch(mine), "Thanks");
        TestUtils.endSession(true);
        ReaderComment theirs = post(owner, ea, BODY).comment();

        assertFalse(deleteOwn(reader, theirs.getId()), "not theirs");
        assertFalse(deleteOwn(reader, "no-such-comment"));
        assertFalse(deleteOwn(reader, null));
        assertFalse(deleteOwn(null, mine.getId()));
        assertNotNull(mgr().getComment(theirs.getId()));

        expires("deleting their published comment", () -> deleteOwn(reader, mine.getId()));
        assertNull(mgr().getComment(mine.getId()));
        assertNull(mgr().getComment(reply.comment().getId()), "the reply goes with it");

        ReaderComment later = post(reader, ea, BODY).comment();
        User current = TestUtils.weblogger().getUserManager().getUserByUserName(reader.getUserName(), null);
        current.setEnabled(Boolean.FALSE);
        TestUtils.weblogger().getUserManager().saveUser(current);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
        assertFalse(deleteOwn(reader, later.getId()), "a disabled account writes nothing");
        assertNotNull(mgr().getComment(later.getId()));
    }

    // ------------------------------------------------------------- criterion 31

    @Test
    void noLogLineCarriesTheBody() throws Exception {
        String bodyMark = "BODYMARK" + UUID.randomUUID().toString().replace("-", "");
        String emailMark = "EMAILMARK" + UUID.randomUUID().toString().replace("-", "");
        User marked = newReader("reader-csmarked1", emailMark + "@example.com");
        TestUtils.endSession(true);

        List<String> lines;
        String id;
        try (SsoLogCapture capture = SsoLogCapture.everyLogger()) {
            ReaderComment c = post(marked, ea, "Hello " + bodyMark).comment();
            id = c.getId();
            setStatus(c, Status.PUBLISHED);
            service.reply(TestUtils.getManagedUser(owner), fetch(c), "Reply " + bodyMark);
            TestUtils.endSession(true);
            post(marked, ea, bodyMark + "x".repeat(CommentRules.MAX_BODY));
            ReaderComment c2 = post(marked, ea, "Again " + bodyMark).comment();
            deleteOwn(marked, c2.getId());
            delete(c);
            lines = capture.everything();
        }

        assertTrue(lines.stream().anyMatch(l -> l.contains(id)), "the capture saw the comment's own log lines");
        for (String line : lines) {
            assertFalse(line.contains(bodyMark), "a body was logged: " + line);
            assertFalse(line.contains(emailMark), "an email address was logged: " + line);
        }
    }

    /** The comments logger writes to roller.log at INFO, as roller.sso does, not to the console. */
    @Test
    void theCommentsLoggerWritesToRollerLog() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Document config = factory.newDocumentBuilder().parse(Path.of("src/main/resources/log4j2.xml").toFile());
        NodeList loggers = config.getElementsByTagName("Logger");
        Element comments = null;
        for (int i = 0; i < loggers.getLength(); i++) {
            Element logger = (Element) loggers.item(i);
            if ("roller.comments".equals(logger.getAttribute("name"))) {
                comments = logger;
            }
        }
        assertNotNull(comments, "log4j2.xml configures roller.comments");
        assertEquals("info", comments.getAttribute("level"));
        assertEquals("false", comments.getAttribute("additivity"));
        Element ref = (Element) comments.getElementsByTagName("AppenderRef").item(0);
        assertEquals("asyncRoller", ref.getAttribute("ref"));
    }

    // ------------------------------------------------------------- helpers

    private interface Op<T> {
        T run() throws Exception;
    }

    private interface VoidOp {
        void run() throws Exception;
    }

    private <T> T expires(String what, Op<T> op) throws Exception {
        Date before = freshLastModified();
        T result = op.run();
        TestUtils.endSession(true);
        assertTrue(TestUtils.getManagedWebsite(a).getLastModified().after(before), what + " must expire the weblog");
        return result;
    }

    private void expires(String what, VoidOp op) throws Exception {
        expires(what, () -> {
            op.run();
            return null;
        });
    }

    private <T> T keeps(String what, Op<T> op) throws Exception {
        Date before = freshLastModified();
        T result = op.run();
        TestUtils.endSession(true);
        assertEquals(before, TestUtils.getManagedWebsite(a).getLastModified(), what + " changes no page");
        return result;
    }

    private void keeps(String what, VoidOp op) throws Exception {
        keeps(what, () -> {
            op.run();
            return null;
        });
    }

    /** A known lastModified, then a pause, so that a later bump is a later millisecond. */
    private Date freshLastModified() throws Exception {
        TestUtils.weblogger().getWeblogManager().saveWeblog(TestUtils.getManagedWebsite(a));
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
        Date known = TestUtils.getManagedWebsite(a).getLastModified();
        Thread.sleep(5);
        return known;
    }

    private static GenericThrottle unlimited() {
        return new GenericThrottle(Integer.MAX_VALUE, 60_000, 1000);
    }

    private static CommentManager mgr() {
        return TestUtils.weblogger().getCommentManager();
    }

    private User newReader(String userName, String email) throws Exception {
        User u = new User();
        u.setUserName(userName);
        u.setScreenName("Reader");
        u.setFullName("Reader");
        u.setEmailAddress(email);
        u.setPassword(User.SSO_PASSWORD_MARKER);
        u.setDateCreated(new Date());
        u.setEnabled(Boolean.TRUE);
        u.setSsoIssuer(ISSUER);
        u.setSsoSubject("sub-" + UUID.randomUUID());
        TestUtils.weblogger().getUserManager().addReader(u);
        TestUtils.weblogger().flush();
        userNames.add(userName);
        return u;
    }

    /** A post as one request: the user as a session holds it, then the session ends. */
    private CommentOutcome post(User who, WeblogEntry entry, String body) throws Exception {
        return post(service, who, entry, body);
    }

    private CommentOutcome post(User who, String entryId, String body, String replyTo) throws Exception {
        CommentOutcome out = service.post(who, entryId, body, replyTo);
        TestUtils.endSession(true);
        return out;
    }

    private static CommentOutcome post(CommentService with, User who, WeblogEntry entry, String body)
            throws Exception {
        CommentOutcome out = with.post(who, entry.getId(), body, null);
        TestUtils.endSession(true);
        return out;
    }

    /** A reader's comment, approved: the reader is now trusted on the entry's weblog. */
    private ReaderComment trust(User who, WeblogEntry entry) throws Exception {
        ReaderComment c = post(who, entry, BODY).comment();
        setStatus(c, Status.PUBLISHED);
        return c;
    }

    private void setStatus(ReaderComment c, Status status) throws Exception {
        service.setStatus(fetch(c), status, TestUtils.getManagedUser(owner));
        TestUtils.endSession(true);
    }

    private void delete(ReaderComment c) throws Exception {
        service.delete(fetch(c), TestUtils.getManagedUser(owner));
        TestUtils.endSession(true);
    }

    private boolean deleteOwn(User who, String id) throws Exception {
        boolean done = service.deleteOwn(who, id);
        TestUtils.endSession(true);
        return done;
    }

    private static ReaderComment fetch(ReaderComment c) throws Exception {
        return mgr().getComment(c.getId());
    }

    private static long countOn(Weblog weblog) throws Exception {
        return mgr().countComments(TestUtils.getManagedWebsite(weblog), null);
    }

    private static void setCommentsEnabled(Weblog weblog, boolean on) throws Exception {
        Weblog w = TestUtils.getManagedWebsite(weblog);
        w.setCommentsEnabled(on);
        TestUtils.weblogger().getWeblogManager().saveWeblog(w);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
    }

    private static void setCustomDomain(Weblog weblog, String domain) throws Exception {
        Weblog w = TestUtils.getManagedWebsite(weblog);
        w.setCustomDomain(domain);
        TestUtils.weblogger().getWeblogManager().saveWeblog(w);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
    }

    private static void setCommentsClosed(WeblogEntry entry, boolean closed) throws Exception {
        WeblogEntry e = TestUtils.getManagedWeblogEntry(entry);
        e.setCommentsClosed(closed);
        TestUtils.weblogger().getWeblogEntryManager().saveWeblogEntry(e);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
    }
}

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
package com.wikantik.pressroom.business;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogEntry.PubStatus;
import com.wikantik.pressroom.pojos.WeblogPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Strand membership against the real database. */
class StrandMembershipTest {

    private User user;
    private Weblog blog;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.setupWeblogger();
        user = TestUtils.setupUser("strandMemUser");
        blog = TestUtils.setupWeblog("strandmemblog", user);
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(blog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    private static WeblogEntryManager entries() {
        return TestUtils.weblogger().getWeblogEntryManager();
    }

    private WeblogPage newStrand(String slug) throws Exception {
        WeblogPage p = new WeblogPage();
        p.setSlug(slug);
        p.setTitle(slug);
        p.setContent("x");
        p.setStatus(WeblogPage.PubStatus.PUBLISHED);
        p.setStrand(Boolean.TRUE);
        p.setWeblog(TestUtils.getManagedWebsite(blog));
        TestUtils.weblogger().getWeblogPageManager().savePage(p);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
        return TestUtils.weblogger().getWeblogPageManager().getPage(p.getId());
    }

    private String newPost(String anchor, PubStatus status) throws Exception {
        String id = TestUtils.setupWeblogEntry(anchor, blog, user, status).getId();
        TestUtils.endSession(true);
        return id;
    }

    private WeblogEntry load(String id) throws Exception {
        return entries().getWeblogEntry(id);
    }

    private void append(String entryId, String strandId) throws Exception {
        WeblogEntry e = load(entryId);
        entries().appendToStrand(e, TestUtils.weblogger().getWeblogPageManager().getPage(strandId));
        entries().saveWeblogEntry(e);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
    }

    private List<String> partIds(String strandId, boolean visibleOnly) throws Exception {
        WeblogPage s = TestUtils.weblogger().getWeblogPageManager().getPage(strandId);
        return entries().getStrandParts(s, visibleOnly).stream().map(WeblogEntry::getId).toList();
    }

    @Test
    void appendingNumbersOneTwoThree() throws Exception {
        String s = newStrand("s-seq").getId();
        String a = newPost("a1", PubStatus.PUBLISHED);
        String b = newPost("b1", PubStatus.PUBLISHED);
        String c = newPost("c1", PubStatus.PUBLISHED);
        append(a, s);
        append(b, s);
        append(c, s);
        assertEquals(1, load(a).getStrandPosition());
        assertEquals(2, load(b).getStrandPosition());
        assertEquals(3, load(c).getStrandPosition());
        assertEquals(s, load(c).getStrandPageId());
        assertEquals(List.of(a, b, c), partIds(s, false));
    }

    @Test
    void appendingAfterATrashedMemberStillUsesMaxPlusOne() throws Exception {
        String s = newStrand("s-trash").getId();
        String a = newPost("a2", PubStatus.PUBLISHED);
        String b = newPost("b2", PubStatus.PUBLISHED);
        append(a, s);
        append(b, s);
        WeblogEntry tb = load(b);
        entries().trashWeblogEntry(tb);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
        String c = newPost("c2", PubStatus.PUBLISHED);
        append(c, s);
        assertEquals(3, load(c).getStrandPosition());
        assertEquals(3, entries().getStrandPartCount(
                TestUtils.weblogger().getWeblogPageManager().getPage(s)));
    }

    @Test
    void appendingToTheStrandItIsAlreadyInChangesNothing() throws Exception {
        String s = newStrand("s-noop").getId();
        String a = newPost("a3", PubStatus.PUBLISHED);
        String b = newPost("b3", PubStatus.PUBLISHED);
        append(a, s);
        append(b, s);
        append(a, s);
        assertEquals(1, load(a).getStrandPosition());
        assertEquals(2, load(b).getStrandPosition());
    }

    @Test
    void readerVisiblePartsLeaveOutDraftTrashedAndFuturePosts() throws Exception {
        String s = newStrand("s-vis").getId();
        String pub = newPost("pub", PubStatus.PUBLISHED);
        String draft = newPost("drf", PubStatus.DRAFT);
        String trashed = newPost("trs", PubStatus.PUBLISHED);
        String future = newPost("fut", PubStatus.PUBLISHED);
        WeblogEntry f = load(future);
        f.setPubTime(Timestamp.from(Instant.now().plus(1, ChronoUnit.DAYS)));
        entries().saveWeblogEntry(f);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
        for (String id : List.of(pub, draft, trashed, future)) {
            append(id, s);
        }
        entries().trashWeblogEntry(load(trashed));
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);

        assertEquals(List.of(pub), partIds(s, true));
        assertEquals(List.of(pub, draft, trashed, future), partIds(s, false));
    }

    @Test
    void movingAPostToAnotherStrandLeavesTheOldPositionsAlone() throws Exception {
        String s1 = newStrand("s-mv1").getId();
        String s2 = newStrand("s-mv2").getId();
        String a = newPost("a4", PubStatus.PUBLISHED);
        String b = newPost("b4", PubStatus.PUBLISHED);
        String c = newPost("c4", PubStatus.PUBLISHED);
        append(a, s1);
        append(b, s1);
        append(c, s1);
        WeblogEntry mb = load(b);
        entries().leaveStrand(mb);
        assertNull(mb.getStrandPageId());
        assertNull(mb.getStrandPosition());
        entries().appendToStrand(mb, TestUtils.weblogger().getWeblogPageManager().getPage(s2));
        entries().saveWeblogEntry(mb);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);

        assertEquals(1, load(a).getStrandPosition());
        assertEquals(3, load(c).getStrandPosition());
        assertEquals(1, load(b).getStrandPosition());
        assertEquals(s2, load(b).getStrandPageId());
    }

    @Test
    void setStrandPartsMakesExactlyTheListTheMembersAndBumpsTheBlog() throws Exception {
        String s = newStrand("s-set").getId();
        String a = newPost("a5", PubStatus.PUBLISHED);
        String b = newPost("b5", PubStatus.PUBLISHED);
        String c = newPost("c5", PubStatus.PUBLISHED);
        append(a, s);
        append(b, s);
        java.util.Date before = TestUtils.getManagedWebsite(blog).getLastModified();
        TestUtils.endSession(true);
        Thread.sleep(20);

        WeblogPage strand = TestUtils.weblogger().getWeblogPageManager().getPage(s);
        entries().setStrandParts(strand, List.of(load(c), load(a)));
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);

        assertEquals(2, load(a).getStrandPosition());
        assertEquals(1, load(c).getStrandPosition());
        assertNull(load(b).getStrandPageId());
        assertNull(load(b).getStrandPosition());
        assertEquals(List.of(c, a), partIds(s, false));
        java.util.Date after = TestUtils.getManagedWebsite(blog).getLastModified();
        assertTrue(after.after(before), "set parts must bump lastModified");
    }

    @Test
    void anEmptyListRemovesEveryPart() throws Exception {
        String s = newStrand("s-empty").getId();
        String a = newPost("a6", PubStatus.PUBLISHED);
        append(a, s);
        entries().setStrandParts(TestUtils.weblogger().getWeblogPageManager().getPage(s), List.of());
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
        assertNull(load(a).getStrandPageId());
        assertEquals(0, entries().getStrandPartCount(
                TestUtils.weblogger().getWeblogPageManager().getPage(s)));
    }

    @Test
    void removingAStrandKeepsItsPostsWithTheStrandCleared() throws Exception {
        String s = newStrand("s-rm").getId();
        String a = newPost("a7", PubStatus.PUBLISHED);
        append(a, s);
        TestUtils.weblogger().getWeblogPageManager().removePage(
                TestUtils.weblogger().getWeblogPageManager().getPage(s));
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
        WeblogEntry kept = load(a);
        assertNotNull(kept);
        assertNull(kept.getStrandPageId());
        assertNull(kept.getStrandPosition());
    }

    @Test
    void removingABlogThatHasAStrandWithPartsSucceeds() throws Exception {
        User u2 = TestUtils.setupUser("strandMemUser2");
        Weblog b2 = TestUtils.setupWeblog("strandmemblog2", u2);
        TestUtils.endSession(true);
        WeblogPage p = new WeblogPage();
        p.setSlug("doomed");
        p.setTitle("doomed");
        p.setContent("x");
        p.setStatus(WeblogPage.PubStatus.PUBLISHED);
        p.setStrand(Boolean.TRUE);
        p.setWeblog(TestUtils.getManagedWebsite(b2));
        TestUtils.weblogger().getWeblogPageManager().savePage(p);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
        String e = TestUtils.setupWeblogEntry("doomedpost", b2, u2, PubStatus.PUBLISHED).getId();
        TestUtils.endSession(true);
        append(e, p.getId());

        TestUtils.teardownWeblog(b2.getId());
        TestUtils.teardownUser(u2.getUserName());
        TestUtils.endSession(true);
        assertNull(entries().getWeblogEntry(e));
    }
}

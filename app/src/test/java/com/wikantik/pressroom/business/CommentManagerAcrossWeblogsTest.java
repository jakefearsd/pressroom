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

import java.util.Date;
import java.util.List;

import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.pojos.ReaderComment;
import com.wikantik.pressroom.pojos.ReaderComment.Status;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The approval queue's query: comments across a chosen set of weblogs, never across all of them. */
class CommentManagerAcrossWeblogsTest {

    private User owner;
    private User reader;
    private Weblog a;
    private Weblog b;
    private Weblog c;
    private WeblogEntry ea;
    private WeblogEntry eb;
    private WeblogEntry ec;
    private long clock = 1_700_000_000_000L;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.setupWeblogger();
        owner = TestUtils.setupUser("cqowner");
        reader = TestUtils.setupUser("cqreader");
        a = TestUtils.setupWeblog("cqbloga", owner);
        b = TestUtils.setupWeblog("cqblogb", owner);
        c = TestUtils.setupWeblog("cqblogc", owner);
        TestUtils.endSession(true);
        ea = TestUtils.setupWeblogEntry("cqea", TestUtils.getManagedWebsite(a), TestUtils.getManagedUser(owner));
        eb = TestUtils.setupWeblogEntry("cqeb", TestUtils.getManagedWebsite(b), TestUtils.getManagedUser(owner));
        ec = TestUtils.setupWeblogEntry("cqec", TestUtils.getManagedWebsite(c), TestUtils.getManagedUser(owner));
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(a.getId());
        TestUtils.teardownWeblog(b.getId());
        TestUtils.teardownWeblog(c.getId());
        TestUtils.teardownUser(owner.getUserName());
        TestUtils.teardownUser(reader.getUserName());
        TestUtils.endSession(true);
    }

    private CommentManager mgr() throws Exception {
        return TestUtils.weblogger().getCommentManager();
    }

    private ReaderComment comment(WeblogEntry entry, Status status) throws Exception {
        ReaderComment comment = new ReaderComment();
        comment.setEntry(TestUtils.getManagedWeblogEntry(entry));
        comment.setAuthor(TestUtils.getManagedUser(reader));
        comment.setBody("body " + clock);
        comment.setStatus(status);
        comment.setCreated(new Date(clock));
        comment.setStatusChanged(new Date(clock));
        clock += 1000;
        mgr().saveComment(comment);
        return comment;
    }

    private List<Weblog> managed(Weblog... blogs) throws Exception {
        List<Weblog> managed = new java.util.ArrayList<>();
        for (Weblog blog : blogs) {
            managed.add(TestUtils.getManagedWebsite(blog));
        }
        return managed;
    }

    @Test
    void twoWeblogsMergeNewestFirstAndAThirdIsLeftOut() throws Exception {
        ReaderComment first = comment(ea, Status.PENDING);
        ReaderComment second = comment(eb, Status.PENDING);
        ReaderComment third = comment(ea, Status.PENDING);
        comment(ec, Status.PENDING);
        comment(ea, Status.PUBLISHED);
        TestUtils.endSession(true);

        List<Weblog> blogs = managed(a, b);
        List<ReaderComment> found = mgr().getComments(blogs, Status.PENDING, 0, 50);

        assertEquals(List.of(third.getId(), second.getId(), first.getId()),
                found.stream().map(ReaderComment::getId).toList());
        assertEquals(3, mgr().countComments(blogs, Status.PENDING));
        assertEquals(4, mgr().countComments(blogs, null));
        assertEquals(2, mgr().getComments(blogs, Status.PENDING, 1, 50).size(), "offset skips the newest");
        assertEquals(1, mgr().getComments(blogs, Status.PENDING, 0, 1).size(), "length pages");
    }

    @Test
    void anEmptySetIsEmptyAndZero() throws Exception {
        comment(ea, Status.PENDING);
        TestUtils.endSession(true);

        assertTrue(mgr().getComments(List.of(), Status.PENDING, 0, 50).isEmpty());
        assertEquals(0, mgr().countComments(List.of(), Status.PENDING));
    }
}

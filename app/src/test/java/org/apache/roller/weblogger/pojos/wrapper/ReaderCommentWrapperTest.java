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
package org.apache.roller.weblogger.pojos.wrapper;

import java.util.Date;

import org.apache.roller.weblogger.business.comments.CommentBodyRenderer;
import org.apache.roller.weblogger.pojos.ReaderComment;
import org.apache.roller.weblogger.pojos.ReaderComment.Status;
import org.apache.roller.weblogger.pojos.User;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a theme sees of a reader's comment. The reply is the subtle part:
 * {@code ReaderComment.getReply()} is the true inverse of {@code replyTo} and
 * so carries the reply in ANY status; the page shows it only when PUBLISHED.
 */
class ReaderCommentWrapperTest {

    private static User user(String userName, String screenName) {
        User u = new User();
        u.setUserName(userName);
        u.setScreenName(screenName);
        return u;
    }

    private static ReaderComment comment(String id, User author, Status status, boolean owner) {
        ReaderComment c = new ReaderComment();
        c.setId(id);
        c.setAuthor(author);
        c.setStatus(status);
        c.setAuthorIsOwner(owner);
        c.setBody("Nice https://ex.com/a\n\n<b>bold</b>");
        c.setCreated(new Date(1_700_000_000_000L));
        return c;
    }

    @Test
    void itShowsTheCommentsOwnValues() {
        ReaderComment c = comment("c1", user("reader-abc", "Ana"), Status.PUBLISHED, false);
        ReaderCommentWrapper w = ReaderCommentWrapper.wrap(c);

        assertEquals("c1", w.getId());
        assertEquals("Ana", w.getDisplayName());
        assertFalse(w.isAuthorIsOwner());
        assertEquals(new Date(1_700_000_000_000L), w.getCreated());
    }

    @Test
    void theDisplayNameIsRawForTheTemplateToEscape() {
        User raw = new User();
        raw.setUserName("reader-x");
        ReaderComment c = comment("c1", raw, Status.PUBLISHED, false);
        // set after construction so no sanitizer setting can alter it
        ReflectionTestUtils.setField(raw, "screenName", "<script>x</script>");

        assertEquals("<script>x</script>", ReaderCommentWrapper.wrap(c).getDisplayName());
    }

    @Test
    void theBodyIsRenderedByTheCommentBodyRenderer() {
        ReaderComment c = comment("c1", user("reader-abc", "Ana"), Status.PUBLISHED, false);
        String html = ReaderCommentWrapper.wrap(c).getBodyHtml();

        assertEquals(CommentBodyRenderer.toHtml(c.getBody()), html);
        assertTrue(html.contains("rel=\"nofollow ugc noopener\""), html);
        assertTrue(html.contains("&lt;b&gt;bold&lt;/b&gt;"), html);
    }

    @Test
    void anOwnerCommentSaysSo() {
        ReaderComment c = comment("c1", user("owner", "Jake"), Status.PUBLISHED, true);
        assertTrue(ReaderCommentWrapper.wrap(c).isAuthorIsOwner());
    }

    @Test
    void aPublishedReplyIsShown() {
        ReaderComment parent = comment("c1", user("reader-abc", "Ana"), Status.PUBLISHED, false);
        ReaderComment reply = comment("r1", user("owner", "Jake"), Status.PUBLISHED, true);
        parent.setReply(reply);
        reply.setReplyTo(parent);

        ReaderCommentWrapper shown = ReaderCommentWrapper.wrap(parent).getReply();
        assertNotNull(shown);
        assertEquals("r1", shown.getId());
        assertEquals("Jake", shown.getDisplayName());
        assertTrue(shown.isAuthorIsOwner());
    }

    @Test
    void aPendingOrHiddenReplyIsNotShown() {
        for (Status status : new Status[] {Status.PENDING, Status.HIDDEN}) {
            ReaderComment parent = comment("c1", user("reader-abc", "Ana"), Status.PUBLISHED, false);
            parent.setReply(comment("r1", user("owner", "Jake"), status, true));
            assertNull(ReaderCommentWrapper.wrap(parent).getReply(), status + " reply must not show");
        }
    }

    @Test
    void noReplyIsNull() {
        ReaderComment c = comment("c1", user("reader-abc", "Ana"), Status.PUBLISHED, false);
        assertNull(ReaderCommentWrapper.wrap(c).getReply());
    }

    @Test
    void aCommentWithNoAuthorRendersBlankNamesRatherThanFailing() {
        ReaderComment c = comment("c1", null, Status.PUBLISHED, false);
        ReaderCommentWrapper w = ReaderCommentWrapper.wrap(c);
        assertEquals("", w.getDisplayName());
    }

    @Test
    void anAuthorWithoutNamesAndACommentWithoutADateRenderBlank() {
        ReaderComment c = comment("c1", new User(), Status.PUBLISHED, false);
        c.setCreated(null);
        ReaderCommentWrapper w = ReaderCommentWrapper.wrap(c);
        assertEquals("", w.getDisplayName());
        assertNull(w.getCreated());
    }

    @Test
    void wrappingNullGivesNull() {
        assertNull(ReaderCommentWrapper.wrap(null));
    }
}

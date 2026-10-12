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
package org.apache.roller.weblogger.ui.controllers.editor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.CommentManager;
import org.apache.roller.weblogger.business.WeblogEntryManager;
import org.apache.roller.weblogger.business.comments.CommentOutcome;
import org.apache.roller.weblogger.business.comments.CommentRefusal;
import org.apache.roller.weblogger.business.comments.CommentService;
import org.apache.roller.weblogger.pojos.ReaderComment;
import org.apache.roller.weblogger.pojos.ReaderComment.Status;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.apache.roller.weblogger.pojos.WeblogPermission;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ui.Model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The moderation page: lists by tab, acts on one or many comments, and
 * ownership-checks every id against the action weblog.
 */
class CommentsModerationControllerTest extends EditorControllerTestSupport {

    private CommentsModerationController controller;
    private CommentService service;
    private CommentManager manager;
    private Model model;
    private Weblog other;

    @BeforeEach
    void setUp() {
        service = mock(CommentService.class);
        controller = prepare(new CommentsModerationController(service));
        model = newModel();
        manager = weblogger.commentManager();
        other = new Weblog();
        other.setId("weblog-2");
        other.setHandle("someoneelse");
        registerMessage("comments.approved", "{0} approved.");
        registerMessage("comments.hidden", "{0} hidden.");
        registerMessage("comments.deleted", "{0} deleted.");
    }

    @Test
    void declaresItsMenuAndPermissionMetadata() {
        assertEquals("comments", controller.getActionName());
        assertEquals("editor", controller.getDesiredMenu());
        assertEquals(List.of(WeblogPermission.POST), controller.requiredWeblogPermissionActions());
    }

    @Test
    void listsByTabAndPages() throws Exception {
        ReaderComment c = comment("c1", weblog, Status.PENDING, "hi");
        when(manager.getComments(weblog, Status.PENDING, null, 0, 30)).thenReturn(List.of(c));
        when(manager.countComments(weblog, Status.PENDING)).thenReturn(31L);

        assertEquals(".Comments", controller.execute("PENDING", 0, null, request, model));
        assertEquals(List.of(c), model.getAttribute("comments"));
        assertEquals(31L, model.getAttribute("commentCount"));
        assertEquals("PENDING", model.getAttribute("status"));

        Model second = newModel();
        controller.execute("HIDDEN", 2, null, request, second);
        verify(manager).getComments(weblog, Status.HIDDEN, null, 60, 30);
        assertEquals("HIDDEN", second.getAttribute("status"));
    }

    @Test
    void anUnknownStatusIsThePendingTab() throws Exception {
        controller.execute("bogus", 0, null, request, model);
        verify(manager).getComments(weblog, Status.PENDING, null, 0, 30);
    }

    @Test
    void anEntryOfThisWeblogFiltersTheList() throws Exception {
        WeblogEntry entry = entry("e1", weblog);
        controller.execute("PENDING", 0, "e1", request, model);
        verify(manager).getComments(weblog, Status.PENDING, entry, 0, 30);
        assertEquals("e1", model.getAttribute("entryFilter"));
    }

    @Test
    void anEntryOfAnotherWeblogIsIgnored() throws Exception {
        entry("e2", other);
        controller.execute("PENDING", 0, "e2", request, model);
        verify(manager).getComments(weblog, Status.PENDING, null, 0, 30);
        assertTrue(errors(model).isEmpty());
    }

    @Test
    void approveHideDeleteAndBulk() throws Exception {
        ReaderComment a = comment("a", weblog, Status.PENDING, "x");
        ReaderComment b = comment("b", weblog, Status.PENDING, "y");

        controller.approve(new String[] {"a", "b"}, "PENDING", request, model);
        verify(service).setStatus(a, Status.PUBLISHED, user);
        verify(service).setStatus(b, Status.PUBLISHED, user);
        assertTrue(messages(model).contains("2 approved."), messages(model).toString());

        Model m2 = newModel();
        controller.hide(new String[] {"a"}, "PUBLISHED", request, m2);
        verify(service).setStatus(a, Status.HIDDEN, user);
        assertTrue(messages(m2).contains("1 hidden."));

        Model m3 = newModel();
        controller.delete(new String[] {"a", "b"}, "HIDDEN", request, m3);
        verify(service).delete(a, user);
        verify(service).delete(b, user);
        assertTrue(messages(m3).contains("2 deleted."));
    }

    @Test
    void foreignIdsAreSkipped() throws Exception {
        ReaderComment mine = comment("m", weblog, Status.PENDING, "x");
        comment("f", other, Status.PENDING, "y");

        controller.approve(new String[] {"m", "f", "missing"}, "PENDING", request, model);
        controller.delete(new String[] {"f"}, "PENDING", request, newModel());
        controller.hide(new String[] {"f"}, "PENDING", request, newModel());

        verify(service).setStatus(mine, Status.PUBLISHED, user);
    }

    @Test
    void aForeignCommentIsNeverTouched() throws Exception {
        ReaderComment foreign = comment("f", other, Status.PENDING, "y");
        controller.approve(new String[] {"f"}, "PENDING", request, model);
        controller.hide(new String[] {"f"}, "PENDING", request, newModel());
        controller.delete(new String[] {"f"}, "PENDING", request, newModel());
        verify(service, never()).setStatus(any(), any(), any());
        verify(service, never()).delete(any(), any());
        assertTrue(messages(model).contains("0 approved."));
        assertEquals("f", foreign.getId());
    }

    @Test
    void aTombstoneCannotBeApproved() throws Exception {
        comment("t", weblog, Status.HIDDEN, "");
        controller.approve(new String[] {"t"}, "HIDDEN", request, model);
        verify(service, never()).setStatus(any(), any(), any());
    }

    @Test
    void aFailingServiceReportsAnError() throws Exception {
        ReaderComment a = comment("a", weblog, Status.PENDING, "x");
        org.mockito.Mockito.doThrow(new WebloggerException("down")).when(service)
                .setStatus(a, Status.PUBLISHED, user);
        controller.approve(new String[] {"a"}, "PENDING", request, model);
        assertTrue(errors(model).contains("generic.error.check.logs"));
    }

    @Test
    void replyPostsAnAuthorReply() throws Exception {
        ReaderComment parent = comment("p", weblog, Status.PUBLISHED, "q");
        ReaderComment stored = comment("r", weblog, Status.PUBLISHED, "thanks");
        when(service.reply(user, parent, "thanks")).thenReturn(CommentOutcome.stored(stored));

        controller.reply("p", "thanks", "PUBLISHED", request, model);

        verify(service).reply(user, parent, "thanks");
        assertTrue(messages(model).contains("comments.replied"), messages(model).toString());
    }

    @Test
    void aRefusedReplyShowsTheMessage() throws Exception {
        ReaderComment parent = comment("p", weblog, Status.PENDING, "q");
        when(service.reply(user, parent, "no")).thenReturn(CommentOutcome.refused(CommentRefusal.BAD_REPLY));

        controller.reply("p", "no", "PENDING", request, model);

        assertTrue(errors(model).contains("comments.reply.refused"), errors(model).toString());
    }

    @Test
    void aForeignCommentTakesNoReply() throws Exception {
        comment("f", other, Status.PUBLISHED, "q");
        controller.reply("f", "hi", "PUBLISHED", request, model);
        verify(service, never()).reply(any(), any(), any());
        assertTrue(errors(model).contains("comments.reply.refused"));
    }

    /** Review Focus 3: every reader-controlled field on the page goes through fn:escapeXml. */
    @Test
    void displayNameIsEscapedOnTheModerationPage() throws Exception {
        String jsp = Files.readString(Path.of("src/main/webapp/WEB-INF/jsps/editor/Comments.jsp"));
        // Strip JSP comments, then find every EL on a comment row variable.
        String src = jsp.replaceAll("(?s)<%--.*?--%>", "");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\$\\{([^}]*)\\}").matcher(src);
        int fields = 0;
        while (m.find()) {
            String el = m.group(1);
            if (el.contains("==") || el.contains("empty ")) {
                continue; // a condition, not output
            }
            if (el.contains("c.author.") || el.contains("c.body") || el.contains("c.reply.body")
                    || el.contains("c.entry.displayTitle")) {
                fields++;
                assertTrue(el.contains("fn:escapeXml"), "unescaped: ${" + el + "}");
            }
        }
        assertTrue(fields >= 3, "expected display name, username and body, found " + fields);
        assertTrue(src.contains("white-space: pre-wrap"));
    }

    private ReaderComment comment(String id, Weblog w, Status status, String body) throws Exception {
        WeblogEntry entry = new WeblogEntry();
        entry.setId("entry-" + id);
        entry.setWebsite(w);
        ReaderComment c = new ReaderComment();
        c.setId(id);
        c.setEntry(entry);
        c.setStatus(status);
        c.setBody(body);
        when(manager.getComment(id)).thenReturn(c);
        return c;
    }

    private WeblogEntry entry(String id, Weblog w) throws Exception {
        WeblogEntry e = new WeblogEntry();
        e.setId(id);
        e.setWebsite(w);
        WeblogEntryManager em = weblogger.weblogger().getWeblogEntryManager();
        when(em.getWeblogEntry(id)).thenReturn(e);
        return e;
    }

    @Test
    void theMainMenuAndTabsCarryTheTombstoneAndHideCopy() throws Exception {
        String jsp = Files.readString(Path.of("src/main/webapp/WEB-INF/jsps/editor/Comments.jsp"));
        assertTrue(jsp.contains("comments.tombstone"));
        assertTrue(jsp.contains("comments.hideVsDelete"));
        assertTrue(jsp.contains("id=\"commentsModerationForm\""));
        assertTrue(jsp.contains("data-confirm"));
        assertFalse(jsp.contains("onclick"));
    }
}

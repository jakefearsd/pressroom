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
import org.apache.roller.weblogger.ui.core.security.sso.SsoLogCapture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ui.Model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
        try {
            // The service answers whether it moved the comment; it does unless a test says not.
            when(service.setStatus(any(), any(), any())).thenReturn(true);
        } catch (WebloggerException e) {
            throw new IllegalStateException(e);
        }
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
        when(manager.countComments(weblog, Status.PENDING, null)).thenReturn(31L);

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

    /**
     * M6: the pager's "Older" link shows while (page + 1) * 30 is below the
     * count, so the count must be of what the list shows: with an entry filter,
     * that entry's comments, not the whole weblog's.
     */
    @Test
    void theCountFollowsTheEntryFilter() throws Exception {
        WeblogEntry entry = entry("e1", weblog);
        when(manager.countComments(weblog, Status.PUBLISHED, entry)).thenReturn(3L);
        when(manager.countComments(weblog, Status.PUBLISHED, null)).thenReturn(95L);
        when(manager.countComments(weblog, Status.PUBLISHED)).thenReturn(95L);

        controller.execute("PUBLISHED", 0, "e1", request, model);

        assertEquals(3L, model.getAttribute("commentCount"));
    }

    /**
     * M3: page is client input, multiplied by the page size into an offset. A
     * negative page would be a negative first result and a huge one overflows
     * int; both are clamped, on the list and on every action's return to it.
     */
    @Test
    void aNegativeOrHugePageIsClampedNotAnError() throws Exception {
        controller.execute("PENDING", -3, null, request, model);
        verify(manager).getComments(weblog, Status.PENDING, null, 0, 30);
        assertEquals(0, model.getAttribute("page"));

        Model huge = newModel();
        controller.execute("PUBLISHED", Integer.MAX_VALUE, null, request, huge);
        int clamped = (Integer) huge.getAttribute("page");
        assertTrue(clamped > 0 && clamped < Integer.MAX_VALUE / 30, "clamped to a sane cap: " + clamped);
        verify(manager).getComments(weblog, Status.PUBLISHED, null, clamped * 30, 30);
        assertTrue(errors(huge).isEmpty(), errors(huge).toString());

        Model posted = newModel();
        controller.hide(new String[0], "HIDDEN", null, Integer.MIN_VALUE, request, posted);
        verify(manager).getComments(weblog, Status.HIDDEN, null, 0, 30);
        assertEquals(0, posted.getAttribute("page"));
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

        controller.approve(new String[] {"a", "b"}, "PENDING", null, 0, request, model);
        verify(service).setStatus(a, Status.PUBLISHED, user);
        verify(service).setStatus(b, Status.PUBLISHED, user);
        assertTrue(messages(model).contains("2 approved."), messages(model).toString());

        Model m2 = newModel();
        controller.hide(new String[] {"a"}, "PUBLISHED", null, 0, request, m2);
        verify(service).setStatus(a, Status.HIDDEN, user);
        assertTrue(messages(m2).contains("1 hidden."));

        Model m3 = newModel();
        controller.delete(new String[] {"a", "b"}, "HIDDEN", null, 0, request, m3);
        verify(service).delete(a, user);
        verify(service).delete(b, user);
        assertTrue(messages(m3).contains("2 deleted."));
    }

    @Test
    void foreignIdsAreSkipped() throws Exception {
        ReaderComment mine = comment("m", weblog, Status.PENDING, "x");
        comment("f", other, Status.PENDING, "y");

        controller.approve(new String[] {"m", "f", "missing"}, "PENDING", null, 0, request, model);
        controller.delete(new String[] {"f"}, "PENDING", null, 0, request, newModel());
        controller.hide(new String[] {"f"}, "PENDING", null, 0, request, newModel());

        verify(service).setStatus(mine, Status.PUBLISHED, user);
    }

    @Test
    void aForeignCommentIsNeverTouched() throws Exception {
        ReaderComment foreign = comment("f", other, Status.PENDING, "y");
        controller.approve(new String[] {"f"}, "PENDING", null, 0, request, model);
        controller.hide(new String[] {"f"}, "PENDING", null, 0, request, newModel());
        controller.delete(new String[] {"f"}, "PENDING", null, 0, request, newModel());
        verify(service, never()).setStatus(any(), any(), any());
        verify(service, never()).delete(any(), any());
        assertTrue(messages(model).contains("0 approved."));
        assertEquals("f", foreign.getId());
    }

    /**
     * I2: the tombstone rule lives in the service (the API reaches it too); the
     * page acts on the service's answer, so a refused move is not counted.
     */
    @Test
    void aTombstoneTheServiceRefusesIsNotCounted() throws Exception {
        ReaderComment t = comment("t", weblog, Status.HIDDEN, "");
        ReaderComment a = comment("a", weblog, Status.PENDING, "x");
        when(service.setStatus(t, Status.PUBLISHED, user)).thenReturn(false);

        controller.approve(new String[] {"t", "a"}, "HIDDEN", null, 0, request, model);

        verify(service).setStatus(t, Status.PUBLISHED, user);
        verify(service).setStatus(a, Status.PUBLISHED, user);
        assertTrue(messages(model).contains("1 approved."), messages(model).toString());
        assertTrue(errors(model).isEmpty(), errors(model).toString());
    }

    @Test
    void aFailingServiceReportsAnError() throws Exception {
        ReaderComment a = comment("a", weblog, Status.PENDING, "x");
        org.mockito.Mockito.doThrow(new WebloggerException("down")).when(service)
                .setStatus(a, Status.PUBLISHED, user);
        controller.approve(new String[] {"a"}, "PENDING", null, 0, request, model);
        assertTrue(errors(model).contains("generic.error.check.logs"));
    }

    @Test
    void replyPostsAnAuthorReply() throws Exception {
        ReaderComment parent = comment("p", weblog, Status.PUBLISHED, "q");
        ReaderComment stored = comment("r", weblog, Status.PUBLISHED, "thanks");
        when(service.reply(user, parent, "thanks")).thenReturn(CommentOutcome.stored(stored));

        controller.reply("p", "thanks", "PUBLISHED", null, 0, request, model);

        verify(service).reply(user, parent, "thanks");
        assertTrue(messages(model).contains("comments.replied"), messages(model).toString());
    }

    @Test
    void aRefusedReplyShowsTheMessage() throws Exception {
        ReaderComment parent = comment("p", weblog, Status.PENDING, "q");
        when(service.reply(user, parent, "no")).thenReturn(CommentOutcome.refused(CommentRefusal.BAD_REPLY));

        controller.reply("p", "no", "PENDING", null, 0, request, model);

        assertTrue(errors(model).contains("comments.reply.refused"), errors(model).toString());
    }

    /**
     * M5: an owner's replies count toward the rate limit, so a refused reply
     * can be the limit, which is not "that comment cannot take a reply".
     */
    @Test
    void aRateLimitedReplySaysSo() throws Exception {
        ReaderComment parent = comment("p", weblog, Status.PUBLISHED, "q");
        when(service.reply(user, parent, "again")).thenReturn(CommentOutcome.refused(CommentRefusal.RATE_LIMITED));

        controller.reply("p", "again", "PUBLISHED", null, 0, request, model);

        assertEquals(List.of("comments.reply.rateLimited"), errors(model));
    }

    /**
     * M7: ids are request parameters. A CR/LF in one must not reach a log line,
     * where it could forge a line of its own: neither the refusal of an unknown
     * id nor the error of a failed lookup logs the raw id.
     */
    @Test
    void aForgedIdNeverReachesTheLog() throws Exception {
        String forged = "x\r\n2026-10-12 ERROR FORGEDLINE";
        when(manager.getComment("y\r\nFORGEDREPLY")).thenThrow(new WebloggerException("down"));
        List<String> lines;
        try (SsoLogCapture capture = SsoLogCapture.everyLogger()) {
            controller.approve(new String[] {forged}, "PENDING", null, 0, request, newModel());
            controller.reply("y\r\nFORGEDREPLY", "hi", "PUBLISHED", null, 0, request, newModel());
            lines = capture.everything();
        }
        assertTrue(lines.stream().anyMatch(l -> l.contains(weblog.getHandle())),
                "the capture saw the refusals: " + lines);
        for (String line : lines) {
            assertFalse(line.contains("FORGED"), "a raw id was logged: " + line);
        }
    }

    @Test
    void aForeignCommentTakesNoReply() throws Exception {
        comment("f", other, Status.PUBLISHED, "q");
        controller.reply("f", "hi", "PUBLISHED", null, 0, request, model);
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
) {
                fields++;
                assertTrue(el.contains("fn:escapeXml"), "unescaped: ${" + el + "}");
            }
        }
        assertTrue(fields >= 3, "expected display name, username and body, found " + fields);
        assertTrue(src.contains("white-space: pre-wrap"));
        // The entry title is the controller's plain-text label escaped once, as on Entries.jsp: the
        // stored title is already escaped (escaping it again shows "Fish &amp; Chips"), and an untitled
        // entry's displayTitle is raw body text, so neither stored form is printed.
        assertTrue(src.contains("${fn:escapeXml(entryTitles[c.entry.id])}"));
        assertFalse(src.contains("c.entry.displayTitle"));
    }

    /** The pager reads the page size from the model rather than hard-coding 30 in the JSP. */
    @Test
    void thePageSizeIsGivenToThePager() throws Exception {
        controller.execute("PENDING", 0, null, request, model);

        assertEquals(30, model.getAttribute("pageSize"));
    }

    /**
     * The entry column shows each comment's entry title as plain text for the
     * page to escape once (see EntriesController.plainTitle): the stored title
     * is already escaped, and an untitled entry's fallback is raw body text.
     */
    @Test
    void eachCommentsEntryGetsAPlainTextTitle() throws Exception {
        ReaderComment c = comment("c1", weblog, Status.PENDING, "hi");
        c.getEntry().setTitle("Tom &amp; Jerry");
        when(manager.getComments(weblog, Status.PENDING, null, 0, 30)).thenReturn(List.of(c));

        controller.execute("PENDING", 0, null, request, model);

        @SuppressWarnings("unchecked")
        java.util.Map<String, String> titles = (java.util.Map<String, String>) model.getAttribute("entryTitles");
        assertNotNull(titles, "the moderation list has no entryTitles map");
        assertEquals("Tom & Jerry", titles.get("entry-c1"));
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

    @Test
    void anActionReturnsToTheTabPageAndEntryFilter() throws Exception {
        ReaderComment a = comment("a", weblog, Status.PUBLISHED, "x");
        WeblogEntry e = entry("e1", weblog);

        String view = controller.hide(new String[] {"a"}, "PUBLISHED", "e1", 2, request, model);

        assertEquals(".Comments", view);
        verify(manager).getComments(weblog, Status.PUBLISHED, e, 60, 30);
        assertEquals("PUBLISHED", model.getAttribute("status"));
        assertEquals(2, model.getAttribute("page"));
        assertEquals("e1", model.getAttribute("entryFilter"));
        verify(service).setStatus(a, Status.HIDDEN, user);
    }

    @Test
    void anActionIgnoresAForeignEntryFilterLikeTheList() throws Exception {
        comment("a", weblog, Status.PUBLISHED, "x");
        entry("e2", other);

        controller.reply("a", "hi", "PUBLISHED", "e2", 0, request, model);

        verify(manager).getComments(weblog, Status.PUBLISHED, null, 0, 30);
        assertEquals(null, model.getAttribute("entryFilter"));
    }
}

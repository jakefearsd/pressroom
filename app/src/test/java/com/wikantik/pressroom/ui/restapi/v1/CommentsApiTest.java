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
package com.wikantik.pressroom.ui.restapi.v1;

import java.util.Date;
import java.util.List;

import com.wikantik.pressroom.business.CommentManager;
import com.wikantik.pressroom.business.WeblogEntryManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.comments.CommentOutcome;
import com.wikantik.pressroom.business.comments.CommentRefusal;
import com.wikantik.pressroom.business.comments.CommentService;
import com.wikantik.pressroom.pojos.ReaderComment;
import com.wikantik.pressroom.pojos.ReaderComment.Status;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogPermission;
import com.wikantik.pressroom.ui.restapi.ApiExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static com.wikantik.pressroom.ui.restapi.ErrorFieldMatchers.errorFieldIs;

/**
 * The comment-moderation API: routing, filters and paging, the status and
 * reply verbs, the ownership check (404, never 403), and the view's shape --
 * in particular that no response ever carries an email address.
 */
class CommentsApiTest {

    private static final String BASE = "/v1/weblogs/myblog/comments";

    private Weblogger weblogger;
    private CommentManager comments;
    private CommentService service;
    private WeblogEntryManager entries;
    private Weblog weblog;
    private Weblog other;
    private WeblogEntry entry;
    private User owner;
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        weblogger = mock(Weblogger.class);
        comments = mock(CommentManager.class);
        entries = mock(WeblogEntryManager.class);
        service = mock(CommentService.class);
        when(weblogger.getCommentManager()).thenReturn(comments);
        when(weblogger.getWeblogEntryManager()).thenReturn(entries);
        // The service moves a comment unless a test says it refuses.
        when(service.setStatus(any(), any(), any())).thenReturn(true);

        weblog = aWeblog("myblog");
        other = aWeblog("other");
        entry = anEntry(weblog, "e1", "Tom &amp; Jerry");
        owner = aUser("jake", "Jake");

        CommentsApi controller = new CommentsApi();
        controller.weblogger = weblogger;
        controller.commentService = service;
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private static Weblog aWeblog(String handle) {
        Weblog w = new Weblog();
        w.setId(handle + "-id");
        w.setHandle(handle);
        return w;
    }

    private static WeblogEntry anEntry(Weblog w, String id, String title) {
        WeblogEntry e = new WeblogEntry();
        e.setId(id);
        e.setWebsite(w);
        e.setTitle(title);
        return e;
    }

    private static User aUser(String name, String screen) {
        User u = new User();
        u.setId("id-" + name);
        u.setUserName(name);
        u.setScreenName(screen);
        u.setEmailAddress("secret@example.com");
        return u;
    }

    private ReaderComment aComment(String id, WeblogEntry on, Status s, String body) {
        ReaderComment c = new ReaderComment();
        c.setId(id);
        c.setEntry(on);
        c.setAuthor(aUser("reader", "Rea der"));
        c.setStatus(s);
        c.setBody(body);
        c.setCreated(new Date(1724500000000L));
        c.setStatusChanged(new Date(1724500001000L));
        return c;
    }

    private JsonNode json(String body) throws Exception {
        return new ObjectMapper().readTree(body);
    }

    private String ok(org.springframework.test.web.servlet.RequestBuilder request) throws Exception {
        return mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    @Test
    void listFiltersAndPages() throws Exception {
        ReaderComment a = aComment("c1", entry, Status.PENDING, "first");
        ReaderComment b = aComment("c2", entry, Status.PENDING, "second");
        ReaderComment c = aComment("c3", entry, Status.PENDING, "third");
        when(entries.getWeblogEntry("e1")).thenReturn(entry);
        // limit + 1 rows asked for: one extra row decides hasMore
        when(comments.getComments(weblog, Status.PENDING, entry, 10, 3)).thenReturn(List.of(a, b, c));

        JsonNode page = json(ok(get(BASE).requestAttr("actionWeblog", weblog)
                .param("status", "pending").param("entry", "e1").param("limit", "2").param("offset", "10")));

        assertEquals(2, page.get("items").size());
        assertTrue(page.get("hasMore").asBoolean());
        assertEquals(10, page.get("offset").asInt());
        assertEquals(2, page.get("limit").asInt());
        assertEquals("c1", page.get("items").get(0).get("id").asString());
        assertEquals("PENDING", page.get("items").get(0).get("status").asString());
    }

    /** {@code entryId} is the name the comment view and the rest of the API use; {@code entry} stays as its alias. */
    @Test
    void listTakesEntryIdAndStillTakesEntry() throws Exception {
        when(entries.getWeblogEntry("e1")).thenReturn(entry);
        when(comments.getComments(eq(weblog), eq(null), eq(entry), anyInt(), anyInt())).thenReturn(List.of());

        ok(get(BASE).requestAttr("actionWeblog", weblog).param("entryId", "e1"));
        ok(get(BASE).requestAttr("actionWeblog", weblog).param("entry", "e1"));
        ok(get(BASE).requestAttr("actionWeblog", weblog).param("entryId", "e1").param("entry", "e1"));
        verify(comments, org.mockito.Mockito.times(3)).getComments(weblog, null, entry, 0, 51);

        mvc.perform(get(BASE).requestAttr("actionWeblog", weblog).param("entryId", "e1").param("entry", "e2"))
                .andExpect(status().isBadRequest())
                .andExpect(com.wikantik.pressroom.ui.restapi.ErrorFieldMatchers.errorFieldIs("entry"));
    }

    @Test
    void listDefaultsToFiftyAndCapsAtTwoHundred() throws Exception {
        when(comments.getComments(eq(weblog), eq(null), eq(null), anyInt(), anyInt())).thenReturn(List.of());

        assertEquals(50, json(ok(get(BASE).requestAttr("actionWeblog", weblog))).get("limit").asInt());
        verify(comments).getComments(weblog, null, null, 0, 51);
        assertEquals(200, json(ok(get(BASE).requestAttr("actionWeblog", weblog).param("limit", "5000")))
                .get("limit").asInt());
        verify(comments).getComments(weblog, null, null, 0, 201);
    }

    @Test
    void listRefusesBadParameters() throws Exception {
        mvc.perform(get(BASE).requestAttr("actionWeblog", weblog).param("limit", "0"))
                .andExpect(status().isBadRequest());
        mvc.perform(get(BASE).requestAttr("actionWeblog", weblog).param("offset", "-1"))
                .andExpect(status().isBadRequest());
        mvc.perform(get(BASE).requestAttr("actionWeblog", weblog).param("status", "bogus"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listWithAnEntryOfAnotherWeblogIs404() throws Exception {
        WeblogEntry foreign = anEntry(other, "e9", "x");
        when(entries.getWeblogEntry("e9")).thenReturn(foreign);

        mvc.perform(get(BASE).requestAttr("actionWeblog", weblog).param("entry", "e9"))
                .andExpect(status().isNotFound());
        mvc.perform(get(BASE).requestAttr("actionWeblog", weblog).param("entry", "nope"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getOne() throws Exception {
        ReaderComment parent = aComment("c1", entry, Status.PUBLISHED, "hello");
        ReaderComment reply = aComment("c2", entry, Status.HIDDEN, "thanks");
        parent.setReply(reply);
        reply.setReplyTo(parent);
        when(comments.getComment("c1")).thenReturn(parent);
        when(comments.getComment("c2")).thenReturn(reply);

        JsonNode v = json(ok(get(BASE + "/c1").requestAttr("actionWeblog", weblog)));
        assertEquals("c1", v.get("id").asString());
        assertEquals("e1", v.get("entryId").asString());
        assertEquals("Tom &amp; Jerry", v.get("entryTitle").asString(), "titles are stored escaped and pass through");
        assertEquals("PUBLISHED", v.get("status").asString());
        assertEquals("hello", v.get("body").asString());
        assertEquals("reader", v.get("authorUserName").asString());
        assertEquals("Rea der", v.get("authorDisplayName").asString());
        assertFalse(v.get("authorIsOwner").asBoolean());
        assertEquals("c2", v.get("reply").asString(), "a reply of any status is named");
        assertTrue(v.hasNonNull("created"));
        assertTrue(v.hasNonNull("statusChanged"));
        assertEquals("c1", json(ok(get(BASE + "/c2").requestAttr("actionWeblog", weblog))).get("replyTo").asString());
    }

    @Test
    void aTombstoneIsReturnedAsIsWithAnEmptyBody() throws Exception {
        ReaderComment gone = aComment("c1", entry, Status.HIDDEN, "");
        when(comments.getComment("c1")).thenReturn(gone);

        JsonNode v = json(ok(get(BASE + "/c1").requestAttr("actionWeblog", weblog)));
        assertEquals("", v.get("body").asString());
        assertEquals("HIDDEN", v.get("status").asString());
    }

    @Test
    void patchSetsStatus() throws Exception {
        ReaderComment c = aComment("c1", entry, Status.PENDING, "hello");
        when(comments.getComment("c1")).thenReturn(c);
        User actor = owner;

        String body = mvc.perform(patch(BASE + "/c1").requestAttr("actionWeblog", weblog)
                        .requestAttr("authenticatedUser", actor)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"PUBLISHED\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        verify(service).setStatus(c, Status.PUBLISHED, actor);
        assertEquals("c1", json(body).get("id").asString());

        mvc.perform(patch(BASE + "/c1").requestAttr("actionWeblog", weblog)
                        .requestAttr("authenticatedUser", actor)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"hidden\"}"))
                .andExpect(status().isOk());
        verify(service).setStatus(c, Status.HIDDEN, actor);
    }

    /**
     * I2: the API reaches the same tombstone rule as the moderation page, in the
     * service. A refused move is a 409 (the comment's state forbids it), never a
     * 200 that published an erased comment.
     */
    @Test
    void publishingATombstoneIsAConflict() throws Exception {
        ReaderComment tombstone = aComment("t1", entry, Status.HIDDEN, "");
        when(comments.getComment("t1")).thenReturn(tombstone);
        when(service.setStatus(tombstone, Status.PUBLISHED, owner)).thenReturn(false);

        mvc.perform(patch(BASE + "/t1").requestAttr("actionWeblog", weblog)
                        .requestAttr("authenticatedUser", owner)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"PUBLISHED\"}"))
                .andExpect(status().isConflict());
        verify(service).setStatus(tombstone, Status.PUBLISHED, owner);
    }

    @Test
    void patchRejectsOtherValues() throws Exception {
        ReaderComment c = aComment("c1", entry, Status.PUBLISHED, "hello");
        when(comments.getComment("c1")).thenReturn(c);

        for (String bad : new String[] {"{\"status\":\"PENDING\"}", "{\"status\":\"DELETED\"}",
            "{\"status\":\"\"}", "{}"}) {
            mvc.perform(patch(BASE + "/c1").requestAttr("actionWeblog", weblog)
                            .requestAttr("authenticatedUser", owner)
                            .contentType(MediaType.APPLICATION_JSON).content(bad))
                    .andExpect(status().isBadRequest());
        }
        verify(service, never()).setStatus(any(), any(), any());
    }

    @Test
    void deleteRemoves() throws Exception {
        ReaderComment c = aComment("c1", entry, Status.PUBLISHED, "hello");
        when(comments.getComment("c1")).thenReturn(c);

        mvc.perform(delete(BASE + "/c1").requestAttr("actionWeblog", weblog)
                        .requestAttr("authenticatedUser", owner))
                .andExpect(status().isNoContent());

        verify(service).delete(c, owner);
    }

    @Test
    void replyCreatesAnAuthorReply() throws Exception {
        ReaderComment parent = aComment("c1", entry, Status.PUBLISHED, "hello");
        ReaderComment reply = aComment("c2", entry, Status.PUBLISHED, "thanks");
        reply.setReplyTo(parent);
        reply.setAuthorIsOwner(true);
        when(comments.getComment("c1")).thenReturn(parent);
        when(service.reply(owner, parent, "thanks")).thenReturn(CommentOutcome.stored(reply));

        String body = mvc.perform(post(BASE + "/c1/replies").requestAttr("actionWeblog", weblog)
                        .requestAttr("authenticatedUser", owner)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"thanks\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        JsonNode v = json(body);
        assertEquals("c2", v.get("id").asString());
        assertEquals("c1", v.get("replyTo").asString());
        assertTrue(v.get("authorIsOwner").asBoolean());
    }

    @Test
    void aRefusedReplyIs400WithTheCode() throws Exception {
        ReaderComment parent = aComment("c1", entry, Status.PUBLISHED, "hello");
        when(comments.getComment("c1")).thenReturn(parent);
        when(service.reply(owner, parent, "x")).thenReturn(CommentOutcome.refused(CommentRefusal.BAD_REPLY));

        String body = mvc.perform(post(BASE + "/c1/replies").requestAttr("actionWeblog", weblog)
                        .requestAttr("authenticatedUser", owner)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("BAD_REPLY"), body);
    }

    @Test
    void anotherWeblogsCommentIs404OnEveryVerb() throws Exception {
        ReaderComment foreign = aComment("c9", anEntry(other, "e9", "x"), Status.PENDING, "hi");
        when(comments.getComment("c9")).thenReturn(foreign);

        mvc.perform(get(BASE + "/c9").requestAttr("actionWeblog", weblog)).andExpect(status().isNotFound());
        mvc.perform(get(BASE + "/missing").requestAttr("actionWeblog", weblog)).andExpect(status().isNotFound());
        mvc.perform(patch(BASE + "/c9").requestAttr("actionWeblog", weblog).requestAttr("authenticatedUser", owner)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"HIDDEN\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(delete(BASE + "/c9").requestAttr("actionWeblog", weblog).requestAttr("authenticatedUser", owner))
                .andExpect(status().isNotFound());
        mvc.perform(post(BASE + "/c9/replies").requestAttr("actionWeblog", weblog)
                        .requestAttr("authenticatedUser", owner)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"x\"}"))
                .andExpect(status().isNotFound());

        verify(service, never()).setStatus(any(), any(), any());
        verify(service, never()).delete(any(), any());
        verify(service, never()).reply(any(), any(), any());
    }

    @Test
    void aTokenWithoutPostOrLimitedToAnotherWeblogGets404() throws Exception {
        CommentsApi api = new CommentsApi();
        assertEquals(List.of(WeblogPermission.POST), api.requiredWeblogPermissionActions());
        assertTrue(api.requiredGlobalPermissionActions().isEmpty());
        assertTrue(api.isUserRequired());
        assertTrue(api.isWeblogRequired());

        // the handler interceptor leaves no actionWeblog for a handle the token cannot act on
        mvc.perform(get(BASE)).andExpect(status().isNotFound());
        mvc.perform(get(BASE + "/c1")).andExpect(status().isNotFound());
        mvc.perform(delete(BASE + "/c1")).andExpect(status().isNotFound());
    }

    @Test
    void noResponseContainsAnEmail() throws Exception {
        ReaderComment parent = aComment("c1", entry, Status.PUBLISHED, "hello");
        ReaderComment reply = aComment("c2", entry, Status.PUBLISHED, "thanks");
        reply.setReplyTo(parent);
        parent.setReply(reply);
        when(comments.getComment("c1")).thenReturn(parent);
        when(comments.getComments(weblog, null, null, 0, 51)).thenReturn(List.of(parent, reply));
        when(service.reply(owner, parent, "thanks")).thenReturn(CommentOutcome.stored(reply));

        List<String> bodies = List.of(
                ok(get(BASE).requestAttr("actionWeblog", weblog)),
                ok(get(BASE + "/c1").requestAttr("actionWeblog", weblog)),
                ok(patch(BASE + "/c1").requestAttr("actionWeblog", weblog).requestAttr("authenticatedUser", owner)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"HIDDEN\"}")),
                mvc.perform(post(BASE + "/c1/replies").requestAttr("actionWeblog", weblog)
                        .requestAttr("authenticatedUser", owner)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"thanks\"}"))
                        .andReturn().getResponse().getContentAsString());
        for (String body : bodies) {
            assertFalse(body.contains("secret@example.com"), body);
            assertFalse(body.contains("example.com"), body);
            assertFalse(body.contains("id-reader"), "the author's id is not part of the view: " + body);
        }
    }

    @Test
    void moderationWorksWithCommentsOff() throws Exception {
        weblog.setCommentsEnabled(false);
        ReaderComment c = aComment("c1", entry, Status.PUBLISHED, "hello");
        when(comments.getComment("c1")).thenReturn(c);
        when(comments.getComments(weblog, null, null, 0, 51)).thenReturn(List.of(c));

        assertEquals(1, json(ok(get(BASE).requestAttr("actionWeblog", weblog))).get("items").size());
        mvc.perform(patch(BASE + "/c1").requestAttr("actionWeblog", weblog).requestAttr("authenticatedUser", owner)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"HIDDEN\"}"))
                .andExpect(status().isOk());
        mvc.perform(delete(BASE + "/c1").requestAttr("actionWeblog", weblog).requestAttr("authenticatedUser", owner))
                .andExpect(status().isNoContent());
        verify(service).setStatus(c, Status.HIDDEN, owner);
        verify(service).delete(c, owner);
    }

    @Test
    void aListStatusFilterNamesTheStatusField() throws Exception {
        mvc.perform(get(BASE).requestAttr("actionWeblog", weblog).param("status", "bogus"))
                .andExpect(status().isBadRequest())
                .andExpect(errorFieldIs("status"));
    }

    @Test
    void aPatchStatusOtherThanPublishedOrHiddenNamesTheStatusField() throws Exception {
        ReaderComment c = aComment("c1", entry, Status.PUBLISHED, "hello");
        when(comments.getComment("c1")).thenReturn(c);

        mvc.perform(patch(BASE + "/c1").requestAttr("actionWeblog", weblog)
                        .requestAttr("authenticatedUser", owner)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"PENDING\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(errorFieldIs("status"));
    }
}

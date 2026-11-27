package com.wikantik.pressroom.ui.restapi.v1;

import java.util.Date;
import java.util.List;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.CommentManager;
import com.wikantik.pressroom.business.URLStrategy;
import com.wikantik.pressroom.business.UserManager;
import com.wikantik.pressroom.business.WeblogManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.pojos.GlobalPermission;
import com.wikantik.pressroom.pojos.ReaderComment;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogPermission;
import com.wikantik.pressroom.ui.restapi.ApiExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * {@code GET /v1/me/comments}, the cross-blog approval queue (0.18.0 Part B): which blogs it asks the manager
 * about, how a row is shaped, and the page rules. The token refusal is the authorization matrix's row.
 */
class MeCommentsApiTest {

    private CommentManager comments;
    private UserManager users;
    private WeblogManager weblogs;
    private MockMvc mvc;
    private User user;

    @BeforeEach
    void setUp() {
        Weblogger weblogger = mock(Weblogger.class);
        users = mock(UserManager.class);
        weblogs = mock(WeblogManager.class);
        comments = mock(CommentManager.class);
        URLStrategy urls = mock(URLStrategy.class);
        when(weblogger.getUserManager()).thenReturn(users);
        when(weblogger.getWeblogManager()).thenReturn(weblogs);
        when(weblogger.getCommentManager()).thenReturn(comments);
        when(weblogger.getUrlStrategy()).thenReturn(urls);
        when(urls.getWeblogEntryURL(any(Weblog.class), any(), any(), eq(true)))
                .thenAnswer(i -> "https://blog.example.com/" + ((Weblog) i.getArgument(0)).getHandle()
                        + "/entry/" + i.getArgument(2));
        MeCommentsApi api = new MeCommentsApi();
        ReflectionTestUtils.setField(api, "weblogger", weblogger);
        mvc = MockMvcBuilders.standaloneSetup(api).setControllerAdvice(new ApiExceptionHandler()).build();
        user = new User();
        user.setUserName("alice");
    }

    private Weblog blog(String handle) throws WebloggerException {
        Weblog weblog = new Weblog();
        weblog.setHandle(handle);
        weblog.setName("Name of " + handle);
        when(weblogs.getWeblogByHandle(handle)).thenReturn(weblog);
        return weblog;
    }

    private WeblogPermission row(Weblog weblog, String... actions) {
        return new WeblogPermission(weblog, user, List.of(actions));
    }

    private ReaderComment comment(Weblog weblog, String id, String body) {
        WeblogEntry entry = new WeblogEntry();
        entry.setId("e-" + id);
        entry.setAnchor("anchor-" + id);
        entry.setTitle("Title &amp; " + id);
        entry.setWebsite(weblog);
        User author = new User();
        author.setUserName("reader");
        author.setScreenName("Rita");
        ReaderComment c = new ReaderComment();
        c.setId(id);
        c.setEntry(entry);
        c.setAuthor(author);
        c.setBody(body);
        c.setStatus(ReaderComment.Status.PENDING);
        c.setCreated(new Date(1_700_000_000_000L));
        return c;
    }

    private JsonNode call(String query) throws Exception {
        String body = mvc.perform(get("/v1/me/comments" + query).requestAttr("authenticatedUser", user))
                .andReturn().getResponse().getContentAsString();
        return new ObjectMapper().readTree(body);
    }

    @Test
    void rowsCarryTheBlogAndTheEntryAddressAcrossBlogs() throws Exception {
        Weblog a = blog("alpha");
        Weblog b = blog("beta");
        when(users.getWeblogPermissions(user)).thenReturn(List.of(
                row(a, WeblogPermission.ADMIN, WeblogPermission.POST), row(b, WeblogPermission.POST)));
        when(comments.getComments(any(), eq(ReaderComment.Status.PENDING), eq(0), eq(51)))
                .thenReturn(List.of(comment(b, "c2", "second"), comment(a, "c1", "first")));
        when(comments.countComments(anyCollection(), eq(ReaderComment.Status.PENDING))).thenReturn(2L);

        JsonNode page = call("");

        assertEquals(2, page.get("total").asLong());
        assertFalse(page.get("hasMore").asBoolean());
        JsonNode first = page.get("items").get(0);
        assertEquals("c2", first.get("id").asString());
        assertEquals("beta", first.get("weblog").get("handle").asString());
        assertEquals("Name of beta", first.get("weblog").get("name").asString());
        assertEquals("Title & c2", first.get("entryTitle").asString());
        assertEquals("https://blog.example.com/beta/entry/anchor-c2", first.get("entryPermalink").asString());
        assertEquals("second", first.get("body").asString());
        assertEquals("alpha", page.get("items").get(1).get("weblog").get("handle").asString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void onlyBlogsWherePostIsImpliedAreAsked() throws Exception {
        Weblog moderated = blog("moderated");
        Weblog draftOnly = blog("draftonly");
        when(users.getWeblogPermissions(user)).thenReturn(List.of(
                row(moderated, WeblogPermission.POST), row(draftOnly, WeblogPermission.EDIT_DRAFT)));

        call("");

        ArgumentCaptor<java.util.Collection<Weblog>> asked = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(comments).getComments(asked.capture(), any(), anyInt(), anyInt());
        assertEquals(List.of("moderated"), asked.getValue().stream().map(Weblog::getHandle).toList());
    }

    /** A site admin sees only blogs they are a member of: the global role is never consulted for the queue. */
    @Test
    @SuppressWarnings("unchecked")
    void aGlobalAdminSeesOnlyTheBlogsTheyAreAMemberOf() throws Exception {
        Weblog mine = blog("mine");
        Weblog elsewhere = blog("elsewhere");
        when(users.checkPermission(any(), eq(user))).thenReturn(false);
        when(users.checkPermission(argThat(p -> p instanceof GlobalPermission g
                && g.getActionsAsList().equals(List.of(GlobalPermission.ADMIN))), eq(user))).thenReturn(true);
        when(weblogs.getWeblogs(any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(List.of(mine, elsewhere));
        when(users.getWeblogPermissions(user)).thenReturn(List.of(row(mine, WeblogPermission.POST)));
        // The manager answers as a real one would: only what it is asked about.
        when(comments.getComments(anyCollection(), any(), anyInt(), anyInt())).thenAnswer(i -> {
            java.util.Collection<Weblog> asked = i.getArgument(0);
            List<ReaderComment> found = new java.util.ArrayList<>();
            if (asked.contains(mine)) {
                found.add(comment(mine, "c-mine", "mine"));
            }
            if (asked.contains(elsewhere)) {
                found.add(comment(elsewhere, "c-else", "else"));
            }
            return found;
        });
        when(comments.countComments(anyCollection(), any())).thenAnswer(i ->
                ((java.util.Collection<Weblog>) i.getArgument(0)).size() * 1L);

        JsonNode page = call("");

        ArgumentCaptor<java.util.Collection<Weblog>> asked = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(comments).getComments(asked.capture(), any(), anyInt(), anyInt());
        assertEquals(List.of("mine"), asked.getValue().stream().map(Weblog::getHandle).toList());
        assertEquals(1, page.get("total").asLong());
        assertEquals(1, page.get("items").size());
        assertEquals("mine", page.get("items").get(0).get("weblog").get("handle").asString());
    }

    @Test
    void aUserWithNoMembershipsAsksAboutNoBlog() throws Exception {
        blog("elsewhere");
        when(users.getWeblogPermissions(user)).thenReturn(List.of());

        JsonNode page = call("");

        verify(comments).getComments(eq(List.of()), any(), anyInt(), anyInt());
        assertEquals(0, page.get("items").size());
    }

    @Test
    void aMissingWeblogIsSkipped() throws Exception {
        Weblog ghost = new Weblog();
        ghost.setHandle("ghost");
        when(users.getWeblogPermissions(user)).thenReturn(List.of(row(ghost, WeblogPermission.POST)));

        call("");

        verify(comments).getComments(eq(List.of()), any(), anyInt(), anyInt());
    }

    @Test
    void theLimitIsCappedAt100AndAnExtraRowMeansMore() throws Exception {
        Weblog a = blog("alpha");
        when(users.getWeblogPermissions(user)).thenReturn(List.of(row(a, WeblogPermission.POST)));
        List<ReaderComment> many = new java.util.ArrayList<>();
        for (int i = 0; i < 101; i++) {
            many.add(comment(a, "c" + i, "b"));
        }
        when(comments.getComments(any(), any(), eq(10), eq(101))).thenReturn(many);
        when(comments.countComments(anyCollection(), any())).thenReturn(300L);

        JsonNode page = call("?limit=500&offset=10&status=pending");

        assertEquals(100, page.get("limit").asInt());
        assertEquals(100, page.get("items").size());
        assertTrue(page.get("hasMore").asBoolean());
        assertEquals(300, page.get("total").asLong());
    }

    @Test
    void anUnknownStatusIsA400AndNoUserIsA401() throws Exception {
        assertEquals(400, mvc.perform(get("/v1/me/comments?status=bogus").requestAttr("authenticatedUser", user))
                .andReturn().getResponse().getStatus());
        assertEquals(400, mvc.perform(get("/v1/me/comments?limit=0").requestAttr("authenticatedUser", user))
                .andReturn().getResponse().getStatus());
        assertEquals(401, mvc.perform(get("/v1/me/comments")).andReturn().getResponse().getStatus());
    }
}

package com.wikantik.pressroom.ui.restapi.v1;

import java.time.Instant;
import java.util.Date;
import java.util.List;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.BlogStanding;
import com.wikantik.pressroom.business.CommentManager;
import com.wikantik.pressroom.business.FormSubmissionManager;
import com.wikantik.pressroom.business.URLStrategy;
import com.wikantik.pressroom.business.UserManager;
import com.wikantik.pressroom.business.WeblogEntryManager;
import com.wikantik.pressroom.business.WeblogManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.pojos.ReaderComment;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry.PubStatus;
import com.wikantik.pressroom.pojos.WeblogPermission;
import com.wikantik.pressroom.ui.restapi.ApiExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** The attention counts a main-menu row carries (0.18.0 Part A): inquiries, drafts, scheduled, last published. */
class MetaApiAttentionCountsTest {

    private WeblogEntryManager entries;
    private CommentManager comments;
    private FormSubmissionManager inbox;
    private UserManager users;
    private WeblogManager weblogs;
    private MockMvc mvc;
    private User user;

    @BeforeEach
    void setUp() {
        Weblogger weblogger = mock(Weblogger.class);
        users = mock(UserManager.class);
        weblogs = mock(WeblogManager.class);
        entries = mock(WeblogEntryManager.class);
        comments = mock(CommentManager.class);
        inbox = mock(FormSubmissionManager.class);
        URLStrategy urls = mock(URLStrategy.class);
        when(weblogger.getUserManager()).thenReturn(users);
        when(weblogger.getWeblogManager()).thenReturn(weblogs);
        when(weblogger.getWeblogEntryManager()).thenReturn(entries);
        when(weblogger.getCommentManager()).thenReturn(comments);
        when(weblogger.getFormSubmissionManager()).thenReturn(inbox);
        when(weblogger.getUrlStrategy()).thenReturn(urls);
        when(urls.getWeblogURL(any(Weblog.class), any(), eq(true))).thenReturn("https://blog.example.com/x");
        MetaApi api = new MetaApi();
        ReflectionTestUtils.setField(api, "weblogger", weblogger);
        mvc = MockMvcBuilders.standaloneSetup(api).setControllerAdvice(new ApiExceptionHandler()).build();
        user = new User();
        user.setUserName("alice");
    }

    private Weblog member(String handle, List<String> actions) throws WebloggerException {
        Weblog weblog = new Weblog();
        weblog.setHandle(handle);
        weblog.setName(handle);
        when(weblogs.getWeblogByHandle(handle)).thenReturn(weblog);
        when(users.getWeblogPermissions(user))
                .thenReturn(List.of(new WeblogPermission(weblog, user, actions)));
        return weblog;
    }

    private JsonNode firstRow() throws Exception {
        String body = mvc.perform(get("/v1/me/weblogs").requestAttr("authenticatedUser", user))
                .andReturn().getResponse().getContentAsString();
        return new ObjectMapper().readTree(body).get("weblogs").get(0);
    }

    @Test
    void theCountsMatchWhatTheManagersReport() throws Exception {
        Weblog blog = member("travels", List.of(WeblogPermission.ADMIN, WeblogPermission.POST));
        Date newest = new Date(1_700_000_000_000L);
        when(comments.countComments(blog, ReaderComment.Status.PENDING)).thenReturn(3L);
        when(inbox.getUnreadCount(blog)).thenReturn(2L);
        when(entries.getEntryCount(blog, PubStatus.DRAFT)).thenReturn(2L);
        when(entries.getEntryCount(blog, PubStatus.SCHEDULED)).thenReturn(1L);
        when(entries.getBlogStanding(eq(blog), any(Date.class))).thenReturn(new BlogStanding(7, newest));

        JsonNode row = firstRow();

        assertEquals(3, row.get("pendingComments").asLong());
        assertEquals(2, row.get("newInquiries").asLong());
        assertEquals(2, row.get("drafts").asLong());
        assertEquals(1, row.get("scheduled").asLong());
        assertEquals(Instant.ofEpochMilli(1_700_000_000_000L).toString(), row.get("lastPublished").asString());
    }

    @Test
    void aBlogWithNothingPublishedHasNoLastPublished() throws Exception {
        member("quiet", List.of(WeblogPermission.POST));
        when(entries.getBlogStanding(any(Weblog.class), any(Date.class))).thenReturn(BlogStanding.NONE);

        JsonNode row = firstRow();

        assertFalse(row.has("lastPublished"), row.toString());
        assertEquals(0, row.get("newInquiries").asLong());
    }

    @Test
    void anEditDraftMemberSeesNoCommentOrInquiryCountButStillSeesDrafts() throws Exception {
        Weblog blog = member("food", List.of(WeblogPermission.EDIT_DRAFT));
        when(comments.countComments(blog, ReaderComment.Status.PENDING)).thenReturn(5L);
        when(inbox.getUnreadCount(blog)).thenReturn(4L);
        when(entries.getEntryCount(blog, PubStatus.DRAFT)).thenReturn(6L);
        when(entries.getBlogStanding(any(Weblog.class), any(Date.class))).thenReturn(BlogStanding.NONE);

        JsonNode row = firstRow();

        assertFalse(row.get("canModerate").asBoolean());
        assertEquals(0, row.get("pendingComments").asLong());
        assertEquals(0, row.get("newInquiries").asLong());
        assertEquals(6, row.get("drafts").asLong());
    }
}

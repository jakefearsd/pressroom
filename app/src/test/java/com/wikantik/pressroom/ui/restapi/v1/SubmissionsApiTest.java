package com.wikantik.pressroom.ui.restapi.v1;

import java.sql.Timestamp;
import java.util.List;
import com.wikantik.pressroom.business.FormSubmissionManager;
import com.wikantik.pressroom.pojos.FormSubmission;
import com.wikantik.pressroom.pojos.Weblog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import static com.wikantik.pressroom.ui.restapi.v1.SessionOnlyApiSupport.body;
import static com.wikantik.pressroom.ui.restapi.v1.SessionOnlyApiSupport.refused;
import static com.wikantik.pressroom.ui.restapi.v1.SessionOnlyApiSupport.status;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** The session-only contact-form inbox (SA-18): paged newest first, one message deleted at a time. */
class SubmissionsApiTest {

    private static final String URL = "/v1/weblogs/myblog/submissions";

    private SessionOnlyApiSupport t;
    private FormSubmissionManager inbox;

    @BeforeEach
    void setUp() {
        t = new SessionOnlyApiSupport(new SubmissionsApi());
        inbox = t.tier.formSubmissionManager();
    }

    @AfterEach
    void tearDown() {
        t.detach();
    }

    private FormSubmission message(String id, Weblog to) {
        FormSubmission m = new FormSubmission();
        m.setId(id);
        m.setWeblog(to);
        m.setName("Ann");
        m.setEmail("ann@example.com");
        m.setSubject("Hello");
        m.setMessage("Hi there");
        m.setPageSlug("contact");
        m.setCreated(new Timestamp(0));
        return m;
    }

    @Test
    void theListIsAPageOfTheInboxWithTheJspsPageSizeByDefault() throws Exception {
        when(inbox.getSubmissions(t.weblog, 0, SubmissionsApi.PAGE_SIZE + 1))
                .thenReturn(List.of(message("a", t.weblog), message("b", t.weblog)));
        when(inbox.getCount(t.weblog)).thenReturn(2);

        JsonNode page = body(t.send(get(URL), null));

        assertEquals(2, page.path("items").size());
        assertEquals("ann@example.com", page.path("items").path(0).path("email").asString());
        assertEquals("1970-01-01T00:00:00Z", page.path("items").path(0).path("created").asString());
        assertEquals(30, page.path("limit").asInt());
        assertFalse(page.path("hasMore").asBoolean());
        assertEquals(2, page.path("total").asInt());
    }

    @Test
    void aFullPageSaysThereIsMore() throws Exception {
        when(inbox.getSubmissions(t.weblog, 5, 3)).thenReturn(
                List.of(message("a", t.weblog), message("b", t.weblog), message("c", t.weblog)));
        when(inbox.getCount(t.weblog)).thenReturn(40);

        JsonNode page = body(t.send(get(URL + "?offset=5&limit=2"), null));

        assertEquals(2, page.path("items").size());
        assertTrue(page.path("hasMore").asBoolean());
        assertEquals(40, page.path("total").asInt());
    }

    @Test
    void aBadRangeIs400() throws Exception {
        refused(t.send(get(URL + "?limit=-1"), null), 400, "paging.invalidRange");
    }

    @Test
    void deleteRemovesOneMessageOfThisWeblog() throws Exception {
        FormSubmission mine = message("a", t.weblog);
        when(inbox.get("a")).thenReturn(mine);

        assertEquals(204, status(t.send(delete(URL + "/a"), null)));

        verify(inbox).remove(mine);
        verify(t.tier.weblogger()).flush();
    }

    @Test
    void aMessageSentToAnotherWeblogOrNoneReadsAsAbsent() throws Exception {
        Weblog other = new Weblog();
        other.setId("other-id");
        other.setHandle("other");
        when(inbox.get("b")).thenReturn(message("b", other));

        refused(t.send(delete(URL + "/b"), null), 404, "submission.notFound");
        refused(t.send(delete(URL + "/nope"), null), 404, "submission.notFound");
        verify(inbox, never()).remove(any());
    }

    @Test
    void aFullLastPageHasNoMore() throws Exception {
        when(inbox.getSubmissions(t.weblog, 5, 3)).thenReturn(List.of(message("a", t.weblog), message("b", t.weblog)));
        when(inbox.getCount(t.weblog)).thenReturn(7);

        JsonNode page = body(t.send(get(URL + "?offset=5&limit=2"), null));

        assertEquals(2, page.path("items").size());
        assertFalse(page.path("hasMore").asBoolean());
    }
}

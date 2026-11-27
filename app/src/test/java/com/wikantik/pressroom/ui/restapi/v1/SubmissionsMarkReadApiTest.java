package com.wikantik.pressroom.ui.restapi.v1;

import java.sql.Timestamp;
import java.util.List;
import com.wikantik.pressroom.business.FormSubmissionManager;
import com.wikantik.pressroom.pojos.FormSubmission;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Marking inquiries read, and the {@code unread} flag the list carries (0.18.0 Part A). */
class SubmissionsMarkReadApiTest {

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

    @Test
    void markReadAnswers204AndPassesTheIdsForThisWeblog() throws Exception {
        when(inbox.markRead(t.weblog, List.of("a", "b"))).thenReturn(2);

        assertEquals(204, status(t.send(post(URL + "/read"), "{\"ids\":[\"a\",\"b\"]}")));

        verify(inbox).markRead(t.weblog, List.of("a", "b"));
        verify(t.tier.weblogger()).flush();
    }

    @Test
    void aForeignOrUnknownIdStillAnswers204() throws Exception {
        when(inbox.markRead(t.weblog, List.of("foreign"))).thenReturn(0);

        assertEquals(204, status(t.send(post(URL + "/read"), "{\"ids\":[\"foreign\"]}")));
    }

    @Test
    void aMissingIdsListIs400() throws Exception {
        refused(t.send(post(URL + "/read"), "{}"), 400, "field.required");
    }

    @Test
    void theListFlagsUnreadRows() throws Exception {
        FormSubmission unread = message("u");
        FormSubmission read = message("r");
        read.setReadAt(java.time.Instant.EPOCH);
        when(inbox.getSubmissions(t.weblog, 0, SubmissionsApi.PAGE_SIZE + 1)).thenReturn(List.of(unread, read));
        when(inbox.getCount(t.weblog)).thenReturn(2);

        JsonNode items = body(t.send(get(URL), null)).path("items");

        assertTrue(items.path(0).path("unread").asBoolean());
        assertFalse(items.path(1).path("unread").asBoolean());
    }

    private FormSubmission message(String id) {
        FormSubmission m = new FormSubmission();
        m.setId(id);
        m.setWeblog(t.weblog);
        m.setName("Ann");
        m.setEmail("ann@example.com");
        m.setMessage("Hi");
        m.setCreated(new Timestamp(0));
        return m;
    }
}

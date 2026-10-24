package org.apache.roller.weblogger.ui.restapi.v1;

import java.sql.Timestamp;
import java.time.Instant;
import org.apache.roller.weblogger.business.URLStrategy;
import org.apache.roller.weblogger.business.WeblogEntryManager;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.business.search.IndexManager;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.apache.roller.weblogger.ui.restapi.ApiExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The times an entry is read with can be written straight back. {@code
 * EntryView} returns {@code pubTime} and the event times as instants
 * ({@code ...Z}); a PATCH used to read them only as wall-clock time, so a
 * client that changed nothing but sent back what it had read got a 400.
 */
class EntryTimeRoundTripTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void theTimesAGetReturnsArePatchedBackUnchanged() throws Exception {
        Weblogger weblogger = mock(Weblogger.class);
        WeblogEntryManager entries = mock(WeblogEntryManager.class);
        when(weblogger.getWeblogEntryManager()).thenReturn(entries);
        when(weblogger.getUrlStrategy()).thenReturn(mock(URLStrategy.class));
        when(weblogger.getIndexManager()).thenReturn(mock(IndexManager.class));

        Weblog weblog = new Weblog();
        weblog.setId("myblog-id");
        weblog.setHandle("myblog");
        weblog.setTimeZone("Europe/Berlin");
        WeblogEntry entry = new WeblogEntry();
        entry.setId("entry-1");
        entry.setWebsite(weblog);
        entry.setTitle("t");
        entry.setText("b");
        entry.setPubTime(Timestamp.from(Instant.parse("2026-03-01T08:30:00Z")));
        entry.setEventStart(Timestamp.from(Instant.parse("2026-08-02T17:30:00Z")));
        entry.setEventEnd(Timestamp.from(Instant.parse("2026-08-02T20:00:00Z")));
        when(entries.getWeblogEntry("entry-1")).thenReturn(entry);

        EntriesApi read = new EntriesApi();
        read.weblogger = weblogger;
        EntriesWriteApi write = new EntriesWriteApi();
        write.weblogger = weblogger;
        MockMvc mvc = MockMvcBuilders.standaloneSetup(read, write)
                .setControllerAdvice(new ApiExceptionHandler()).build();

        JsonNode got = JSON.readTree(mvc.perform(get("/v1/weblogs/myblog/entries/{id}", "entry-1")
                        .requestAttr("actionWeblog", weblog))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertEquals("2026-03-01T08:30:00Z", got.get("pubTime").asString(), "the read format");

        String patchBody = "{\"pubTime\":\"" + got.get("pubTime").asString()
                + "\",\"eventStart\":\"" + got.get("eventStart").asString()
                + "\",\"eventEnd\":\"" + got.get("eventEnd").asString() + "\"}";
        // The interceptor stores the token's user; the PATCH credits its revision to them.
        User user = new User();
        user.setUserName("agent");
        mvc.perform(patch("/v1/weblogs/myblog/entries/{id}", "entry-1")
                        .requestAttr("actionWeblog", weblog)
                        .requestAttr("authenticatedUser", user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(patchBody))
                .andExpect(status().isOk());

        assertEquals(Instant.parse("2026-03-01T08:30:00Z"), entry.getPubTime().toInstant());
        assertEquals(Instant.parse("2026-08-02T17:30:00Z"), entry.getEventStart().toInstant());
        assertEquals(Instant.parse("2026-08-02T20:00:00Z"), entry.getEventEnd().toInstant());
    }
}

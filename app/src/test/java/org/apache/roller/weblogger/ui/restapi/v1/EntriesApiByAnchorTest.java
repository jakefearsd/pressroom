package org.apache.roller.weblogger.ui.restapi.v1;

import org.apache.roller.weblogger.business.URLStrategy;
import org.apache.roller.weblogger.business.WeblogEntryManager;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.apache.roller.weblogger.ui.restapi.ApiExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code GET .../entries/by-anchor/{anchor}}: the same entry, body and refusals as get-by-id. */
class EntriesApiByAnchorTest {

    private final Weblogger weblogger = mock(Weblogger.class);
    private final WeblogEntryManager entries = mock(WeblogEntryManager.class);
    private final Weblog weblog = weblog("weblog-1", "myblog");

    EntriesApiByAnchorTest() {
        when(weblogger.getWeblogEntryManager()).thenReturn(entries);
        when(weblogger.getUrlStrategy()).thenReturn(mock(URLStrategy.class));
    }

    private static Weblog weblog(String id, String handle) {
        Weblog w = new Weblog();
        w.setId(id);
        w.setHandle(handle);
        return w;
    }

    private MockMvc mvc() {
        EntriesApi controller = new EntriesApi();
        controller.weblogger = weblogger;
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    private WeblogEntry entry(Weblog owner, WeblogEntry.PubStatus status) {
        WeblogEntry e = new WeblogEntry();
        e.setId("entry-1");
        e.setAnchor("hello-world");
        e.setTitle("Hello World");
        e.setStatus(status);
        e.setWebsite(owner);
        return e;
    }

    @Test
    void byAnchorReturnsTheSameBodyAsById() throws Exception {
        WeblogEntry e = entry(weblog, WeblogEntry.PubStatus.DRAFT);
        when(entries.getWeblogEntry("entry-1")).thenReturn(e);
        when(entries.getWeblogEntryByAnchor(weblog, "hello-world")).thenReturn(e);

        String byId = mvc().perform(get("/v1/weblogs/myblog/entries/{id}", "entry-1")
                .requestAttr("actionWeblog", weblog)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String byAnchor = mvc().perform(get("/v1/weblogs/myblog/entries/by-anchor/{a}", "hello-world")
                .requestAttr("actionWeblog", weblog)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertEquals(byId, byAnchor);
    }

    @Test
    void anUnknownAnchorIsTheSame404AsAnUnknownId() throws Exception {
        String byId = mvc().perform(get("/v1/weblogs/myblog/entries/{id}", "nope")
                .requestAttr("actionWeblog", weblog)).andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
        String byAnchor = mvc().perform(get("/v1/weblogs/myblog/entries/by-anchor/{a}", "nope")
                .requestAttr("actionWeblog", weblog))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andReturn().getResponse().getContentAsString();
        assertEquals(byId.replace("/entries/nope", "/entries/by-anchor/nope"), byAnchor);
    }

    @Test
    void anotherWeblogsEntryIsNotFound() throws Exception {
        Weblog other = weblog("weblog-2", "someoneelse");
        when(entries.getWeblogEntryByAnchor(weblog, "hello-world")).thenReturn(entry(other, WeblogEntry.PubStatus.PUBLISHED));
        mvc().perform(get("/v1/weblogs/myblog/entries/by-anchor/{a}", "hello-world")
                .requestAttr("actionWeblog", weblog)).andExpect(status().isNotFound());
    }

    @Test
    void aBlankAnchorOrAManagerFailureIsNotFound() throws Exception {
        when(entries.getWeblogEntryByAnchor(weblog, "boom"))
                .thenThrow(new org.apache.roller.weblogger.WebloggerException("down"));
        mvc().perform(get("/v1/weblogs/myblog/entries/by-anchor/{a}", "boom")
                .requestAttr("actionWeblog", weblog)).andExpect(status().isNotFound());
    }

    @Test
    void aTrashedEntryIsVisibleExactlyAsById() throws Exception {
        WeblogEntry e = entry(weblog, WeblogEntry.PubStatus.TRASHED);
        when(entries.getWeblogEntry("entry-1")).thenReturn(e);
        when(entries.getWeblogEntryByAnchor(weblog, "hello-world")).thenReturn(e);
        mvc().perform(get("/v1/weblogs/myblog/entries/{id}", "entry-1")
                .requestAttr("actionWeblog", weblog)).andExpect(status().isOk());
        mvc().perform(get("/v1/weblogs/myblog/entries/by-anchor/{a}", "hello-world")
                .requestAttr("actionWeblog", weblog)).andExpect(status().isOk());
    }
}

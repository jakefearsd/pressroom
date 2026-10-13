package org.apache.roller.weblogger.ui.restapi.v1;

import java.util.List;
import org.apache.roller.weblogger.business.MediaFileManager;
import org.apache.roller.weblogger.business.WeblogEntryManager;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.pojos.MediaFile;
import org.apache.roller.weblogger.pojos.MediaFileDirectory;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.apache.roller.weblogger.pojos.WeblogEntrySearchCriteria;
import org.apache.roller.weblogger.pojos.WeblogPermission;
import org.apache.roller.weblogger.ui.restapi.ApiExceptionHandler;
import org.apache.roller.weblogger.ui.restapi.dto.AuditDtos;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.apache.roller.weblogger.ui.restapi.ErrorFieldMatchers.errorFieldIs;

class AuditApiTest {

    @Test
    void anEntryWithNothingSetReportsEveryGap() {
        WeblogEntry entry = new WeblogEntry();
        List<String> gaps = AuditDtos.gapsFor(entry);

        assertTrue(gaps.contains("missing_search_description"));
        assertTrue(gaps.contains("missing_meta_title"));
        assertTrue(gaps.contains("missing_featured_image"));
        assertFalse(gaps.contains("noindex"), "noindex is off by default");
    }

    @Test
    void aFullyDescribedEntryReportsNoGaps() {
        WeblogEntry entry = new WeblogEntry();
        entry.setSearchDescription("A short description.");
        entry.setMetaTitle("A title");
        entry.setFeaturedImageId("some-media-id");

        assertTrue(AuditDtos.gapsFor(entry).isEmpty());
    }

    /**
     * Blank means missing, matching the renderer's isNotBlank rather than
     * EL's empty -- whitespace-only text would otherwise report as described
     * while every rendered page fell back to something else.
     */
    @Test
    void whitespaceOnlyValuesCountAsMissing() {
        WeblogEntry entry = new WeblogEntry();
        entry.setSearchDescription("   ");
        entry.setMetaTitle("\t");
        entry.setFeaturedImageId("some-media-id");

        List<String> gaps = AuditDtos.gapsFor(entry);
        assertTrue(gaps.contains("missing_search_description"));
        assertTrue(gaps.contains("missing_meta_title"));
    }

    @Test
    void noindexIsReportedAsItsOwnGap() {
        WeblogEntry entry = new WeblogEntry();
        entry.setSearchDescription("d");
        entry.setMetaTitle("t");
        entry.setFeaturedImageId("m");
        entry.setNoindex(Boolean.TRUE);

        assertEquals(List.of("noindex"), AuditDtos.gapsFor(entry));
    }

    // -----------------------------------------------------------------
    // Controller-level tests: routing, status codes, the default-status
    // and pagination-guard business logic -- none of which the gapsFor
    // tests above can see. actionWeblog is injected as a request attribute
    // rather than resolved by a real interceptor, matching
    // EntriesApiReadTest/MediaApiTest's standalone style.
    // -----------------------------------------------------------------

    private MockMvc mockMvc(AuditApi controller) {
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private Weblogger mockedWeblogger() {
        Weblogger weblogger = mock(Weblogger.class);
        WeblogEntryManager entryManager = mock(WeblogEntryManager.class);
        MediaFileManager mediaFileManager = mock(MediaFileManager.class);
        when(weblogger.getWeblogEntryManager()).thenReturn(entryManager);
        when(weblogger.getMediaFileManager()).thenReturn(mediaFileManager);
        return weblogger;
    }

    private AuditApi controllerFor(Weblogger weblogger) {
        AuditApi controller = new AuditApi();
        controller.weblogger = weblogger;
        return controller;
    }

    private static Weblog aWeblog() {
        Weblog weblog = new Weblog();
        weblog.setId("weblog-1");
        weblog.setHandle("myblog");
        return weblog;
    }

    /**
     * No ?status= at all must default to PUBLISHED, never to "every status"
     * the way EntriesApi.list's null default does -- an SEO audit is about
     * what a reader can find, and includeTrashed must stay false so a
     * trashed entry can never appear as work to do.
     */
    @Test
    void seoAuditDefaultsToPublishedAndExcludesTrash() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        when(weblogger.getWeblogEntryManager().getWeblogEntries(any())).thenReturn(List.of());

        mockMvc(controllerFor(weblogger))
                .perform(get("/v1/weblogs/myblog/audit/seo").requestAttr("actionWeblog", aWeblog()))
                .andExpect(status().isOk());

        ArgumentCaptor<WeblogEntrySearchCriteria> captor =
                ArgumentCaptor.forClass(WeblogEntrySearchCriteria.class);
        verify(weblogger.getWeblogEntryManager()).getWeblogEntries(captor.capture());
        assertEquals(WeblogEntry.PubStatus.PUBLISHED, captor.getValue().getStatus());
        assertFalse(captor.getValue().isIncludeTrashed());
    }

    /**
     * Unlike EntriesApi.list, the SEO audit refuses status=TRASHED outright
     * rather than widening to it. This is a to-do-list generator meant to be
     * iterated (find a gap, fix it, re-run); handing that loop a trashed
     * entry framed as "needs missing_meta_title fixed" invites writing
     * metadata onto a deleted entry -- the side-door-resurrection class of
     * bug this repo has already firefought once (see the Trash section of
     * CLAUDE.md). Refused with an explicit 400 rather than silently ignored,
     * since silently dropping a parameter the client asked for is its own
     * trap. The manager is never even called.
     */
    @Test
    void seoAuditRefusesStatusTrashed() throws Exception {
        Weblogger weblogger = mockedWeblogger();

        mockMvc(controllerFor(weblogger))
                .perform(get("/v1/weblogs/myblog/audit/seo")
                        .param("status", "TRASHED")
                        .requestAttr("actionWeblog", aWeblog()))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verify(weblogger.getWeblogEntryManager(), never()).getWeblogEntries(any());
    }

    /** An unknown status answers 400, not a 500 from a bad enum lookup. */
    @Test
    void seoAuditWithAnUnknownStatusIsBadRequest() throws Exception {
        Weblogger weblogger = mockedWeblogger();

        mockMvc(controllerFor(weblogger))
                .perform(get("/v1/weblogs/myblog/audit/seo")
                        .param("status", "BANANA")
                        .requestAttr("actionWeblog", aWeblog()))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    /**
     * limit=-2 must never reach the manager -- copied from EntriesApi.list's
     * own guard, since Math.min(-2, MAX) alone lets it through as "no
     * limit" and an audit is exactly where an unbounded read hurts most.
     */
    @Test
    void seoAuditRejectsANegativeLimitBeforeCallingTheManager() throws Exception {
        Weblogger weblogger = mockedWeblogger();

        mockMvc(controllerFor(weblogger))
                .perform(get("/v1/weblogs/myblog/audit/seo")
                        .param("limit", "-2")
                        .requestAttr("actionWeblog", aWeblog()))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verify(weblogger.getWeblogEntryManager(), never()).getWeblogEntries(any());
    }

    /** A negative offset is rejected the same way, before any query runs. */
    @Test
    void seoAuditRejectsANegativeOffsetBeforeCallingTheManager() throws Exception {
        Weblogger weblogger = mockedWeblogger();

        mockMvc(controllerFor(weblogger))
                .perform(get("/v1/weblogs/myblog/audit/seo")
                        .param("offset", "-1")
                        .requestAttr("actionWeblog", aWeblog()))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verify(weblogger.getWeblogEntryManager(), never()).getWeblogEntries(any());
    }

    /**
     * total/counts reflect every gappy entry the manager returned, while
     * entries is only the requested page -- an entry with no gaps at all
     * must not appear anywhere in the response.
     */
    @Test
    void seoAuditReportsTotalsCountsAndAPagedSlice() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog();

        WeblogEntry described = new WeblogEntry();
        described.setId("e0");
        described.setAnchor("described");
        described.setSearchDescription("d");
        described.setMetaTitle("t");
        described.setFeaturedImageId("m");

        WeblogEntry first = new WeblogEntry();
        first.setId("e1");
        first.setAnchor("first");
        first.setTitle("First");

        WeblogEntry second = new WeblogEntry();
        second.setId("e2");
        second.setAnchor("second");
        second.setTitle("Second");
        second.setNoindex(Boolean.TRUE);
        second.setSearchDescription("d");
        second.setMetaTitle("t");
        second.setFeaturedImageId("m");

        when(weblogger.getWeblogEntryManager().getWeblogEntries(any()))
                .thenReturn(List.of(described, first, second));

        String body = mockMvc(controllerFor(weblogger))
                .perform(get("/v1/weblogs/myblog/audit/seo")
                        .param("limit", "1")
                        .requestAttr("actionWeblog", weblog))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        tools.jackson.databind.JsonNode json = new tools.jackson.databind.ObjectMapper().readTree(body);
        assertEquals(2, json.get("total").asInt());
        assertEquals(1, json.get("entries").size());
        assertEquals("first", json.get("entries").get(0).get("anchor").asString());
        assertEquals(1, json.get("counts").get("missing_search_description").asInt());
        assertEquals(1, json.get("counts").get("missing_meta_title").asInt());
        assertEquals(1, json.get("counts").get("missing_featured_image").asInt());
        assertEquals(1, json.get("counts").get("noindex").asInt());
    }

    @Test
    void mediaAuditListsOnlyFilesMissingAltText() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog();

        MediaFileDirectory directory = new MediaFileDirectory();
        directory.setId("dir-1");
        directory.setName("photos");

        MediaFile described = new MediaFile();
        described.setId("m1");
        described.setName("cat.jpg");
        described.setAltText("A cat");

        MediaFile undescribed = new MediaFile();
        undescribed.setId("m2");
        undescribed.setName("dog.jpg");
        undescribed.setAltText("   ");
        undescribed.setDirectory(directory);

        when(weblogger.getMediaFileManager().searchMediaFiles(any(), any()))
                .thenReturn(List.of(described, undescribed));

        String body = mockMvc(controllerFor(weblogger))
                .perform(get("/v1/weblogs/myblog/audit/media").requestAttr("actionWeblog", weblog))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        tools.jackson.databind.JsonNode json = new tools.jackson.databind.ObjectMapper().readTree(body);
        assertEquals(1, json.get("missingAltText").asInt());
        assertEquals(1, json.get("items").size());
        assertEquals("m2", json.get("items").get(0).get("mediaId").asString());
        assertEquals("dog.jpg", json.get("items").get(0).get("name").asString());
        assertEquals("photos", json.get("items").get(0).get("directoryName").asString());
    }

    /**
     * AuditApi declares UISecurityEnforced.requiredWeblogPermissionActions
     * as EDIT_DRAFT -- auditing is a read, open to the same contributors
     * EntriesApi's reads are open to.
     */
    @Test
    void declaresEditDraftAsTheRequiredWeblogPermission() {
        assertEquals(List.of(WeblogPermission.EDIT_DRAFT), new AuditApi().requiredWeblogPermissionActions());
        assertTrue(new AuditApi().isUserRequired());
        assertTrue(new AuditApi().isWeblogRequired());
    }

    @Test
    void seoAuditRefusalOfStatusTrashedNamesTheStatusField() throws Exception {
        Weblogger weblogger = mockedWeblogger();

        mockMvc(controllerFor(weblogger))
                .perform(get("/v1/weblogs/myblog/audit/seo")
                        .param("status", "TRASHED")
                        .requestAttr("actionWeblog", aWeblog()))
                .andExpect(status().isBadRequest())
                .andExpect(errorFieldIs("status"));
    }
}

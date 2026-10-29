package com.wikantik.pressroom.ui.restapi.v1;

import java.util.List;
import com.wikantik.pressroom.business.MediaFileManager;
import com.wikantik.pressroom.business.WeblogEntryManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.EntryRenderer;
import com.wikantik.pressroom.business.URLStrategy;
import com.wikantik.pressroom.business.plugins.PluginManager;
import com.wikantik.pressroom.business.seo.SeoLimits;
import com.wikantik.pressroom.business.shortcodes.ShortcodeExpander;
import com.wikantik.pressroom.pojos.MediaFile;
import com.wikantik.pressroom.pojos.MediaFileDirectory;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogEntrySearchCriteria;
import com.wikantik.pressroom.pojos.WeblogPermission;
import com.wikantik.pressroom.ui.restapi.ApiExceptionHandler;
import com.wikantik.pressroom.ui.restapi.dto.AuditDtos;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static com.wikantik.pressroom.ui.restapi.ErrorFieldMatchers.errorFieldIs;

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

        // Fully described under the length and duplicate checks too: a
        // description inside SeoLimits, a meta title no other entry has.
        WeblogEntry described = new WeblogEntry();
        described.setId("e0");
        described.setAnchor("described");
        described.setSearchDescription("d".repeat(SeoLimits.DESCRIPTION_MIN));
        described.setMetaTitle("Described");
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

    private static WeblogEntry published(String id, String title) {
        WeblogEntry entry = new WeblogEntry();
        entry.setId(id);
        entry.setAnchor(id);
        entry.setTitle(title);
        entry.setStatus(WeblogEntry.PubStatus.PUBLISHED);
        entry.setFeaturedImageId("m-" + id);
        return entry;
    }

    private static tools.jackson.databind.JsonNode json(String body) {
        return new tools.jackson.databind.ObjectMapper().readTree(body);
    }

    private static List<String> gapsOf(tools.jackson.databind.JsonNode entry) {
        List<String> gaps = new java.util.ArrayList<>();
        entry.get("gaps").forEach(g -> gaps.add(g.asString()));
        return gaps;
    }

    /**
     * Acceptance criterion 7: two published entries sharing a title are
     * each a title_duplicate. The audit compares the page title the themes
     * print, which with no meta title is the title and the weblog name.
     */
    @Test
    void seoAuditReportsTitleDuplicateOnBothEntriesSharingATitle() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        WeblogEntry first = published("first", "Harbour walk");
        WeblogEntry second = published("second", "Harbour walk");
        WeblogEntry other = published("other", "Ferry times");
        when(weblogger.getWeblogEntryManager().getWeblogEntries(any())).thenReturn(List.of(first, second, other));

        tools.jackson.databind.JsonNode audit = json(mockMvc(controllerFor(weblogger))
                .perform(get("/v1/weblogs/myblog/audit/seo").requestAttr("actionWeblog", aWeblog()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        assertEquals(2, audit.get("counts").get("title_duplicate").asInt());
        assertTrue(gapsOf(audit.get("entries").get(0)).contains("title_duplicate"), audit.toString());
        assertTrue(gapsOf(audit.get("entries").get(1)).contains("title_duplicate"), audit.toString());
        assertFalse(gapsOf(audit.get("entries").get(2)).contains("title_duplicate"), audit.toString());
    }

    /**
     * The field checks join today's gap codes in the same list; the entry
     * body is never rendered (that would render every entry of the weblog),
     * and counts names every code the audit can report, zero or not.
     */
    @Test
    void seoAuditAddsLengthCodesAndNeverRendersABody() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        WeblogEntry entry = published("e1", "Harbour walk");
        entry.setMetaTitle("Harbour walk");
        entry.setSearchDescription("x".repeat(SeoLimits.DESCRIPTION_MAX + 1));
        when(weblogger.getWeblogEntryManager().getWeblogEntries(any())).thenReturn(List.of(entry));

        tools.jackson.databind.JsonNode audit = json(mockMvc(controllerFor(weblogger))
                .perform(get("/v1/weblogs/myblog/audit/seo").requestAttr("actionWeblog", aWeblog()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        assertEquals(List.of("search_description_long"), gapsOf(audit.get("entries").get(0)));
        assertEquals(List.of("missing_search_description", "missing_meta_title", "missing_featured_image",
                "noindex", "search_description_long", "search_description_short", "title_long",
                "title_duplicate", "search_description_duplicate"),
                List.copyOf(audit.get("counts").propertyNames()));
        verify(weblogger, never()).getEntryRenderer();
    }

    /** A draft is compared with the published entries: its title would collide once published. */
    @Test
    void seoAuditOfDraftsComparesThemWithThePublishedEntries() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        WeblogEntry draft = published("draft", "Harbour walk");
        draft.setStatus(WeblogEntry.PubStatus.DRAFT);
        WeblogEntry live = published("live", "Harbour walk");
        WeblogEntryManager entries = weblogger.getWeblogEntryManager();
        when(entries.getWeblogEntries(argThat(c -> c != null && c.getStatus() == WeblogEntry.PubStatus.DRAFT)))
                .thenReturn(List.of(draft));
        when(entries.getWeblogEntries(argThat(c -> c != null && c.getStatus() == WeblogEntry.PubStatus.PUBLISHED)))
                .thenReturn(List.of(live));

        tools.jackson.databind.JsonNode audit = json(mockMvc(controllerFor(weblogger))
                .perform(get("/v1/weblogs/myblog/audit/seo").param("status", "DRAFT")
                        .requestAttr("actionWeblog", aWeblog()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        assertEquals(1, audit.get("total").asInt());
        assertTrue(gapsOf(audit.get("entries").get(0)).contains("title_duplicate"), audit.toString());
    }

    // ---- audit/seo/entries/{id}: every check for one entry, body included --

    /** A weblogger whose renderer is real, so a body is rendered as it publishes. */
    private Weblogger renderingWeblogger() {
        Weblogger weblogger = mockedWeblogger();
        when(weblogger.getUrlStrategy()).thenReturn(mock(URLStrategy.class));
        EntryRenderer renderer = new EntryRenderer(
                ShortcodeExpander.builtIn(weblogger, weblogger.getMediaFileManager()), mock(PluginManager.class));
        when(weblogger.getEntryRenderer()).thenReturn(renderer);
        return weblogger;
    }

    private WeblogEntry entryOf(Weblogger weblogger, Weblog weblog, String text) throws Exception {
        WeblogEntry entry = published("e1", "Harbour walk");
        entry.setWebsite(weblog);
        entry.setText(text);
        when(weblogger.getWeblogEntryManager().getWeblogEntry("e1")).thenReturn(entry);
        return entry;
    }

    @Test
    void seoEntryReportsFieldAndBodyFindingsForOneEntry() throws Exception {
        Weblogger weblogger = renderingWeblogger();
        Weblog weblog = aWeblog();
        WeblogEntry entry = entryOf(weblogger, weblog, "# Welcome\n\nSee [fares](#fares).\n");
        entry.setSearchDescription("Too short.");
        when(weblogger.getWeblogEntryManager().getWeblogEntries(any())).thenReturn(List.of(entry));

        tools.jackson.databind.JsonNode audit = json(mockMvc(controllerFor(weblogger))
                .perform(get("/v1/weblogs/myblog/audit/seo/entries/{id}", "e1").requestAttr("actionWeblog", weblog))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        assertEquals("e1", audit.get("id").asString());
        assertEquals("e1", audit.get("anchor").asString());
        assertEquals("Harbour walk", audit.get("title").asString());
        List<String> codes = new java.util.ArrayList<>();
        audit.get("findings").forEach(f -> codes.add(f.get("code").asString()));
        assertEquals(List.of("missing_meta_title", "search_description_short", "body_h1", "fragment_link_broken"),
                codes, audit.toString());
        tools.jackson.databind.JsonNode shortOne = audit.get("findings").get(1);
        assertEquals("info", shortOne.get("severity").asString());
        assertEquals("Too short.".length(), shortOne.get("value").asInt());
        assertEquals(SeoLimits.DESCRIPTION_MIN, shortOne.get("limit").asInt());
        tools.jackson.databind.JsonNode h1 = audit.get("findings").get(2);
        assertEquals("warning", h1.get("severity").asString());
        assertTrue(h1.get("message").asString().contains("Welcome"), h1.toString());
        assertFalse(h1.has("value") || h1.has("limit"), "value and limit are for lengths only: " + h1);
    }

    /** Duplicates are among the other published entries, never the entry itself. */
    @Test
    void seoEntryReportsADuplicateTitleAmongThePublishedEntries() throws Exception {
        Weblogger weblogger = renderingWeblogger();
        Weblog weblog = aWeblog();
        WeblogEntry entry = entryOf(weblogger, weblog, "Text.");
        when(weblogger.getWeblogEntryManager().getWeblogEntries(any()))
                .thenReturn(List.of(entry, published("twin", "Harbour walk")));

        String body = mockMvc(controllerFor(weblogger))
                .perform(get("/v1/weblogs/myblog/audit/seo/entries/{id}", "e1").requestAttr("actionWeblog", weblog))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("\"title_duplicate\"") && body.contains("twin"), body);
    }

    /**
     * entry_link_broken reads this weblog's entry urls from the url
     * strategy, relative and absolute, and asks the manager whether each
     * linked anchor is published; a link to the entry itself is fine.
     */
    @Test
    void seoEntryReportsALinkToAnUnpublishedEntryOfThisWeblog() throws Exception {
        Weblogger weblogger = renderingWeblogger();
        Weblog weblog = aWeblog();
        URLStrategy urls = weblogger.getUrlStrategy();
        when(urls.getWeblogEntryURL(weblog, null, "", false)).thenReturn("/myblog/entry/");
        when(urls.getWeblogEntryURL(weblog, null, "", true)).thenReturn("https://blog.example.com/myblog/entry/");
        WeblogEntry entry = entryOf(weblogger, weblog, "[live](/myblog/entry/live) [draft](/myblog/entry/draft) "
                + "[gone](https://blog.example.com/myblog/entry/gone) [self](/myblog/entry/e1)\n");
        WeblogEntry draft = published("draft", "Draft");
        draft.setStatus(WeblogEntry.PubStatus.DRAFT);
        WeblogEntryManager entries = weblogger.getWeblogEntryManager();
        when(entries.getWeblogEntries(any())).thenReturn(List.of(entry));
        when(entries.getWeblogEntryByAnchor(weblog, "live")).thenReturn(published("live", "Live"));
        when(entries.getWeblogEntryByAnchor(weblog, "draft")).thenReturn(draft);

        tools.jackson.databind.JsonNode audit = json(mockMvc(controllerFor(weblogger))
                .perform(get("/v1/weblogs/myblog/audit/seo/entries/{id}", "e1").requestAttr("actionWeblog", weblog))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        tools.jackson.databind.JsonNode broken = null;
        for (tools.jackson.databind.JsonNode finding : audit.get("findings")) {
            if ("entry_link_broken".equals(finding.get("code").asString())) {
                broken = finding;
            }
        }
        assertNotNull(broken, audit.toString());
        String message = broken.get("message").asString();
        assertTrue(message.startsWith("2 links") && message.contains("/myblog/entry/draft")
                && message.contains("https://blog.example.com/myblog/entry/gone"), message);
        assertFalse(message.contains("/myblog/entry/live") || message.contains("/myblog/entry/e1"), message);
    }

    /**
     * The [image] shortcode's last resort is the media file's name as words,
     * and its src carries only the id: the file is looked up to recognise it.
     */
    @Test
    void seoEntryRecognisesAMediaFileWhoseAltIsItsFileName() throws Exception {
        Weblogger weblogger = renderingWeblogger();
        Weblog weblog = aWeblog();
        when(weblogger.getUrlStrategy().getMediaFileURL(weblog, "", true))
                .thenReturn("https://blog.example.com/myblog/mediaresource/");
        MediaFile photo = new MediaFile();
        photo.setId("m1");
        photo.setName("IMG_2034.jpg");
        photo.setWeblog(weblog);
        when(weblogger.getMediaFileManager().getMediaFile("m1")).thenReturn(photo);
        Weblog elsewhere = new Weblog();
        elsewhere.setId("weblog-2");
        MediaFile foreign = new MediaFile();
        foreign.setId("m2");
        foreign.setName("IMG_9.jpg");
        foreign.setWeblog(elsewhere);
        when(weblogger.getMediaFileManager().getMediaFile("m2")).thenReturn(foreign);
        entryOf(weblogger, weblog, "<img src=\"https://blog.example.com/myblog/mediaresource/m1?w=640\" "
                + "alt=\"IMG 2034\"> <img src=\"https://blog.example.com/myblog/mediaresource/m2\" alt=\"IMG 9\">\n");
        when(weblogger.getWeblogEntryManager().getWeblogEntries(any())).thenReturn(List.of());

        String body = mockMvc(controllerFor(weblogger))
                .perform(get("/v1/weblogs/myblog/audit/seo/entries/{id}", "e1").requestAttr("actionWeblog", weblog))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("1 image has no alt text") && body.contains("mediaresource/m1?w=640"), body);
    }

    /**
     * A media url with no id after it names no file and is never looked up:
     * an empty id is a wildcard to some managers (WeblogOwnership). An id
     * the manager does not know is judged by its url alone.
     */
    @Test
    void seoEntryNeverLooksUpAnEmptyMediaIdAndSurvivesAnUnknownOne() throws Exception {
        Weblogger weblogger = renderingWeblogger();
        Weblog weblog = aWeblog();
        when(weblogger.getUrlStrategy().getMediaFileURL(weblog, "", false)).thenReturn("/myblog/mediaresource/");
        entryOf(weblogger, weblog, "<img src=\"/myblog/mediaresource/\" alt=\"A\"> "
                + "<img src=\"/myblog/mediaresource/?w=10\" alt=\"B\"> "
                + "<img src=\"/myblog/mediaresource/m3/x\" alt=\"C\"> "
                + "<img src=\"/myblog/mediaresource/m9\" alt=\"D\">\n");
        when(weblogger.getWeblogEntryManager().getWeblogEntries(any())).thenReturn(List.of());

        String body = mockMvc(controllerFor(weblogger))
                .perform(get("/v1/weblogs/myblog/audit/seo/entries/{id}", "e1").requestAttr("actionWeblog", weblog))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertFalse(body.contains("image_alt_missing"), body);
        verify(weblogger.getMediaFileManager(), never()).getMediaFile("");
        verify(weblogger.getMediaFileManager(), never()).getMediaFile("m3/x");
        verify(weblogger.getMediaFileManager()).getMediaFile("m9");
    }

    @Test
    void seoEntryOfAnUnknownIdIsNotFound() throws Exception {
        Weblogger weblogger = renderingWeblogger();

        mockMvc(controllerFor(weblogger))
                .perform(get("/v1/weblogs/myblog/audit/seo/entries/{id}", "nope")
                        .requestAttr("actionWeblog", aWeblog()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        verify(weblogger, never()).getEntryRenderer();
    }

    /** Another weblog's entry is indistinguishable from a missing one: 404, never 403. */
    @Test
    void seoEntryOfAnotherWeblogsEntryIsNotFound() throws Exception {
        Weblogger weblogger = renderingWeblogger();
        Weblog other = new Weblog();
        other.setId("weblog-2");
        other.setHandle("other");
        entryOf(weblogger, other, "# Theirs\n");

        mockMvc(controllerFor(weblogger))
                .perform(get("/v1/weblogs/myblog/audit/seo/entries/{id}", "e1")
                        .requestAttr("actionWeblog", aWeblog()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        verify(weblogger, never()).getEntryRenderer();
    }

    /** Like audit/seo, never a trashed entry: restore it first. */
    @Test
    void seoEntryOfATrashedEntryIsAConflict() throws Exception {
        Weblogger weblogger = renderingWeblogger();
        Weblog weblog = aWeblog();
        WeblogEntry entry = entryOf(weblogger, weblog, "Text.");
        entry.setStatus(WeblogEntry.PubStatus.TRASHED);

        mockMvc(controllerFor(weblogger))
                .perform(get("/v1/weblogs/myblog/audit/seo/entries/{id}", "e1").requestAttr("actionWeblog", weblog))
                .andExpect(status().isConflict());
        verify(weblogger.getWeblogEntryManager(), never()).getWeblogEntries(any());
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

    @Test
    void seoAuditWithAnUnknownStatusNamesTheStatusField() throws Exception {
        Weblogger weblogger = mockedWeblogger();

        mockMvc(controllerFor(weblogger))
                .perform(get("/v1/weblogs/myblog/audit/seo")
                        .param("status", "BANANA")
                        .requestAttr("actionWeblog", aWeblog()))
                .andExpect(status().isBadRequest())
                .andExpect(errorFieldIs("status"));
    }
}

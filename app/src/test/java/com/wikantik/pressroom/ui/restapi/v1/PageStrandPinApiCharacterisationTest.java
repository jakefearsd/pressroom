package com.wikantik.pressroom.ui.restapi.v1;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.EntryPins;
import com.wikantik.pressroom.business.WeblogEntryManager;
import com.wikantik.pressroom.business.WeblogPageManager;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

import static com.wikantik.pressroom.ui.restapi.v1.EntriesWriteApiFixture.json;
import static com.wikantik.pressroom.ui.restapi.v1.ProblemAsserts.oneField;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Characterisation test (spec 2026-11-20-jsp-rules-and-view-models-design,
 * Task 4): written against the code before the page slug, strand and pin
 * rules moved into {@code PageRules}, {@code StrandChoice} and
 * {@code EntryPinRules}, and passing on it. It pins what the API answers
 * today (status, code, detail, the field) for every case the move touches,
 * and that a refused write saved nothing.
 */
class PageStrandPinApiCharacterisationTest {

    private static final String PAGES = "/v1/weblogs/myblog/pages";
    private static final String P1 = PAGES + "/p1";
    private static final String E1 = "/v1/weblogs/myblog/entries/e1";

    private EntriesWriteApiFixture fx;
    private WeblogPageManager pages;
    private WeblogEntryManager entries;
    private Weblog weblog;
    private WeblogPage stored;
    private WeblogEntry entry;
    private PagesApi pagesApi;

    @BeforeEach
    void setUp() throws Exception {
        fx = new EntriesWriteApiFixture();
        entries = fx.entries;
        weblog = fx.weblog;
        pages = mock(WeblogPageManager.class);
        when(fx.weblogger.getWeblogPageManager()).thenReturn(pages);
        stored = page("p1", "about", weblog);
        entry = new WeblogEntry();
        entry.setId("e1");
        entry.setWebsite(weblog);
        entry.setTitle("Harbour");
        entry.setText("The pier.");
        entry.setStatus(WeblogEntry.PubStatus.DRAFT);
        when(entries.getWeblogEntry("e1")).thenReturn(entry);
        pagesApi = new PagesApi();
        when(fx.weblogger.getUrlStrategy()).thenReturn(mock(com.wikantik.pressroom.business.URLStrategy.class));
        pagesApi.weblogger = fx.weblogger;
    }

    private WeblogPage page(String id, String slug, Weblog owner) throws Exception {
        WeblogPage made = new WeblogPage();
        made.setId(id);
        made.setWeblog(owner);
        made.setSlug(slug);
        made.setTitle("Title of " + slug);
        made.setStatus(WeblogPage.PubStatus.PUBLISHED);
        when(pages.getPage(id)).thenReturn(made);
        return made;
    }

    private WeblogPage strandPage(String id, boolean strand, Weblog owner) throws Exception {
        WeblogPage made = page(id, id, owner);
        made.setStrand(strand);
        return made;
    }

    private JsonNode pageRefused(MockHttpServletRequestBuilder request) throws Exception {
        return answer(status().isBadRequest(), request);
    }

    private JsonNode answer(org.springframework.test.web.servlet.ResultMatcher expected,
            MockHttpServletRequestBuilder request) throws Exception {
        return json(send(request).andExpect(expected));
    }

    private org.springframework.test.web.servlet.ResultActions send(MockHttpServletRequestBuilder request)
            throws Exception {
        com.wikantik.pressroom.pojos.User user = new com.wikantik.pressroom.pojos.User();
        user.setUserName("maiia");
        return org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(pagesApi, fx.controller)
                .setControllerAdvice(new com.wikantik.pressroom.ui.restapi.ApiExceptionHandler()).build()
                .perform(request.requestAttr("actionWeblog", weblog).requestAttr("authenticatedUser", user)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON));
    }

    private JsonNode createPage(String body) throws Exception {
        return pageRefused(post(PAGES).content(body));
    }

    private JsonNode updatePage(String body) throws Exception {
        return pageRefused(patch(P1).content(body));
    }

    // --- slugs ---

    @Test
    void aReservedOrBlankSlugIsSlugReservedOnTheSlugField() throws Exception {
        oneField(createPage("{\"title\":\"Feed\",\"slug\":\"feed\"}"), "slug", "slug.reserved",
                "slug is blank or reserved: 'feed'.");
        oneField(updatePage("{\"slug\":\"  \"}"), "slug", "slug.reserved", "slug is blank or reserved: '  '.");
        oneField(createPage("{\"title\":\"!!!\"}"), "slug", "slug.reserved", "slug is blank or reserved: ''.");
    }

    @Test
    void aLocaleSlugIsSlugReserved() throws Exception {
        oneField(createPage("{\"title\":\"English\",\"slug\":\"en_US\"}"), "slug", "slug.reserved",
                "slug is blank or reserved: 'en_US'.");
    }

    @Test
    void aSlugWithASlashIsSlugHasSlashWithTheSlugAsSent() throws Exception {
        oneField(createPage("{\"title\":\"Deep\",\"slug\":\" a/b \"}"), "slug", "slug.hasSlash",
                "slug may not contain '/': ' a/b '.");
    }

    @Test
    void aSlugOverTheColumnIsFieldTooLong() throws Exception {
        oneField(createPage("{\"title\":\"Long\",\"slug\":\"" + "s".repeat(256) + "\"}"), "slug", "field.tooLong",
                "'slug' must be 255 characters or fewer.");
        verify(pages, never()).savePage(any(), any(), any());
    }

    @Test
    void aSlugHeldByAPageInTheTrashIsAConflictOnTheSlug() throws Exception {
        WeblogPage trashed = page("t1", "gone", weblog);
        trashed.setStatus(WeblogPage.PubStatus.TRASHED);
        when(pages.getPageBySlug(weblog, "gone")).thenReturn(trashed);

        oneField(answer(status().isConflict(), post(PAGES).content("{\"title\":\"Gone\",\"slug\":\"gone\"}")),
                "slug", "slug.heldByTrashedPage",
                "A page in the trash holds slug 'gone'. Restore it or delete it forever first.");
        verify(pages, never()).savePage(any(), any(), any());
    }

    @Test
    void aSlugHeldByALivePageIsAConflictOnTheSlugOnCreateAndRename() throws Exception {
        WeblogPage live = page("p9", "taken", weblog);
        when(pages.getPageBySlug(weblog, "taken")).thenReturn(live);

        oneField(answer(status().isConflict(), post(PAGES).content("{\"title\":\"T\",\"slug\":\"taken\"}")),
                "slug", "slug.inUse", "A page with slug 'taken' already exists.");
        oneField(answer(status().isConflict(), patch(P1).content("{\"slug\":\"taken\"}")),
                "slug", "slug.inUse", "A page with slug 'taken' already exists.");
        verify(pages, never()).savePage(any(), any(), any());
    }

    @Test
    void aPageKeepingItsOwnSlugIsNotAConflict() throws Exception {
        when(pages.getPageBySlug(weblog, "about")).thenReturn(stored);
        send(patch(P1).content("{\"slug\":\"about\"}")).andExpect(status().isOk());
    }

    @Test
    void aNewPageWithNoSlugTakesItsTitlesAndIsHeldToTheSameRules() throws Exception {
        send(post(PAGES).content("{\"title\":\"About Us\"}")).andExpect(status().isCreated());
        org.mockito.ArgumentCaptor<WeblogPage> saved = org.mockito.ArgumentCaptor.forClass(WeblogPage.class);
        verify(pages).savePage(saved.capture(), any(), any());
        assertEquals("about-us", saved.getValue().getSlug());

        oneField(createPage("{\"title\":\"Feed\",\"slug\":\"\"}"), "slug", "slug.reserved",
                "slug is blank or reserved: 'feed'.");
    }

    @Test
    void aSlugLookupThatFailsIsTheOpaque500() throws Exception {
        when(pages.getPageBySlug(weblog, "fresh")).thenThrow(new WebloggerException("database down"));
        JsonNode problem = answer(status().isInternalServerError(),
                post(PAGES).content("{\"title\":\"T\",\"slug\":\"fresh\"}"));
        assertEquals("server.internalError", problem.get("code").asString());
        verify(pages, never()).savePage(any(), any(), any());
    }

    // --- strands on pages ---

    @Test
    void aSitePageCannotBeAStrand() throws Exception {
        weblog.setSiteSpace(true);
        oneField(createPage("{\"title\":\"T\",\"slug\":\"fresh\",\"strand\":true}"), "strand",
                "strand.sitePage", "A site page cannot be a strand.");
        oneField(updatePage("{\"strand\":true}"), "strand", "strand.sitePage", "A site page cannot be a strand.");
    }

    @Test
    void aStrandWithPartsCannotBeUnset() throws Exception {
        stored.setStrand(true);
        when(entries.getStrandPartCount(stored)).thenReturn(3L);
        oneField(updatePage("{\"strand\":false}"), "strand", "strand.hasParts",
                "This strand has 3 posts. Take them out first: PUT .../pages/{id}/parts with an empty entries list.");
    }

    @Test
    void aStrandWithNoPartsCanBeUnsetAndAPlainPageStaysPlain() throws Exception {
        stored.setStrand(true);
        when(entries.getStrandPartCount(stored)).thenReturn(0L);
        send(patch(P1).content("{\"strand\":false}")).andExpect(status().isOk());
        send(post(PAGES).content("{\"title\":\"T\",\"slug\":\"fresh\",\"strand\":false}"))
                .andExpect(status().isCreated());
    }

    @Test
    void aPartCountThatCannotBeReadIsTheOpaque500() throws Exception {
        stored.setStrand(true);
        when(entries.getStrandPartCount(stored)).thenThrow(new WebloggerException("database down"));
        JsonNode problem = answer(status().isInternalServerError(), patch(P1).content("{\"strand\":false}"));
        assertEquals("server.internalError", problem.get("code").asString());
    }

    // --- an entry joining a strand ---

    private JsonNode entryRefused(String body) throws Exception {
        return answer(status().isBadRequest(), patch(E1).content(body));
    }

    private static final String NOT_A_STRAND = "strand must be the id of one of this blog's strand pages; "
            + "pages list shows which pages are strands.";

    @Test
    void anotherBlogsPageAPlainPageAnUnknownIdAndABlankOneAreOneErrorOnStrand() throws Exception {
        Weblog elsewhere = new Weblog();
        elsewhere.setId("other-id");
        elsewhere.setHandle("elsewhere");
        strandPage("foreign", true, elsewhere);
        strandPage("plain", false, weblog);
        for (String id : new String[] {"foreign", "plain", "nope", ""}) {
            oneField(entryRefused("{\"strand\":\"" + id + "\"}"), "strand", "strand.idNotStrandPage",
                    NOT_A_STRAND);
        }
        verify(entries, never()).appendToStrand(any(), any());
    }

    @Test
    void aTrashedStrandPageIsRefusedLikeAnUnknownOne() throws Exception {
        strandPage("binned", true, weblog).setStatus(WeblogPage.PubStatus.TRASHED);
        oneField(entryRefused("{\"strand\":\"binned\"}"), "strand", "strand.idNotStrandPage", NOT_A_STRAND);
    }

    @Test
    void oneOfTheBlogsStrandPagesIsJoinedAndItsCurrentStrandChangesNothing() throws Exception {
        WeblogPage strand = strandPage("s1", true, weblog);
        send(patch(E1).content("{\"strand\":\"s1\"}")).andExpect(status().isOk());
        verify(entries).appendToStrand(entry, strand);

        entry.setStrandPageId("s1");
        org.mockito.Mockito.clearInvocations(entries);
        send(patch(E1).content("{\"strand\":\"s1\"}")).andExpect(status().isOk());
        verify(entries, never()).appendToStrand(any(), any());
    }

    @Test
    void anAbsentStrandChangesNothing() throws Exception {
        send(patch(E1).content("{\"title\":\"Harbour 2\"}")).andExpect(status().isOk());
        verify(entries, never()).appendToStrand(any(), any());
    }

    // --- pins ---

    private static final String TOO_MANY = "This blog already has " + EntryPins.MAX_PER_WEBLOG
            + " pinned posts, the most it allows. Unpin one first.";

    @Test
    void aPinOverTheLimitIsPinnedTooManyOnPinnedAndNothingIsSaved() throws Exception {
        when(entries.getPinnedEntryCount(weblog)).thenReturn((long) EntryPins.MAX_PER_WEBLOG);
        oneField(entryRefused("{\"pinned\":true}"), "pinned", "pinned.tooMany", TOO_MANY);
        oneField(answer(status().isBadRequest(),
                post("/v1/weblogs/myblog/entries").content("{\"title\":\"t\",\"text\":\"x\",\"pinned\":true}")),
                "pinned", "pinned.tooMany", TOO_MANY);
        verify(entries, never()).saveWeblogEntry(any(), any());
    }

    @Test
    void aPinWithRoomAndAnUnpinNeverCountAndAPinnedEntryIsNotRecounted() throws Exception {
        when(entries.getPinnedEntryCount(weblog)).thenReturn(2L);
        send(patch(E1).content("{\"pinned\":true}")).andExpect(status().isOk());

        when(entries.getPinnedEntryCount(weblog)).thenReturn((long) EntryPins.MAX_PER_WEBLOG);
        entry.setPinned(true);
        send(patch(E1).content("{\"pinned\":true}")).andExpect(status().isOk());
        send(patch(E1).content("{\"pinned\":false}")).andExpect(status().isOk());
    }

    @Test
    void aPinCountThatCannotBeReadIsTheOpaque500() throws Exception {
        when(entries.getPinnedEntryCount(weblog)).thenThrow(new WebloggerException("database down"));
        JsonNode problem = answer(status().isInternalServerError(), patch(E1).content("{\"pinned\":true}"));
        assertEquals("server.internalError", problem.get("code").asString());
    }
}

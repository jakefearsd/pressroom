package com.wikantik.pressroom.ui.restapi.v1;

import java.sql.Timestamp;
import java.util.List;
import com.wikantik.pressroom.business.RevisionKeepLimitException;
import com.wikantik.pressroom.business.WeblogEntryManager;
import com.wikantik.pressroom.business.WeblogPageManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogPage;
import com.wikantik.pressroom.pojos.WeblogPageRevision;
import com.wikantik.pressroom.ui.restapi.ApiExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;

import static com.wikantik.pressroom.ui.restapi.v1.ProblemAsserts.fieldCodes;
import static com.wikantik.pressroom.ui.restapi.v1.ProblemAsserts.oneField;
import static com.wikantik.pressroom.ui.restapi.v1.ProblemAsserts.problem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Every refusal of the page routes carries its own code, with the English it
 * always had (spec 2026-11-20-api-contract-design, Task 2, criteria 1 and 3):
 * the code asserted beside the byte-for-byte detail, and for a field error
 * its own {@code errors[].code}.
 */
class PageProblemCodesTest {

    private static final String P1 = "/v1/weblogs/myblog/pages/p1";

    private WeblogPageManager pages;
    private WeblogEntryManager entries;
    private Weblog weblog;
    private WeblogPage page;
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        Weblogger weblogger = mock(Weblogger.class);
        pages = mock(WeblogPageManager.class);
        entries = mock(WeblogEntryManager.class);
        when(weblogger.getWeblogPageManager()).thenReturn(pages);
        when(weblogger.getWeblogEntryManager()).thenReturn(entries);
        weblog = new Weblog();
        weblog.setId("myblog-id");
        weblog.setHandle("myblog");
        page = page("p1", "about", WeblogPage.PubStatus.PUBLISHED);
        PagesApi controller = new PagesApi();
        controller.weblogger = weblogger;
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ApiExceptionHandler()).build();
    }

    private WeblogPage page(String id, String slug, WeblogPage.PubStatus status) throws Exception {
        WeblogPage made = new WeblogPage();
        made.setId(id);
        made.setWeblog(weblog);
        made.setSlug(slug);
        made.setTitle("Title of " + slug);
        made.setStatus(status);
        when(pages.getPage(id)).thenReturn(made);
        return made;
    }

    private JsonNode answer(ResultMatcher status, MockHttpServletRequestBuilder request) throws Exception {
        String body = mvc.perform(request.requestAttr("actionWeblog", weblog)
                        .requestAttr("authenticatedUser", new User())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status).andReturn().getResponse().getContentAsString();
        return new tools.jackson.databind.ObjectMapper().readTree(body);
    }

    private JsonNode refused(MockHttpServletRequestBuilder request) throws Exception {
        return answer(status().isBadRequest(), request);
    }

    private JsonNode create(String body) throws Exception {
        return refused(post("/v1/weblogs/myblog/pages").content(body));
    }

    private JsonNode update(String body) throws Exception {
        return refused(patch(P1).content(body));
    }

    // ------------------------------------------------------------ lookups and state

    @Test
    void anUnknownPageIsPageNotFound() throws Exception {
        problem(answer(status().isNotFound(), get("/v1/weblogs/myblog/pages/nope")),
                "page.notFound", "No such page.");
        problem(answer(status().isNotFound(), post("/v1/weblogs/myblog/pages/nope/restore")),
                "page.notFound", "No such page.");
    }

    @Test
    void aWriteToATrashedPageIsPageTrashed() throws Exception {
        page.setStatus(WeblogPage.PubStatus.TRASHED);
        page.setTrashedAt(new Timestamp(0));
        when(pages.getPage("p1")).thenReturn(page);

        problem(answer(status().isConflict(), patch(P1).content("{}")),
                "page.trashed", "Page is in the trash; restore it first.");
    }

    @Test
    void theTrashRoutesNameWhatIsWrong() throws Exception {
        page.setStatus(WeblogPage.PubStatus.TRASHED);
        page.setTrashedAt(new Timestamp(0));
        problem(answer(status().isConflict(), delete(P1)), "page.alreadyTrashed", "Page is already in the trash.");

        page.setStatus(WeblogPage.PubStatus.PUBLISHED);
        page.setTrashedAt(null);
        problem(answer(status().isConflict(), post(P1 + "/restore")), "page.notInTrash", "Page is not trashed.");
        problem(answer(status().isConflict(), post(P1 + "/delete-forever")),
                "page.deleteForeverNeedsTrash", "Page is not trashed; trash it first.");
    }

    @Test
    void anUnknownRevisionIsRevisionNotFoundAndAnEleventhKeptOneRevisionTooMany() throws Exception {
        problem(answer(status().isNotFound(), post(P1 + "/revisions/nope/keep")),
                "revision.notFound", "No such revision.");

        WeblogPageRevision revision = new WeblogPageRevision();
        revision.setId("r1");
        revision.setPage(page);
        when(pages.getRevision("r1")).thenReturn(revision);
        doThrow(new RevisionKeepLimitException(10)).when(pages).setRevisionKept(revision, true);
        problem(answer(status().isConflict(), post(P1 + "/revisions/r1/keep")),
                "revision.tooMany", "At most 10 kept revisions for one item.");
    }

    // ------------------------------------------------------------ fields

    @Test
    void aCreateWithNoTitleIsFieldRequired() throws Exception {
        oneField(create("{\"slug\":\"fresh\"}"), "title", "field.required", "title is required.");
    }

    @Test
    void aCreateSendingUpdateOnlyFieldsNamesEachWithItsOwnCode() throws Exception {
        JsonNode problem = create("{\"title\":\"T\",\"clear\":[\"metaTitle\"],\"revisionNote\":\"n\","
                + "\"expectedContentHash\":\"h\"}");

        assertEquals("request.invalidFields", problem.get("code").asString());
        assertEquals(List.of("clear=field.updateOnly", "revisionNote=revisionNote.onCreate",
                "expectedContentHash=field.updateOnly"), fieldCodes(problem));
    }

    @Test
    void aBlankTitleOnAnUpdateIsFieldBlank() throws Exception {
        oneField(update("{\"title\":\" \"}"), "title", "field.blank", "title cannot be blank.");
    }

    @Test
    void aMalformedExpectedContentHashIsPageExpectedContentHashInvalid() throws Exception {
        oneField(update("{\"expectedContentHash\":\"xyz\"}"), "expectedContentHash",
                "page.expectedContentHashInvalid",
                "expectedContentHash must be the contentHash a page is read with: 64 hex digits.");
    }

    @Test
    void aStatusFilterThatIsNotTrashedIsPageStatusFilterInvalid() throws Exception {
        oneField(refused(get("/v1/weblogs/myblog/pages").param("status", "bogus")), "status",
                "page.statusFilterInvalid", "status accepts only TRASHED, which lists the page trash.");
    }

    @Test
    void anUnknownStatusIsStatusUnknownAndATrashedOneIsNotWritable() throws Exception {
        oneField(update("{\"status\":\"NOPE\"}"), "status", "status.unknown", "Unknown status 'NOPE'.");
        oneField(update("{\"status\":\"TRASHED\"}"), "status", "page.trashStatusNotWritable",
                "Use DELETE to trash a page and POST .../restore to bring it back.");
    }

    @Test
    void aNegativeNavOrderIsNavOrderNegative() throws Exception {
        oneField(update("{\"navOrder\":-1}"), "navOrder", "navOrder.negative", "navOrder must be 0 or more, got -1.");
    }

    @Test
    void aTitleOverItsColumnIsFieldTooLong() throws Exception {
        oneField(update("{\"title\":\"" + "t".repeat(256) + "\"}"), "title", "field.tooLong",
                "'title' must be 255 characters or fewer.");
    }

    @Test
    void aBadSlugIsSlugReservedOrSlugHasSlash() throws Exception {
        oneField(update("{\"slug\":\"entry\"}"), "slug", "slug.reserved", "slug is blank or reserved: 'entry'.");
        oneField(update("{\"slug\":\"a/b\"}"), "slug", "slug.hasSlash", "slug may not contain '/': 'a/b'.");
    }

    @Test
    void aSlugAnotherPageHoldsIsSlugInUseOrHeldByTrashedPage() throws Exception {
        WeblogPage holder = page("p2", "contact", WeblogPage.PubStatus.PUBLISHED);
        when(pages.getPageBySlug(weblog, "contact")).thenReturn(holder);
        String body = "{\"title\":\"Contact\",\"slug\":\"contact\"}";

        oneField(answer(status().isConflict(), post("/v1/weblogs/myblog/pages").content(body)), "slug",
                "slug.inUse", "A page with slug 'contact' already exists.");

        holder.setStatus(WeblogPage.PubStatus.TRASHED);
        oneField(answer(status().isConflict(), post("/v1/weblogs/myblog/pages").content(body)), "slug",
                "slug.heldByTrashedPage",
                "A page in the trash holds slug 'contact'. Restore it or delete it forever first.");
    }

    // ------------------------------------------------------------ strands

    private JsonNode putParts(String json) throws Exception {
        return refused(put(P1 + "/parts").content(json));
    }

    @Test
    void thePartsRefusalsEachHaveTheirOwnCode() throws Exception {
        oneField(putParts("{}"), "entries", "strand.entriesRequired",
                "entries is required: the strand's post ids in reading order. An empty list removes every part.");
        oneField(putParts("{\"entries\":[]}"), "entries", "strand.flagNotSet",
                "This page is not a strand; set strand true on it first.");

        page.setStrand(true);
        String many = "\"x\",".repeat(PagesApi.MAX_PARTS) + "\"x\"";
        oneField(putParts("{\"entries\":[" + many + "]}"), "entries", "strand.tooMany",
                "At most " + PagesApi.MAX_PARTS + " parts.");
    }

    @Test
    void aPostTheStrandCannotTakeIsPostUnknownDuplicateOrInAnotherStrand() throws Exception {
        page.setStrand(true);
        WeblogEntry mine = entryOf("e1", null);
        entryOf("e2", "other-strand");

        JsonNode problem = putParts("{\"entries\":[\"nope\",\"e1\",\"e1\",\"e2\"]}");

        assertEquals("request.invalidFields", problem.get("code").asString());
        assertEquals(List.of("entries=strand.postUnknown", "entries=strand.postListedTwice",
                "entries=strand.postInAnotherStrand"), fieldCodes(problem));
        assertEquals("No post of this blog has id 'nope'. Post 'e1' is listed twice. "
                + "Post 'e2' is already in another strand; move it with entries update strand, "
                + "or take it out with clear: [\"strand\"].", problem.get("detail").asString());
        assertEquals("e1", mine.getId());
    }

    private WeblogEntry entryOf(String id, String strandPageId) throws Exception {
        WeblogEntry entry = new WeblogEntry();
        entry.setId(id);
        entry.setWebsite(weblog);
        entry.setStrandPageId(strandPageId);
        when(entries.getWeblogEntry(id)).thenReturn(entry);
        return entry;
    }

    @Test
    void aSitePageAsAStrandIsStrandSitePage() throws Exception {
        weblog.setSiteSpace(true);

        oneField(update("{\"strand\":true}"), "strand", "strand.sitePage", "A site page cannot be a strand.");
    }

    @Test
    void aStrandThatStillHasPartsIsStrandHasParts() throws Exception {
        page.setStrand(true);
        when(entries.getStrandPartCount(page)).thenReturn(2L);

        oneField(update("{\"strand\":false}"), "strand", "strand.hasParts",
                "This strand has 2 posts. Take them out first: PUT .../pages/{id}/parts with an empty entries list.");
    }
}

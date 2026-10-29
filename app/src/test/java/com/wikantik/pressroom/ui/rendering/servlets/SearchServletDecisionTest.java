package com.wikantik.pressroom.ui.rendering.servlets;

import java.time.Duration;

import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.pojos.WeblogEntry.PubStatus;
import com.wikantik.pressroom.pojos.WeblogTheme;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogCategory;
import com.wikantik.pressroom.pojos.WeblogEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the search servlet does with a query that actually matches something.
 *
 * <p>SearchServletRenderingTest covers the empty and the broken search -- a
 * query with no hits, and the no-results block each theme renders. Nothing
 * covered the case the feature exists for, so a search that silently stopped
 * returning results would have failed no test.
 *
 * <p>Indexing is queued rather than synchronous, so these poll rather than
 * asserting straight after the write. That is the same shape
 * SearchIndexQueryTest uses against the index directly; here it goes through
 * the servlet, which is what a reader actually hits.
 */
class SearchServletDecisionTest {

    private static final String SEARCH_SERVLET = "/pressroom-ui/rendering/search";
    private static final String TERM = "quokkaword";

    private final String handle = "searchdecisionblog";
    private User user;
    private Weblog weblog;

    @BeforeEach
    void setUp() throws Exception {
        RenderingTestSupport.ensureRenderingRuntime();
        RenderingTestSupport.clearRenderCaches();
        user = TestUtils.setupUser("searchdecisionuser");
        weblog = TestUtils.setupWeblog(handle, user);
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    private MockHttpServletResponse search(String query, String category) throws Exception {
        MockHttpServletRequest request =
                RenderingTestSupport.anonymousGet(SEARCH_SERVLET, "/" + handle);
        if (query != null) {
            request.setParameter("q", query);
        }
        if (category != null) {
            request.setParameter("cat", category);
        }
        return RenderingTestSupport.execute(RenderingTestSupport.searchServlet(), request);
    }

    /** Renders repeatedly until the indexer has caught up, or gives up loudly. */
    private String awaitSearchBody(String query, String category, String mustContain)
            throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        String body = "";
        while (System.nanoTime() < deadline) {
            RenderingTestSupport.clearRenderCaches();
            MockHttpServletResponse response = search(query, category);
            assertEquals(200, response.getStatus());
            body = response.getContentAsString();
            if (body.contains(mustContain)) {
                return body;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("search for '" + query + "' never showed '" + mustContain
                + "' within 10s. Last body:\n" + body);
    }

    private WeblogEntry indexed(String anchor, String title, String text, WeblogCategory category)
            throws Exception {
        WeblogCategory cat = category != null ? category
                : TestUtils.setupWeblogCategory(TestUtils.getManagedWebsite(weblog),
                        "General-" + anchor);
        WeblogEntry entry = TestUtils.setupWeblogEntry(anchor, cat, PubStatus.PUBLISHED,
                weblog, user);
        entry.setTitle(title);
        entry.setText(text);
        TestUtils.weblogger().getWeblogEntryManager().saveWeblogEntry(entry);
        TestUtils.weblogger().flush();
        TestUtils.weblogger().getIndexManager()
                .addEntryIndexOperation(TestUtils.getManagedWeblogEntry(entry));
        return entry;
    }

    @Test
    void aSearchThatMatchesAnEntryRendersIt() throws Exception {
        indexed("hit-entry", "Hiking in Spain", "the pyrenees are extraordinary " + TERM, null);
        TestUtils.endSession(true);

        String body = awaitSearchBody(TERM, null, "Hiking in Spain");

        assertFalse(body.contains("qj-search-empty"),
                "a search with a hit must not render the theme's no-results block:\n" + body);
    }

    @Test
    void aSearchNarrowedToACategoryExcludesOtherCategories() throws Exception {
        WeblogCategory travel =
                TestUtils.setupWeblogCategory(TestUtils.getManagedWebsite(weblog), "Travel");
        TestUtils.endSession(true);

        indexed("travel-entry", "Hiking in Spain", "the pyrenees " + TERM, travel);
        indexed("other-entry", "Refactoring Notes", "some code " + TERM, null);
        TestUtils.endSession(true);

        // wait for both to be indexed before narrowing, or the assertion below
        // could pass simply because the second entry had not landed yet
        awaitSearchBody(TERM, null, "Refactoring Notes");

        String narrowed = awaitSearchBody(TERM, travel.getName(), "Hiking in Spain");

        assertFalse(narrowed.contains("Refactoring Notes"),
                "a search narrowed to one category must not return another category's "
                        + "entries:\n" + narrowed);
    }

    @Test
    void aSearchWithNoQueryAtAllStillRendersThePage() throws Exception {
        MockHttpServletResponse response = search(null, null);

        assertEquals(200, response.getStatus(),
                "the search page is reachable without a query -- it is where the search "
                        + "form itself lives");
        assertTrue(response.getContentType().startsWith("text/html"));
    }

    /**
     * A weblog on a custom theme that has defined no templates has no search
     * page and no default page either. The servlet's template lookup logs that
     * and carries on with a null template rather than returning early, so the
     * request fails later, at the renderer, instead of here.
     *
     * <p>The outcome (a 404 rather than a stack trace reaching the reader) is
     * acceptable; the route to it is not obvious, and this pins it so that
     * tidying the lookup into an early return does not change what a reader
     * sees.
     */
    @Test
    void aWeblogWithNoUsableTemplateIsNotFoundRatherThanRenderingNothing() throws Exception {
        Weblog managed = TestUtils.getManagedWebsite(weblog);
        managed.setEditorTheme(WeblogTheme.CUSTOM);
        TestUtils.weblogger().getWeblogManager().saveWeblog(managed);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);

        MockHttpServletResponse response = search(TERM, null);

        assertEquals(404, response.getStatus(),
                "no search template and no default template means nothing can be rendered");
    }
}

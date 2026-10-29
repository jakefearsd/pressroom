package com.wikantik.pressroom.ui.rendering.servlets;

import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.business.WeblogEntryManager;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The decisions FeedServlet makes before it renders anything: what it narrows a
 * feed by, what it refuses, and what it serves from cache.
 *
 * <p>These sit alongside FeedServletRenderingTest, which covers what a rendered
 * feed contains. The split matters because a request that is turned away early
 * never reaches a template, so the two halves fail in different ways and are
 * worth telling apart.
 */
class FeedServletDecisionTest {

    private static final String FEED_SERVLET = "/pressroom-ui/rendering/feed";

    private final String handle = "feeddecisionblog";
    private User user;
    private Weblog weblog;

    @BeforeEach
    void setUp() throws Exception {
        RenderingTestSupport.ensureRenderingRuntime();
        RenderingTestSupport.clearRenderCaches();
        user = TestUtils.setupUser("feeddecisionuser");
        weblog = TestUtils.setupWeblog(handle, user);
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    private MockHttpServletRequest atomRequest() {
        return RenderingTestSupport.anonymousGet(FEED_SERVLET, "/" + handle + "/entries/atom");
    }

    private MockHttpServletResponse execute(MockHttpServletRequest request) throws Exception {
        return RenderingTestSupport.execute(RenderingTestSupport.feedServlet(), request);
    }

    private static void tag(WeblogEntry entry, String tagName) throws Exception {
        WeblogEntryManager manager = TestUtils.weblogger().getWeblogEntryManager();
        WeblogEntry managed = manager.getWeblogEntry(entry.getId());
        managed.addTag(tagName);
        manager.saveWeblogEntry(managed);
        TestUtils.weblogger().flush();
    }

    // --- narrowing by category --------------------------------------------

    /**
     * The other half of unknownCategoryFeedIsNotFound. That one uses the
     * retired rss format, which 404s on its own, so it would pass even with the
     * category check removed; this one asks for atom, so only the category
     * lookup can decide the outcome.
     */
    @Test
    void aFeedNarrowedByAKnownCategoryIsServed() throws Exception {
        WeblogCategory category = TestUtils.setupWeblogCategory(TestUtils.getManagedWebsite(weblog), "Travel");
        TestUtils.endSession(true);

        MockHttpServletRequest request = atomRequest();
        request.setParameter("cat", category.getName());

        assertEquals(200, execute(request).getStatus(),
                "a category that exists must narrow the feed, not reject the request");
    }

    @Test
    void aFeedNarrowedByAnUnknownCategoryIsNotFound() throws Exception {
        MockHttpServletRequest request = atomRequest();
        request.setParameter("cat", "NoSuchCategory");

        assertEquals(404, execute(request).getStatus(),
                "an unknown category must 404 rather than silently serving the whole feed, "
                        + "which would be a different feed than the one asked for");
    }

    // --- narrowing by tag --------------------------------------------------

    @Test
    void aFeedNarrowedByAKnownTagIsServed() throws Exception {
        WeblogEntry entry = TestUtils.setupWeblogEntry("tagged-entry", weblog, user);
        TestUtils.endSession(true);
        tag(entry, "cycling");
        TestUtils.endSession(true);

        MockHttpServletRequest request = atomRequest();
        request.setParameter("tags", "cycling");

        MockHttpServletResponse response = execute(request);
        assertEquals(200, response.getStatus(),
                "a tag that is actually in use must narrow the feed");
        assertTrue(response.getContentAsString().contains("tagged-entry"),
                "and the entry carrying it must be in the result");
    }

    @Test
    void aFeedNarrowedByAnUnusedTagIsNotFound() throws Exception {
        MockHttpServletRequest request = atomRequest();
        request.setParameter("tags", "nosuchtaganywhere");

        assertEquals(404, execute(request).getStatus(),
                "a tag nothing carries must 404. Serving an empty feed instead would make "
                        + "a typo indistinguishable from a tag that simply has no posts yet");
    }

    @Test
    void aFeedCannotBeNarrowedByCategoryAndTagAtOnce() throws Exception {
        MockHttpServletRequest request = atomRequest();
        request.setParameter("cat", "Travel");
        request.setParameter("tags", "cycling");

        assertEquals(404, execute(request).getStatus(),
                "the two narrowings are mutually exclusive; asking for both is a request "
                        + "the feed url space cannot express");
    }

    // --- conditional requests and caching ---------------------------------

    @Test
    void aConditionalRequestForUnchangedContentIsNotModified() throws Exception {
        TestUtils.setupWeblogEntry("conditional-entry", weblog, user);
        TestUtils.endSession(true);

        // first fetch, to learn when the feed says it last changed
        MockHttpServletResponse first = execute(atomRequest());
        assertEquals(200, first.getStatus());
        long lastModified = first.getDateHeader("Last-Modified");

        MockHttpServletRequest conditional = atomRequest();
        conditional.addHeader("If-Modified-Since", lastModified);

        assertEquals(304, execute(conditional).getStatus(),
                "a reader who already has this copy must be told so rather than sent the "
                        + "whole feed again -- this is the entire point of the "
                        + "Last-Modified header the first response set");
    }

    @Test
    void aSecondIdenticalRequestIsServedFromTheCache() throws Exception {
        TestUtils.setupWeblogEntry("cached-entry", weblog, user);
        TestUtils.endSession(true);

        String first = execute(atomRequest()).getContentAsString();
        String second = execute(atomRequest()).getContentAsString();

        assertEquals(first, second,
                "the second request is a cache hit and must return byte-for-byte what the "
                        + "first one rendered");
        assertTrue(first.contains("cached-entry"), "and it is the real feed, not an empty one");
    }
}

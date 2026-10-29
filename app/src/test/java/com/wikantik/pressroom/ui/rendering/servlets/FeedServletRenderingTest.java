package com.wikantik.pressroom.ui.rendering.servlets;

import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry.PubStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeedServletRenderingTest {

    private User user;
    private Weblog weblog;
    private String handle;

    @BeforeEach
    void setUp() throws Exception {
        RenderingTestSupport.ensureRenderingRuntime();
        RenderingTestSupport.clearRenderCaches();
        // unique handle per test method: FeedServlet has no cache bypass
        handle = "feedblog" + System.nanoTime();
        user = TestUtils.setupUser("feeduser");
        weblog = TestUtils.setupWeblog(handle, user);
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    private MockHttpServletResponse feed(String typeAndFormat) throws Exception {
        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/roller-ui/rendering/feed", "/" + handle + "/" + typeAndFormat);
        return RenderingTestSupport.execute(RenderingTestSupport.feedServlet(), request);
    }

    /**
     * W2: feeds are Atom only. {@code entries/rss} used to be a live format;
     * the template that rendered it ({@code weblog-entries-rss.vm}) is gone,
     * so the same request must now 404 rather than pretend to still work.
     */
    @Test
    void rssEntriesFeedIsGone() throws Exception {
        TestUtils.setupWeblogEntry("rss-entry", weblog, user);
        TestUtils.endSession(true);

        MockHttpServletResponse response = feed("entries/rss");

        assertEquals(404, response.getStatus());
    }

    @Test
    void atomEntriesFeedContainsPublishedEntry() throws Exception {
        TestUtils.setupWeblogEntry("atom-entry", weblog, user);
        TestUtils.endSession(true);

        MockHttpServletResponse response = feed("entries/atom");

        assertEquals(200, response.getStatus());
        String body = response.getContentAsString();
        assertTrue(body.contains("<feed"), "must be an Atom document:\n" + body);
        assertTrue(body.contains("atom-entry"), "entry must appear in the feed:\n" + body);
        // Browsers are removing XSLT (Chrome already bannered every feed
        // that asked for it), so the feed no longer asks for atom.xsl.
        assertFalse(body.contains("xml-stylesheet"),
                "the feed must not ask for an XSLT stylesheet:\n" + body);
        // the Firefox 2 sniffing workaround: a 500-byte comment of digits
        assertFalse(body.contains("123456789101112"),
                "the feed must not open with the Firefox feed workaround comment:\n" + body);
        assertTrue(body.startsWith("<?xml"), "the XML declaration comes first:\n" + body);
    }

    /**
     * Atom requires each feed's {@code <id>} to be its own, and the main,
     * category and tag feeds all declared the main feed's URL, so a reader
     * that keys subscriptions by id merged them. The id is now the feed's own
     * {@code rel="self"} address.
     */
    @Test
    void aCategoryFeedHasItsOwnIdWhichIsItsSelfLink() throws Exception {
        TestUtils.setupWeblogEntry("cat-feed-entry", weblog, user);
        TestUtils.endSession(true);

        String main = feed("entries/atom").getContentAsString();
        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/roller-ui/rendering/feed", "/" + handle + "/entries/atom");
        request.setParameter("cat", "General");
        String category = RenderingTestSupport.execute(RenderingTestSupport.feedServlet(), request)
                .getContentAsString();

        String mainId = between(main, "<id>", "</id>");
        String categoryId = between(category, "<id>", "</id>");
        assertNotEquals(mainId, categoryId, "the category feed needs an id of its own:\n" + category);
        assertEquals(between(category, "<link rel=\"self\" type=\"application/atom+xml\" href=\"", "\""),
                categoryId, "the id is the feed's self link:\n" + category);
    }

    /**
     * A feed URL that carries two parameters joins them with {@code &}; the
     * self link and the id printed it bare, so {@code ?cat=X&excerpts=true}
     * was a feed no XML parser would read.
     */
    @Test
    void aFeedWhoseSelfLinkCarriesTwoParametersIsWellFormed() throws Exception {
        TestUtils.setupWeblogEntry("two-param-entry", weblog, user);
        TestUtils.endSession(true);
        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/roller-ui/rendering/feed", "/" + handle + "/entries/atom");
        request.setParameter("cat", "General");
        request.setParameter("excerpts", "true");
        String body = RenderingTestSupport.execute(RenderingTestSupport.feedServlet(), request)
                .getContentAsString();

        assertTrue(between(body, "<id>", "</id>").contains("&amp;"), "the id must be escaped:\n" + body);
        javax.xml.parsers.DocumentBuilderFactory dbf = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        dbf.newDocumentBuilder().parse(new org.xml.sax.InputSource(new java.io.StringReader(body)));
    }

    private static String between(String body, String start, String end) {
        int from = body.indexOf(start);
        assertTrue(from >= 0, "no " + start + " in:\n" + body);
        from += start.length();
        return body.substring(from, body.indexOf(end, from));
    }

    /**
     * {@code UserWrapper#getScreenName} sanitizes tags but does not
     * entity-escape ({@code HTMLSanitizer.conditionallySanitizeText}), so a
     * screen name reaches {@code feeds.vm} carrying live {@code &} and
     * {@code <} characters. {@code <author><name>} is an Atom text
     * construct, not {@code type="html"} like the title beside it: a bare
     * {@code &} there is not "unescaped display text", it is a malformed
     * XML document that a strict feed reader refuses outright. Every other
     * value in the macro already went through {@code $utils.escapeXML};
     * the author name was the one that did not.
     */
    @Test
    void theAtomAuthorNameIsXmlEscaped() throws Exception {
        User managed = TestUtils.weblogger().getUserManager()
                .getUserByUserName(user.getUserName());
        managed.setScreenName("Ampersand & Sons");
        TestUtils.weblogger().getUserManager().saveUser(managed);
        TestUtils.weblogger().flush();
        TestUtils.setupWeblogEntry("author-entry", weblog, user);
        TestUtils.endSession(true);

        MockHttpServletResponse response = feed("entries/atom");

        assertEquals(200, response.getStatus());
        String body = response.getContentAsString();
        assertTrue(body.contains("<name>Ampersand &amp; Sons</name>"),
                "the author name must be XML-escaped inside the Atom text "
                        + "construct:\n" + body);
    }

    /**
     * A trashed entry must not be syndicated -- same invariant as
     * {@code draftEntryPermalinkIsNotFound}/{@code frontPageExcludesTrashedEntry}
     * in {@code PageServletRenderingTest}, for the Atom feed. A live entry
     * alongside it is what stops "the trashed entry is absent" from being
     * satisfied by an empty feed.
     */
    @Test
    void atomEntriesFeedExcludesTrashedEntry() throws Exception {
        TestUtils.setupWeblogEntry("atom-live-entry", weblog, user);
        TestUtils.setupWeblogEntry("atom-gone-entry", weblog, user, PubStatus.TRASHED);
        TestUtils.endSession(true);

        MockHttpServletResponse response = feed("entries/atom");

        assertEquals(200, response.getStatus());
        String body = response.getContentAsString();
        assertTrue(body.contains("atom-live-entry"),
                "the published entry must be syndicated:\n" + body);
        assertFalse(body.contains("atom-gone-entry"),
                "a trashed entry must not be syndicated in atom:\n" + body);
    }

    /**
     * W2: search feeds are gone ({@code weblog-search-atom.vm} and
     * {@code SearchResultsFeedModel} are deleted). A {@code q} parameter on
     * a feed request must 404, not silently fall through and serve the
     * unfiltered entries feed under the same URL a search reader bookmarked.
     */
    @Test
    void searchTermOnAFeedRequestIsNotFound() throws Exception {
        TestUtils.setupWeblogEntry("atom-entry", weblog, user);
        TestUtils.endSession(true);

        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/roller-ui/rendering/feed", "/" + handle + "/entries/atom");
        request.setParameter("q", "atom");
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.feedServlet(), request);

        assertEquals(404, response.getStatus());
    }

    /**
     * W2: multi-locale weblogs are gone, but the URL shape
     * {@code /<handle>/<locale>/...} the old per-locale feeds used is still
     * parsed by WeblogFeedRequest -- {@code getLocale()} can come back
     * non-null even though there is no per-locale content behind it anymore.
     * Without this check the request would fall through and quietly serve
     * the ordinary unfiltered feed under a URL that looks locale-scoped,
     * instead of 404ing on a feed shape that no longer exists.
     */
    @Test
    void aFeedRequestNamingALocaleIsNotFound() throws Exception {
        TestUtils.setupWeblogEntry("atom-entry", weblog, user);
        TestUtils.endSession(true);

        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/roller-ui/rendering/feed", "/" + handle + "/en_US/entries/atom");
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.feedServlet(), request);

        assertEquals(404, response.getStatus());
    }

    @Test
    void unknownCategoryFeedIsNotFound() throws Exception {
        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/roller-ui/rendering/feed", "/" + handle + "/entries/rss");
        request.setParameter("cat", "Nope");
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.feedServlet(), request);

        assertEquals(404, response.getStatus());
    }

    @Test
    void unknownWeblogFeedIsNotFound() throws Exception {
        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/roller-ui/rendering/feed", "/nosuchblog/entries/rss");
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.feedServlet(), request);

        assertEquals(404, response.getStatus());
    }
}

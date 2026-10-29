package com.wikantik.pressroom.ui.rendering;

import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.WebloggerProvider;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

/**
 * CHARACTERISATION TEST -- written before the forward-url switch was replaced
 * by a table, and expected to pass immediately against the switch as it stood.
 * It describes where each public url shape is forwarded; it is not a
 * specification of new behaviour.
 *
 * <p>Every case below was read off the switch rather than reasoned about, which
 * is the point: the switch had nine labels and repeated the same
 * {@code SERVLET/handle[/locale][/context][/data]} assembly in each of them,
 * with the optional segments varying per label in ways no reader could hold in
 * their head. Pinning all of it first is what makes replacing it safe.
 *
 * <p>The table these pin, in the order the cases appear:
 * <pre>
 *   context                              servlet   locale  context  data
 *   (none)                               page        y        n       n
 *   page/entry/date/category/tags        page        y        y       y
 *   feed                                 feed        y        n       y
 *   resource                             resources   n        n       y
 *   mediaresource                        media       n        n       y
 *   search                               search      y        n       n
 *   anything else, no data               page        y        y       n
 *   anything else, with data             (unsupported)
 * </pre>
 */
class WeblogForwardUrlCharacterisationTest {

    // Pure url-shape parsing: the mapper never reaches its provider or facade here.
    private final WeblogRequestMapper mapper =
            new WeblogRequestMapper(mock(WebloggerProvider.class), mock(Weblogger.class));

    private static final String PAGE = "/pressroom-ui/rendering/page";
    private static final String FEED = "/pressroom-ui/rendering/feed";
    private static final String RESOURCES = "/pressroom-ui/rendering/resources";
    private static final String MEDIA = "/pressroom-ui/rendering/media-resources";
    private static final String SEARCH = "/pressroom-ui/rendering/search";

    private String forward(String handle, String locale, String context, String data) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod("GET");
        return mapper.calculateForwardUrl(request, handle, locale, context, data);
    }

    // --- the weblog home --------------------------------------------------

    @Test
    void noContextIsTheWeblogHomePage() {
        assertEquals(PAGE + "/myblog", forward("myblog", null, null, null));
    }

    @Test
    void theHomePageKeepsItsLocale() {
        assertEquals(PAGE + "/myblog/en_US", forward("myblog", "en_US", null, null));
    }

    // --- contexts served by PageServlet -----------------------------------

    @Test
    void pageEntryDateCategoryAndTagsAllGoToThePageServlet() {
        for (String context : new String[]{"page", "entry", "date", "category", "tags"}) {
            assertEquals(PAGE + "/myblog/" + context + "/thedata",
                    forward("myblog", null, context, "thedata"),
                    context + " is served by PageServlet, with its context and data kept");
        }
    }

    @Test
    void aPageContextKeepsLocaleBetweenHandleAndContext() {
        assertEquals(PAGE + "/myblog/en_US/entry/my-post",
                forward("myblog", "en_US", "entry", "my-post"),
                "the locale sits between the handle and the context, not after it");
    }

    @Test
    void aPageContextWithNoDataStillCarriesTheContext() {
        assertEquals(PAGE + "/myblog/tags", forward("myblog", null, "tags", null));
    }

    // --- feeds ------------------------------------------------------------

    @Test
    void aFeedGoesToTheFeedServletWithoutItsContextSegment() {
        assertEquals(FEED + "/myblog/entries",
                forward("myblog", null, "feed", "entries"),
                "the word 'feed' selects the servlet and is then dropped from the path");
    }

    @Test
    void aFeedKeepsItsLocale() {
        assertEquals(FEED + "/myblog/en_US/entries",
                forward("myblog", "en_US", "feed", "entries"));
    }

    @Test
    void aFeedWithNoDataIsJustTheWeblogFeed() {
        assertEquals(FEED + "/myblog", forward("myblog", null, "feed", null));
    }

    // --- resources --------------------------------------------------------

    @Test
    void aResourceDropsBothItsContextAndItsLocale() {
        assertEquals(RESOURCES + "/myblog/logo.png",
                forward("myblog", "en_US", "resource", "logo.png"),
                "a resource is not localised, so the locale is deliberately not carried");
    }

    @Test
    void aMediaResourceLikewise() {
        assertEquals(MEDIA + "/myblog/photo.jpg",
                forward("myblog", "en_US", "mediaresource", "photo.jpg"));
    }

    @Test
    void aResourceWithNoPathIsJustTheWeblog() {
        assertEquals(RESOURCES + "/myblog", forward("myblog", null, "resource", null));
        assertEquals(MEDIA + "/myblog", forward("myblog", null, "mediaresource", null));
    }

    // --- search -----------------------------------------------------------

    @Test
    void aSearchKeepsItsLocaleButDropsAnyData() {
        assertEquals(SEARCH + "/myblog/en_US",
                forward("myblog", "en_US", "search", "ignored"),
                "search terms travel as query parameters, so path data is dropped");
        assertEquals(SEARCH + "/myblog", forward("myblog", null, "search", null));
    }

    // --- anything else ----------------------------------------------------

    @Test
    void anUnknownSingleSegmentIsTreatedAsAStaticPageSlug() {
        assertEquals(PAGE + "/myblog/about",
                forward("myblog", null, "about", null),
                "an unreserved first segment with nothing after it is a page slug, which "
                        + "WeblogPageRequest resolves and 404s itself if there is no such page");
    }

    @Test
    void anUnknownSlugKeepsTheLocale() {
        assertEquals(PAGE + "/myblog/en_US/about",
                forward("myblog", "en_US", "about", null));
    }

    @Test
    void anUnknownSegmentWithFurtherPathIsUnsupported() {
        assertNull(forward("myblog", null, "about", "more"),
                "a second path segment is never part of a page slug, so this is not a "
                        + "weblog url at all and must fall through to the next handler");
    }

    // --- method -----------------------------------------------------------

    @Test
    void everyPostIsDeclined() {
        MockHttpServletRequest post = new MockHttpServletRequest();
        post.setMethod("POST");

        assertNull(mapper.calculateForwardUrl(post, "myblog", null, "entry", "my-post"),
                "Nothing in the public url space accepts a POST since the comment subsystem "
                        + "was removed, so every POST falls through");
        assertNull(mapper.calculateForwardUrl(post, "myblog", null, null, null),
                "including one aimed at the weblog home");
    }
}

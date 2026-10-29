package com.wikantik.pressroom.ui.rendering.servlets;

import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The type a feed is served as: Atom, for every client.
 *
 * <p>A browser that accepted anything used to be told {@code text/xml} so it
 * would run the feed's XSLT stylesheet ({@code site.newsfeeds.styledFeeds}).
 * Browsers are removing XSLT and the feed no longer names a stylesheet, so
 * that lie bought nothing; the setting and the branch are gone.
 */
class FeedServletContentTypeTest {

    private User user;
    private Weblog weblog;
    private String handle;

    @BeforeEach
    void setUp() throws Exception {
        RenderingTestSupport.ensureRenderingRuntime();
        RenderingTestSupport.clearRenderCaches();
        // FeedServlet has no cache bypass: a unique handle per test
        handle = "feedtypeblog" + System.nanoTime();
        user = TestUtils.setupUser("feedtypeuser");
        weblog = TestUtils.setupWeblog(handle, user);
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    private MockHttpServletResponse atom(String accept, String userAgent) throws Exception {
        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/roller-ui/rendering/feed", "/" + handle + "/entries/atom");
        if (accept != null) {
            request.addHeader("Accept", accept);
        }
        if (userAgent != null) {
            request.addHeader("User-Agent", userAgent);
        }
        return RenderingTestSupport.execute(RenderingTestSupport.feedServlet(), request);
    }

    @Test
    void aBrowserAcceptingAnythingIsServedTheRealAtomTypeToo() throws Exception {
        MockHttpServletResponse response = atom("text/html,*/*;q=0.8", "Mozilla/5.0 (X11; Linux)");

        assertEquals(200, response.getStatus());
        assertTrue(response.getContentType().startsWith("application/atom+xml"), response.getContentType());
    }

    @Test
    void aFeedReaderIsServedTheRealAtomType() throws Exception {
        MockHttpServletResponse response = atom("application/atom+xml", "FeedReader/2.0");

        assertEquals(200, response.getStatus());
        assertTrue(response.getContentType().startsWith("application/atom+xml"), response.getContentType());
    }

    @Test
    void aBrowserThatDoesNotAcceptAnythingIsNotTreatedAsOneNeedingTheStyledView() throws Exception {
        MockHttpServletResponse response = atom("application/atom+xml", "Mozilla/5.0");

        assertTrue(response.getContentType().startsWith("application/atom+xml"), response.getContentType());
    }

    @Test
    void aClientSendingNoHeadersAtAllStillGetsAtom() throws Exception {
        MockHttpServletResponse response = atom(null, null);

        assertEquals(200, response.getStatus());
        assertTrue(response.getContentType().startsWith("application/atom+xml"), response.getContentType());
    }
}

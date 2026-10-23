package org.apache.roller.weblogger.ui.rendering;

import org.apache.roller.weblogger.TestUtils;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogPage;
import org.apache.roller.weblogger.pojos.WeblogTheme;
import org.apache.roller.weblogger.ui.rendering.servlets.RenderingTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shared static-page fallback ({@code templates/weblog/page.vm}) titles
 * a page the way the bundled themes do: "Page — Weblog", and an author's
 * meta title alone, as the whole title, since authors write a complete one
 * there. Reached through a CUSTOM theme with no templates of its own, so no
 * {@code _page} override can answer instead.
 */
class FallbackPageTitleTest {

    private User user;
    private Weblog weblog;

    @BeforeEach
    void setUp() throws Exception {
        RenderingTestSupport.ensureRenderingRuntime();
        RenderingTestSupport.clearRenderCaches();
        user = TestUtils.setupUser("fallbacktitleuser");
        weblog = TestUtils.setupWeblog("fallbacktitleblog", user);
        Weblog managed = TestUtils.getManagedWebsite(weblog);
        managed.setEditorTheme(WeblogTheme.CUSTOM);
        TestUtils.weblogger().getWeblogManager().saveWeblog(managed);
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.weblogger().getWeblogPageManager().removePages(TestUtils.getManagedWebsite(weblog));
        TestUtils.endSession(true);
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    private void savePage(String slug, String title, String metaTitle) throws Exception {
        WeblogPage page = new WeblogPage();
        page.setWeblog(TestUtils.getManagedWebsite(weblog));
        page.setSlug(slug);
        page.setTitle(title);
        page.setMetaTitle(metaTitle);
        page.setContent("Body of " + slug);
        page.setStatus(WeblogPage.PubStatus.PUBLISHED);
        TestUtils.weblogger().getWeblogPageManager().savePage(page);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
        RenderingTestSupport.clearRenderCaches();
    }

    private String render(String slug) throws Exception {
        MockHttpServletResponse response = RenderingTestSupport.execute(RenderingTestSupport.pageServlet(),
                RenderingTestSupport.anonymousGet("/roller-ui/rendering/page", "/fallbacktitleblog/" + slug));
        assertEquals(200, response.getStatus());
        return response.getContentAsString();
    }

    @Test
    void aPageIsTitledPageThenWeblogWithAnEmDash() throws Exception {
        savePage("about", "About & Contact", null);

        String body = render("about");

        assertTrue(body.contains("<h1>About &amp; Contact</h1>"), "this is the fallback template:\n" + body);
        assertTrue(body.contains("<title>About &amp; Contact — Test Weblog</title>"), body);
    }

    @Test
    void aMetaTitleIsTheWholeTitle() throws Exception {
        savePage("rates", "Rates", "Rates & seasons in Lisbon");

        String body = render("rates");

        assertTrue(body.contains("<title>Rates &amp; seasons in Lisbon</title>"), body);
    }
}

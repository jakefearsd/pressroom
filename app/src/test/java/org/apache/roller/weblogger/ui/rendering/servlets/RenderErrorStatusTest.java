package org.apache.roller.weblogger.ui.rendering.servlets;

import org.apache.roller.weblogger.TestUtils;
import org.apache.roller.weblogger.business.WeblogManager;
import org.apache.roller.weblogger.pojos.CustomTemplateRendition;
import org.apache.roller.weblogger.pojos.TemplateRendition.RenditionType;
import org.apache.roller.weblogger.pojos.TemplateRendition.TemplateLanguage;
import org.apache.roller.weblogger.pojos.ThemeTemplate.ComponentType;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogTemplate;
import org.apache.roller.weblogger.ui.rendering.util.WeblogPageRequest;
import org.apache.roller.weblogger.ui.rendering.util.cache.RenderCache;
import org.apache.roller.weblogger.ui.rendering.util.cache.RenderCaches;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A template that fails mid-render shows the reader the error page (or the
 * error block spliced where a failed {@code #parse} would have gone), and
 * that page used to go out as a 200 and into the page cache. The cache
 * expires only when {@code weblog.lastModified} moves, so one transient
 * failure -- a database blip inside a model getter -- stayed pinned in front
 * of every reader until an author next saved something.
 *
 * <p>An error render is now a 500 and is never cached. The good template in
 * {@link #aTemplateThatRendersIsStillCached} is the control: without it,
 * "not in the cache" could pass because the cache is simply off.
 */
class RenderErrorStatusTest {

    private static final String HANDLE = "rendererrorblog";

    private User user;
    private Weblog weblog;

    @BeforeEach
    void setUp() throws Exception {
        RenderingTestSupport.ensureRenderingRuntime();
        RenderingTestSupport.clearRenderCaches();
        user = TestUtils.setupUser("rendererroruser");
        weblog = TestUtils.setupWeblog(HANDLE, user);
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
        RenderingTestSupport.clearRenderCaches();
    }

    /** The constructor path: a template that does not parse renders error-page.vm. */
    @Test
    void aTemplateThatDoesNotParseIsAServerErrorAndIsNotCached() throws Exception {
        saveCustomTemplate("unparsable", "<p>before</p>\n#if($model.weblog.name\n<p>never closed</p>");

        MockHttpServletRequest request = get("unparsable");
        MockHttpServletResponse response = render(request);

        assertEquals(500, response.getStatus(), "an error page is not a 200");
        assertTrue(response.getContentAsString().contains("<!-- error-page -->"),
                "the reader still gets the error page's body:\n" + response.getContentAsString());
        assertNull(cached(request), "an error page must never be cached");
    }

    /** The merge path: a failure mid-render splices in error-parse.vm. */
    @Test
    void aTemplateThatFailsMidRenderIsAServerErrorAndIsNotCached() throws Exception {
        saveCustomTemplate("failing", "<p>before</p>\n#parse(\"no-such-template.vm\")\n<p>after</p>");

        MockHttpServletRequest request = get("failing");
        MockHttpServletResponse response = render(request);

        assertEquals(500, response.getStatus(), "a page with an error block is not a 200");
        assertTrue(response.getContentAsString().contains("<!-- error-parse -->"),
                "the reader still gets the error block:\n" + response.getContentAsString());
        assertNull(cached(request), "a page with an error block must never be cached");
    }

    @Test
    void aTemplateThatRendersIsStillCached() throws Exception {
        saveCustomTemplate("working", "<p>fine</p>");

        MockHttpServletRequest request = get("working");
        MockHttpServletResponse response = render(request);

        assertEquals(200, response.getStatus());
        assertNotNull(cached(request), "the control: a good render is cached");
    }

    /** A request the servlet may cache: RenderingTestSupport opts every request out by default. */
    private static MockHttpServletRequest get(String link) {
        MockHttpServletRequest request = RenderingTestSupport.anonymousGet(
                "/roller-ui/rendering/page", "/" + HANDLE + "/page/" + link);
        request.removeAttribute("skipCache");
        return request;
    }

    private static MockHttpServletResponse render(MockHttpServletRequest request) throws Exception {
        return RenderingTestSupport.execute(RenderingTestSupport.pageServlet(), request);
    }

    private static Object cached(MockHttpServletRequest request) throws Exception {
        RenderCache<WeblogPageRequest> cache = RenderCaches.forPage(false);
        WeblogPageRequest pageRequest = new WeblogPageRequest(TestUtils.weblogger(), request);
        Weblog current = TestUtils.weblogger().getWeblogManager().getWeblogByHandle(HANDLE);
        return cache.get(cache.generateKey(pageRequest), cache.lastModified(current));
    }

    private void saveCustomTemplate(String name, String contents) throws Exception {
        WeblogManager wmgr = TestUtils.weblogger().getWeblogManager();
        WeblogTemplate template = new WeblogTemplate();
        template.setWeblog(TestUtils.getManagedWebsite(weblog));
        template.setAction(ComponentType.CUSTOM);
        template.setName(name);
        template.setLink(name);
        template.setDescription(name);
        template.setHidden(false);
        template.setNavbar(false);
        template.setLastModified(new Date());
        wmgr.saveTemplate(template);
        CustomTemplateRendition rendition =
                new CustomTemplateRendition(template, RenditionType.STANDARD);
        rendition.setTemplate(contents);
        rendition.setTemplateLanguage(TemplateLanguage.VELOCITY);
        wmgr.saveTemplateRendition(rendition);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
        RenderingTestSupport.clearRenderCaches();
    }
}

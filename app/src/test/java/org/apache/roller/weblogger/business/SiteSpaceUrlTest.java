package org.apache.roller.weblogger.business;

import java.util.HashMap;
import java.util.Map;

import org.apache.roller.weblogger.config.RuntimeConfigAttachment;
import org.apache.roller.weblogger.config.WebloggerRuntimeConfig;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.ui.rendering.model.PageModel;
import org.apache.roller.weblogger.ui.rendering.model.URLModel;
import org.apache.roller.weblogger.ui.rendering.util.WeblogPageRequest;
import org.apache.roller.weblogger.pojos.WeblogPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

/**
 * One URL rule for pages: the site space lives at the context root, the page
 * named by {@code site.frontpage.page} is the root itself, and everything that
 * links a page (the strategy, {@code $url.staticPage}, the canonical link)
 * agrees. Run under context {@code /roller}, never the root context.
 */
class SiteSpaceUrlTest {

    private final MultiWeblogURLStrategy strategy = new MultiWeblogURLStrategy();
    private RuntimeConfigAttachment runtimeConfig;
    private String previousRelative;
    private String previousAbsolute;
    private Weblog site;
    private Weblog ordinary;

    @BeforeEach
    void setUp() {
        previousRelative = WebloggerRuntimeConfig.getRelativeContextURL();
        previousAbsolute = WebloggerRuntimeConfig.getAbsoluteContextURL();
        WebloggerRuntimeConfig.setRelativeContextURL("/roller");
        WebloggerRuntimeConfig.setAbsoluteContextURL("http://localhost:8080/roller");
        runtimeConfig = RuntimeConfigAttachment.answering("site.frontpage.page", "home");
        site = new Weblog(Weblog.SITE_SPACE_HANDLE, "admin", "Site", "d",
                "a@example.com", "frontpage", "en_US", "UTC");
        site.setSiteSpace(true);
        ordinary = new Weblog("blog", "admin", "Blog", "d",
                "a@example.com", "journal", "en_US", "UTC");
    }

    @AfterEach
    void tearDown() {
        runtimeConfig.close();
        WebloggerRuntimeConfig.setRelativeContextURL(previousRelative);
        WebloggerRuntimeConfig.setAbsoluteContextURL(previousAbsolute);
    }

    @Test
    void theSiteSpaceRootIsTheContextRoot() {
        assertEquals("/roller/", strategy.getWeblogURL(site, null, false));
        assertEquals("http://localhost:8080/roller/", strategy.getWeblogURL(site, null, true));
    }

    @Test
    void aSitePageHangsOffTheContextRoot() {
        assertEquals("/roller/about", strategy.getWeblogStaticPageURL(site, "about", false));
        assertEquals("http://localhost:8080/roller/about",
                strategy.getWeblogStaticPageURL(site, "about", true));
    }

    @Test
    void theFrontDoorPageIsTheRootItself() {
        assertEquals("/roller/", strategy.getWeblogStaticPageURL(site, "home", false));
        assertEquals("http://localhost:8080/roller/",
                strategy.getWeblogStaticPageURL(site, "home", true));
    }

    @Test
    void anOrdinaryBlogsPageNamedLikeTheFrontDoorIsStillItsOwn() {
        assertEquals("/roller/blog/home", strategy.getWeblogStaticPageURL(ordinary, "home", false));
        assertEquals("/roller/blog/about", strategy.getWeblogStaticPageURL(ordinary, "about", false));
    }

    @Test
    void urlModelStaticPageDelegatesToTheStrategy() throws Exception {
        assertEquals("/roller/about", urlModel(site).staticPage("about"));
        assertEquals("/roller/", urlModel(site).staticPage("home"));
        assertEquals("/roller/blog/about", urlModel(ordinary).staticPage("about"));
    }

    @Test
    void theCanonicalUrlOfASitePageIsTheStrategysAbsoluteUrl() throws Exception {
        assertEquals("http://localhost:8080/roller/about", canonical(site, "about"));
        assertEquals("http://localhost:8080/roller/", canonical(site, "home"));
        assertEquals("http://localhost:8080/roller/blog/about", canonical(ordinary, "about"));
    }

    private URLModel urlModel(Weblog weblog) throws Exception {
        org.apache.roller.weblogger.ui.rendering.util.WeblogRequest request =
                new org.apache.roller.weblogger.ui.rendering.util.WeblogRequest();
        request.setWeblog(weblog);
        Map<String, Object> init = new HashMap<>();
        init.put("parsedRequest", request);
        init.put("weblogger", mock(Weblogger.class));
        init.put("urlStrategy", strategy);
        URLModel model = new URLModel();
        model.init(init);
        return model;
    }

    private String canonical(Weblog weblog, String slug) throws Exception {
        WeblogPageRequest request = new WeblogPageRequest();
        request.setWeblog(weblog);
        request.setWeblogHandle(weblog.getHandle());
        request.setPageSlug(slug);
        WeblogPage page = new WeblogPage();
        page.setSlug(slug);
        request.setWeblogPageContent(page);
        Map<String, Object> init = new HashMap<>();
        init.put("parsedRequest", request);
        init.put("weblogger", mock(Weblogger.class));
        init.put("urlStrategy", strategy);
        PageModel model = new PageModel();
        model.init(init);
        return model.getCanonicalUrl();
    }
}

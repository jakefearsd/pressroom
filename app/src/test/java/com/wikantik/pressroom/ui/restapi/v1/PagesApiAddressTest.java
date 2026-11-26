package com.wikantik.pressroom.ui.restapi.v1;

import java.util.List;
import com.wikantik.pressroom.business.URLStrategy;
import com.wikantik.pressroom.business.WeblogPageManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.config.WebloggerRuntimeConfig;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogPage;
import com.wikantik.pressroom.ui.restapi.ApiExceptionHandler;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The page view says where the page is served and whether it is the site's front door, so the React page
 * editor shows the address the reader types and the front-door note instead of rebuilding either from the
 * site URL (React admin migration, wave 2: the client approximation is gone).
 */
class PagesApiAddressTest {

    private final Weblogger weblogger = mock(Weblogger.class);
    private final WeblogPageManager pages = mock(WeblogPageManager.class);
    private final URLStrategy urls = mock(URLStrategy.class);

    PagesApiAddressTest() {
        when(weblogger.getWeblogPageManager()).thenReturn(pages);
        when(weblogger.getUrlStrategy()).thenReturn(urls);
    }

    private MockMvc mockMvc() {
        PagesApi controller = new PagesApi();
        controller.weblogger = weblogger;
        return MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ApiExceptionHandler()).build();
    }

    private static Weblog weblog(String handle, boolean siteSpace) {
        Weblog weblog = new Weblog();
        weblog.setId(handle + "-id");
        weblog.setHandle(handle);
        weblog.setSiteSpace(siteSpace);
        return weblog;
    }

    private static WeblogPage page(Weblog weblog, String slug) {
        WeblogPage page = new WeblogPage();
        page.setId(slug + "-id");
        page.setWeblog(weblog);
        page.setSlug(slug);
        page.setTitle(slug);
        return page;
    }

    private JsonNode view(Weblog weblog, WeblogPage page) throws Exception {
        String body = mockMvc().perform(get("/v1/weblogs/{h}/pages/{id}", weblog.getHandle(), page.getId())
                        .requestAttr("actionWeblog", weblog).requestAttr("authenticatedUser", new User()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return new ObjectMapper().readTree(body);
    }

    @Test
    void aPageCarriesTheAddressTheStrategyBuildsForIt() throws Exception {
        Weblog blog = weblog("myblog", false);
        WeblogPage about = page(blog, "about");
        when(pages.getPage("about-id")).thenReturn(about);
        when(urls.getWeblogStaticPageURL(blog, about, true)).thenReturn("https://example.com/myblog/about");

        JsonNode view = view(blog, about);

        assertEquals("https://example.com/myblog/about", view.get("absoluteUrl").asString());
        assertFalse(view.get("frontDoor").asBoolean());
    }

    @Test
    void theListCarriesItToo() throws Exception {
        Weblog blog = weblog("myblog", false);
        WeblogPage about = page(blog, "about");
        when(pages.getPages(blog)).thenReturn(List.of(about));
        when(urls.getWeblogStaticPageURL(blog, about, true)).thenReturn("https://example.com/myblog/about");

        String body = mockMvc().perform(get("/v1/weblogs/myblog/pages")
                        .requestAttr("actionWeblog", blog).requestAttr("authenticatedUser", new User()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertEquals("https://example.com/myblog/about", new ObjectMapper().readTree(body).get(0).get("absoluteUrl").asString());
    }

    @Test
    void theSitePageNamedAsTheFrontDoorSaysSo() throws Exception {
        Weblog site = weblog("site-space", true);
        WeblogPage home = page(site, "home");
        WeblogPage other = page(site, "about");
        when(pages.getPage("home-id")).thenReturn(home);
        when(pages.getPage("about-id")).thenReturn(other);
        when(urls.getWeblogStaticPageURL(site, home, true)).thenReturn("https://example.com/");
        try (MockedStatic<WebloggerRuntimeConfig> rt = Mockito.mockStatic(WebloggerRuntimeConfig.class)) {
            rt.when(WebloggerRuntimeConfig::getFrontDoorPageSlug).thenReturn("home");
            rt.when(() -> WebloggerRuntimeConfig.isFrontDoorPage("home")).thenReturn(true);

            JsonNode view = view(site, home);
            assertTrue(view.get("frontDoor").asBoolean());
            assertEquals("https://example.com/", view.get("absoluteUrl").asString());
            assertFalse(view(site, other).get("frontDoor").asBoolean());
        }
    }

    @Test
    void aBlogPageWithTheFrontDoorSlugIsNotTheFrontDoor() throws Exception {
        Weblog blog = weblog("myblog", false);
        WeblogPage home = page(blog, "home");
        when(pages.getPage("home-id")).thenReturn(home);
        try (MockedStatic<WebloggerRuntimeConfig> rt = Mockito.mockStatic(WebloggerRuntimeConfig.class)) {
            rt.when(() -> WebloggerRuntimeConfig.isFrontDoorPage("home")).thenReturn(true);

            assertFalse(view(blog, home).get("frontDoor").asBoolean());
        }
    }
}

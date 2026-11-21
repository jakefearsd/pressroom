package com.wikantik.pressroom.ui.restapi.v1;

import java.util.Date;
import java.util.List;
import java.util.Map;

import com.wikantik.pressroom.business.PropertiesManager;
import com.wikantik.pressroom.business.WeblogManager;
import com.wikantik.pressroom.business.WeblogPageManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.discovery.DiscoveryManager;
import com.wikantik.pressroom.pojos.RuntimeConfigProperty;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogPage;
import com.wikantik.pressroom.ui.restapi.ApiExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

/**
 * Characterisation tests (spec 2026-11-20-jsp-rules-and-view-models-design,
 * Task 1, criterion 1): written before the site-settings rules moved out of
 * {@code GlobalConfigController}, and passing on the code they replaced. They
 * pin what {@code PATCH /v1/admin/config} answers today for the inputs the
 * Global Config screen's characterisation covers: the status, and for a
 * refusal its whole problem body; for an acceptance what is stored and what
 * else happens. Most of those inputs were accepted then.
 *
 * <p>Criterion 4 changed the API's answers on purpose, in a commit of its own
 * after the move, each new expectation seen to fail first: the front-door and
 * privacy references are judged as the screen judges them, strings are stored
 * trimmed, a changed rendered-page input moves every weblog, and several bad
 * values are one 400 naming each. The type refusals are byte-identical.
 */
class AdminConfigCharacterisationTest {

    private Weblogger weblogger;
    private PropertiesManager properties;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        weblogger = mock(Weblogger.class);
        properties = mock(PropertiesManager.class);
        when(weblogger.getPropertiesManager()).thenReturn(properties);
        when(weblogger.getDiscoveryManager()).thenReturn(mock(DiscoveryManager.class));
        when(weblogger.getWeblogManager()).thenReturn(mock(WeblogManager.class));
        when(weblogger.getWeblogPageManager()).thenReturn(mock(WeblogPageManager.class));
        AdminApi controller = new AdminApi();
        controller.weblogger = weblogger;
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ApiExceptionHandler()).build();
    }

    // --- per-type judgement ---

    @Test
    void aDiscoveryValueOutOfRangeIsConfigOutOfRange() throws Exception {
        assertEquals("{\"type\":\"https://pressroom.invalid/problems/invalid-request\",\"title\":\"Invalid request\","
                + "\"status\":400,\"detail\":\"'site.discovery.sharePercent' must be between 0 and 100.\","
                + "\"instance\":\"/v1/admin/config\",\"errors\":[{\"field\":\"site.discovery.sharePercent\","
                + "\"message\":\"'site.discovery.sharePercent' must be between 0 and 100.\","
                + "\"code\":\"config.outOfRange\"}],\"code\":\"config.outOfRange\"}",
                refused("{\"site.discovery.sharePercent\":\"101\"}"));
    }

    @Test
    void anUnparsableIntegerIsConfigNotInteger() throws Exception {
        assertEquals("{\"type\":\"https://pressroom.invalid/problems/invalid-request\",\"title\":\"Invalid request\","
                + "\"status\":400,\"detail\":\"'site.pages.maxEntries' must be an integer.\","
                + "\"instance\":\"/v1/admin/config\",\"errors\":[{\"field\":\"site.pages.maxEntries\","
                + "\"message\":\"'site.pages.maxEntries' must be an integer.\",\"code\":\"config.notInteger\"}],"
                + "\"code\":\"config.notInteger\"}",
                refused("{\"site.pages.maxEntries\":\"many\"}"));
    }

    @Test
    void anUnparsableFloatIsConfigNotNumber() throws Exception {
        assertEquals("{\"type\":\"https://pressroom.invalid/problems/invalid-request\",\"title\":\"Invalid request\","
                + "\"status\":400,\"detail\":\"'uploads.file.maxsize' must be a number.\","
                + "\"instance\":\"/v1/admin/config\",\"errors\":[{\"field\":\"uploads.file.maxsize\","
                + "\"message\":\"'uploads.file.maxsize' must be a number.\",\"code\":\"config.notNumber\"}],"
                + "\"code\":\"config.notNumber\"}",
                refused("{\"uploads.file.maxsize\":\"big\"}"));
    }

    @Test
    void anUnparsableBooleanIsConfigNotBoolean() throws Exception {
        assertEquals("{\"type\":\"https://pressroom.invalid/problems/invalid-request\",\"title\":\"Invalid request\","
                + "\"status\":400,\"detail\":\"'groupblogging.enabled' must be true or false.\","
                + "\"instance\":\"/v1/admin/config\",\"errors\":[{\"field\":\"groupblogging.enabled\","
                + "\"message\":\"'groupblogging.enabled' must be true or false.\",\"code\":\"config.notBoolean\"}],"
                + "\"code\":\"config.notBoolean\"}",
                refused("{\"groupblogging.enabled\":\"on\"}"));
    }

    @Test
    void severalBadValuesAreOneRefusalNamingEach() throws Exception {
        String body = refused("{\"site.pages.maxEntries\":\"many\",\"uploads.file.maxsize\":\"big\","
                + "\"site.privacy.page\":\"nope\"}");

        assertEquals("{\"type\":\"https://pressroom.invalid/problems/invalid-request\",\"title\":\"Invalid request\","
                + "\"status\":400,\"detail\":\"'site.pages.maxEntries' must be an integer. "
                + "'uploads.file.maxsize' must be a number. " + PRIVACY_REFUSED + "\","
                + "\"instance\":\"/v1/admin/config\",\"errors\":[{\"field\":\"site.pages.maxEntries\","
                + "\"message\":\"'site.pages.maxEntries' must be an integer.\",\"code\":\"config.notInteger\"},"
                + "{\"field\":\"uploads.file.maxsize\",\"message\":\"'uploads.file.maxsize' must be a number.\","
                + "\"code\":\"config.notNumber\"},{\"field\":\"site.privacy.page\",\"message\":\""
                + PRIVACY_REFUSED + "\",\"code\":\"config.privacyPageUnpublished\"}],"
                + "\"code\":\"request.invalidFields\"}", body);
    }

    @Test
    void stringValuesAreStoredTrimmed() throws Exception {
        String body = accepted("{\"site.name\":\"  New Name \\t\"}");

        assertEquals("New Name", saved().get("site.name").getValue());
        assertEquals("[{\"name\":\"site.name\",\"value\":\"New Name\",\"type\":\"string\"}]", body,
                "the answer echoes what was stored");
    }

    // --- the front door and the privacy page ---

    @Test
    void aChangedFrontDoorPageThatIsNoPublishedSitePageIsRefused() throws Exception {
        siteSpaceWith("draft", WeblogPage.PubStatus.DRAFT);

        assertEquals(oneField("site.frontpage.page", "The front page must be a published site page; no such page "
                + "was found, or it could not be checked.", "config.frontDoorPageUnpublished"),
                refused("{\"site.frontpage.page\":\"draft\"}"));
    }

    @Test
    void anUnchangedFrontDoorPageIsNotLookedUp() throws Exception {
        when(properties.getProperty("site.frontpage.page"))
                .thenReturn(new RuntimeConfigProperty("site.frontpage.page", "gone"));

        accepted("{\"site.frontpage.page\":\"gone\"}");

        verify(weblogger.getWeblogPageManager(), never()).getPageBySlug(any(), any());
    }

    @Test
    void theSiteSpaceHandleInAnyCaseIsRefusedAsTheFrontDoorBlog() throws Exception {
        assertEquals(oneField("site.frontpage.weblog.handle", "site.frontpage.weblog.handle cannot be the site "
                + "space: it holds the site's pages, not a blog.", "config.frontDoorSiteSpace"),
                refused("{\"site.frontpage.weblog.handle\":\"SITE-SPACE\"}"));
    }

    @Test
    void bothFrontDoorSettingsNonEmptyAreRefusedOnThePage() throws Exception {
        siteSpaceWith("home", WeblogPage.PubStatus.PUBLISHED);
        when(properties.getProperty("site.frontpage.weblog.handle"))
                .thenReturn(new RuntimeConfigProperty("site.frontpage.weblog.handle", "travel"));
        String both = oneField("site.frontpage.page", "Set site.frontpage.page or site.frontpage.weblog.handle, "
                + "not both: send the other as an empty string.", "config.frontDoorBothSet");

        assertEquals(both, refused("{\"site.frontpage.weblog.handle\":\"travel\",\"site.frontpage.page\":\"home\"}"));
        assertEquals(both, refused("{\"site.frontpage.page\":\"home\"}"), "the stored blog stays");

        accepted("{\"site.frontpage.weblog.handle\":\"\",\"site.frontpage.page\":\"home\"}");
        assertEquals("", saved().get("site.frontpage.weblog.handle").getValue());
        assertEquals("home", saved().get("site.frontpage.page").getValue());
    }

    @Test
    void aChangedPrivacyPageThatIsNotPublishedIsRefused() throws Exception {
        siteSpaceWith("privacy", WeblogPage.PubStatus.DRAFT);

        assertEquals(oneField("site.privacy.page", PRIVACY_REFUSED, "config.privacyPageUnpublished"),
                refused("{\"site.privacy.page\":\"privacy\"}"));
    }

    // --- what a save does afterwards ---

    @Test
    void aChangedRenderedPageInputMovesEveryWeblogAndTheSiteSpace() throws Exception {
        Weblog blog = new Weblog();
        blog.setLastModified(new Date(1_000L));
        when(weblogger.getWeblogManager().getWeblogs(any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of(blog));
        Weblog site = siteSpaceWith("home", WeblogPage.PubStatus.PUBLISHED);
        site.setLastModified(new Date(1_000L));
        when(properties.getProperty("site.name")).thenReturn(new RuntimeConfigProperty("site.name", "Old"));

        accepted("{\"site.name\":\"New\"}");

        assertTrue(blog.getLastModified().after(new Date(1_000L)));
        assertTrue(site.getLastModified().after(new Date(1_000L)));
        verify(weblogger.getWeblogManager()).saveWeblog(blog);
        verify(weblogger.getWeblogManager()).saveWeblog(site);
    }

    @Test
    void anUnchangedRenderedPageInputMovesNoWeblog() throws Exception {
        when(properties.getProperty("site.name")).thenReturn(new RuntimeConfigProperty("site.name", "Same"));

        accepted("{\"site.name\":\" Same \"}");

        verify(weblogger.getWeblogManager(), never()).saveWeblog(any());
    }

    @Test
    void theDiscoveryRefreshFollowsAChangedDiscoveryValueOnly() throws Exception {
        when(properties.getProperty("site.discovery.windowDays"))
                .thenReturn(new RuntimeConfigProperty("site.discovery.windowDays", "30"));

        accepted("{\"site.discovery.windowDays\":\"30\"}");
        verify(weblogger.getDiscoveryManager(), never()).refresh();

        accepted("{\"site.discovery.windowDays\":\"31\"}");
        verify(weblogger.getDiscoveryManager()).refresh();
    }

    // --- helpers ---

    private String refused(String json) throws Exception {
        String body = mvc.perform(patch("/v1/admin/config").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(r -> assertEquals(400, r.getResponse().getStatus()))
                .andReturn().getResponse().getContentAsString();
        verify(properties, never()).saveProperties(any());
        return body;
    }

    private String accepted(String json) throws Exception {
        return mvc.perform(patch("/v1/admin/config").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(r -> assertEquals(200, r.getResponse().getStatus(), r.getResponse().getContentAsString()))
                .andReturn().getResponse().getContentAsString();
    }

    private static final String PRIVACY_REFUSED = "The privacy policy page must be a published page, written as "
            + "a site page slug or blog-handle/slug; no such published page was found, or it could not be checked.";

    /** The whole body of a 400 naming one field. */
    private static String oneField(String field, String message, String code) {
        return "{\"type\":\"https://pressroom.invalid/problems/invalid-request\",\"title\":\"Invalid request\","
                + "\"status\":400,\"detail\":\"" + message + "\",\"instance\":\"/v1/admin/config\","
                + "\"errors\":[{\"field\":\"" + field + "\",\"message\":\"" + message + "\",\"code\":\""
                + code + "\"}],\"code\":\"" + code + "\"}";
    }

    private Map<String, RuntimeConfigProperty> saved() throws Exception {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, RuntimeConfigProperty>> captor = ArgumentCaptor.forClass(Map.class);
        verify(properties).saveProperties(captor.capture());
        return captor.getValue();
    }

    private Weblog siteSpaceWith(String slug, WeblogPage.PubStatus status) throws Exception {
        Weblog site = new Weblog();
        site.setHandle(Weblog.SITE_SPACE_HANDLE);
        site.setSiteSpace(true);
        when(weblogger.getWeblogManager().getSiteSpace()).thenReturn(site);
        WeblogPage page = new WeblogPage();
        page.setSlug(slug);
        page.setStatus(status);
        when(weblogger.getWeblogPageManager().getPageBySlug(site, slug)).thenReturn(page);
        return site;
    }
}

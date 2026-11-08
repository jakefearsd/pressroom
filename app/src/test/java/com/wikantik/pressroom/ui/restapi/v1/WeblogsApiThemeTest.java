package com.wikantik.pressroom.ui.restapi.v1;

import java.util.ArrayList;
import java.util.List;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.BusinessManager;
import com.wikantik.pressroom.business.WeblogManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.themes.SharedTheme;
import com.wikantik.pressroom.business.themes.ThemeManager;
import com.wikantik.pressroom.config.WebloggerRuntimeConfig;
import com.wikantik.pressroom.pojos.ThemeTemplate;
import com.wikantik.pressroom.pojos.ThemeTemplate.ComponentType;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogTemplate;
import com.wikantik.pressroom.pojos.WeblogTheme;
import com.wikantik.pressroom.ui.restapi.ApiExceptionHandler;
import com.wikantik.pressroom.util.cache.CacheManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

/**
 * Switching a blog's shared theme through {@code PATCH /v1/weblogs/{handle}}
 * (spec 2026-11-08 agent API parity, H1-H4): the theme page's rule, its
 * stylesheet handling and its cache expiry.
 */
class WeblogsApiThemeTest {

    private final Weblogger weblogger = mock(Weblogger.class);
    private final WeblogManager weblogs = mock(WeblogManager.class);
    private final ThemeManager themes = mock(ThemeManager.class);
    private final WeblogTheme current = mock(WeblogTheme.class);
    private final Weblog weblog = new Weblog();
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        when(weblogger.getWeblogManager()).thenReturn(weblogs);
        when(weblogger.getBusinessManager()).thenReturn(mock(BusinessManager.class));
        when(weblogger.getThemeManager()).thenReturn(themes);
        List<SharedTheme> enabled = new ArrayList<>();
        for (String id : List.of("journal", "frontpage", "travel")) {
            SharedTheme theme = mock(SharedTheme.class);
            when(theme.getId()).thenReturn(id);
            enabled.add(theme);
        }
        when(themes.getEnabledThemesList()).thenReturn(enabled);
        when(themes.getTheme(weblog)).thenReturn(current);

        weblog.setId("myblog-id");
        weblog.setHandle("myblog");
        weblog.setName("My Blog");
        weblog.setActive(Boolean.TRUE);
        weblog.setEntryDisplayCount(15);
        weblog.setEditorTheme("journal");

        WeblogsApi controller = new WeblogsApi();
        controller.weblogger = weblogger;
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ApiExceptionHandler()).build();
    }

    private static MockedStatic<WebloggerRuntimeConfig> siteConfig() {
        MockedStatic<WebloggerRuntimeConfig> config = mockStatic(WebloggerRuntimeConfig.class);
        config.when(() -> WebloggerRuntimeConfig.getIntProperty("site.pages.maxEntries")).thenReturn(30);
        config.when(() -> WebloggerRuntimeConfig.getPropertyWithConfigFallback("site.absoluteurl"))
                .thenReturn("https://blog.example.com");
        return config;
    }

    /** Sends the PATCH; {@code cacheCheck} runs while CacheManager is still mocked. */
    private JsonNode patchTheme(String json, java.util.function.Consumer<MockedStatic<CacheManager>> cacheCheck)
            throws Exception {
        try (var _ = siteConfig(); var cache = mockStatic(CacheManager.class)) {
            String body = mvc.perform(patch("/v1/weblogs/myblog").requestAttr("actionWeblog", weblog)
                            .contentType(MediaType.APPLICATION_JSON).content(json))
                    .andReturn().getResponse().getContentAsString();
            cacheCheck.accept(cache);
            return new ObjectMapper().readTree(body);
        }
    }

    private JsonNode patchTheme(String json) throws Exception {
        return patchTheme(json, cache -> { });
    }

    // ------------------------------------------------------------------ H1

    @Test
    void anOfferedThemeIsSwitchedToSavedAndThePagesExpire() throws Exception {
        JsonNode view = patchTheme("{\"theme\":\"travel\"}",
                cache -> cache.verify(() -> CacheManager.invalidate(weblog)));

        assertEquals("travel", weblog.getEditorTheme());
        assertEquals("travel", view.path("theme").asString());
        verify(weblogs).saveWeblog(weblog);
        verify(weblogger).flush();
    }

    /** The theme page drops the blog's copy of the old theme's stylesheet on a switch; so does the API. */
    @Test
    void switchingDropsTheOldThemesStylesheetCopyAsTheThemePageDoes() throws Exception {
        when(current.getStylesheet()).thenReturn(mock(ThemeTemplate.class));
        WeblogTemplate copy = new WeblogTemplate();
        when(weblogs.getTemplateByAction(weblog, ComponentType.STYLESHEET)).thenReturn(copy);

        patchTheme("{\"theme\":\"travel\"}");

        verify(weblogs).removeTemplate(copy);
    }

    /**
     * A blog whose theme can no longer be resolved (disabled or removed) is
     * exactly the one that needs a switch: like the theme page, the switch
     * goes ahead and, with no current theme to compare, keeps the
     * stylesheet copy rather than guessing.
     */
    @Test
    void aBlogWhoseThemeCannotBeResolvedCanStillSwitch() throws Exception {
        when(themes.getTheme(weblog)).thenThrow(new WebloggerException("theme gone"));

        JsonNode view = patchTheme("{\"theme\":\"travel\"}");

        assertEquals("travel", view.path("theme").asString(), view.toString());
        assertEquals("travel", weblog.getEditorTheme());
        verify(weblogs).saveWeblog(weblog);
        verify(weblogs, never()).removeTemplate(any());
    }

    @Test
    void reSendingTheCurrentThemeKeepsItsStylesheet() throws Exception {
        when(current.getStylesheet()).thenReturn(mock(ThemeTemplate.class));

        patchTheme("{\"theme\":\"journal\"}");

        verify(weblogs, never()).removeTemplate(any());
        assertEquals("journal", weblog.getEditorTheme());
    }

    @Test
    void aBlogAlreadyOnTheFrontPageThemeMayKeepIt() throws Exception {
        weblog.setEditorTheme("frontpage");

        JsonNode view = patchTheme("{\"theme\":\"frontpage\"}");

        assertEquals("frontpage", view.path("theme").asString());
    }

    @Test
    void aPatchWithoutThemeLeavesItAlone() throws Exception {
        patchTheme("{\"name\":\"Renamed\"}");

        assertEquals("journal", weblog.getEditorTheme());
        verify(weblogs, never()).removeTemplate(any());
    }

    // ------------------------------------------------------------------ H2

    @Test
    void aThemeNotOfferedIsA400OnThemeListingTheOfferedIds() throws Exception {
        for (String theme : List.of("frontpage", "nosuchtheme")) {
            JsonNode problem = patchTheme("{\"theme\":\"" + theme + "\"}");

            assertEquals(400, problem.path("status").asInt(), theme);
            assertEquals("theme", problem.path("errors").get(0).path("field").asString());
            assertEquals("theme must be one of: journal, travel.",
                    problem.path("errors").get(0).path("message").asString());
        }
        assertEquals("journal", weblog.getEditorTheme());
        verify(weblogs, never()).saveWeblog(any());
    }

    // ------------------------------------------------------------------ H3

    @Test
    void customIsRefusedAsManagedInTheAdminUi() throws Exception {
        JsonNode problem = patchTheme("{\"theme\":\"custom\"}");

        assertEquals(400, problem.path("status").asInt());
        assertEquals("theme", problem.path("errors").get(0).path("field").asString());
        assertTrue(problem.path("errors").get(0).path("message").asString().contains("admin UI"),
                problem.toString());
        verify(weblogs, never()).saveWeblog(any());
    }

    // ------------------------------------------------------------------ H4

    @Test
    void theViewCarriesTheCurrentTheme() throws Exception {
        String body = mvc.perform(get("/v1/weblogs/myblog").requestAttr("actionWeblog", weblog))
                .andReturn().getResponse().getContentAsString();

        assertEquals("journal", new ObjectMapper().readTree(body).path("theme").asString());
    }
}

package com.wikantik.pressroom.ui.restapi.v1;

import java.util.List;
import com.wikantik.pressroom.business.MockWeblogger;
import com.wikantik.pressroom.business.themes.SharedTheme;
import com.wikantik.pressroom.pojos.ThemeResource;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.ui.controllers.util.UIUtils;
import com.wikantik.pressroom.ui.restapi.ApiExceptionHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;

import static com.wikantik.pressroom.ui.restapi.v1.SessionOnlyApiSupport.JSON;
import static com.wikantik.pressroom.ui.restapi.v1.SessionOnlyApiSupport.body;
import static com.wikantik.pressroom.ui.restapi.v1.SessionOnlyApiSupport.status;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** The create-weblog screen's lists on {@code /v1/meta/weblog-options}, and the session-only weblog deletion. */
class Wave3MiscApiTest {

    private MockWeblogger tier;

    @BeforeEach
    void setUp() {
        tier = MockWeblogger.attached();
    }

    @AfterEach
    void tearDown() {
        tier.detach();
    }

    private SharedTheme theme(String id, boolean preview) {
        SharedTheme theme = mock(SharedTheme.class);
        when(theme.getId()).thenReturn(id);
        when(theme.getName()).thenReturn(id.toUpperCase());
        ThemeResource image = mock(ThemeResource.class);
        when(image.getPath()).thenReturn("p.png");
        when(theme.getPreviewImage()).thenReturn(preview ? image : null);
        return theme;
    }

    private MockMvc metaMvc() {
        MetaApi api = new MetaApi();
        ReflectionTestUtils.setField(api, "weblogger", tier.weblogger());
        return MockMvcBuilders.standaloneSetup(api).setControllerAdvice(new ApiExceptionHandler()).build();
    }

    @Test
    void theOptionsAreTheCreateFormsThemesLocalesAndTimeZones() throws Exception {
        List<SharedTheme> enabled = List.of(theme("travel", true), theme("frontpage", true), theme("journal", false));
        when(tier.themeManager().getEnabledThemesList()).thenReturn(enabled);

        var result = metaMvc().perform(get("/v1/meta/weblog-options").requestAttr("authenticatedUser", new User()));

        JsonNode options = JSON.readTree(result.andReturn().getResponse().getContentAsString());
        assertEquals(List.of("travel", "journal"),
                options.path("themes").valueStream().map(n -> n.path("id").asString()).toList());
        assertEquals("/themes/travel/p.png", options.path("themes").path(0).path("previewPath").asString());
        assertEquals("journal", options.path("defaultTheme").asString());
        assertEquals(UIUtils.getLocales().size(), options.path("locales").size());
        assertEquals(UIUtils.getLocales().get(0).toString(), options.path("locales").path(0).path("id").asString());
        assertEquals(UIUtils.getLocales().get(0).getDisplayName(),
                options.path("locales").path(0).path("name").asString());
        assertEquals(UIUtils.getTimeZones(),
                options.path("timeZones").valueStream().map(JsonNode::asString).toList());
    }

    @Test
    void noDefaultThemeWhenJournalIsNotEnabledAndAnAnonymousCallerIs401() throws Exception {
        List<SharedTheme> enabled = List.of(theme("travel", true));
        when(tier.themeManager().getEnabledThemesList()).thenReturn(enabled);

        var ok = metaMvc().perform(get("/v1/meta/weblog-options").requestAttr("authenticatedUser", new User()));
        assertTrue(JSON.readTree(ok.andReturn().getResponse().getContentAsString()).path("defaultTheme")
                .isMissingNode());

        var anonymous = metaMvc().perform(get("/v1/meta/weblog-options"));
        assertEquals(401, anonymous.andReturn().getResponse().getStatus());
    }

    @Test
    void deleteRemovesTheWeblogWithEverythingOnIt() throws Exception {
        SessionOnlyApiSupport t = new SessionOnlyApiSupport(new WeblogSettingsApi());
        try {
            var result = t.send(delete("/v1/weblogs/myblog"), null);

            assertEquals(204, status(result));
            verify(t.tier.weblogManager()).removeWeblog(t.weblog);
            verify(t.tier.weblogger()).flush();
        } finally {
            t.detach();
        }
    }
}

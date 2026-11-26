package com.wikantik.pressroom.ui.restapi.v1;

import static com.wikantik.pressroom.ui.restapi.v1.SessionOnlyApiSupport.body;
import static com.wikantik.pressroom.ui.restapi.v1.SessionOnlyApiSupport.refused;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.wikantik.pressroom.business.themes.SharedTheme;
import com.wikantik.pressroom.business.themes.ThemeManager;
import com.wikantik.pressroom.pojos.WeblogTheme;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The site's own theme: the Theme screen on the site space, which {@code PressroomHandlerInterceptor}
 * withholds from every {@code /v1/weblogs/{handle}} route. These two admin routes are the only way to reach it,
 * and they name no handle: the server looks the site space up itself. Offered themes and the switch are
 * {@code SharedThemeRules}, the rules the old Theme page applied to the site space.
 */
class AdminSiteThemeApiTest {

    private static final String URL = "/v1/admin/site-theme";

    private SessionOnlyApiSupport t;
    private ThemeManager themes;

    @BeforeEach
    void setUp() throws Exception {
        t = new SessionOnlyApiSupport(new AdminSiteThemeApi());
        t.weblog.setHandle("site-space");
        t.weblog.setSiteSpace(true);
        t.weblog.setEditorTheme("frontpage");
        when(t.tier.weblogManager().getOrCreateSiteSpace(t.admin)).thenReturn(t.weblog);
        themes = t.tier.themeManager();
        SharedTheme journal = shared("journal");
        List<SharedTheme> enabled = List.of(journal, shared("frontpage"), shared("gaia"));
        when(themes.getEnabledThemesList()).thenReturn(enabled);
        WeblogTheme current = mock(WeblogTheme.class);
        when(current.getId()).thenReturn("frontpage");
        when(themes.getTheme(t.weblog)).thenReturn(current);
        when(themes.getTheme("journal")).thenReturn(journal);
    }

    @AfterEach
    void tearDown() {
        t.detach();
    }

    private static SharedTheme shared(String id) {
        SharedTheme theme = mock(SharedTheme.class);
        when(theme.getId()).thenReturn(id);
        when(theme.getName()).thenReturn(id.toUpperCase());
        when(theme.getDescription()).thenReturn(id + " theme");
        return theme;
    }

    @Test
    void theGetOffersTheSiteThemesAndNamesTheCurrentOne() throws Exception {
        JsonNode view = body(t.send(get(URL), null));

        assertEquals("frontpage", view.path("current").asString());
        assertFalse(view.path("custom").asBoolean());
        assertFalse(view.path("customAllowed").asBoolean(), "the site space never takes a custom theme");
        List<String> offered = view.path("themes").valueStream().map(n -> n.path("id").asString()).toList();
        assertEquals(List.of("journal", "frontpage"), offered, "gaia is no site theme");
    }

    @Test
    void thePutSwitchesTheSiteSpaceSavesItAndFlushes() throws Exception {
        JsonNode view = body(t.send(put(URL), "{\"theme\":\"journal\"}"));

        assertEquals("journal", t.weblog.getEditorTheme());
        verify(t.tier.weblogManager()).saveWeblog(t.weblog);
        verify(t.tier.weblogger()).flush();
        assertTrue(view.path("themes").size() > 0, "the answer is the same view the GET gives");
    }

    @Test
    void aThemeThatIsNoSiteThemeIsRefusedAndNothingIsSaved() throws Exception {
        refused(t.send(put(URL), "{\"theme\":\"gaia\"}"), 400, "theme.notOffered");
        refused(t.send(put(URL), "{\"theme\":\"custom\"}"), 400, "theme.notOffered");
        refused(t.send(put(URL), "{\"theme\":\"nosuch\"}"), 400, "theme.notOffered");

        assertEquals("frontpage", t.weblog.getEditorTheme());
        verify(t.tier.weblogManager(), never()).saveWeblog(t.weblog);
    }

    @Test
    void aMissingThemeIsARequiredFieldProblem() throws Exception {
        refused(t.send(put(URL), "{}"), 400, "field.required");
    }

    @Test
    void aBlankThemeIsARequiredFieldProblemAndNothingIsSaved() throws Exception {
        refused(t.send(put(URL), "{\"theme\":\"   \"}"), 400, "field.required");
        refused(t.send(put(URL), "{\"theme\":\"\"}"), 400, "field.required");

        assertEquals("frontpage", t.weblog.getEditorTheme());
        verify(t.tier.weblogManager(), never()).saveWeblog(t.weblog);
    }

    /** Rendered pages expire only through the weblog's invalidation, which every cache handler hears. */
    @Test
    void switchingInvalidatesTheSiteSpaceInTheCaches() throws Exception {
        java.util.List<com.wikantik.pressroom.pojos.Weblog> heard = new java.util.concurrent.CopyOnWriteArrayList<>();
        com.wikantik.pressroom.util.cache.CacheManager.registerHandler(
                new com.wikantik.pressroom.util.cache.CacheHandler() {
                    @Override
                    public void invalidate(com.wikantik.pressroom.pojos.Weblog website) {
                        heard.add(website);
                    }
                });

        t.send(put(URL), "{\"theme\":\"journal\"}");

        assertTrue(heard.contains(t.weblog));
    }
}

package com.wikantik.pressroom.ui.restapi.v1;

import java.util.List;
import com.wikantik.pressroom.business.themes.SharedTheme;
import com.wikantik.pressroom.business.themes.ThemeManager;
import com.wikantik.pressroom.pojos.RuntimeConfigProperty;
import com.wikantik.pressroom.pojos.ThemeResource;
import com.wikantik.pressroom.pojos.ThemeTemplate;
import com.wikantik.pressroom.pojos.ThemeTemplate.ComponentType;
import com.wikantik.pressroom.pojos.WeblogTemplate;
import com.wikantik.pressroom.pojos.WeblogTheme;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import static com.wikantik.pressroom.ui.restapi.v1.SessionOnlyApiSupport.body;
import static com.wikantik.pressroom.ui.restapi.v1.SessionOnlyApiSupport.refused;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** The Theme page over the API: the shared themes on offer, and the session-only switch to a custom theme. */
class WeblogThemesApiTest {

    private static final String URL = "/v1/weblogs/myblog/themes";

    private SessionOnlyApiSupport t;
    private ThemeManager themes;
    private SharedTheme journal;

    @BeforeEach
    void setUp() throws Exception {
        t = new SessionOnlyApiSupport(new WeblogThemesApi());
        themes = t.tier.themeManager();
        journal = shared("journal", "Journal");
        SharedTheme frontpage = shared("frontpage", "Front page");
        when(themes.getEnabledThemesList()).thenReturn(List.of(journal, frontpage));
        WeblogTheme current = mock(WeblogTheme.class);
        when(current.getId()).thenReturn("journal");
        when(themes.getTheme(t.weblog)).thenReturn(current);
        when(themes.getTheme("journal")).thenReturn(journal);
        customThemesAllowed(true);
    }

    @AfterEach
    void tearDown() {
        t.detach();
    }

    private void customThemesAllowed(boolean allowed) throws Exception {
        when(t.tier.propertiesManager().getProperty("themes.customtheme.allowed"))
                .thenReturn(new RuntimeConfigProperty("themes.customtheme.allowed", Boolean.toString(allowed)));
    }

    private SharedTheme shared(String id, String name) {
        SharedTheme theme = mock(SharedTheme.class);
        when(theme.getId()).thenReturn(id);
        when(theme.getName()).thenReturn(name);
        when(theme.getDescription()).thenReturn(name + " theme");
        ThemeResource preview = mock(ThemeResource.class);
        when(preview.getPath()).thenReturn("preview.png");
        when(theme.getPreviewImage()).thenReturn(preview);
        return theme;
    }

    @Test
    void theListIsTheThemesThePickerOffersWithTheCurrentOne() throws Exception {
        JsonNode list = body(t.send(get(URL), null));

        assertEquals("journal", list.path("current").asString());
        assertFalse(list.path("custom").asBoolean());
        assertTrue(list.path("customAllowed").asBoolean());
        assertTrue(list.path("firstCustomization").asBoolean());
        assertFalse(list.path("sharedStylesheetCustomised").asBoolean());
        // frontpage is the site-wide aggregator theme, not offered to an ordinary weblog.
        assertEquals(1, list.path("themes").size());
        JsonNode theme = list.path("themes").path(0);
        assertEquals("journal", theme.path("id").asString());
        assertEquals("Journal", theme.path("name").asString());
        assertEquals("Journal theme", theme.path("description").asString());
        assertEquals("/themes/journal/preview.png", theme.path("previewPath").asString());
    }

    @Test
    void aCustomWeblogHasNoCurrentSharedThemeAndAThemeWithoutPreviewHasNoPath() throws Exception {
        t.weblog.setEditorTheme(WeblogTheme.CUSTOM);
        when(journal.getPreviewImage()).thenReturn(null);
        WeblogTemplate front = new WeblogTemplate();
        when(t.tier.weblogManager().getTemplateByAction(t.weblog, ComponentType.WEBLOG)).thenReturn(front);

        JsonNode list = body(t.send(get(URL), null));

        assertTrue(list.path("custom").asBoolean());
        assertTrue(list.path("current").isMissingNode());
        assertFalse(list.path("firstCustomization").asBoolean());
        assertTrue(list.path("themes").path(0).path("previewPath").isMissingNode());
    }

    @Test
    void convertingImportsTheChosenThemeAndPutsTheWeblogOnItsOwnTemplates() throws Exception {
        JsonNode result = body(t.send(post(URL + "/custom"), "{\"importTheme\":\"journal\"}"));

        verify(themes).importTheme(t.weblog, journal, false);
        verify(t.tier.weblogManager()).saveWeblog(t.weblog);
        assertEquals(WeblogTheme.CUSTOM, t.weblog.getEditorTheme());
        assertTrue(result.path("custom").asBoolean());
    }

    @Test
    void convertingWithNoBodyImportsNothing() throws Exception {
        t.send(post(URL + "/custom"), null);

        verify(themes, never()).importTheme(any(), any(), anyBoolean());
        assertEquals(WeblogTheme.CUSTOM, t.weblog.getEditorTheme());
    }

    @Test
    void reimportingTheCurrentThemeKeepsACustomisedStylesheet() throws Exception {
        t.weblog.setEditorTheme("journal");
        ThemeTemplate css = mock(ThemeTemplate.class);
        when(css.getLink()).thenReturn("css");
        WeblogTheme current = mock(WeblogTheme.class);
        when(current.getId()).thenReturn("journal");
        when(current.getStylesheet()).thenReturn(css);
        when(themes.getTheme(t.weblog)).thenReturn(current);
        when(t.tier.weblogManager().getTemplateByLink(t.weblog, "css")).thenReturn(new WeblogTemplate());

        t.send(post(URL + "/custom"), "{\"importTheme\":\"journal\"}");

        verify(themes).importTheme(t.weblog, journal, true);
    }

    @Test
    void convertingIsRefusedWhereTheSiteOrTheWeblogDoesNotAllowIt() throws Exception {
        customThemesAllowed(false);
        refused(t.send(post(URL + "/custom"), null), 403, "theme.customisationDisabled");

        customThemesAllowed(true);
        t.weblog.setSiteSpace(true);
        refused(t.send(post(URL + "/custom"), null), 403, "theme.customisationDisabled");
        verify(t.tier.weblogManager(), never()).saveWeblog(any());
    }

    @Test
    void convertingRefusesAnImportThatIsNotAnEnabledOfferedTheme() throws Exception {
        refused(t.send(post(URL + "/custom"), "{\"importTheme\":\"nosuch\"}"), 400, "theme.notOffered");
        refused(t.send(post(URL + "/custom"), "{\"importTheme\":\"frontpage\"}"), 400, "theme.notOffered");
        verify(themes, never()).importTheme(any(), any(), anyBoolean());
    }

    @Test
    void aCustomWeblogMayReimportWhateverTheSettingSays() throws Exception {
        customThemesAllowed(false);
        t.weblog.setEditorTheme(WeblogTheme.CUSTOM);

        t.send(post(URL + "/custom"), "{\"importTheme\":\"journal\"}");

        verify(themes).importTheme(any(), any(), anyBoolean());
    }

    @Test
    void anEmptyImportThemeImportsNothingAndStillGoesCustom() throws Exception {
        t.send(post(URL + "/custom"), "{\"importTheme\":\"\"}");

        verify(themes, never()).importTheme(any(), any(), anyBoolean());
        assertEquals(WeblogTheme.CUSTOM, t.weblog.getEditorTheme());
    }

    @Test
    void aWeblogWhoseThemeCannotBeResolvedNamesNoCurrentThemeButStillLists() throws Exception {
        when(themes.getTheme(t.weblog)).thenReturn(null);

        JsonNode list = body(t.send(get(URL), null));

        assertTrue(list.path("current").isMissingNode());
        assertFalse(list.path("custom").asBoolean());
        assertEquals(1, list.path("themes").size());
    }
}

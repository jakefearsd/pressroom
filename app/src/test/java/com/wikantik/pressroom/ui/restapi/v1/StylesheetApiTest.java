package com.wikantik.pressroom.ui.restapi.v1;

import java.util.Date;
import com.wikantik.pressroom.business.WeblogManager;
import com.wikantik.pressroom.business.themes.SharedTheme;
import com.wikantik.pressroom.pojos.CustomTemplateRendition;
import com.wikantik.pressroom.pojos.TemplateRendition;
import com.wikantik.pressroom.pojos.TemplateRendition.RenditionType;
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
import static com.wikantik.pressroom.ui.restapi.v1.SessionOnlyApiSupport.status;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/** The session-only stylesheet routes (SA-18), over {@code ThemeStylesheets}, the Stylesheet page's own rules. */
class StylesheetApiTest {

    private static final String URL = "/v1/weblogs/myblog/stylesheet";

    private SessionOnlyApiSupport t;
    private WeblogManager weblogs;
    private ThemeTemplate shared;
    private WeblogTemplate own;

    @BeforeEach
    void setUp() throws Exception {
        t = new SessionOnlyApiSupport(new StylesheetApi());
        weblogs = t.tier.weblogManager();
        shared = mock(ThemeTemplate.class);
        when(shared.getLink()).thenReturn("css");
        when(shared.getName()).thenReturn("Stylesheet");
        TemplateRendition themeText = mock(TemplateRendition.class);
        when(themeText.getTemplate()).thenReturn("shared {}");
        when(shared.getTemplateRendition(RenditionType.STANDARD)).thenReturn(themeText);
        SharedTheme theme = mock(SharedTheme.class);
        when(theme.getStylesheet()).thenReturn(shared);
        when(t.tier.themeManager().getTheme("journal")).thenReturn(theme);
        own = new WeblogTemplate();
        own.setId("css-1");
        own.setWeblog(t.weblog);
        own.setLink("css");
        own.setAction(ComponentType.STYLESHEET);
        own.setLastModified(new Date(0));
        new CustomTemplateRendition(own, RenditionType.STANDARD).setTemplate("mine {}");
    }

    @AfterEach
    void tearDown() {
        t.detach();
    }

    private void hasOwn() throws Exception {
        when(weblogs.getTemplateByLink(t.weblog, "css")).thenReturn(own);
    }

    @Test
    void theViewSaysWhetherTheWeblogHoldsACopyAndWhatItCanDo() throws Exception {
        JsonNode none = body(t.send(get(URL), null));
        assertFalse(none.path("exists").asBoolean());
        assertTrue(none.path("sharedThemeStylesheet").asBoolean());
        assertFalse(none.path("customTheme").asBoolean());
        assertTrue(none.path("contents").isMissingNode());

        hasOwn();
        JsonNode some = body(t.send(get(URL), null));
        assertTrue(some.path("exists").asBoolean());
        assertEquals("mine {}", some.path("contents").asString());
        assertTrue(some.has("lastModified"));
    }

    @Test
    void saveStoresTheCssAndRefusesWhenThereIsNoCopyOrNoText() throws Exception {
        refused(t.send(put(URL), "{\"contents\":\"a {}\"}"), 404, "stylesheet.notFound");
        hasOwn();
        refused(t.send(put(URL), "{}"), 400, "field.required");

        JsonNode saved = body(t.send(put(URL), "{\"contents\":\"a {}\"}"));

        assertEquals("a {}", saved.path("contents").asString());
        assertEquals("a {}", own.getTemplateRendition(RenditionType.STANDARD).getTemplate());
        verify(weblogs).saveTemplate(own);
        verify(t.tier.weblogger()).flush();
    }

    @Test
    void copyFromSharedCreatesTheCopyCarryingTheThemesCss() throws Exception {
        when(weblogs.getTemplateByLink(t.weblog, "css")).thenReturn(null).thenReturn(own);

        assertEquals(200, status(t.send(post(URL + "/copy-from-shared"), null)));

        // The new copy's rendition, and the revert the page finishes with.
        verify(weblogs, org.mockito.Mockito.times(2)).saveTemplateRendition(any());
        assertEquals("shared {}", own.getTemplateRendition(RenditionType.STANDARD).getTemplate());
    }

    @Test
    void copyFromSharedWithACopyAlreadyThereResetsItToTheThemesText() throws Exception {
        hasOwn();

        t.send(post(URL + "/copy-from-shared"), null);

        assertEquals("shared {}", own.getTemplateRendition(RenditionType.STANDARD).getTemplate());
    }

    @Test
    void copyFromSharedNeedsAThemeWithAStylesheet() throws Exception {
        SharedTheme bare = mock(SharedTheme.class);
        when(t.tier.themeManager().getTheme("journal")).thenReturn(bare);

        refused(t.send(post(URL + "/copy-from-shared"), null), 404, "stylesheet.notFound");
    }

    @Test
    void revertResetsTheCopyToTheThemesText() throws Exception {
        hasOwn();

        assertEquals(200, status(t.send(post(URL + "/revert"), null)));

        assertEquals("shared {}", own.getTemplateRendition(RenditionType.STANDARD).getTemplate());
        verify(weblogs).saveTemplate(own);
    }

    @Test
    void deleteRemovesTheCopyAndSavesTheWeblog() throws Exception {
        hasOwn();

        assertEquals(204, status(t.send(delete(URL), null)));

        verify(weblogs).removeTemplate(own);
        verify(weblogs).saveWeblog(t.weblog);
    }

    @Test
    void revertAndDeleteNeedACopy() throws Exception {
        refused(t.send(post(URL + "/revert"), null), 404, "stylesheet.notFound");
        refused(t.send(delete(URL), null), 404, "stylesheet.notFound");
        verify(weblogs, never()).removeTemplate(any());
    }

    @Test
    void aCustomThemeOwnsItsStylesheetSoCopyRevertAndDeleteAreRefused() throws Exception {
        t.weblog.setEditorTheme(WeblogTheme.CUSTOM);
        WeblogTheme custom = mock(WeblogTheme.class);
        when(custom.getStylesheet()).thenReturn(shared);
        when(t.tier.themeManager().getTheme(t.weblog)).thenReturn(custom);
        hasOwn();

        refused(t.send(post(URL + "/copy-from-shared"), null), 409, "stylesheet.customTheme");
        refused(t.send(post(URL + "/revert"), null), 409, "stylesheet.customTheme");
        refused(t.send(delete(URL), null), 409, "stylesheet.customTheme");
        JsonNode view = body(t.send(get(URL), null));
        assertTrue(view.path("customTheme").asBoolean());
        assertFalse(view.path("sharedThemeStylesheet").asBoolean());
        assertEquals(200, status(t.send(put(URL), "{\"contents\":\"c {}\"}")));
    }
}

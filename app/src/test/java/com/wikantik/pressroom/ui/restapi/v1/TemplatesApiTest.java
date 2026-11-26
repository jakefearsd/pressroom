package com.wikantik.pressroom.ui.restapi.v1;

import java.util.Date;
import java.util.List;
import com.wikantik.pressroom.business.WeblogManager;
import com.wikantik.pressroom.pojos.CustomTemplateRendition;
import com.wikantik.pressroom.pojos.TemplateRendition.RenditionType;
import com.wikantik.pressroom.pojos.ThemeTemplate;
import com.wikantik.pressroom.pojos.ThemeTemplate.ComponentType;
import com.wikantik.pressroom.pojos.WeblogTemplate;
import com.wikantik.pressroom.pojos.WeblogTheme;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;

import static com.wikantik.pressroom.ui.restapi.v1.SessionOnlyApiSupport.body;
import static com.wikantik.pressroom.ui.restapi.v1.SessionOnlyApiSupport.refused;
import static com.wikantik.pressroom.ui.restapi.v1.SessionOnlyApiSupport.status;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The session-only template routes (SA-18), judged by {@code CustomTemplates}, the rules and writes the
 * Templates and Template Edit pages share. Who may call them is swept by {@code SessionOnlyRoutesTest}.
 */
class TemplatesApiTest {

    private static final String URL = "/v1/weblogs/myblog/templates";

    private SessionOnlyApiSupport t;
    private WeblogManager weblogs;
    private WeblogTemplate custom;
    private WeblogTemplate stylesheet;

    @BeforeEach
    void setUp() throws Exception {
        t = new SessionOnlyApiSupport(new TemplatesApi());
        weblogs = t.tier.weblogManager();
        custom = template("tpl-1", "About", ComponentType.CUSTOM, "about", "<p>about</p>");
        stylesheet = template("css-1", "Stylesheet", ComponentType.STYLESHEET, "css", "body {}");
        when(weblogs.getTemplates(t.weblog)).thenReturn(List.of(custom, stylesheet));
        ThemeTemplate themeCss = mock(ThemeTemplate.class);
        when(themeCss.getLink()).thenReturn("css");
        WeblogTheme theme = mock(WeblogTheme.class);
        when(theme.getStylesheet()).thenReturn(themeCss);
        when(t.tier.themeManager().getTheme(t.weblog)).thenReturn(theme);
        when(weblogs.getTemplateByLink(t.weblog, "css")).thenReturn(stylesheet);
        when(weblogs.getTemplate("tpl-1")).thenReturn(custom);
    }

    @AfterEach
    void tearDown() {
        t.detach();
    }

    private WeblogTemplate template(String id, String name, ComponentType action, String link, String markup)
            throws Exception {
        WeblogTemplate template = new WeblogTemplate();
        template.setId(id);
        template.setWeblog(t.weblog);
        template.setName(name);
        template.setAction(action);
        template.setLink(link);
        template.setLastModified(new Date(0));
        if (markup != null) {
            CustomTemplateRendition rendition = new CustomTemplateRendition(template, RenditionType.STANDARD);
            rendition.setTemplate(markup);
        }
        return template;
    }

    @Test
    void theListLeavesOutTheStylesheetAndOffersWhatMayBeAdded() throws Exception {
        JsonNode list = body(t.send(get(URL), null));

        assertEquals(1, list.path("items").size());
        assertEquals("About", list.path("items").path(0).path("name").asString());
        assertTrue(list.path("items").path(0).path("contents").isMissingNode(), "the list carries no markup");
        // A shared theme: a custom page, and a default page when the weblog has none.
        assertEquals(List.of("WEBLOG", "CUSTOM"),
                list.path("availableActions").valueStream().map(JsonNode::asString).sorted(
                        java.util.Comparator.comparing(a -> "CUSTOM".equals(a) ? 1 : 0)).toList());
    }

    @Test
    void oneTemplateCarriesItsMarkup() throws Exception {
        JsonNode one = body(t.send(get(URL + "/tpl-1"), null));

        assertEquals("<p>about</p>", one.path("contents").asString());
        assertEquals("CUSTOM", one.path("action").asString());
        assertEquals("about", one.path("link").asString());
        assertEquals(false, one.path("required").asBoolean());
    }

    @Test
    void aTemplateOfAnotherWeblogOrNoneReadsAsAbsent() throws Exception {
        WeblogTemplate foreign = template("tpl-9", "Theirs", ComponentType.CUSTOM, "x", null);
        com.wikantik.pressroom.pojos.Weblog other = new com.wikantik.pressroom.pojos.Weblog();
        other.setId("other-id");
        other.setHandle("other");
        foreign.setWeblog(other);
        when(weblogs.getTemplate("tpl-9")).thenReturn(foreign);

        refused(t.send(get(URL + "/tpl-9"), null), 404, "template.notFound");
        refused(t.send(get(URL + "/nope"), null), 404, "template.notFound");
        refused(t.send(delete(URL + "/tpl-9"), null), 404, "template.notFound");
    }

    @Test
    void createAddsACustomTemplateWithTheStarterMarkup() throws Exception {
        var result = t.send(post(URL), "{\"name\":\"Contact\",\"action\":\"CUSTOM\"}");

        assertEquals(201, status(result));
        ArgumentCaptor<WeblogTemplate> saved = ArgumentCaptor.forClass(WeblogTemplate.class);
        verify(weblogs).saveTemplate(saved.capture());
        assertEquals("Contact", saved.getValue().getName());
        assertEquals("Contact", saved.getValue().getLink());
        verify(weblogs).saveTemplateRendition(any());
        verify(t.tier.weblogger()).flush();
        assertEquals("Contact", body(result).path("name").asString());
    }

    @Test
    void createOfADefaultPageNamesItAsTheEngineExpectsAndSavesTheWeblog() throws Exception {
        var result = t.send(post(URL), "{\"name\":\"Front\",\"action\":\"WEBLOG\"}");

        assertEquals(201, status(result));
        assertEquals(WeblogTemplate.DEFAULT_PAGE, body(result).path("name").asString());
        verify(weblogs).saveWeblog(t.weblog);
    }

    @Test
    void createRefusesWhatTheFormRefusesAndWhatItsPickerWouldNotOffer() throws Exception {
        when(weblogs.getTemplateByName(t.weblog, "About")).thenReturn(custom);

        refused(t.send(post(URL), "{\"name\":\"About\",\"action\":\"CUSTOM\"}"), 409, "template.nameInUse");
        refused(t.send(post(URL), "{\"action\":\"CUSTOM\"}"), 400, "field.required");
        refused(t.send(post(URL), "{\"name\":\"x\"}"), 400, "field.required");
        refused(t.send(post(URL), "{\"name\":\"" + "n".repeat(256) + "\",\"action\":\"CUSTOM\"}"), 400,
                "field.tooLong");
        refused(t.send(post(URL), "{\"name\":\"x\",\"action\":\"BOGUS\"}"), 400, "template.actionUnavailable");
        // A shared theme takes no PERMALINK page: the picker does not offer it.
        refused(t.send(post(URL), "{\"name\":\"x\",\"action\":\"PERMALINK\"}"), 400, "template.actionUnavailable");
        verify(weblogs, never()).saveTemplate(any());
    }

    @Test
    void updateStoresMarkupNameLinkAndContentTypeOnACustomTemplate() throws Exception {
        var result = t.send(patch(URL + "/tpl-1"), "{\"name\":\"About us\",\"link\":\"about-us\","
                + "\"description\":\"d\",\"navbar\":true,\"hidden\":true,\"contents\":\"<p>new</p>\","
                + "\"contentType\":\"text/plain\"}");

        assertEquals(200, status(result));
        assertEquals("About us", custom.getName());
        assertEquals("about-us", custom.getLink());
        assertEquals("d", custom.getDescription());
        assertTrue(custom.isNavbar() && custom.isHidden());
        assertEquals("text/plain", custom.getOutputContentType());
        assertEquals("<p>new</p>", custom.getTemplateRendition(RenditionType.STANDARD).getTemplate());
        verify(weblogs).saveTemplate(custom);
        verify(t.tier.weblogger()).flush();
    }

    @Test
    void anEmptyContentTypeLetsTheEngineChooseAndAnAbsentOneKeepsTheStoredChoice() throws Exception {
        custom.setOutputContentType("text/plain");

        t.send(patch(URL + "/tpl-1"), "{\"contents\":\"x\"}");
        assertEquals("text/plain", custom.getOutputContentType());

        t.send(patch(URL + "/tpl-1"), "{\"contentType\":\"\"}");
        assertNull(custom.getOutputContentType());
    }

    @Test
    void updateRefusesANameOrLinkAnotherTemplateHasAndTooLongValues() throws Exception {
        WeblogTemplate other = template("tpl-2", "Contact", ComponentType.CUSTOM, "contact", null);
        when(weblogs.getTemplateByName(t.weblog, "Contact")).thenReturn(other);
        when(weblogs.getTemplateByLink(t.weblog, "contact")).thenReturn(other);

        refused(t.send(patch(URL + "/tpl-1"), "{\"name\":\"Contact\"}"), 409, "template.nameInUse");
        refused(t.send(patch(URL + "/tpl-1"), "{\"link\":\"contact\"}"), 409, "template.nameInUse");
        refused(t.send(patch(URL + "/tpl-1"), "{\"name\":\"" + "n".repeat(256) + "\"}"), 400, "field.tooLong");
        refused(t.send(patch(URL + "/tpl-1"), "{\"description\":\"" + "n".repeat(256) + "\"}"), 400,
                "field.tooLong");
        refused(t.send(patch(URL + "/tpl-1"), "{\"link\":\"" + "n".repeat(256) + "\"}"), 400, "field.tooLong");
        refused(t.send(patch(URL + "/tpl-1"), "{\"contentType\":\"" + "n".repeat(49) + "\"}"), 400,
                "field.tooLong");
        verify(weblogs, never()).saveTemplate(any());
    }

    @Test
    void updateOfARequiredTemplateChangesItsMarkupOnly() throws Exception {
        WeblogTemplate page = template("tpl-3", WeblogTemplate.DEFAULT_PAGE, ComponentType.WEBLOG, "Weblog", "m");
        when(weblogs.getTemplate("tpl-3")).thenReturn(page);

        t.send(patch(URL + "/tpl-3"), "{\"name\":\"Renamed\",\"contents\":\"n\"}");

        assertEquals(WeblogTemplate.DEFAULT_PAGE, page.getName());
        assertEquals("n", page.getTemplateRendition(RenditionType.STANDARD).getTemplate());
    }

    @Test
    void deleteRemovesTheTemplate() throws Exception {
        assertEquals(204, status(t.send(delete(URL + "/tpl-1"), null)));

        verify(weblogs).removeTemplate(custom);
        verify(t.tier.weblogger()).flush();
    }

    @Test
    void deleteOfARequiredTemplateOfACustomThemeIsRefused() throws Exception {
        t.weblog.setEditorTheme(WeblogTheme.CUSTOM);
        WeblogTemplate page = template("tpl-3", WeblogTemplate.DEFAULT_PAGE, ComponentType.WEBLOG, "Weblog", "m");
        when(weblogs.getTemplate("tpl-3")).thenReturn(page);

        refused(t.send(delete(URL + "/tpl-3"), null), 409, "template.required");
        verify(weblogs, never()).removeTemplate(any());
    }

    @Test
    void deletingTheDefaultPageOfASharedThemeTakesItsStylesheetCopyToo() throws Exception {
        WeblogTemplate page = template("tpl-3", WeblogTemplate.DEFAULT_PAGE, ComponentType.WEBLOG, "Weblog", "m");
        when(weblogs.getTemplate("tpl-3")).thenReturn(page);

        assertEquals(204, status(t.send(delete(URL + "/tpl-3"), null)));

        verify(weblogs).removeTemplate(page);
        verify(weblogs).removeTemplate(stylesheet);
    }

    @Test
    void aCustomThemeOffersEachSpecialPageItHasNone() throws Exception {
        t.weblog.setEditorTheme(WeblogTheme.CUSTOM);
        WeblogTemplate front = template("tpl-3", WeblogTemplate.DEFAULT_PAGE, ComponentType.WEBLOG, "Weblog", "m");
        when(weblogs.getTemplates(t.weblog)).thenReturn(List.of(custom, front));

        List<String> offered = body(t.send(get(URL), null)).path("availableActions").valueStream()
                .map(JsonNode::asString).sorted().toList();

        assertEquals(List.of("CUSTOM", "PERMALINK", "SEARCH", "TAGSINDEX"), offered);
    }

    /** The column's own guard answers first: a long name is refused as long even when the action is also wrong. */
    @Test
    void anOverlongNameIsRefusedBeforeTheActionIsJudged() throws Exception {
        refused(t.send(post(URL), "{\"name\":\"" + "n".repeat(256) + "\",\"action\":\"BOGUS\"}"), 400,
                "field.tooLong");
        verify(weblogs, never()).saveTemplate(any());
    }

    @Test
    void anUpdateAnswersWithTheTemplateAsStoredMarkupIncluded() throws Exception {
        var view = body(t.send(patch(URL + "/tpl-1"), "{\"name\":\"About us\",\"contents\":\"<p>new</p>\"}"));

        assertEquals("About us", view.path("name").asString());
        assertEquals("<p>new</p>", view.path("contents").asString());
    }
}

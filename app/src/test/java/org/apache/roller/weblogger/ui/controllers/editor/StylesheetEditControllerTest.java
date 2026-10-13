/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  The ASF licenses this file to You
 * under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.  For additional information regarding
 * copyright in this work, please see the NOTICE file in the top level
 * directory of this distribution.
 */
package org.apache.roller.weblogger.ui.controllers.editor;

import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.themes.SharedTheme;
import org.apache.roller.weblogger.pojos.CustomTemplateRendition;
import org.apache.roller.weblogger.pojos.TemplateRendition;
import org.apache.roller.weblogger.pojos.TemplateRendition.RenditionType;
import org.apache.roller.weblogger.pojos.ThemeTemplate;
import org.apache.roller.weblogger.pojos.WeblogTemplate;
import org.apache.roller.weblogger.pojos.WeblogTheme;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ui.Model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link StylesheetEditController}.
 *
 * <p>The controller edits the one template a weblog is allowed to override
 * while still using a shared theme. Two behaviours are load-bearing: the CSS
 * the user typed must actually be stored (see
 * {@link #firstTimeSaveStoresTheSubmittedCssRatherThanAnEmptyRendition()},
 * which covers a fixed data-loss bug), and revert/delete must only be offered
 * for shared themes — a custom theme has no upstream stylesheet to fall back
 * to, so reverting it would blank the blog's styling with nothing to restore.
 */
class StylesheetEditControllerTest extends EditorControllerTestSupport {

    private static final String STYLESHEET_LINK = "custom.css";

    private StylesheetEditController controller;
    private Model model;
    private WeblogTemplate template;
    private ThemeTemplate sharedStylesheet;
    private SharedTheme sharedTheme;

    @BeforeEach
    void setUp() throws Exception {
        controller = prepare(new StylesheetEditController());
        model = newModel();
        weblog.setEditorTheme("journal");

        sharedStylesheet = mock(ThemeTemplate.class);
        when(sharedStylesheet.getLink()).thenReturn(STYLESHEET_LINK);
        when(sharedStylesheet.getName()).thenReturn("Stylesheet");
        when(sharedStylesheet.getDescription()).thenReturn("Theme stylesheet");

        sharedTheme = mock(SharedTheme.class);
        when(sharedTheme.getStylesheet()).thenReturn(sharedStylesheet);
        when(weblogger.getThemeManager().getTheme("journal")).thenReturn(sharedTheme);

        template = new WeblogTemplate();
        template.setId("tmpl-1");
        template.setName("Stylesheet");
        template.setLink(STYLESHEET_LINK);
        template.setWeblog(weblog);
        when(weblogger.getWeblogManager().getTemplateByLink(weblog, STYLESHEET_LINK))
                .thenReturn(template);

        // The custom-theme paths resolve the weblog's theme through the facade's
        // ThemeManager, so it needs wiring up too.
        WeblogTheme customTheme = mock(WeblogTheme.class);
        when(customTheme.getStylesheet()).thenReturn(sharedStylesheet);
        when(weblogger.getThemeManager().getTheme(weblog)).thenReturn(customTheme);
    }

    // --- viewing ---

    @Test
    void openingTheEditorLoadsTheCurrentStylesheetContents() throws Exception {
        givenExistingRendition("body { color: red; }");

        String view = controller.execute(request, model);

        assertEquals(".StylesheetEdit", view);
        assertEquals("body { color: red; }", model.getAttribute("contentsStandard"));
        assertEquals(template, model.getAttribute("template"));
    }

    @Test
    void openingTheEditorForATemplateWithNoRenditionYetShowsAnEmptyBox() throws Exception {
        String view = controller.execute(request, model);

        assertEquals(".StylesheetEdit", view);
        assertEquals("", model.getAttribute("contentsStandard"),
                "An absent rendition must render as an empty editor, not a null");
    }

    @Test
    void theEditorKnowsWhetherTheWeblogIsOnACustomTheme() {
        weblog.setEditorTheme(WeblogTheme.CUSTOM);

        controller.execute(request, model);

        assertEquals(Boolean.TRUE, model.getAttribute("customTheme"));
        assertEquals(Boolean.FALSE, model.getAttribute("sharedTheme"),
                "customTheme and sharedTheme must never agree; the page shows different "
                        + "actions for each");
    }

    // --- saving ---

    @Test
    void savingStoresTheSubmittedCssOverTheExistingRendition() throws Exception {
        CustomTemplateRendition existing = givenExistingRendition("body { color: red; }");

        controller.save(request, model, "body { color: blue; }");

        assertEquals("body { color: blue; }", existing.getTemplate());
        verify(weblogger.getWeblogManager()).saveTemplateRendition(existing);
        verify(weblogger.getWeblogManager()).saveTemplate(template);
        assertTrue(messages(model).contains("stylesheetEdit.save.success"),
                "Expected a save confirmation, got: " + messages(model));
    }

    @Test
    void firstTimeSaveStoresTheSubmittedCssRatherThanAnEmptyRendition() throws Exception {
        // Regression test for a data-loss bug: when the weblog had no STANDARD
        // rendition yet -- which is exactly the first time a user overrides
        // their theme's stylesheet -- the controller created the rendition with
        // an empty template and threw the submitted CSS away, while still
        // reporting "saved successfully". The user's work vanished on reload.
        controller.save(request, model, "body { color: blue; }");

        ArgumentCaptor<CustomTemplateRendition> captor =
                ArgumentCaptor.forClass(CustomTemplateRendition.class);
        verify(weblogger.getWeblogManager()).saveTemplateRendition(captor.capture());
        assertEquals("body { color: blue; }", captor.getValue().getTemplate(),
                "The CSS the user typed must be what gets stored");
    }

    @Test
    void firstTimeSaveWithNoContentStoresAnEmptyStringNotNull() throws Exception {
        // The rendition's template column is NOT NULL, so an absent form field
        // has to become "" rather than propagating a null to the database.
        controller.save(request, model, null);

        ArgumentCaptor<CustomTemplateRendition> captor =
                ArgumentCaptor.forClass(CustomTemplateRendition.class);
        verify(weblogger.getWeblogManager()).saveTemplateRendition(captor.capture());
        assertEquals("", captor.getValue().getTemplate());
    }

    @Test
    void savingMarksTheTemplateAsAStylesheetAndStampsIt() throws Exception {
        givenExistingRendition("");

        controller.save(request, model, "body {}");

        assertEquals(ThemeTemplate.ComponentType.STYLESHEET, template.getAction(),
                "The override must be recorded as a stylesheet or the renderer will not find it");
        assertTrue(template.getLastModified() != null,
                "A modified template must be stamped so caches and conditional GETs invalidate");
    }

    @Test
    void savingWhenNoStylesheetTemplateExistsDoesNothingRatherThanThrowing() throws Exception {
        when(weblogger.getWeblogManager().getTemplateByLink(weblog, STYLESHEET_LINK))
                .thenReturn(null);

        String view = controller.save(request, model, "body {}");

        assertEquals(".StylesheetEdit", view);
        verify(weblogger.getWeblogManager(), never()).saveTemplate(any());
        assertNull(model.getAttribute("template"));
    }

    @Test
    void aFailedSaveIsReportedRatherThanConfirmed() throws Exception {
        givenExistingRendition("");
        org.mockito.Mockito.doThrow(new WebloggerException("database down"))
                .when(weblogger.getWeblogManager()).saveTemplate(any());

        controller.save(request, model, "body {}");

        assertTrue(messages(model).isEmpty(), "A failed save must not report success");
        assertEquals(1, errors(model).size(),
                "Expected the failure to be surfaced, got: " + errors(model));
    }

    // --- copy from shared theme ---

    @Test
    void copyingTheSharedStylesheetCreatesAWeblogOverrideCarryingTheThemesCss() throws Exception {
        // No override yet -- but copyStylesheet finishes by delegating to
        // revert(), which re-reads the template it just saved, so the lookup has
        // to start returning it the way a real flush would.
        when(weblogger.getWeblogManager().getTemplateByLink(weblog, STYLESHEET_LINK))
                .thenReturn(null, template);
        TemplateRendition themeRendition = mock(TemplateRendition.class);
        when(themeRendition.getTemplate()).thenReturn("body { color: green; }");
        when(sharedStylesheet.getTemplateRendition(RenditionType.STANDARD)).thenReturn(themeRendition);

        controller.copyStylesheet(request, model);

        ArgumentCaptor<CustomTemplateRendition> captor =
                ArgumentCaptor.forClass(CustomTemplateRendition.class);
        verify(weblogger.getWeblogManager()).saveTemplateRendition(captor.capture());
        assertEquals("body { color: green; }", captor.getValue().getTemplate(),
                "The copy must start from the theme's CSS, otherwise the user's first edit "
                        + "starts from a blank page and the blog loses all styling");
        // Twice: once for the newly created override, once more from the revert()
        // this method delegates to at the end.
        ArgumentCaptor<WeblogTemplate> saved = ArgumentCaptor.forClass(WeblogTemplate.class);
        verify(weblogger.getWeblogManager(), org.mockito.Mockito.times(2))
                .saveTemplate(saved.capture());

        // The override has to be a complete, resolvable template: the renderer
        // finds it by weblog + link, and treats it as CSS only because of the
        // STYLESHEET action.
        WeblogTemplate created = saved.getAllValues().get(0);
        assertEquals(weblog, created.getWeblog());
        assertEquals(ThemeTemplate.ComponentType.STYLESHEET, created.getAction());
        assertEquals(STYLESHEET_LINK, created.getLink(),
                "The override must claim the same link as the theme stylesheet it replaces");
        assertEquals("Stylesheet", created.getName());
        assertEquals("Theme stylesheet", created.getDescription());
        assertFalse(created.isHidden());
        assertFalse(created.isNavbar());
        assertTrue(messages(model).contains("stylesheetEdit.create.success"),
                "Expected a creation confirmation, got: " + messages(model));
    }

    @Test
    void copyingWhenTheSharedThemeCannotBeLoadedReportsAnErrorInsteadOfThrowing() throws Exception {
        // Regression test: the lookup of the shared theme used to be wrapped in
        // a try/catch that swallowed the WebloggerException and left
        // `stylesheet` null, so the very next line (stylesheet.getLink()) always
        // threw an unhandled NullPointerException instead of the error message
        // this endpoint is supposed to report.
        when(weblogger.getThemeManager().getTheme("journal"))
                .thenThrow(new WebloggerException("theme not found"));

        String view = controller.copyStylesheet(request, model);

        assertEquals(".StylesheetEdit", view);
        assertTrue(messages(model).isEmpty(), "A failed lookup must not report success");
        assertEquals(1, errors(model).size(),
                "Expected the failure to be surfaced, got: " + errors(model));
        verify(weblogger.getWeblogManager(), never()).saveTemplate(any());
    }

    @Test
    void copyingDoesNothingWhenAnOverrideAlreadyExists() throws Exception {
        // The override is already there; recreating it would discard the user's
        // existing customisations. copyStylesheet always finishes by delegating
        // to revert(), which legitimately re-saves the *existing* template --
        // so the assertion is that no second, freshly built template appeared.
        controller.copyStylesheet(request, model);

        ArgumentCaptor<WeblogTemplate> captor = ArgumentCaptor.forClass(WeblogTemplate.class);
        verify(weblogger.getWeblogManager(), org.mockito.Mockito.atMost(1))
                .saveTemplate(captor.capture());
        captor.getAllValues().forEach(saved -> assertEquals(template, saved,
                "copyStylesheet must not build a second stylesheet template when one exists"));
        assertTrue(messages(model).stream().noneMatch("stylesheetEdit.create.success"::equals),
                "Nothing was created, so no creation message: " + messages(model));
    }

    // --- revert ---

    @Test
    void revertingRestoresTheSharedThemesCss() throws Exception {
        CustomTemplateRendition existing = givenExistingRendition("body { color: blue; }");
        TemplateRendition themeRendition = mock(TemplateRendition.class);
        when(themeRendition.getTemplate()).thenReturn("body { color: green; }");
        when(sharedStylesheet.getTemplateRendition(RenditionType.STANDARD)).thenReturn(themeRendition);

        controller.revert(request, model);

        assertEquals("body { color: green; }", existing.getTemplate());
        verify(weblogger.getWeblogManager()).saveTemplateRendition(existing);
    }

    @Test
    void revertingWithNoOverrideToRevertIsANoOpRatherThanA500() throws Exception {
        // Regression test: loadTemplate returns null when there is no stylesheet
        // override, and only WebloggerException was caught, so the null
        // dereference escaped as a server error.
        when(weblogger.getWeblogManager().getTemplateByLink(weblog, STYLESHEET_LINK))
                .thenReturn(null);

        String view = controller.revert(request, model);

        assertEquals(".StylesheetEdit", view);
        verify(weblogger.getWeblogManager(), never()).saveTemplate(any());
    }

    @Test
    void revertingIsRefusedForACustomTheme() throws Exception {
        // A custom theme has no upstream stylesheet to revert to; doing it
        // anyway would wipe the blog's styling with nothing to restore.
        weblog.setEditorTheme(WeblogTheme.CUSTOM);

        controller.revert(request, model);

        verify(weblogger.getWeblogManager(), never()).saveTemplateRendition(any());
        verify(weblogger.getWeblogManager(), never()).saveTemplate(any());
    }

    // --- delete ---

    @Test
    void deletingTheOverrideRemovesItAndClearsItFromTheView() throws Exception {
        String view = controller.delete(request, model);

        assertEquals(".StylesheetEdit", view);
        verify(weblogger.getWeblogManager()).removeTemplate(template);
        assertNull(model.getAttribute("template"),
                "The deleted template must not be left on the model for the page to redraw");
        assertTrue(messages(model).contains("stylesheetEdit.default.success"),
                "Expected a delete confirmation, got: " + messages(model));
    }

    @Test
    void deletingIsRefusedForACustomTheme() throws Exception {
        weblog.setEditorTheme(WeblogTheme.CUSTOM);

        controller.delete(request, model);

        verify(weblogger.getWeblogManager(), never()).removeTemplate(any());
    }

    @Test
    void deletingDoesNothingWhenThereIsNoOverrideToDelete() throws Exception {
        when(weblogger.getWeblogManager().getTemplateByLink(weblog, STYLESHEET_LINK))
                .thenReturn(null);

        controller.delete(request, model);

        verify(weblogger.getWeblogManager(), never()).removeTemplate(any());
    }

    @Test
    void aFailedDeleteLeavesTheTemplateOnTheModelAndReportsTheError() throws Exception {
        org.mockito.Mockito.doThrow(new WebloggerException("in use"))
                .when(weblogger.getWeblogManager()).removeTemplate(any());

        controller.delete(request, model);

        assertEquals(template, model.getAttribute("template"),
                "If the delete failed the template still exists and must still be shown");
        assertTrue(errors(model).contains("generic.error.check.logs"),
                "Expected the failure to be surfaced, got: " + errors(model));
    }

    @Test
    void aFailedRevertIsReportedRatherThanConfirmed() throws Exception {
        givenExistingRendition("body { color: blue; }");
        TemplateRendition themeRendition = mock(TemplateRendition.class);
        when(themeRendition.getTemplate()).thenReturn("body { color: green; }");
        when(sharedStylesheet.getTemplateRendition(RenditionType.STANDARD)).thenReturn(themeRendition);
        org.mockito.Mockito.doThrow(new WebloggerException("database down"))
                .when(weblogger.getWeblogManager()).saveTemplate(any());

        controller.revert(request, model);

        assertTrue(messages(model).isEmpty(), "A failed revert must not report success: " + messages(model));
        assertEquals(java.util.List.of("generic.error.check.logs"), errors(model));
    }

    @Test
    void aFailedCopyIsReportedAndNoOverrideIsAnnounced() throws Exception {
        when(weblogger.getWeblogManager().getTemplateByLink(weblog, STYLESHEET_LINK)).thenReturn(null);
        org.mockito.Mockito.doThrow(new WebloggerException("database down"))
                .when(weblogger.getWeblogManager()).saveTemplate(any());

        controller.copyStylesheet(request, model);

        assertFalse(messages(model).contains("stylesheetEdit.create.success"), "Got: " + messages(model));
        assertTrue(errors(model).contains("generic.error.check.logs"), "Got: " + errors(model));
    }

    private CustomTemplateRendition givenExistingRendition(String css) throws WebloggerException {
        CustomTemplateRendition rendition =
                new CustomTemplateRendition(template, RenditionType.STANDARD);
        // The CustomTemplateRendition constructor already registers itself with
        // the template, so there is nothing further to wire up here.
        rendition.setTemplate(css);
        return rendition;
    }
}

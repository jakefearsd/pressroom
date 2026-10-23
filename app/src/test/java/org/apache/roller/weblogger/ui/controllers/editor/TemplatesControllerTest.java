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

import java.util.List;
import java.util.Map;

import org.apache.roller.util.RollerConstants;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.pojos.ThemeTemplate;
import org.apache.roller.weblogger.pojos.ThemeTemplate.ComponentType;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogTemplate;
import org.apache.roller.weblogger.pojos.WeblogTheme;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ui.Model;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link TemplatesController}.
 *
 * <p>{@code loadTemplatesList} decides which template actions a user is even
 * offered, which differs between a fully custom theme and a shared one, so
 * getting that map wrong either hides an action the user needs or offers one
 * that would collide with the shared theme's own template. {@code remove} is
 * the trickiest of the three handlers: removing the weblog's default page can
 * also have to remove a custom stylesheet override riding along on the same
 * link, and a required template on a custom theme must be protected from
 * deletion entirely.
 */
class TemplatesControllerTest extends EditorControllerTestSupport {

    private TemplatesController controller;
    private Model model;
    private RedirectAttributes redirectAttributes;

    @BeforeEach
    void setUp() throws Exception {
        controller = prepare(new TemplatesController());
        model = newModel();
        redirectAttributes = newRedirectAttributes();
        weblog.setEditorTheme(WeblogTheme.CUSTOM);

        // The controller resolves the weblog's theme through its injected facade's
        // ThemeManager. Without this stub it resolves to null and loadTemplatesList's
        // `theme.getStylesheet()` NPEs before the test even gets to assert anything.
        org.apache.roller.weblogger.pojos.WeblogTheme defaultWeblogTheme =
                mock(org.apache.roller.weblogger.pojos.WeblogTheme.class);
        when(weblogger.getThemeManager().getTheme(weblog)).thenReturn(defaultWeblogTheme);
    }

    // --- execute / loadTemplatesList ---

    @Test
    void executeOnACustomThemeOffersEveryComponentType() throws Exception {
        when(weblogger.getWeblogManager().getTemplates(weblog)).thenReturn(List.of());

        String view = controller.execute(request, model);

        assertEquals(".Templates", view);
        @SuppressWarnings("unchecked")
        Map<ComponentType, String> actions = (Map<ComponentType, String>) model.getAttribute("availableActions");
        assertEquals(
                java.util.Set.of(ComponentType.CUSTOM, ComponentType.PERMALINK, ComponentType.SEARCH,
                        ComponentType.WEBLOG, ComponentType.TAGSINDEX),
                actions.keySet(),
                "A custom theme with no pages yet must offer every component type");
        assertEquals(Boolean.TRUE, model.getAttribute("customTheme"));
    }

    @Test
    void executeOnACustomThemeRemovesAnActionThatAlreadyHasAPage() throws Exception {
        WeblogTemplate weblogPage = templateNamed("Weblog", ComponentType.WEBLOG);
        when(weblogger.getWeblogManager().getTemplates(weblog)).thenReturn(List.of(weblogPage));

        controller.execute(request, model);

        @SuppressWarnings("unchecked")
        Map<ComponentType, String> actions = (Map<ComponentType, String>) model.getAttribute("availableActions");
        assertFalse(actions.containsKey(ComponentType.WEBLOG),
                "An action that already has a page must not be offered again: " + actions.keySet());
        assertTrue(actions.containsKey(ComponentType.SEARCH),
                "Other, still-unused component types must remain available");
    }

    @Test
    void executeOnASharedThemeOnlyOffersCustomAndWeblog() throws Exception {
        weblog.setEditorTheme("shared-theme-1");
        when(weblogger.getWeblogManager().getTemplates(weblog)).thenReturn(List.of());

        controller.execute(request, model);

        @SuppressWarnings("unchecked")
        Map<ComponentType, String> actions = (Map<ComponentType, String>) model.getAttribute("availableActions");
        assertEquals(java.util.Set.of(ComponentType.CUSTOM, ComponentType.WEBLOG), actions.keySet());
        assertEquals(Boolean.FALSE, model.getAttribute("customTheme"));
    }

    @Test
    void executeOnASharedThemeDropsWeblogOnceAPageUsesIt() throws Exception {
        weblog.setEditorTheme("shared-theme-1");
        WeblogTemplate weblogPage = templateNamed("Weblog", ComponentType.WEBLOG);
        when(weblogger.getWeblogManager().getTemplates(weblog)).thenReturn(List.of(weblogPage));

        controller.execute(request, model);

        @SuppressWarnings("unchecked")
        Map<ComponentType, String> actions = (Map<ComponentType, String>) model.getAttribute("availableActions");
        assertEquals(java.util.Set.of(ComponentType.CUSTOM), actions.keySet(),
                "On a shared theme, once a Weblog-action page exists there is nothing left to offer");
    }

    @Test
    void executeReportsAnErrorInsteadOfPropagatingAManagerFailure() throws Exception {
        when(weblogger.getWeblogManager().getTemplates(weblog))
                .thenThrow(new WebloggerException("database down"));

        String view = controller.execute(request, model);

        assertEquals(".Templates", view);
        assertTrue(errors(model).contains("generic.error.check.logs"),
                "Expected a template-list error, got: " + errors(model));
    }

    // --- add ---

    @Test
    void addRejectsAnEmptyName() throws Exception {
        controller.add(request, model, redirectAttributes, "", ComponentType.CUSTOM);

        assertTrue(errors(model).contains("Template.error.nameNull"),
                "Expected a nameNull error, got: " + errors(model));
        verify(weblogger.getWeblogManager(), never()).saveTemplate(any());
    }

    @Test
    void addRejectsANameThatIsTooLong() throws Exception {
        String tooLong = "x".repeat(RollerConstants.TEXTWIDTH_255 + 1);

        controller.add(request, model, redirectAttributes, tooLong, ComponentType.CUSTOM);

        assertTrue(errors(model).contains("Template.error.nameSize"),
                "Expected a nameSize error, got: " + errors(model));
        verify(weblogger.getWeblogManager(), never()).saveTemplate(any());
    }

    @Test
    void addRejectsANullAction() throws Exception {
        controller.add(request, model, redirectAttributes, "MyTemplate", null);

        assertTrue(errors(model).contains("Template.error.actionNull"),
                "Expected an actionNull error, got: " + errors(model));
        verify(weblogger.getWeblogManager(), never()).saveTemplate(any());
    }

    @Test
    void addRejectsANameThatAlreadyExists() throws Exception {
        WeblogTemplate existing = templateNamed("MyTemplate", ComponentType.CUSTOM);
        when(weblogger.getWeblogManager().getTemplateByName(weblog, "MyTemplate")).thenReturn(existing);

        controller.add(request, model, redirectAttributes, "MyTemplate", ComponentType.CUSTOM);

        assertTrue(errors(model).contains("pagesForm.error.alreadyExists"),
                "Expected an alreadyExists error, got: " + errors(model));
        verify(weblogger.getWeblogManager(), never()).saveTemplate(any());
    }

    @Test
    void addReportsTheDuplicateNameInTheErrorMessage() throws Exception {
        registerMessage("pagesForm.error.alreadyExists", "duplicate:{0}");
        WeblogTemplate existing = templateNamed("MyTemplate", ComponentType.CUSTOM);
        when(weblogger.getWeblogManager().getTemplateByName(weblog, "MyTemplate")).thenReturn(existing);

        controller.add(request, model, redirectAttributes, "MyTemplate", ComponentType.CUSTOM);

        assertTrue(errors(model).contains("duplicate:MyTemplate"), "Got: " + errors(model));
    }

    @Test
    void addEscapesTheDuplicateNameInTheAlreadyExistsError() throws Exception {
        // The submitted name is user-typed and lands in the error tile's
        // deliberately-raw sink (same as the success-message case above) --
        // an unescaped name here is stored HTML in the error banner.
        registerMessage("pagesForm.error.alreadyExists", "duplicate:{0}");
        WeblogTemplate existing = templateNamed("<b>MyTemplate</b>", ComponentType.CUSTOM);
        when(weblogger.getWeblogManager().getTemplateByName(weblog, "<b>MyTemplate</b>")).thenReturn(existing);

        controller.add(request, model, redirectAttributes, "<b>MyTemplate</b>", ComponentType.CUSTOM);

        assertTrue(errors(model).contains("duplicate:&lt;b&gt;MyTemplate&lt;/b&gt;"),
                "Expected the template name HTML-escaped, got: " + errors(model));
    }

    @Test
    void addingACustomTemplateSetsItsLinkToItsName() throws Exception {
        when(weblogger.getWeblogManager().getTemplates(weblog)).thenReturn(List.of());

        controller.add(request, model, redirectAttributes, "MyTemplate", ComponentType.CUSTOM);

        ArgumentCaptor<WeblogTemplate> saved = ArgumentCaptor.forClass(WeblogTemplate.class);
        verify(weblogger.getWeblogManager()).saveTemplate(saved.capture());
        assertEquals("MyTemplate", saved.getValue().getName());
        assertEquals("MyTemplate", saved.getValue().getLink(),
                "A custom template's link must default to its name");
        verify(weblogger.getWeblogManager(), never()).saveWeblog(any());
    }

    @Test
    void addingAWeblogTemplateForcesTheDefaultPageNameAndSavesTheWeblog() throws Exception {
        when(weblogger.getWeblogManager().getTemplates(weblog)).thenReturn(List.of());

        controller.add(request, model, redirectAttributes, "AnythingAtAll", ComponentType.WEBLOG);

        ArgumentCaptor<WeblogTemplate> saved = ArgumentCaptor.forClass(WeblogTemplate.class);
        verify(weblogger.getWeblogManager()).saveTemplate(saved.capture());
        assertEquals(WeblogTemplate.DEFAULT_PAGE, saved.getValue().getName(),
                "A Weblog-action template must always be named after DEFAULT_PAGE");
        verify(weblogger.getWeblogManager()).saveWeblog(weblog);
    }

    @Test
    void everySuccessfulAddSavesAStandardRenditionAndFlushes() throws Exception {
        registerMessage("pageForm.newTemplateContent", "-- new template body --");
        when(weblogger.getWeblogManager().getTemplates(weblog)).thenReturn(List.of());

        controller.add(request, model, redirectAttributes, "MyTemplate", ComponentType.CUSTOM);

        ArgumentCaptor<org.apache.roller.weblogger.pojos.CustomTemplateRendition> rendition =
                ArgumentCaptor.forClass(org.apache.roller.weblogger.pojos.CustomTemplateRendition.class);
        verify(weblogger.getWeblogManager()).saveTemplateRendition(rendition.capture());
        assertEquals("-- new template body --", rendition.getValue().getTemplate());
        assertTrue(weblogger.flushCount() > 0, "A successful add must be committed");
    }

    @Test
    void addReportsAnErrorInsteadOfPropagatingASaveFailure() throws Exception {
        when(weblogger.getWeblogManager().getTemplates(weblog)).thenReturn(List.of());
        doThrow(new WebloggerException("boom")).when(weblogger.getWeblogManager()).saveTemplate(any());

        String view = controller.add(request, model, redirectAttributes, "MyTemplate", ComponentType.CUSTOM);

        assertEquals(".Templates", view);
        assertTrue(errors(model).contains("generic.error.check.logs"),
                "Expected a save error, got: " + errors(model));
    }

    @Test
    void addReportsSuccessNamingTheTemplateItCreated() throws Exception {
        // Before this, a successful add answered with the list page and no
        // banner at all -- indistinguishable from a POST that silently did
        // nothing, on a screen where the new row is easy to miss.
        registerMessage("pagesForm.added", "added:{0}");
        when(weblogger.getWeblogManager().getTemplates(weblog)).thenReturn(List.of());

        controller.add(request, model, redirectAttributes, "MyTemplate", ComponentType.CUSTOM);

        assertTrue(flashMessages(redirectAttributes).contains("added:MyTemplate"),
                "Expected a success notice naming the template, got: " + flashMessages(redirectAttributes));
    }

    @Test
    void addEscapesTheTemplateNameInTheSuccessMessage() throws Exception {
        // The template name is user-typed and lands in messages.jsp's
        // deliberately-raw sink -- an unescaped name is stored HTML in the
        // success banner.
        registerMessage("pagesForm.added", "added:{0}");
        when(weblogger.getWeblogManager().getTemplates(weblog)).thenReturn(List.of());

        controller.add(request, model, redirectAttributes, "<b>MyTemplate</b>", ComponentType.CUSTOM);

        assertTrue(flashMessages(redirectAttributes).contains("added:&lt;b&gt;MyTemplate&lt;/b&gt;"),
                "Expected the template name HTML-escaped, got: " + flashMessages(redirectAttributes));
    }

    @Test
    void addReportsTheForcedNameWhenAWeblogTemplateIsRenamedToTheDefaultPage() throws Exception {
        // A WEBLOG-action template is stored under DEFAULT_PAGE whatever the
        // user typed, so the notice has to name the row that actually appeared.
        registerMessage("pagesForm.added", "added:{0}");
        when(weblogger.getWeblogManager().getTemplates(weblog)).thenReturn(List.of());

        controller.add(request, model, redirectAttributes, "AnythingAtAll", ComponentType.WEBLOG);

        assertTrue(flashMessages(redirectAttributes).contains("added:" + WeblogTemplate.DEFAULT_PAGE),
                "Expected the stored name, not the submitted one, got: " + flashMessages(redirectAttributes));
    }

    @Test
    void addReportsNoSuccessWhenTheSaveFailed() throws Exception {
        registerMessage("pagesForm.added", "added:{0}");
        when(weblogger.getWeblogManager().getTemplates(weblog)).thenReturn(List.of());
        doThrow(new WebloggerException("boom")).when(weblogger.getWeblogManager()).saveTemplate(any());

        controller.add(request, model, redirectAttributes, "MyTemplate", ComponentType.CUSTOM);

        assertTrue(messages(model).isEmpty(),
                "A failed add must not also claim success: " + messages(model));
        assertTrue(flashMessages(redirectAttributes).isEmpty(),
                "A failed add must not queue a success notice either: " + flashMessages(redirectAttributes));
    }

    @Test
    void addAlwaysReloadsTheTemplateListRegardlessOfOutcome() throws Exception {
        // A validation failure must not skip the list refresh, or the page
        // would render with stale data alongside the new error.
        when(weblogger.getWeblogManager().getTemplates(weblog)).thenReturn(List.of());

        String view = controller.add(request, model, redirectAttributes, "", ComponentType.CUSTOM);

        assertEquals(".Templates", view);
        assertTrue(model.containsAttribute("templates"),
                "loadTemplatesList must always run, even after a validation failure");
    }

    @Test
    void addKeepsTheTypedNameAndActionAfterAValidationError() throws Exception {
        // The sidebar field reads ${newTmplName}; before this the handler
        // never put it back in the model, so "name too long" highlighted an
        // EMPTY field and the reader's text was gone.
        String tooLong = "x".repeat(RollerConstants.TEXTWIDTH_255 + 1);
        when(weblogger.getWeblogManager().getTemplates(weblog)).thenReturn(List.of());

        controller.add(request, model, redirectAttributes, tooLong, ComponentType.SEARCH);

        assertEquals(tooLong, model.getAttribute("newTmplName"));
        assertEquals(ComponentType.SEARCH, model.getAttribute("newTmplAction"));
    }

    @Test
    void addKeepsTheTypedNameWhenItAlreadyExists() throws Exception {
        WeblogTemplate existing = templateNamed("MyTemplate", ComponentType.CUSTOM);
        when(weblogger.getWeblogManager().getTemplateByName(weblog, "MyTemplate")).thenReturn(existing);
        when(weblogger.getWeblogManager().getTemplates(weblog)).thenReturn(List.of(existing));

        String view = controller.add(request, model, redirectAttributes, "MyTemplate", ComponentType.CUSTOM);

        assertEquals(".Templates", view, "a refused add renders in place, errors and all");
        assertEquals("MyTemplate", model.getAttribute("newTmplName"));
    }

    @Test
    void aSuccessfulAddRedirectsSoAReloadDoesNotResubmit() throws Exception {
        when(weblogger.getWeblogManager().getTemplates(weblog)).thenReturn(List.of());

        String view = controller.add(request, model, redirectAttributes, "MyTemplate", ComponentType.CUSTOM);

        assertEquals("redirect:/roller-ui/authoring/templates.rol?weblog=" + WEBLOG_HANDLE, view);
        assertFalse(model.containsAttribute("newTmplName"),
                "after a success the field starts empty for the next template");
    }

    // --- remove ---

    @Test
    void removeReportsAnErrorWhenTheTemplateIdDoesNotResolve() throws Exception {
        when(weblogger.getWeblogManager().getTemplate("gone")).thenReturn(null);
        when(weblogger.getWeblogManager().getTemplates(weblog)).thenReturn(List.of());

        controller.remove(request, model, "gone");

        assertTrue(errors(model).contains("editPages.remove.error"),
                "Expected a remove error, got: " + errors(model));
        verify(weblogger.getWeblogManager(), never()).removeTemplate(any());
    }

    @Test
    void removeReportsExactlyOneErrorWhenTheTemplateIdDoesNotResolve() throws Exception {
        // The not-found path used to fall through into the generic
        // "else" arm as well, stacking two banners for one cause.
        when(weblogger.getWeblogManager().getTemplate("gone")).thenReturn(null);
        when(weblogger.getWeblogManager().getTemplates(weblog)).thenReturn(List.of());

        controller.remove(request, model, "gone");

        assertEquals(List.of("editPages.remove.error"), errors(model),
                "One cause, one message: " + errors(model));
    }

    @Test
    void removeReportsSuccessNamingTheTemplateItDeleted() throws Exception {
        registerMessage("pagesForm.removed", "removed:{0}");
        WeblogTemplate custom = templateNamed("MyTemplate", ComponentType.CUSTOM);
        when(weblogger.getWeblogManager().getTemplate("t-1")).thenReturn(custom);
        when(weblogger.getWeblogManager().getTemplates(weblog)).thenReturn(List.of());

        controller.remove(request, model, "t-1");

        assertTrue(messages(model).contains("removed:MyTemplate"),
                "A deletion is the most consequential action on this screen and reported "
                        + "nothing at all before: " + messages(model));
    }

    @Test
    void removeEscapesTheTemplateNameInTheSuccessMessage() throws Exception {
        registerMessage("pagesForm.removed", "removed:{0}");
        WeblogTemplate custom = templateNamed("<b>MyTemplate</b>", ComponentType.CUSTOM);
        when(weblogger.getWeblogManager().getTemplate("t-1")).thenReturn(custom);
        when(weblogger.getWeblogManager().getTemplates(weblog)).thenReturn(List.of());

        controller.remove(request, model, "t-1");

        assertTrue(messages(model).contains("removed:&lt;b&gt;MyTemplate&lt;/b&gt;"),
                "Expected the template name HTML-escaped, got: " + messages(model));
    }

    @Test
    void aRefusedRemoveReportsNoSuccess() throws Exception {
        WeblogTemplate required = templateNamed("Weblog", ComponentType.WEBLOG);
        required.setLink("Weblog");
        when(weblogger.getWeblogManager().getTemplate("t-1")).thenReturn(required);
        when(weblogger.getWeblogManager().getTemplates(weblog)).thenReturn(List.of());

        controller.remove(request, model, "t-1");

        assertTrue(messages(model).isEmpty(),
                "A protected template that was not deleted must not report a deletion: "
                        + messages(model));
    }

    @Test
    void removeReportsAnErrorWhenLookingUpTheTemplateFails() throws Exception {
        when(weblogger.getWeblogManager().getTemplate("boom"))
                .thenThrow(new WebloggerException("database down"));
        when(weblogger.getWeblogManager().getTemplates(weblog)).thenReturn(List.of());

        controller.remove(request, model, "boom");

        assertTrue(errors(model).contains("editPages.remove.error"),
                "Expected a remove error, got: " + errors(model));
        verify(weblogger.getWeblogManager(), never()).removeTemplate(any());
    }

    @Test
    void removeRefusesARequiredTemplateOnACustomTheme() throws Exception {
        WeblogTemplate required = templateNamed("Weblog", ComponentType.WEBLOG);
        required.setLink("Weblog"); // isRequired() is true when link == "Weblog"
        when(weblogger.getWeblogManager().getTemplate("t-1")).thenReturn(required);
        when(weblogger.getWeblogManager().getTemplates(weblog)).thenReturn(List.of());

        controller.remove(request, model, "t-1");

        assertTrue(errors(model).contains("editPages.remove.requiredTemplate"),
                "Expected a requiredTemplate error, got: " + errors(model));
        verify(weblogger.getWeblogManager(), never()).removeTemplate(any());
    }

    @Test
    void removeDeletesANonRequiredTemplateAndCommits() throws Exception {
        WeblogTemplate custom = templateNamed("MyTemplate", ComponentType.CUSTOM);
        when(weblogger.getWeblogManager().getTemplate("t-1")).thenReturn(custom);
        when(weblogger.getWeblogManager().getTemplates(weblog)).thenReturn(List.of());

        String view = controller.remove(request, model, "t-1");

        assertEquals(".Templates", view);
        verify(weblogger.getWeblogManager()).removeTemplate(custom);
        assertTrue(weblogger.flushCount() > 0, "A successful remove must be committed");
        assertTrue(errors(model).isEmpty(), "A successful remove must not report an error: " + errors(model));
    }

    @Test
    void removingARequiredTemplateOnANonCustomThemeIsAllowed() throws Exception {
        // isRequired() alone does not block removal; it is only protected
        // when the weblog is on the CUSTOM theme.
        weblog.setEditorTheme("shared-theme-1");
        WeblogTemplate required = templateNamed("Weblog", ComponentType.WEBLOG);
        required.setLink("Weblog");
        when(weblogger.getWeblogManager().getTemplate("t-1")).thenReturn(required);
        when(weblogger.getWeblogManager().getTemplates(weblog)).thenReturn(List.of());

        controller.remove(request, model, "t-1");

        verify(weblogger.getWeblogManager()).removeTemplate(required);
        assertTrue(errors(model).isEmpty());
    }

    @Test
    void removingTheDefaultPageAlsoRemovesAMatchingStylesheetOverride() throws Exception {
        // A template named "Weblog" is always isRequired() (it's in the
        // hard-coded requiredTemplates set), so the required-template guard
        // would otherwise block this removal outright. That guard only
        // applies on a CUSTOM theme, so this scenario needs a shared theme to
        // reach the stylesheet-override cleanup at all.
        weblog.setEditorTheme("shared-theme-1");
        WeblogTemplate defaultPage = templateNamed(WeblogTemplate.DEFAULT_PAGE, ComponentType.WEBLOG);

        ThemeTemplate stylesheet = mock(ThemeTemplate.class);
        when(stylesheet.getLink()).thenReturn("style.css");
        org.apache.roller.weblogger.pojos.WeblogTheme weblogTheme =
                mock(org.apache.roller.weblogger.pojos.WeblogTheme.class);
        when(weblogTheme.getStylesheet()).thenReturn(stylesheet);
        when(weblogger.getThemeManager().getTheme(weblog)).thenReturn(weblogTheme);

        WeblogTemplate cssOverride = templateNamed("Override CSS", ComponentType.STYLESHEET);
        when(weblogger.getWeblogManager().getTemplateByLink(weblog, "style.css")).thenReturn(cssOverride);

        when(weblogger.getWeblogManager().getTemplate("t-1")).thenReturn(defaultPage);
        when(weblogger.getWeblogManager().getTemplates(weblog)).thenReturn(List.of());

        controller.remove(request, model, "t-1");

        verify(weblogger.getWeblogManager()).removeTemplate(defaultPage);
        verify(weblogger.getWeblogManager()).removeTemplate(cssOverride);
    }

    @Test
    void aFailedRemoveIsReportedRatherThanPropagated() throws Exception {
        WeblogTemplate custom = templateNamed("MyTemplate", ComponentType.CUSTOM);
        when(weblogger.getWeblogManager().getTemplate("t-1")).thenReturn(custom);
        when(weblogger.getWeblogManager().getTemplates(weblog)).thenReturn(List.of());
        doThrow(new RuntimeException("disk full")).when(weblogger.getWeblogManager()).removeTemplate(custom);

        controller.remove(request, model, "t-1");

        assertTrue(errors(model).contains("editPages.remove.error"),
                "Expected a remove error, got: " + errors(model));
    }

    // --- helpers ---

    private WeblogTemplate templateNamed(String name, ComponentType action) {
        WeblogTemplate template = new WeblogTemplate();
        template.setId(name + "-id");
        template.setName(name);
        template.setAction(action);
        template.setWeblog(weblog);
        return template;
    }

    /**
     * The delete path took a client-supplied id straight to a global by-id
     * lookup, so an editor on one weblog could delete another weblog's theme
     * templates. Worse, the isRequired() guard below the lookup consults the
     * CALLER's weblog theme, so it would happily have removed a template the
     * owning weblog does require.
     */
    @Test
    void aTemplateBelongingToAnotherWeblogIsNotDeleted() throws Exception {
        Weblog otherWeblog = new Weblog();
        otherWeblog.setId("weblog-2");
        otherWeblog.setHandle("someoneelse");

        WeblogTemplate foreign = new WeblogTemplate();
        foreign.setId("foreign-tmpl");
        foreign.setName("Their Sidebar");
        foreign.setLink("their-sidebar");
        foreign.setAction(ComponentType.CUSTOM);
        foreign.setWeblog(otherWeblog);
        when(weblogger.getWeblogManager().getTemplate("foreign-tmpl")).thenReturn(foreign);

        controller.remove(request, model, "foreign-tmpl");

        verify(weblogger.getWeblogManager(), never()).removeTemplate(any());
    }

    // --- errors point at the field they name (B8) ---

    @Test
    void aRefusedTemplateNameMarksTheNameField() throws Exception {
        controller.add(request, model, redirectAttributes, "", ComponentType.CUSTOM);

        assertEquals(List.of("newTmplName"), invalidFields(model));
    }

    @Test
    void aMissingTemplateActionMarksTheActionSelect() throws Exception {
        controller.add(request, model, redirectAttributes, "MyTemplate", null);

        assertEquals(List.of("newTmplAction"), invalidFields(model));
    }
}

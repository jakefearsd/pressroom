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
package com.wikantik.pressroom.ui.controllers.editor;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.pojos.CustomTemplateRendition;
import com.wikantik.pressroom.pojos.TemplateRendition.RenditionType;
import com.wikantik.pressroom.pojos.ThemeTemplate.ComponentType;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.ui.Model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link TemplateEditController} and {@link TemplateEditBean}.
 *
 * <p>Templates are executable Velocity, and their name and link are how the
 * renderer finds them — two templates sharing either one makes the blog render
 * unpredictably, which is what the uniqueness validation prevents. The other
 * behaviour worth pinning is that the edited contents are actually persisted:
 * see {@link #savingATemplateWithNoRenditionYetStoresTheEditedContents()},
 * which covers a fixed data-loss bug.
 */
class TemplateEditControllerTest extends EditorControllerTestSupport {

    private TemplateEditController controller;
    private TemplateEditBean bean;
    private Model model;
    private WeblogTemplate template;

    @BeforeEach
    void setUp() throws Exception {
        controller = prepare(new TemplateEditController());
        bean = new TemplateEditBean();
        model = newModel();

        template = new WeblogTemplate();
        template.setId("tmpl-1");
        template.setName("Sidebar");
        template.setLink("sidebar");
        template.setAction(ComponentType.CUSTOM);
        template.setWeblog(weblog);
        when(weblogger.getWeblogManager().getTemplate("tmpl-1")).thenReturn(template);
    }

    // --- opening the editor ---

    @Test
    void openingATemplateLoadsItIntoTheForm() throws Exception {
        givenRendition("## sidebar markup");
        bean.setId("tmpl-1");

        String view = controller.execute(request, model, bean);

        assertEquals(".TemplateEdit", view);
        assertEquals("Sidebar", bean.getName());
        assertEquals("## sidebar markup", bean.getContentsStandard());
        assertEquals(template, model.getAttribute("template"));
    }

    @Test
    void openingATemplateThatDoesNotExistFallsBackToTheTemplateList() throws Exception {
        bean.setId("gone");
        when(weblogger.getWeblogManager().getTemplate("gone")).thenReturn(null);

        assertEquals(".Templates", controller.execute(request, model, bean),
                "There is nothing to edit, so the editor must not be rendered");
        assertEquals(1, errors(model).size(),
                "The user must be told why they were bounced: " + errors(model));
    }

    @Test
    void openingATemplateWithNoIdFallsBackToTheTemplateList() {
        bean.setId(null);

        assertEquals(".Templates", controller.execute(request, model, bean));
    }

    @Test
    void aTemplateWithNoExplicitContentTypeOpensInAutomaticMode() throws Exception {
        // Auto mode lets the renderer pick the content type from the template's
        // action; a stale manual value would override it with something wrong.
        givenRendition("");
        template.setOutputContentType(null);
        bean.setId("tmpl-1");

        controller.execute(request, model, bean);

        assertEquals(Boolean.TRUE, bean.getAutoContentType());
    }

    @Test
    void aTemplateWithAnExplicitContentTypeOpensInManualMode() throws Exception {
        givenRendition("");
        template.setOutputContentType("application/rss+xml");
        bean.setId("tmpl-1");

        controller.execute(request, model, bean);

        assertEquals(Boolean.FALSE, bean.getAutoContentType());
        assertEquals("application/rss+xml", bean.getManualContentType());
    }

    // --- saving ---

    @Test
    void savingStoresTheEditedContentsOverTheExistingRendition() throws Exception {
        CustomTemplateRendition rendition = givenRendition("old markup");
        bean.setId("tmpl-1");
        bean.setName("Sidebar");
        bean.setContentsStandard("new markup");

        String view = controller.save(request, model, bean);

        assertEquals(".TemplateEdit", view);
        assertEquals("new markup", rendition.getTemplate());
        verify(weblogger.getWeblogManager()).saveTemplate(template);
        assertTrue(messages(model).contains("pageForm.save.success"),
                "Expected a save confirmation, got: " + messages(model));
    }

    @Test
    void savingATemplateWithNoRenditionYetStoresTheEditedContents() throws Exception {
        // Regression test for a data-loss bug: when a template had no STANDARD
        // rendition, copyTo created one with an empty template and discarded
        // everything the user had typed, while the controller still reported
        // "saved successfully".
        bean.setId("tmpl-1");
        bean.setName("Sidebar");
        bean.setContentsStandard("brand new markup");

        controller.save(request, model, bean);

        ArgumentCaptor<CustomTemplateRendition> captor =
                ArgumentCaptor.forClass(CustomTemplateRendition.class);
        verify(weblogger.getWeblogManager()).saveTemplateRendition(captor.capture());
        assertEquals("brand new markup", captor.getValue().getTemplate(),
                "The markup the user typed must be what gets stored");
    }

    @Test
    void savingATemplateWithNoRenditionAndNoContentStoresAnEmptyStringNotNull() throws Exception {
        // The rendition's template column is NOT NULL.
        bean.setId("tmpl-1");
        bean.setName("Sidebar");
        bean.setContentsStandard(null);

        controller.save(request, model, bean);

        ArgumentCaptor<CustomTemplateRendition> captor =
                ArgumentCaptor.forClass(CustomTemplateRendition.class);
        verify(weblogger.getWeblogManager()).saveTemplateRendition(captor.capture());
        assertEquals("", captor.getValue().getTemplate());
    }

    @Test
    void savingRefusesANameAlreadyUsedByAnotherTemplate() throws Exception {
        // Two templates with the same name makes lookup by name ambiguous.
        givenRendition("");
        WeblogTemplate other = new WeblogTemplate();
        other.setId("tmpl-2");
        other.setName("Footer");
        when(weblogger.getWeblogManager().getTemplateByName(weblog, "Footer")).thenReturn(other);

        bean.setId("tmpl-1");
        bean.setName("Footer");

        controller.save(request, model, bean);

        assertTrue(errors(model).contains("pagesForm.error.alreadyExists"),
                "Expected a duplicate-name error, got: " + errors(model));
        verify(weblogger.getWeblogManager(), never()).saveTemplate(any());
    }

    @Test
    void theDuplicateNameAndLinkErrorsEscapeTheValueTheyName() throws Exception {
        // Both are user-typed and land in messages.jsp's deliberately-raw
        // sink, so an unescaped name is stored HTML in the error banner.
        registerMessage("pagesForm.error.alreadyExists", "duplicate:{0}");
        givenRendition("");
        WeblogTemplate other = new WeblogTemplate();
        other.setId("tmpl-2");
        other.setName("<b>Footer</b>");
        when(weblogger.getWeblogManager().getTemplateByName(weblog, "<b>Footer</b>"))
                .thenReturn(other);

        bean.setId("tmpl-1");
        bean.setName("<b>Footer</b>");

        controller.save(request, model, bean);

        assertTrue(errors(model).contains("duplicate:&lt;b&gt;Footer&lt;/b&gt;"),
                "Expected the template name HTML-escaped, got: " + errors(model));
    }

    @Test
    void theDuplicateLinkErrorEscapesTheLinkItNames() throws Exception {
        registerMessage("pagesForm.error.alreadyExists", "duplicate:{0}");
        givenRendition("");
        WeblogTemplate other = new WeblogTemplate();
        other.setId("tmpl-2");
        other.setLink("<b>footer</b>");
        when(weblogger.getWeblogManager().getTemplateByLink(weblog, "<b>footer</b>"))
                .thenReturn(other);

        bean.setId("tmpl-1");
        bean.setName("Sidebar");
        bean.setLink("<b>footer</b>");

        controller.save(request, model, bean);

        assertTrue(errors(model).contains("duplicate:&lt;b&gt;footer&lt;/b&gt;"),
                "Expected the template link HTML-escaped, got: " + errors(model));
    }

    @Test
    void savingATemplateUnderItsOwnNameIsNotADuplicate() throws Exception {
        // The uniqueness check is skipped entirely when the name has not
        // changed; otherwise no template could ever be saved twice.
        givenRendition("");
        bean.setId("tmpl-1");
        bean.setName("Sidebar");

        controller.save(request, model, bean);

        assertTrue(errors(model).isEmpty(), "Unchanged name must not be a duplicate: " + errors(model));
        verify(weblogger.getWeblogManager(), never()).getTemplateByName(any(), any());
        verify(weblogger.getWeblogManager()).saveTemplate(template);
    }

    @Test
    void savingRefusesALinkAlreadyUsedByAnotherTemplate() throws Exception {
        // The link is the URL the template is served at.
        givenRendition("");
        WeblogTemplate other = new WeblogTemplate();
        other.setId("tmpl-2");
        other.setLink("footer");
        when(weblogger.getWeblogManager().getTemplateByLink(weblog, "footer")).thenReturn(other);

        bean.setId("tmpl-1");
        bean.setName("Sidebar");
        bean.setLink("footer");

        controller.save(request, model, bean);

        assertTrue(errors(model).contains("pagesForm.error.alreadyExists"),
                "Expected a duplicate-link error, got: " + errors(model));
        verify(weblogger.getWeblogManager(), never()).saveTemplate(any());
    }

    @Test
    void anEmptyLinkIsNotCheckedForUniqueness() throws Exception {
        // Several templates legitimately have no link at all, so "" must not
        // collide with itself.
        givenRendition("");
        bean.setId("tmpl-1");
        bean.setName("Sidebar");
        bean.setLink("");

        controller.save(request, model, bean);

        verify(weblogger.getWeblogManager(), never()).getTemplateByLink(any(), any());
        assertTrue(errors(model).isEmpty(), "Expected no errors, got: " + errors(model));
    }

    @Test
    void savingInAutomaticModeClearsAnyStoredContentType() throws Exception {
        givenRendition("");
        template.setOutputContentType("application/rss+xml");
        bean.setId("tmpl-1");
        bean.setName("Sidebar");
        bean.setAutoContentType(Boolean.TRUE);
        bean.setManualContentType("text/html");

        controller.save(request, model, bean);

        assertNull(template.getOutputContentType(),
                "Automatic mode must clear the override, not leave the old value behind");
    }

    @Test
    void savingInManualModeStoresTheChosenContentType() throws Exception {
        givenRendition("");
        bean.setId("tmpl-1");
        bean.setName("Sidebar");
        bean.setAutoContentType(Boolean.FALSE);
        bean.setManualContentType("application/rss+xml");

        controller.save(request, model, bean);

        assertEquals("application/rss+xml", template.getOutputContentType());
    }

    @Test
    void anUnsetAutoContentTypeFlagIsTreatedAsManual() throws Exception {
        // The checkbox is absent from the POST when unticked, so the bean can
        // arrive with a null; that must not NPE its way into a 500.
        givenRendition("");
        bean.setId("tmpl-1");
        bean.setName("Sidebar");
        bean.setAutoContentType(null);
        bean.setManualContentType("text/plain");

        controller.save(request, model, bean);

        assertEquals("text/plain", template.getOutputContentType());
    }

    @Test
    void savingStampsTheTemplateSoCachesInvalidate() throws Exception {
        givenRendition("");
        bean.setId("tmpl-1");
        bean.setName("Sidebar");

        controller.save(request, model, bean);

        assertNotNull(template.getLastModified(),
                "An unstamped template keeps being served from cache after an edit");
    }

    @Test
    void aFailedSaveIsReportedRatherThanConfirmed() throws Exception {
        givenRendition("");
        bean.setId("tmpl-1");
        bean.setName("Sidebar");
        org.mockito.Mockito.doThrow(new WebloggerException("database down"))
                .when(weblogger.getWeblogManager()).saveTemplate(any());

        controller.save(request, model, bean);

        assertTrue(messages(model).isEmpty(), "A failed save must not report success");
        assertEquals(1, errors(model).size(),
                "Expected the failure to be surfaced, got: " + errors(model));
    }

    @Test
    void savingATemplateThatDisappearedFallsBackToTheTemplateList() throws Exception {
        bean.setId("gone");
        when(weblogger.getWeblogManager().getTemplate("gone")).thenReturn(null);

        assertEquals(".Templates", controller.save(request, model, bean));
        verify(weblogger.getWeblogManager(), never()).saveTemplate(any());
    }

    // --- TemplateEditBean ---

    /**
     * The form bean is pure: it writes the edited markup onto the template's
     * rendition and persists nothing -- the controller's save path owns the
     * {@code saveTemplateRendition} call. Before the DI wave {@code copyTo}
     * reached the static locator for a manager and saved the
     * rendition itself.
     */
    @Test
    void beanCopyToDoesNotPersistTheRendition() throws Exception {
        CustomTemplateRendition rendition = givenRendition("old markup");
        bean.setContentsStandard("new markup");

        bean.copyTo(template);

        assertEquals("new markup", rendition.getTemplate(),
                "copyTo still writes the edited markup onto the rendition");
        verify(weblogger.getWeblogManager(), never()).saveTemplateRendition(any());
    }

    /**
     * Characterisation: with the persistence moved out of the bean, the
     * controller's save must still store an existing rendition's edited
     * contents, and before {@code saveTemplate} as it always did. Expected to
     * pass on arrival.
     */
    @Test
    void savingAnExistingRenditionPersistsItsEditedContents() throws Exception {
        CustomTemplateRendition rendition = givenRendition("old markup");
        bean.setId("tmpl-1");
        bean.setName("Sidebar");
        bean.setContentsStandard("new markup");

        controller.save(request, model, bean);

        InOrder inOrder = inOrder(weblogger.getWeblogManager());
        inOrder.verify(weblogger.getWeblogManager()).saveTemplateRendition(rendition);
        inOrder.verify(weblogger.getWeblogManager()).saveTemplate(template);
        assertEquals("new markup", rendition.getTemplate());
    }

    @Test
    void beanCopyToOnlyRenamesCustomTemplates() throws Exception {
        // Built-in templates (weblog, permalink, search...) are addressed by
        // their action; letting a user rename or relink one would make the
        // renderer unable to find it.
        template.setAction(ComponentType.WEBLOG);
        givenRendition("markup");
        bean.setName("Renamed");
        bean.setLink("renamed");
        bean.setDescription("changed");
        bean.setContentsStandard("markup");

        bean.copyTo(template);

        assertEquals("Sidebar", template.getName(),
                "A non-custom template's name must be immutable");
        assertEquals("sidebar", template.getLink(),
                "A non-custom template's link must be immutable");
    }

    @Test
    void beanCopyToRenamesCustomTemplates() throws Exception {
        CustomTemplateRendition rendition = givenRendition("markup");
        bean.setName("Renamed");
        bean.setLink("renamed");
        bean.setDescription("changed");
        bean.setNavbar(true);
        bean.setHidden(true);
        bean.setAction(ComponentType.CUSTOM);
        bean.setContentsStandard("markup");

        bean.copyTo(template);

        assertEquals("Renamed", template.getName());
        assertEquals("renamed", template.getLink());
        assertEquals("changed", template.getDescription());
        assertTrue(template.isNavbar());
        assertTrue(template.isHidden());
        assertEquals("markup", rendition.getTemplate());
    }

    @Test
    void beanCopyFromShowsAnEmptyEditorForATemplateWithNoRendition() throws Exception {
        bean.copyFrom(template);

        assertEquals("", bean.getContentsStandard(),
                "A missing rendition must render as an empty editor, not a null");
    }

    private CustomTemplateRendition givenRendition(String contents) {
        // The constructor registers the rendition with the template.
        CustomTemplateRendition rendition =
                new CustomTemplateRendition(template, RenditionType.STANDARD);
        rendition.setTemplate(contents);
        return rendition;
    }

    // --- cross-weblog isolation ---

    /**
     * A template id is client input and {@code getTemplate} is a global by-id
     * lookup, so without an ownership check an editor on one weblog can pull
     * another weblog's theme template into their own editor.
     */
    @Test
    void aTemplateBelongingToAnotherWeblogIsNotOpened() throws Exception {
        givenForeignTemplate();
        bean.setId("foreign-tmpl");

        String view = controller.execute(request, model, bean);

        assertEquals(".Templates", view);
        assertNull(bean.getName(),
                "another weblog's template was copied into this weblog's form");
        assertNull(model.getAttribute("template"));
    }

    /**
     * The worse half: saving would write this weblog's posted content into the
     * other weblog's template, which is arbitrary markup on somebody else's
     * public pages.
     */
    @Test
    void aTemplateBelongingToAnotherWeblogIsNotSaved() throws Exception {
        givenForeignTemplate();
        bean.setId("foreign-tmpl");
        bean.setName("Hijacked");
        bean.setContentsStandard("## replaced by another weblog");

        String view = controller.execute(request, model, bean);

        assertEquals(".Templates", view);
        verify(weblogger.getWeblogManager(), never()).saveTemplate(any());
        verify(weblogger.getWeblogManager(), never()).saveTemplateRendition(any());
    }

    /** A template owned by a different weblog, resolvable by its global id. */
    private void givenForeignTemplate() throws Exception {
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
    }

    /**
     * A uniqueness check that cannot be performed must not be read as "unique".
     *
     * <p>myValidate logged the failure and added no error, so the caller's
     * {@code if (!hasErrors(model))} saw a clean form and saved -- the template
     * store being unreachable was the one condition under which the duplicate
     * check was skipped entirely. Two templates sharing a name make lookup by
     * name ambiguous, which is the thing the check exists to prevent.
     */
    @Test
    void aNameUniquenessCheckThatFailsRefusesTheSave() throws Exception {
        givenRendition("");
        when(weblogger.getWeblogManager().getTemplateByName(eq(weblog), any()))
                .thenThrow(new WebloggerException("template store unreachable"));

        bean.setId("tmpl-1");
        bean.setName("Footer");

        controller.save(request, model, bean);

        assertFalse(errors(model).isEmpty(),
                "the author must be told the save did not happen, got: " + errors(model));
        verify(weblogger.getWeblogManager(), never()).saveTemplate(any());
    }

    @Test
    void aLinkUniquenessCheckThatFailsRefusesTheSave() throws Exception {
        givenRendition("");
        when(weblogger.getWeblogManager().getTemplateByLink(eq(weblog), any()))
                .thenThrow(new WebloggerException("template store unreachable"));

        bean.setId("tmpl-1");
        bean.setName("Sidebar");
        // a DIFFERENT link from the template's own, or the check is skipped
        bean.setLink("footer");

        controller.save(request, model, bean);

        assertFalse(errors(model).isEmpty(),
                "same for the link, which is what a page is served under: " + errors(model));
        verify(weblogger.getWeblogManager(), never()).saveTemplate(any());
    }
}

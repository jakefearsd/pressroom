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

import java.util.List;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogCategory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ui.Model;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link CategoryEditController}.
 *
 * <p>The interesting behaviour here is validation. Category names are rendered
 * into the blog's HTML, so the "name must survive HTML escaping unchanged"
 * check is the only thing standing between a blog admin and stored markup in
 * the category list. Duplicate detection differs between add and edit — on
 * edit a category is allowed to keep its own name — and getting that backwards
 * would either block every rename or allow two categories to collide.
 */
class CategoryEditControllerTest extends EditorControllerTestSupport {

    private static final String LIST_REDIRECT =
            "redirect:/pressroom-ui/authoring/categories.rol?weblog=" + WEBLOG_HANDLE;

    private CategoryEditController controller;
    private CategoryBean bean;
    private Model model;
    private RedirectAttributes redirectAttributes;

    @BeforeEach
    void setUp() {
        controller = prepare(new CategoryEditController());
        bean = new CategoryBean();
        model = newModel();
        redirectAttributes = newRedirectAttributes();
    }

    @Test
    void addSavesCategoryAndRedirectsToTheList() throws Exception {
        bean.setName("Travel");
        bean.setDescription("Trip reports");
        bean.setImage("travel.png");

        String view = controller.categoryAddSave(request, model, bean, redirectAttributes);

        assertEquals(LIST_REDIRECT, view, "A successful add must redirect to the category list");

        ArgumentCaptor<WeblogCategory> saved = ArgumentCaptor.forClass(WeblogCategory.class);
        verify(weblogger.getWeblogEntryManager()).saveWeblogCategory(saved.capture());
        assertEquals("Travel", saved.getValue().getName());
        assertEquals("Trip reports", saved.getValue().getDescription());
        assertEquals("travel.png", saved.getValue().getImage());
        assertEquals(weblog, saved.getValue().getWeblog(),
                "A new category must be attached to the action weblog, not left dangling");

        assertTrue(weblog.hasCategory("Travel"),
                "The category must be added to the weblog so calculatePosition and the "
                        + "in-memory list stay consistent with what was persisted");
    }

    @Test
    void addReportsTheCreatedCategoryNameInTheFlashMessage() {
        registerMessage("categoryForm.created", "created:{0}");
        bean.setName("Travel");

        controller.categoryAddSave(request, model, bean, redirectAttributes);

        assertEquals(java.util.List.of("created:Travel"), flashMessages(redirectAttributes),
                "The flash confirmation must name the category that was created");
    }

    @Test
    void addRejectsANameThatChangesUnderHtmlEscaping() throws Exception {
        // "<b>x</b>" escapes to "&lt;b&gt;x&lt;/b&gt;", so it is rejected. This is
        // the stored-XSS guard for the category list page.
        bean.setName("<b>Travel</b>");

        String view = controller.categoryAddSave(request, model, bean, redirectAttributes);

        assertEquals(LIST_REDIRECT, view);
        assertTrue(errors(model).contains("categoryForm.error.invalidName"),
                "Expected an invalidName error, got: " + errors(model));
        verify(weblogger.getWeblogEntryManager(), never()).saveWeblogCategory(any());
    }

    @Test
    void addRejectsANullName() throws Exception {
        bean.setName(null);

        controller.categoryAddSave(request, model, bean, redirectAttributes);

        assertTrue(errors(model).contains("categoryForm.error.invalidName"),
                "A missing name must be rejected before it reaches the manager");
        verify(weblogger.getWeblogEntryManager(), never()).saveWeblogCategory(any());
    }

    @Test
    void addRejectsANameAlreadyUsedOnThisWeblog() throws Exception {
        weblog.getWeblogCategories().add(categoryNamed("cat-1", "Travel"));
        bean.setName("Travel");

        controller.categoryAddSave(request, model, bean, redirectAttributes);

        assertTrue(errors(model).contains("categoryForm.error.duplicateName"),
                "Expected a duplicateName error, got: " + errors(model));
        verify(weblogger.getWeblogEntryManager(), never()).saveWeblogCategory(any());
    }

    @Test
    void addFallsBackToTheListWithAFlashErrorWhenTheSaveBlowsUp() throws Exception {
        bean.setName("Travel");
        doThrow(new WebloggerException("boom"))
                .when(weblogger.getWeblogEntryManager()).saveWeblogCategory(any());

        String view = controller.categoryAddSave(request, model, bean, redirectAttributes);

        assertEquals(LIST_REDIRECT, view);
        assertTrue(flashErrors(redirectAttributes).contains("generic.error.check.logs"),
                "A failed save must survive the redirect as a flash error, otherwise the "
                        + "user is bounced back to the list with no indication anything went wrong");
        assertTrue(flashMessages(redirectAttributes).isEmpty(),
                "A failed save must not also report success");
    }

    @Test
    void editSavesChangesToTheLookedUpCategory() throws Exception {
        WeblogCategory existing = categoryNamed("cat-1", "Travel");
        when(weblogger.getWeblogEntryManager().getWeblogCategory("cat-1")).thenReturn(existing);

        bean.setId("cat-1");
        bean.setName("Travel");
        bean.setDescription("Updated description");

        String view = controller.categoryEditSave(request, model, bean, redirectAttributes);

        assertEquals(LIST_REDIRECT, view);
        assertEquals("Updated description", existing.getDescription(),
                "The edit must be applied to the category that was looked up");
        verify(weblogger.getWeblogEntryManager()).saveWeblogCategory(existing);
    }

    @Test
    void editAllowsACategoryToKeepItsOwnName() throws Exception {
        // The duplicate check must exempt the category being edited, or no
        // category could ever be saved without also being renamed.
        WeblogCategory existing = categoryNamed("cat-1", "Travel");
        weblog.getWeblogCategories().add(existing);
        when(weblogger.getWeblogEntryManager().getWeblogCategory("cat-1")).thenReturn(existing);
        when(weblogger.getWeblogEntryManager().getWeblogCategoryByName(weblog, "Travel"))
                .thenReturn(existing);

        bean.setId("cat-1");
        bean.setName("Travel");

        controller.categoryEditSave(request, model, bean, redirectAttributes);

        assertTrue(errors(model).isEmpty(),
                "Re-saving a category under its existing name must not be a duplicate: "
                        + errors(model));
        verify(weblogger.getWeblogEntryManager()).saveWeblogCategory(existing);
    }

    @Test
    void editRejectsRenamingOntoAnotherCategorysName() throws Exception {
        WeblogCategory other = categoryNamed("cat-2", "Food");
        weblog.getWeblogCategories().add(other);
        when(weblogger.getWeblogEntryManager().getWeblogCategoryByName(weblog, "Food"))
                .thenReturn(other);

        bean.setId("cat-1");
        bean.setName("Food");

        controller.categoryEditSave(request, model, bean, redirectAttributes);

        assertTrue(errors(model).contains("categoryForm.error.duplicateName"),
                "Renaming onto a different category's name must be rejected: " + errors(model));
        verify(weblogger.getWeblogEntryManager(), never()).saveWeblogCategory(any());
    }

    @Test
    void editReportsTheSavedCategoryNameInTheFlashMessage() throws Exception {
        registerMessage("categoryForm.changesSaved", "saved:{0}");
        WeblogCategory existing = categoryNamed("cat-1", "Travel");
        when(weblogger.getWeblogEntryManager().getWeblogCategory("cat-1")).thenReturn(existing);
        bean.setId("cat-1");
        bean.setName("Travel");

        controller.categoryEditSave(request, model, bean, redirectAttributes);

        assertEquals(java.util.List.of("saved:Travel"), flashMessages(redirectAttributes));
    }

    @Test
    void editFallsBackToTheListWithAFlashErrorWhenTheCategoryIsMissing() throws Exception {
        // An unknown id is a not-found, reported as such. It used to reach
        // copyTo(null) and raise an NPE that the catch-all laundered into
        // "check the logs" -- a real error message produced by accident, which
        // told the user nothing and told the operator to go reading logs over
        // a mistyped id.
        when(weblogger.getWeblogEntryManager().getWeblogCategory("gone")).thenReturn(null);
        bean.setId("gone");
        bean.setName("Travel");

        String view = controller.categoryEditSave(request, model, bean, redirectAttributes);

        assertEquals(LIST_REDIRECT, view);
        assertTrue(flashErrors(redirectAttributes).contains("categoryForm.error.notFound"),
                "Editing a category that no longer exists must report not-found, got flash errors: "
                        + flashErrors(redirectAttributes));
        assertTrue(flashMessages(redirectAttributes).isEmpty(),
                "A failed edit must not report success");
    }

    /**
     * A category id belonging to another weblog must be refused.
     *
     * <p>bean.id is client input and the permission interceptor only vouches
     * for the ACTION weblog, so the global by-id lookup that used to be here
     * let any editor rename any category on the install -- including renaming
     * one out of recognition on somebody else's public blog.
     */
    @Test
    void editingACategoryOfAnotherWeblogIsRefused() throws Exception {
        Weblog otherWeblog = new Weblog();
        otherWeblog.setId("weblog-2");
        otherWeblog.setHandle("someoneelse");
        WeblogCategory foreign = new WeblogCategory();
        foreign.setId("cat-9");
        foreign.setName("Their Category");
        foreign.setWeblog(otherWeblog);
        when(weblogger.getWeblogEntryManager().getWeblogCategory("cat-9")).thenReturn(foreign);

        bean.setId("cat-9");
        bean.setName("Renamed By An Outsider");

        String view = controller.categoryEditSave(request, model, bean, redirectAttributes);

        assertEquals(LIST_REDIRECT, view);
        assertEquals("Their Category", foreign.getName(),
                "a foreign category must come through the request unchanged");
        verify(weblogger.getWeblogEntryManager(), never()).saveWeblogCategory(any());
        assertTrue(flashMessages(redirectAttributes).isEmpty(),
                "and the edit must not report success");
    }

    /**
     * A uniqueness check that cannot be performed must not be read as "unique".
     *
     * <p>{@code categoryNamed} logged the lookup failure and returned null, and
     * the duplicate-name check reads null as "no such category" -- so the
     * category store being unreachable was the one condition under which a
     * rename onto another category's name went through unchecked. Same shape,
     * same fix as TemplateEditController's name and link checks.
     */
    @Test
    void aNameUniquenessCheckThatFailsRefusesTheSave() throws Exception {
        WeblogCategory existing = categoryNamed("cat-1", "Travel");
        when(weblogger.getWeblogEntryManager().getWeblogCategory("cat-1")).thenReturn(existing);
        when(weblogger.getWeblogEntryManager().getWeblogCategoryByName(eq(weblog), any()))
                .thenThrow(new WebloggerException("category store unreachable"));

        bean.setId("cat-1");
        bean.setName("Food");

        controller.categoryEditSave(request, model, bean, redirectAttributes);

        assertFalse(errors(model).isEmpty(),
                "the author must be told the save did not happen, got: " + errors(model));
        verify(weblogger.getWeblogEntryManager(), never()).saveWeblogCategory(any());
        assertTrue(flashMessages(redirectAttributes).isEmpty(),
                "and the edit must not report success");
    }

    @Test
    void beanCopiesRoundTrip() throws Exception {
        WeblogCategory source = categoryNamed("cat-1", "Travel");
        source.setDescription("Trip reports");
        source.setImage("travel.png");

        CategoryBean copy = new CategoryBean();
        copy.copyFrom(source);

        assertEquals("cat-1", copy.getId());
        assertEquals("Travel", copy.getName());
        assertEquals("Trip reports", copy.getDescription());
        assertEquals("travel.png", copy.getImage());

        WeblogCategory target = new WeblogCategory();
        copy.copyTo(target);

        assertEquals("Travel", target.getName());
        assertEquals("Trip reports", target.getDescription());
        assertEquals("travel.png", target.getImage());
        assertNotNull(copy.getId());
        assertFalse("cat-1".equals(target.getId()),
                "copyTo must not carry the id across; the target's identity is the caller's business");
    }

    private WeblogCategory categoryNamed(String id, String name) {
        WeblogCategory category = new WeblogCategory();
        category.setId(id);
        category.setName(name);
        category.setWeblog(weblog);
        return category;
    }

    // --- errors point at the field they name (B8) ---

    @Test
    void aRefusedCategoryNameMarksTheNameField() throws Exception {
        weblog.getWeblogCategories().add(categoryNamed("cat-1", "Travel"));
        bean.setName("Travel");

        controller.categoryAddSave(request, model, bean, redirectAttributes);

        assertEquals(List.of("category_bean_name"), invalidFields(model));
    }

    @Test
    void anUnescapedCategoryNameMarksTheSameField() throws Exception {
        bean.setName("<b>Travel</b>");

        controller.categoryAddSave(request, model, bean, redirectAttributes);

        assertEquals(List.of("category_bean_name"), invalidFields(model));
    }

    /**
     * The error path redirects (Add/edit is driven by #category-edit-modal on
     * the category list; there is no standalone form page to redisplay), and
     * a redirect starts a brand new request -- the {@code model} this test's
     * siblings above assert on is thrown away with it. Before this fix the
     * only thing that survived the redirect was a generic "check the logs"
     * flash error, which told the author nothing about what was actually
     * wrong and did not mark the name field either. The specific duplicate-
     * name message and its field marker must survive the same way
     * {@code errors}/{@code messages} already do (see {@code addFlashError}).
     */
    @Test
    void addRejectsADuplicateNameAndTheDetailSurvivesTheRedirect() throws Exception {
        registerMessage("categoryForm.error.duplicateName", "duplicate:{0}");
        weblog.getWeblogCategories().add(categoryNamed("cat-1", "Travel"));
        bean.setName("Travel");

        controller.categoryAddSave(request, model, bean, redirectAttributes);

        assertEquals(List.of("duplicate:Travel"), flashErrors(redirectAttributes),
                "the specific duplicate-name complaint must survive the redirect, not a "
                        + "generic check-the-logs message: " + flashErrors(redirectAttributes));
        assertEquals(List.of("category_bean_name"), flashInvalidFields(redirectAttributes),
                "the field marker must survive the redirect too, so the reopened modal can "
                        + "highlight the name field: " + flashInvalidFields(redirectAttributes));
    }

    /**
     * Same defect, the edit endpoint's own validation path.
     */
    @Test
    void editRejectsRenamingOntoAnotherCategorysNameAndTheDetailSurvivesTheRedirect() throws Exception {
        registerMessage("categoryForm.error.duplicateName", "duplicate:{0}");
        WeblogCategory other = categoryNamed("cat-2", "Food");
        weblog.getWeblogCategories().add(other);
        when(weblogger.getWeblogEntryManager().getWeblogCategoryByName(weblog, "Food"))
                .thenReturn(other);

        bean.setId("cat-1");
        bean.setName("Food");

        controller.categoryEditSave(request, model, bean, redirectAttributes);

        assertEquals(List.of("duplicate:Food"), flashErrors(redirectAttributes),
                "the specific duplicate-name complaint must survive the redirect: "
                        + flashErrors(redirectAttributes));
        assertEquals(List.of("category_bean_name"), flashInvalidFields(redirectAttributes),
                "the field marker must survive the redirect too: " + flashInvalidFields(redirectAttributes));
    }
}

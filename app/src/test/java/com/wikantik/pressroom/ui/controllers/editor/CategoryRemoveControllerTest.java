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
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogCategory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.ui.Model;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link CategoryRemoveController}.
 *
 * <p>Deleting a category can take entries with it. The controller offers to
 * move the contents to another category first, and the order matters
 * absolutely: move-then-delete preserves the entries, delete-then-move loses
 * them. Everything here is asserted against the manager rather than the view,
 * because the view is the same redirect in every case.
 */
class CategoryRemoveControllerTest extends EditorControllerTestSupport {

    private static final String LIST_REDIRECT =
            "redirect:/roller-ui/authoring/categories.rol?weblog=" + WEBLOG_HANDLE;

    private CategoryRemoveController controller;
    private Model model;
    private RedirectAttributes redirectAttributes;
    private WeblogCategory doomed;
    private WeblogCategory target;

    @BeforeEach
    void setUp() throws Exception {
        controller = prepare(new CategoryRemoveController());
        model = newModel();
        redirectAttributes = newRedirectAttributes();

        doomed = category("cat-1", "Travel");
        target = category("cat-2", "General");
        when(weblogger.getWeblogEntryManager().getWeblogCategory("cat-1")).thenReturn(doomed);
        when(weblogger.getWeblogEntryManager().getWeblogCategory("cat-2")).thenReturn(target);

        // The controller refuses to delete a weblog's last category, so every
        // test that expects a deletion to go through needs the weblog to hold
        // more than one. A fresh Weblog starts with an empty list.
        weblog.getWeblogCategories().add(doomed);
        weblog.getWeblogCategories().add(target);
    }

    @Test
    void removingACategoryWithNoTargetJustDeletesIt() throws Exception {
        String view = controller.remove(request, model, "cat-1", null, redirectAttributes);

        assertEquals(LIST_REDIRECT, view);
        verify(weblogger.getWeblogEntryManager()).removeWeblogCategory(doomed);
        verify(weblogger.getWeblogEntryManager(), never())
                .moveWeblogCategoryContents(any(), any());
    }

    @Test
    void contentsAreMovedBeforeTheCategoryIsDeleted() throws Exception {
        // Deleting first would cascade the entries away; there is nothing left
        // to move afterwards.
        controller.remove(request, model, "cat-1", "cat-2", redirectAttributes);

        InOrder order = inOrder(weblogger.getWeblogEntryManager());
        order.verify(weblogger.getWeblogEntryManager()).moveWeblogCategoryContents(doomed, target);
        order.verify(weblogger.getWeblogEntryManager()).removeWeblogCategory(doomed);
    }

    @Test
    void aSuccessfulRemovalNamesTheCategoryInTheFlashMessage() throws Exception {
        registerMessage("categoryForm.removed", "removed:{0}");

        controller.remove(request, model, "cat-1", null, redirectAttributes);

        assertEquals(java.util.List.of("removed:Travel"), flashMessages(redirectAttributes));
    }

    @Test
    void removingAnUnknownCategoryIsReportedAndDeletesNothing() throws Exception {
        when(weblogger.getWeblogEntryManager().getWeblogCategory("gone")).thenReturn(null);

        String view = controller.remove(request, model, "gone", null, redirectAttributes);

        assertEquals(LIST_REDIRECT, view);
        assertTrue(flashErrors(redirectAttributes).contains("categoryForm.error.notFound"),
                "Expected a not-found error, got: " + flashErrors(redirectAttributes));
        verify(weblogger.getWeblogEntryManager(), never()).removeWeblogCategory(any());
    }

    @Test
    void removingWithNoIdAtAllIsReportedAndDeletesNothing() throws Exception {
        // A blank id must not be looked up -- with some managers an empty
        // string is a wildcard.
        controller.remove(request, model, "", null, redirectAttributes);

        assertTrue(flashErrors(redirectAttributes).contains("categoryForm.error.notFound"),
                "Expected a not-found error, got: " + flashErrors(redirectAttributes));
        verify(weblogger.getWeblogEntryManager(), never()).getWeblogCategory("");
        verify(weblogger.getWeblogEntryManager(), never()).removeWeblogCategory(any());
    }

    @Test
    void aFailedLookupIsTreatedAsNotFoundRatherThanDeletingBlindly() throws Exception {
        when(weblogger.getWeblogEntryManager().getWeblogCategory("cat-1"))
                .thenThrow(new WebloggerException("database down"));

        controller.remove(request, model, "cat-1", null, redirectAttributes);

        assertTrue(flashErrors(redirectAttributes).contains("categoryForm.error.notFound"),
                "Expected a not-found error, got: " + flashErrors(redirectAttributes));
        verify(weblogger.getWeblogEntryManager(), never()).removeWeblogCategory(any());
    }

    @Test
    void aFailedMoveAbandonsTheDeleteSoNoEntriesAreLost() throws Exception {
        // This is the important one: if the contents could not be moved, the
        // category must survive, because deleting it now would take the entries
        // that failed to move with it.
        org.mockito.Mockito.doThrow(new WebloggerException("constraint violation"))
                .when(weblogger.getWeblogEntryManager()).moveWeblogCategoryContents(any(), any());

        String view = controller.remove(request, model, "cat-1", "cat-2", redirectAttributes);

        assertEquals(LIST_REDIRECT, view);
        verify(weblogger.getWeblogEntryManager(), never()).removeWeblogCategory(any());
        assertTrue(flashErrors(redirectAttributes).contains("generic.error.check.logs"),
                "Expected the failure to be surfaced, got: " + flashErrors(redirectAttributes));
    }

    @Test
    void aFailedDeleteIsReportedRatherThanConfirmed() throws Exception {
        org.mockito.Mockito.doThrow(new WebloggerException("in use"))
                .when(weblogger.getWeblogEntryManager()).removeWeblogCategory(any());

        controller.remove(request, model, "cat-1", null, redirectAttributes);

        assertTrue(flashErrors(redirectAttributes).contains("generic.error.check.logs"),
                "Expected the failure to be surfaced, got: " + flashErrors(redirectAttributes));
        assertTrue(flashMessages(redirectAttributes).isEmpty(),
                "A failed delete must not also report success");
    }

    // --- multi-tenant isolation ---

    /**
     * A category id belonging to another weblog must be refused.
     *
     * <p>removeId is client input and the permission interceptor only
     * establishes that the caller may edit the ACTION weblog, so a global by-id
     * lookup here let any editor delete any category on the whole install --
     * taking that weblog's entries with it, since a delete cascades whatever
     * the move did not rescue.
     */
    @Test
    void aCategoryBelongingToAnotherWeblogIsNotDeleted() throws Exception {
        WeblogCategory foreign = foreignCategory("cat-9", "Someone Else's");
        when(weblogger.getWeblogEntryManager().getWeblogCategory("cat-9")).thenReturn(foreign);

        String view = controller.remove(request, model, "cat-9", null, redirectAttributes);

        assertEquals(LIST_REDIRECT, view);
        verify(weblogger.getWeblogEntryManager(), never()).removeWeblogCategory(any());
    }

    /**
     * The move destination is a separate id and needs the same check.
     *
     * <p>This one is worse than the delete, because it is silent: naming a
     * foreign category as the target re-files this weblog's entries into
     * somebody else's blog, and the only visible result is a successful delete.
     */
    @Test
    void aForeignMoveTargetIsRefusedAndNothingIsDeleted() throws Exception {
        WeblogCategory foreign = foreignCategory("cat-9", "Someone Else's");
        when(weblogger.getWeblogEntryManager().getWeblogCategory("cat-9")).thenReturn(foreign);

        String view = controller.remove(request, model, "cat-1", "cat-9", redirectAttributes);

        assertEquals(LIST_REDIRECT, view);
        verify(weblogger.getWeblogEntryManager(), never())
                .moveWeblogCategoryContents(any(), any());
        verify(weblogger.getWeblogEntryManager(), never()).removeWeblogCategory(any());
    }

    private WeblogCategory foreignCategory(String id, String name) {
        Weblog otherWeblog = new Weblog();
        otherWeblog.setId("weblog-2");
        otherWeblog.setHandle("someoneelse");
        WeblogCategory category = new WeblogCategory();
        category.setId(id);
        category.setName(name);
        category.setWeblog(otherWeblog);
        return category;
    }

    @Test
    void aWeblogsLastCategoryIsRefusedRatherThanDeleted() throws Exception {
        // Categories.jsp hides Delete on the last row, but that only stops the
        // UI. A direct POST used to take the last category out from under
        // saveWeblogEntry's "first category found" fallback, after which every
        // entry save died on NoSuchElementException with no way back through
        // the UI.
        weblog.getWeblogCategories().remove(target);

        String view = controller.remove(request, model, "cat-1", null, redirectAttributes);

        assertEquals(LIST_REDIRECT, view);
        assertTrue(flashErrors(redirectAttributes).contains("categoryForm.error.lastCategory"),
                "Expected a last-category error, got: " + flashErrors(redirectAttributes));
        verify(weblogger.getWeblogEntryManager(), never()).removeWeblogCategory(any());
    }

    @Test
    void refusingTheLastCategoryAlsoLeavesItsContentsWhereTheyAre() throws Exception {
        // The refusal comes before the move, so naming a move target cannot be
        // used to empty the last category on the way to deleting it.
        weblog.getWeblogCategories().remove(target);

        controller.remove(request, model, "cat-1", "cat-2", redirectAttributes);

        verify(weblogger.getWeblogEntryManager(), never())
                .moveWeblogCategoryContents(any(), any());
    }

    private WeblogCategory category(String id, String name) {
        WeblogCategory category = new WeblogCategory();
        category.setId(id);
        category.setName(name);
        category.setWeblog(weblog);
        return category;
    }
}

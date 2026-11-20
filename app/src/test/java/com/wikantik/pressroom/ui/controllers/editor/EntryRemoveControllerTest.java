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
import com.wikantik.pressroom.pojos.WeblogCategory;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogEntry.PubStatus;
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
 * Tests for {@link EntryRemoveController}.
 *
 * <p>"Deleting" an entry through this controller trashes it (see
 * {@code BaseController#trashEntry}) rather than removing it from the
 * database, but it still has to come out of the search index -- an entry that
 * survives only in the Lucene index keeps appearing in site search results and
 * links to a page that now 404s, exactly the failure a genuine delete would
 * produce. The two endpoints differ only in where they send the user
 * afterwards, and both are covered because they are separate code paths that
 * could drift.
 */
class EntryRemoveControllerTest extends EditorControllerTestSupport {

    private static final String ENTRIES_REDIRECT =
            "redirect:/pressroom-ui/authoring/entries.rol?weblog=" + WEBLOG_HANDLE;

    private EntryRemoveController controller;
    private Model model;
    private RedirectAttributes redirectAttributes;
    private WeblogEntry entry;

    @BeforeEach
    void setUp() throws Exception {
        controller = prepare(new EntryRemoveController());
        model = newModel();
        redirectAttributes = newRedirectAttributes();

        WeblogCategory category = new WeblogCategory();
        category.setId("cat-1");
        category.setName("Travel");
        category.setWeblog(weblog);

        entry = new WeblogEntry();
        entry.setId("entry-1");
        entry.setTitle("Doomed post");
        entry.setWebsite(weblog);
        entry.setCategory(category);
        entry.setStatus(PubStatus.PUBLISHED);
        when(weblogger.getWeblogEntryManager().getWeblogEntry("entry-1")).thenReturn(entry);
        // a member who may post, unless a test says otherwise
        when(weblogger.getUserManager().checkPermission(any(), any())).thenAnswer(invocation -> mayPost);
    }

    /** Whether the caller holds POST on the weblog (EDIT_DRAFT alone otherwise). */
    private boolean mayPost = true;

    /**
     * {@code entryRemove!remove.rol} was orphaned: nothing in a JSP, script or
     * browser test posted to it, yet it trashed entries like its sibling. The
     * list's own endpoint is the one way in.
     */
    @Test
    void theOrphanedRemoveEndpointIsGone() {
        java.util.List<String> mappings = java.util.Arrays.stream(EntryRemoveController.class.getDeclaredMethods())
                .map(m -> m.getAnnotation(org.springframework.web.bind.annotation.PostMapping.class))
                .filter(java.util.Objects::nonNull)
                .flatMap(a -> java.util.Arrays.stream(a.value()))
                .toList();

        assertEquals(java.util.List.of("/entryRemoveViaList!remove.rol"), mappings);
    }

    /**
     * A contributor (EDIT_DRAFT, no POST) submits work for review and cannot
     * publish, so they may not take other people's posts, or any published
     * post, off the blog either: published entry ids are in every page's
     * source (code review, 2026-11-03). Refused like an unknown id.
     */
    @Test
    void aContributorCannotTrashSomeoneElsesEntry() throws Exception {
        mayPost = false;
        entry.setStatus(PubStatus.DRAFT);
        entry.setCreatorUserName("someone-else");

        assertEquals("redirect:/pressroom-ui/menu.rol",
                controller.entryRemoveViaListRemove(request, model, "entry-1", redirectAttributes));
        verify(weblogger.getWeblogEntryManager(), never()).trashWeblogEntry(any());
    }

    @Test
    void aContributorCannotTrashAPublishedEntryEvenTheirOwn() throws Exception {
        mayPost = false;
        entry.setCreatorUserName(USER_NAME);

        assertEquals("redirect:/pressroom-ui/menu.rol",
                controller.entryRemoveViaListRemove(request, model, "entry-1", redirectAttributes));
        verify(weblogger.getWeblogEntryManager(), never()).trashWeblogEntry(any());
    }

    @Test
    void aContributorMayTrashTheirOwnDraft() throws Exception {
        mayPost = false;
        entry.setStatus(PubStatus.DRAFT);
        entry.setCreatorUserName(USER_NAME);

        assertEquals(ENTRIES_REDIRECT,
                controller.entryRemoveViaListRemove(request, model, "entry-1", redirectAttributes));
        verify(weblogger.getWeblogEntryManager()).trashWeblogEntry(entry);
    }

    @Test
    void removingAnEntrySendsTheAuthorBackToTheList() throws Exception {
        // Back to the list, never onto a blank entryAdd form.
        String view = controller.entryRemoveViaListRemove(request, model, "entry-1", redirectAttributes);

        assertEquals(ENTRIES_REDIRECT, view);
        verify(weblogger.getWeblogEntryManager()).trashWeblogEntry(entry);
    }

    @Test
    void anEntryFromAnotherWeblogCannotBeRemoved() throws Exception {
        // removeId is client input and getWeblogEntry is a global by-id
        // lookup: without an ownership check an editor on weblog A can delete
        // weblog B's posts.
        com.wikantik.pressroom.pojos.Weblog other =
                new com.wikantik.pressroom.pojos.Weblog();
        other.setId("weblog-2");
        other.setHandle("otherblog");
        entry.setWebsite(other);

        assertEquals("redirect:/pressroom-ui/menu.rol",
                controller.entryRemoveViaListRemove(request, model, "entry-1", redirectAttributes),
                "a foreign entryId must bounce, exactly like an unknown one");
        assertEquals("redirect:/pressroom-ui/menu.rol",
                controller.entryRemoveViaListRemove(request, model, "entry-1", newRedirectAttributes()));
        verify(weblogger.getWeblogEntryManager(), never()).trashWeblogEntry(any());
    }

    @Test
    void removingViaTheEntryListReturnsToTheList() throws Exception {
        String view = controller.entryRemoveViaListRemove(request, model, "entry-1", redirectAttributes);

        assertEquals(ENTRIES_REDIRECT, view,
                "Deleting from the list must put the user back on the list, not on a blank form");
        verify(weblogger.getWeblogEntryManager()).trashWeblogEntry(entry);
    }

    @Test
    void aSuccessfulRemovalNamesTheEntryInTheFlashMessage() throws Exception {
        registerMessage("weblogEdit.entryRemoved", "removed:{0}");

        controller.entryRemoveViaListRemove(request, model, "entry-1", redirectAttributes);

        assertEquals(java.util.List.of("removed:Doomed post"), flashMessages(redirectAttributes));
    }

    @Test
    void removingAnEntryThatNoLongerExistsBouncesToTheMenu() throws Exception {
        when(weblogger.getWeblogEntryManager().getWeblogEntry("gone")).thenReturn(null);

        String view = controller.entryRemoveViaListRemove(request, model, "gone", redirectAttributes);

        assertEquals("redirect:/pressroom-ui/menu.rol", view);
        assertTrue(flashErrors(redirectAttributes).contains("weblogEntry.notFound"),
                "Expected a not-found error, got: " + flashErrors(redirectAttributes));
        verify(weblogger.getWeblogEntryManager(), never()).trashWeblogEntry(any());
    }

    @Test
    void removingWithNoIdBouncesToTheMenu() throws Exception {
        String view = controller.entryRemoveViaListRemove(request, model, null, redirectAttributes);

        assertEquals("redirect:/pressroom-ui/menu.rol", view);
        verify(weblogger.getWeblogEntryManager(), never()).trashWeblogEntry(any());
    }

    @Test
    void removingViaTheListWithNoIdBouncesToTheMenu() throws Exception {
        String view = controller.entryRemoveViaListRemove(request, model, null, redirectAttributes);

        assertEquals("redirect:/pressroom-ui/menu.rol", view);
        verify(weblogger.getWeblogEntryManager(), never()).trashWeblogEntry(any());
    }

    @Test
    void theListVariantReportsAFailedDeletion() throws Exception {
        org.mockito.Mockito.doThrow(new WebloggerException("constraint violation"))
                .when(weblogger.getWeblogEntryManager()).trashWeblogEntry(any());

        String view = controller.entryRemoveViaListRemove(request, model, "entry-1", redirectAttributes);

        assertEquals(ENTRIES_REDIRECT, view);
        assertTrue(flashErrors(redirectAttributes).contains("generic.error.check.logs"),
                "Expected the failure to be surfaced, got: " + flashErrors(redirectAttributes));
        assertTrue(flashMessages(redirectAttributes).isEmpty(),
                "A failed delete must not also report success");
    }

    @Test
    void theListVariantNamesTheDeletedEntryInItsFlashMessage() throws Exception {
        registerMessage("weblogEdit.entryRemoved", "removed:{0}");

        controller.entryRemoveViaListRemove(request, model, "entry-1", redirectAttributes);

        assertEquals(java.util.List.of("removed:Doomed post"), flashMessages(redirectAttributes));
    }

    @Test
    void aSuccessfulDeletionIsCommitted() throws Exception {
        // An uncommitted delete leaves the entry in the database while the page
        // reports it gone.
        controller.entryRemoveViaListRemove(request, model, "entry-1", redirectAttributes);

        assertEquals(1, weblogger.flushCount(), "The deletion must be committed");
    }

    @Test
    void aFailedDeletionIsReportedAndReturnsToTheList() throws Exception {
        org.mockito.Mockito.doThrow(new WebloggerException("constraint violation"))
                .when(weblogger.getWeblogEntryManager()).trashWeblogEntry(any());

        String view = controller.entryRemoveViaListRemove(request, model, "entry-1", redirectAttributes);

        assertEquals(ENTRIES_REDIRECT, view,
                "A failed delete must not send the author to a fresh entry form as if it worked");
        assertTrue(flashErrors(redirectAttributes).contains("generic.error.check.logs"),
                "Expected the failure to be surfaced, got: " + flashErrors(redirectAttributes));
        assertTrue(flashMessages(redirectAttributes).isEmpty(),
                "A failed delete must not also report success");
    }
}

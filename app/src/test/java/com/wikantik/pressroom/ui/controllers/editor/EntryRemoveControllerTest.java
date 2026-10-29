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
 * {@code BaseController#trashEntryWithIndex}) rather than removing it from the
 * database, but it still has to come out of the search index -- an entry that
 * survives only in the Lucene index keeps appearing in site search results and
 * links to a page that now 404s, exactly the failure a genuine delete would
 * produce. The two endpoints differ only in where they send the user
 * afterwards, and both are covered because they are separate code paths that
 * could drift.
 */
class EntryRemoveControllerTest extends EditorControllerTestSupport {

    private static final String ENTRIES_REDIRECT =
            "redirect:/roller-ui/authoring/entries.rol?weblog=" + WEBLOG_HANDLE;

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
    }

    @Test
    void removingAnEntrySendsTheAuthorBackToTheList() throws Exception {
        // This endpoint is orphaned in the UI (nothing posts to it directly
        // any more), but it must still behave like its sibling
        // entryRemoveViaList rather than dropping the author onto a blank
        // entryAdd form.
        String view = controller.remove(request, model, "entry-1", redirectAttributes);

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

        assertEquals("redirect:/roller-ui/menu.rol",
                controller.remove(request, model, "entry-1", redirectAttributes),
                "a foreign entryId must bounce, exactly like an unknown one");
        assertEquals("redirect:/roller-ui/menu.rol",
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
    void aPublishedEntryIsTakenOutOfTheSearchIndexBeforeItIsDeleted() throws Exception {
        // Once the row is gone the index entry can no longer be resolved, so
        // the de-index has to happen first.
        controller.remove(request, model, "entry-1", redirectAttributes);

        InOrder order = inOrder(weblogger.getIndexManager(), weblogger.getWeblogEntryManager());
        order.verify(weblogger.getIndexManager()).removeEntryIndexOperation(entry);
        order.verify(weblogger.getWeblogEntryManager()).trashWeblogEntry(entry);
    }

    @Test
    void anUnpublishedEntryIsAlsoDeIndexed() throws Exception {
        // The de-index call is unconditional now, not gated on isPublished():
        // removing a document that was never indexed is a safe no-op, and
        // asking every time is simpler than trusting a status check to be
        // right in every caller.
        entry.setStatus(PubStatus.DRAFT);

        controller.remove(request, model, "entry-1", redirectAttributes);

        verify(weblogger.getIndexManager()).removeEntryIndexOperation(entry);
        verify(weblogger.getWeblogEntryManager()).trashWeblogEntry(entry);
    }

    @Test
    void theEntrysPublicationStatusIsUntouchedByDeIndexing() throws Exception {
        // De-indexing used to flip the entry to DRAFT to trigger a re-index
        // job, then flip it back. That trick is gone -- the de-index call
        // asks the index directly and never touches the entry's own status.
        controller.remove(request, model, "entry-1", redirectAttributes);

        assertEquals(PubStatus.PUBLISHED, entry.getStatus(),
                "De-indexing must not mutate the entry's own status field");
        verify(weblogger.getIndexManager()).removeEntryIndexOperation(entry);
    }

    @Test
    void aSuccessfulRemovalNamesTheEntryInTheFlashMessage() throws Exception {
        registerMessage("weblogEdit.entryRemoved", "removed:{0}");

        controller.remove(request, model, "entry-1", redirectAttributes);

        assertEquals(java.util.List.of("removed:Doomed post"), flashMessages(redirectAttributes));
    }

    @Test
    void removingAnEntryThatNoLongerExistsBouncesToTheMenu() throws Exception {
        when(weblogger.getWeblogEntryManager().getWeblogEntry("gone")).thenReturn(null);

        String view = controller.remove(request, model, "gone", redirectAttributes);

        assertEquals("redirect:/roller-ui/menu.rol", view);
        assertTrue(flashErrors(redirectAttributes).contains("weblogEntry.notFound"),
                "Expected a not-found error, got: " + flashErrors(redirectAttributes));
        verify(weblogger.getWeblogEntryManager(), never()).trashWeblogEntry(any());
    }

    @Test
    void removingWithNoIdBouncesToTheMenu() throws Exception {
        String view = controller.remove(request, model, null, redirectAttributes);

        assertEquals("redirect:/roller-ui/menu.rol", view);
        verify(weblogger.getWeblogEntryManager(), never()).trashWeblogEntry(any());
    }

    @Test
    void removingViaTheListWithNoIdBouncesToTheMenu() throws Exception {
        String view = controller.entryRemoveViaListRemove(request, model, null, redirectAttributes);

        assertEquals("redirect:/roller-ui/menu.rol", view);
        verify(weblogger.getWeblogEntryManager(), never()).trashWeblogEntry(any());
    }

    @Test
    void theListVariantAlsoDeIndexesAPublishedEntry() throws Exception {
        // remove() and entryRemoveViaListRemove() are near-identical copies of
        // the same logic. Testing only one lets the other drift and quietly
        // stop cleaning up the search index.
        controller.entryRemoveViaListRemove(request, model, "entry-1", redirectAttributes);

        InOrder order = inOrder(weblogger.getIndexManager(), weblogger.getWeblogEntryManager());
        order.verify(weblogger.getIndexManager()).removeEntryIndexOperation(entry);
        order.verify(weblogger.getWeblogEntryManager()).trashWeblogEntry(entry);
        assertEquals(PubStatus.PUBLISHED, entry.getStatus(),
                "De-indexing must not mutate the entry's own status field here either");
    }

    @Test
    void theListVariantAlsoDeIndexesAnUnpublishedEntry() throws Exception {
        entry.setStatus(PubStatus.DRAFT);

        controller.entryRemoveViaListRemove(request, model, "entry-1", redirectAttributes);

        verify(weblogger.getIndexManager()).removeEntryIndexOperation(entry);
        verify(weblogger.getWeblogEntryManager()).trashWeblogEntry(entry);
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
        controller.remove(request, model, "entry-1", redirectAttributes);

        assertEquals(1, weblogger.flushCount(), "The deletion must be committed");
    }

    @Test
    void aFailedIndexUpdateDoesNotStopTheDeletion() throws Exception {
        // The index is a derived artefact; failing to update it must not leave
        // the author unable to delete their own post.
        org.mockito.Mockito.doThrow(new WebloggerException("index locked"))
                .when(weblogger.getIndexManager()).removeEntryIndexOperation(any(WeblogEntry.class));

        String view = controller.remove(request, model, "entry-1", redirectAttributes);

        assertEquals(ENTRIES_REDIRECT, view);
        verify(weblogger.getWeblogEntryManager()).trashWeblogEntry(entry);
    }

    @Test
    void aFailedDeletionIsReportedAndReturnsToTheList() throws Exception {
        org.mockito.Mockito.doThrow(new WebloggerException("constraint violation"))
                .when(weblogger.getWeblogEntryManager()).trashWeblogEntry(any());

        String view = controller.remove(request, model, "entry-1", redirectAttributes);

        assertEquals(ENTRIES_REDIRECT, view,
                "A failed delete must not send the author to a fresh entry form as if it worked");
        assertTrue(flashErrors(redirectAttributes).contains("generic.error.check.logs"),
                "Expected the failure to be surfaced, got: " + flashErrors(redirectAttributes));
        assertTrue(flashMessages(redirectAttributes).isEmpty(),
                "A failed delete must not also report success");
    }
}

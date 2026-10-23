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

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.apache.roller.weblogger.pojos.WeblogEntry.PubStatus;
import org.apache.roller.weblogger.pojos.WeblogPermission;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.ui.Model;
import org.apache.roller.weblogger.business.runnable.TrashPurgeTask;
import org.apache.roller.weblogger.config.WebloggerRuntimeConfig;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * Tests for {@link TrashController}.
 *
 * <p>The security story this class exists to pin: {@link
 * org.apache.roller.weblogger.ui.controllers.BaseController#lookupEntry}
 * checks ownership only, not status, so without an extra check in this
 * controller {@code trash!delete.rol} given a <em>live</em> entry's id would
 * be an undocumented hard-delete endpoint, and {@code trash!restore.rol}
 * given one would silently flip a published entry to {@code DRAFT}. Every
 * "refused" test below exercises exactly that: an id that resolves through
 * {@code lookupEntry} but is not actually {@code TRASHED}.
 */
class TrashControllerTest extends EditorControllerTestSupport {

    private static final String REDIRECT =
            "redirect:/roller-ui/authoring/trash.rol?weblog=" + WEBLOG_HANDLE;

    private TrashController controller;
    private Model model;
    private RedirectAttributes redirectAttributes;

    @BeforeEach
    void setUp() {
        controller = prepare(new TrashController());
        model = newModel();
        redirectAttributes = newRedirectAttributes();
    }

    /**
     * {@code requiredWeblogPermissionActions} is what {@code
     * RollerHandlerInterceptor} checks before this controller is ever
     * entered -- unlike {@code getActionName}/{@code getDesiredMenu}/{@code
     * getPageTitle}, which only steer rendering, a wrong value here changes
     * who is allowed to reach {@code trash!restore.rol} and {@code
     * trash!delete.rol} at all. POST matches every other blog-wide-structure
     * controller (categories, media, pages), not the lower EDIT_DRAFT bar
     * entry editing uses.
     */
    @Test
    void declaresPostPermissionAndTheEditorMenuIdentity() {
        assertEquals(List.of(WeblogPermission.POST), controller.requiredWeblogPermissionActions());
        assertEquals("editor", controller.getDesiredMenu());
        assertEquals("trash", controller.getActionName());
        assertEquals("trash.title", controller.getPageTitle());
    }

    // ------------------------------------------------------------------ list

    @Test
    void theListRendersEveryTrashedEntryReturnedByTheManager() throws Exception {
        List<WeblogEntry> trashed = List.of(trashedEntry("entry-1", "First"), trashedEntry("entry-2", "Second"));
        when(weblogger.getWeblogEntryManager().getTrashedEntries(weblog)).thenReturn(trashed);

        String view = controller.execute(request, model);

        assertEquals(".Trash", view);
        assertEquals(trashed, model.getAttribute("trashedEntries"));
    }

    /**
     * Each row is labelled by a plain-text title the page escapes once
     * (EntriesController.plainTitle): a stored title is already escaped, and
     * an UNTITLED entry's displayTitle is its body with complete tags
     * stripped, so an unterminated {@code <img onerror>} survives into it.
     */
    @Test
    void eachTrashedEntryGetsAPlainTextTitleThePageEscapesOnce() throws Exception {
        WeblogEntry titled = trashedEntry("entry-1", "Tom &amp; Jerry");
        WeblogEntry untitled = trashedEntry("entry-2", "");
        untitled.setText("<img src=x onerror=alert(1)//");
        when(weblogger.getWeblogEntryManager().getTrashedEntries(weblog)).thenReturn(List.of(titled, untitled));

        controller.execute(request, model);

        @SuppressWarnings("unchecked")
        Map<String, String> titles = (Map<String, String>) model.getAttribute("entryTitles");
        assertNotNull(titles, "the list has no plain entry titles to label its rows with");
        assertEquals("Tom & Jerry", titles.get("entry-1"));
        assertEquals("<img src=x onerror=alert(1)//", titles.get("entry-2"),
                "raw text, for the page to escape exactly once");
    }

    /**
     * The page tells an author their entry is recoverable but, until this,
     * never for how long. The value must come from the RUNTIME property
     * {@code entry.trash.retention.days} on every request rather than a
     * cached copy -- it is editable on Admin Settings, and TrashPurgeTask
     * re-reads it per sweep for the same reason, so a latched value here
     * would advertise a retention the purge no longer honours.
     */
    @Test
    void theListCarriesTheRetentionThePurgeActuallyUses() throws Exception {
        when(weblogger.getWeblogEntryManager().getTrashedEntries(weblog))
                .thenReturn(List.of());

        // The property is CHANGED between two renders, and each render must
        // reflect the value in force at the time. Asserting against a live
        // getIntProperty() call instead would be tautological -- it computes
        // the expectation the same way the code under test does, so it passes
        // just as happily against a value latched once in init(), which is the
        // exact defect this test exists to catch (CLAUDE.md's third
        // configuration-scope trap).
        try (MockedStatic<WebloggerRuntimeConfig> config = mockStatic(
                WebloggerRuntimeConfig.class, withSettings().defaultAnswer(CALLS_REAL_METHODS))) {

            config.when(() -> WebloggerRuntimeConfig.getIntProperty(TrashPurgeTask.RETENTION_PROPERTY))
                    .thenReturn(30);
            controller.execute(request, model);
            assertEquals(30, model.getAttribute("trashRetentionDays"));

            config.when(() -> WebloggerRuntimeConfig.getIntProperty(TrashPurgeTask.RETENTION_PROPERTY))
                    .thenReturn(-1);
            controller.execute(request, model);
            assertEquals(-1, model.getAttribute("trashRetentionDays"),
                    "the retention must be re-read per request: it is a RUNTIME property an "
                            + "admin can change, and TrashPurgeTask re-reads it per sweep, so a "
                            + "value cached here would advertise a retention the purge no "
                            + "longer honours");
        }
    }

    @Test
    void aFailedTrashedEntriesLookupIsReportedAndStillRendersAnEmptyList() throws Exception {
        when(weblogger.getWeblogEntryManager().getTrashedEntries(weblog))
                .thenThrow(new WebloggerException("database down"));

        String view = controller.execute(request, model);

        assertEquals(".Trash", view, "The trash page must still render after a failed query");
        assertTrue(errors(model).contains("generic.error.check.logs"),
                "Expected the lookup failure to be reported, got: " + errors(model));
        assertEquals(List.of(), model.getAttribute("trashedEntries"));
    }

    // --------------------------------------------------------------- restore

    @Test
    void restoringATrashedEntryBringsItBackAndReportsSuccess() throws Exception {
        registerMessage("trash.restored", "restored:{0}");
        WeblogEntry entry = trashedEntry("entry-1", "Cinque Terre");

        String view = controller.restore(request, "entry-1", redirectAttributes);

        assertEquals(REDIRECT, view);
        verify(weblogger.getWeblogEntryManager()).restoreWeblogEntry(entry);
        assertEquals(List.of("restored:Cinque Terre"), flashMessages(redirectAttributes));
    }

    @Test
    void restoringCommitsTheChange() throws Exception {
        trashedEntry("entry-1", "Cinque Terre");

        controller.restore(request, "entry-1", redirectAttributes);

        assertEquals(1, weblogger.flushCount(), "The restore must be committed");
    }

    @Test
    void restoringAnUnknownEntryIdIsRefused() throws Exception {
        when(weblogger.getWeblogEntryManager().getWeblogEntry("no-such-entry")).thenReturn(null);

        String view = controller.restore(request, "no-such-entry", redirectAttributes);

        assertEquals(REDIRECT, view);
        assertEquals(List.of("weblogEntry.notFound"), flashErrors(redirectAttributes));
        verify(weblogger.getWeblogEntryManager(), never()).restoreWeblogEntry(any());
    }

    @Test
    void restoringAForeignEntryIsRefusedTheSameWayAsAnUnknownOne() throws Exception {
        WeblogEntry foreign = foreignTrashedEntry("entry-1");

        String view = controller.restore(request, "entry-1", redirectAttributes);

        assertEquals(REDIRECT, view);
        assertEquals(List.of("weblogEntry.notFound"), flashErrors(redirectAttributes));
        verify(weblogger.getWeblogEntryManager(), never()).restoreWeblogEntry(foreign);
    }

    /**
     * The security case for restore: lookupEntry's ownership check succeeds
     * (this is genuinely the caller's entry) but the entry is live, not
     * trashed. Without the extra status check, posting a live entry's id here
     * would silently flip a published entry back to DRAFT.
     */
    @Test
    void restoringALiveEntryIsRefused() throws Exception {
        WeblogEntry live = ownedEntry("entry-1", PubStatus.PUBLISHED);

        String view = controller.restore(request, "entry-1", redirectAttributes);

        assertEquals(REDIRECT, view);
        assertEquals(List.of("weblogEntry.notFound"), flashErrors(redirectAttributes));
        assertEquals(PubStatus.PUBLISHED, live.getStatus(), "a live entry's status must not change");
        verify(weblogger.getWeblogEntryManager(), never()).restoreWeblogEntry(any());
    }

    @Test
    void aFailedRestoreIsReportedWithAGenericError() throws Exception {
        trashedEntry("entry-1", "Cinque Terre");
        doThrow(new WebloggerException("constraint violation"))
                .when(weblogger.getWeblogEntryManager()).restoreWeblogEntry(any());

        String view = controller.restore(request, "entry-1", redirectAttributes);

        assertEquals(REDIRECT, view);
        assertEquals(List.of("generic.error.check.logs"), flashErrors(redirectAttributes));
        assertTrue(flashMessages(redirectAttributes).isEmpty(), "A failed restore must not also report success");
    }

    // ---------------------------------------------------------- delete forever

    @Test
    void deletingForeverHardDeletesATrashedEntryAndReportsSuccess() throws Exception {
        registerMessage("trash.deletedForever", "gone:{0}");
        WeblogEntry entry = trashedEntry("entry-1", "Cinque Terre");

        String view = controller.delete(request, "entry-1", redirectAttributes);

        assertEquals(REDIRECT, view);
        verify(weblogger.getWeblogEntryManager()).removeWeblogEntry(entry);
        assertEquals(List.of("gone:Cinque Terre"), flashMessages(redirectAttributes));
    }

    @Test
    void deletingForeverCommitsTheChange() throws Exception {
        trashedEntry("entry-1", "Cinque Terre");

        controller.delete(request, "entry-1", redirectAttributes);

        assertEquals(1, weblogger.flushCount(), "The permanent delete must be committed");
    }

    @Test
    void deletingForeverRefusesAnUnknownEntryId() throws Exception {
        when(weblogger.getWeblogEntryManager().getWeblogEntry("no-such-entry")).thenReturn(null);

        String view = controller.delete(request, "no-such-entry", redirectAttributes);

        assertEquals(REDIRECT, view);
        assertEquals(List.of("weblogEntry.notFound"), flashErrors(redirectAttributes));
        verify(weblogger.getWeblogEntryManager(), never()).removeWeblogEntry(any());
    }

    @Test
    void deletingForeverRefusesAForeignEntry() throws Exception {
        WeblogEntry foreign = foreignTrashedEntry("entry-1");

        String view = controller.delete(request, "entry-1", redirectAttributes);

        assertEquals(REDIRECT, view);
        assertEquals(List.of("weblogEntry.notFound"), flashErrors(redirectAttributes));
        verify(weblogger.getWeblogEntryManager(), never()).removeWeblogEntry(foreign);
    }

    /**
     * The security case that gives this screen its reason to exist:
     * lookupEntry alone would let {@code trash!delete.rol} hard-delete any
     * live entry whose id can be guessed. This proves the extra TRASHED
     * check refuses it instead of calling through to a real delete.
     */
    @Test
    void deletingForeverRefusesALiveEntry() throws Exception {
        WeblogEntry live = ownedEntry("entry-1", PubStatus.DRAFT);

        String view = controller.delete(request, "entry-1", redirectAttributes);

        assertEquals(REDIRECT, view);
        assertEquals(List.of("weblogEntry.notFound"), flashErrors(redirectAttributes));
        verify(weblogger.getWeblogEntryManager(), never()).removeWeblogEntry(live);
    }

    @Test
    void aFailedPermanentDeleteIsReportedWithAGenericError() throws Exception {
        trashedEntry("entry-1", "Cinque Terre");
        doThrow(new WebloggerException("constraint violation"))
                .when(weblogger.getWeblogEntryManager()).removeWeblogEntry(any());

        String view = controller.delete(request, "entry-1", redirectAttributes);

        assertEquals(REDIRECT, view);
        assertEquals(List.of("generic.error.check.logs"), flashErrors(redirectAttributes));
        assertTrue(flashMessages(redirectAttributes).isEmpty(), "A failed delete must not also report success");
    }

    // -------------------------------------------------------------- empty all

    @Test
    void emptyingTrashPurgesEverythingCurrentlyInIt() throws Exception {
        registerMessage("trash.emptied", "emptied:{0}");
        when(weblogger.getWeblogEntryManager().purgeTrash(weblog, 0)).thenReturn(3);

        String view = controller.empty(request, redirectAttributes);

        assertEquals(REDIRECT, view);
        verify(weblogger.getWeblogEntryManager()).purgeTrash(weblog, 0);
        assertEquals(List.of("emptied:3"), flashMessages(redirectAttributes));
    }

    @Test
    void emptyingAnAlreadyEmptyTrashSaysSoInsteadOfClaimingACount() throws Exception {
        when(weblogger.getWeblogEntryManager().purgeTrash(weblog, 0)).thenReturn(0);

        controller.empty(request, redirectAttributes);

        assertEquals(List.of("trash.alreadyEmpty"), flashMessages(redirectAttributes));
    }

    @Test
    void emptyingCommitsTheChange() throws Exception {
        when(weblogger.getWeblogEntryManager().purgeTrash(weblog, 0)).thenReturn(1);

        controller.empty(request, redirectAttributes);

        assertEquals(1, weblogger.flushCount(), "Emptying the trash must be committed");
    }

    @Test
    void aFailedEmptyIsReportedWithAGenericError() throws Exception {
        doThrow(new WebloggerException("database down"))
                .when(weblogger.getWeblogEntryManager()).purgeTrash(any(), org.mockito.ArgumentMatchers.anyInt());

        String view = controller.empty(request, redirectAttributes);

        assertEquals(REDIRECT, view);
        assertEquals(List.of("generic.error.check.logs"), flashErrors(redirectAttributes));
        assertTrue(flashMessages(redirectAttributes).isEmpty(), "A failed empty must not also report success");
    }

    // --- helpers ---

    /** A TRASHED entry of the action weblog, stubbed into the by-id lookup. */
    private WeblogEntry trashedEntry(String id, String title) throws Exception {
        WeblogEntry entry = new WeblogEntry();
        entry.setId(id);
        entry.setAnchor("anchor-" + id);
        entry.setTitle(title);
        entry.setWebsite(weblog);
        entry.setStatus(PubStatus.TRASHED);
        entry.setTrashedAt(new Timestamp(System.currentTimeMillis()));
        when(weblogger.getWeblogEntryManager().getWeblogEntry(id)).thenReturn(entry);
        return entry;
    }

    /** An entry of the action weblog carrying a live (non-trashed) status. */
    private WeblogEntry ownedEntry(String id, PubStatus status) throws Exception {
        WeblogEntry entry = new WeblogEntry();
        entry.setId(id);
        entry.setAnchor("anchor-" + id);
        entry.setTitle("Live entry " + id);
        entry.setWebsite(weblog);
        entry.setStatus(status);
        when(weblogger.getWeblogEntryManager().getWeblogEntry(id)).thenReturn(entry);
        return entry;
    }

    /** A TRASHED entry belonging to a different weblog, stubbed into the by-id lookup. */
    private WeblogEntry foreignTrashedEntry(String id) throws Exception {
        Weblog other = new Weblog();
        other.setId("weblog-2");
        other.setHandle("someoneelse");

        WeblogEntry entry = new WeblogEntry();
        entry.setId(id);
        entry.setAnchor("anchor-" + id);
        entry.setTitle("Someone else's entry");
        entry.setWebsite(other);
        entry.setStatus(PubStatus.TRASHED);
        when(weblogger.getWeblogEntryManager().getWeblogEntry(id)).thenReturn(entry);
        return entry;
    }
}

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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.config.WebloggerRuntimeConfig;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.ui.Model;
import org.springframework.web.bind.ServletRequestDataBinder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link PageEditController} and {@link PageBean}.
 *
 * <p>The critical behaviour is the same hazard as {@code lookupCategory} /
 * {@code lookupEntry} / {@code lookupTemplate}: a page id is client input and
 * {@code getPage} is a global by-id lookup, so without an ownership check an
 * editor on one weblog could open -- and on save, overwrite -- another
 * weblog's page. See {@link #aPageBelongingToAnotherWeblogIsNotFound()} and
 * {@link #savingAPageBelongingToAnotherWeblogIsRefused()}.
 */
class PageEditControllerTest extends EditorControllerTestSupport {

    /** Where a denied lookup goes: back to the list, same as TemplateEditController. */
    private static final String DENIED_VIEW = ".Pages";

    private Weblog weblogA;
    private Weblog weblogB;

    private PageEditController controller;
    private Model model;

    @BeforeEach
    void setUp() throws Exception {
        controller = prepare(new PageEditController());
        // The manager's restore is mocked: stand in for what it does to the
        // page, so what the route answers can be checked. That it never
        // coalesces is the manager's own test (RevisionRetentionWriteTest).
        org.mockito.Mockito.doAnswer(call -> {
            WeblogPage restored = call.getArgument(0);
            com.wikantik.pressroom.pojos.WeblogPageRevision from = call.getArgument(1);
            restored.setTitle(from.getTitle());
            restored.setContent(from.getContent());
            return null;
        }).when(weblogger.getWeblogPageManager()).restoreRevision(any(), any(), any());
        model = newModel();

        weblogA = weblog;
        weblogB = new Weblog();
        weblogB.setId("weblog-2");
        weblogB.setHandle("someoneelse");
    }

    // --- ownership ---

    @Test
    void aPageBelongingToAnotherWeblogIsNotFound() throws Exception {
        // actionWeblog is weblogA; the id names a page on weblogB
        WeblogPage foreign = pageOn(weblogB, "their-about");

        String view = controller.edit(foreign.getId(), requestFor(weblogA), model);

        assertEquals(DENIED_VIEW, view,
                "the permission interceptor vouches only for the action weblog, "
                        + "so a global by-id lookup would let any editor edit any "
                        + "weblog's pages");
    }

    @Test
    void aBlankIdIsTreatedAsAbsentRatherThanLookedUp() throws Exception {
        assertNull(controller.lookupPage("   ", requestFor(weblogA)));
    }

    @Test
    void aFailedPageLookupYieldsNullRatherThanThrowing() throws Exception {
        // Same defensive shape as lookupEntry/lookupTemplate/lookupCategory: a
        // failing global lookup must degrade to "not found", not propagate out
        // of what both edit() and save() treat as an ordinary miss.
        when(weblogger.getWeblogPageManager().getPage("page-1"))
                .thenThrow(new WebloggerException("database down"));

        assertNull(controller.lookupPage("page-1", requestFor(weblogA)));
    }

    @Test
    void savingAPageBelongingToAnotherWeblogIsRefused() throws Exception {
        WeblogPage foreign = pageOn(weblogB, "their-about");
        PageBean bean = new PageBean();
        bean.setId(foreign.getId());
        bean.setTitle("Hijacked");
        bean.setSlug("hijacked");

        String view = controller.save(requestFor(weblogA), model, bean);

        assertEquals(DENIED_VIEW, view);
        assertEquals("their-about", foreign.getSlug(),
                "a foreign page must come through the request unchanged");
        verify(weblogger.getWeblogPageManager(), never()).savePage(any(), any(), any());
    }

    // --- the trash: a trashed page answers like an unknown id (spec 2026-11-15, Part 1) ---

    @Test
    void editingATrashedPageIsNotFound() throws Exception {
        WeblogPage trashed = pageOn(weblogA, "about");
        trashed.setStatus(WeblogPage.PubStatus.TRASHED);

        assertEquals(DENIED_VIEW, controller.edit(trashed.getId(), requestFor(weblogA), model));
    }

    /** A page trashed after the editor was opened: the save writes nothing. */
    @Test
    void savingATrashedPageIsRefusedAndWritesNothing() throws Exception {
        WeblogPage trashed = pageOn(weblogA, "about");
        trashed.setStatus(WeblogPage.PubStatus.TRASHED);
        PageBean bean = new PageBean();
        bean.setId(trashed.getId());
        bean.setTitle("Edited in another tab");
        bean.setSlug("about");
        bean.setStatus("PUBLISHED");

        String view = controller.save(requestFor(weblogA), model, bean);

        assertEquals(DENIED_VIEW, view);
        assertEquals(WeblogPage.PubStatus.TRASHED, trashed.getStatus(), "a save must not bring it back");
        verify(weblogger.getWeblogPageManager(), never()).savePage(any(), any(), any());
    }

    @Test
    void previewOfATrashedPageIsNotFound() throws Exception {
        WeblogPage trashed = pageOn(weblogA, "about");
        trashed.setStatus(WeblogPage.PubStatus.TRASHED);

        var response = controller.pageEditPreview(requestFor(weblogA), trashed.getId(), "anything");

        assertEquals(404, response.getStatusCode().value());
    }

    /**
     * The form offers DRAFT and PUBLISHED. A crafted POST naming TRASHED must
     * not trash a page past trashPage: it would carry no stamp, so the purge
     * would never take it and the trash screen would show no date. It reads
     * as any other unrecognized status does: a draft.
     */
    @Test
    void aCraftedTrashedStatusOnTheFormIsNotAWayIntoTheTrash() throws Exception {
        WeblogPage mine = pageOn(weblogA, "about");
        mine.setStatus(WeblogPage.PubStatus.PUBLISHED);
        PageBean bean = new PageBean();
        bean.setId(mine.getId());
        bean.setTitle("About");
        bean.setSlug("about");
        bean.setStatus("TRASHED");

        controller.save(requestFor(weblogA), model, bean);

        assertEquals(WeblogPage.PubStatus.DRAFT, mine.getStatus());
        verify(weblogger.getWeblogPageManager(), never()).trashPage(any());
    }

    /** The slug a trashed page holds, as getPageBySlug answers it for this weblog. */
    private WeblogPage trashedHolder(String slug) throws WebloggerException {
        WeblogPage holder = new WeblogPage();
        holder.setId("trashed-" + slug);
        holder.setWeblog(weblogA);
        holder.setSlug(slug);
        holder.setStatus(WeblogPage.PubStatus.TRASHED);
        when(weblogger.getWeblogPageManager().getPageBySlug(weblogA, slug)).thenReturn(holder);
        return holder;
    }

    /** A trashed page keeps its address for as long as it can be restored. */
    @Test
    void aNewPageOnASlugHeldByTheTrashIsAFieldErrorNamingTheTrash() throws Exception {
        trashedHolder("about");
        PageBean bean = new PageBean();
        bean.setTitle("About");
        bean.setSlug("about");

        String view = controller.save(requestFor(weblogA), model, bean);

        assertEquals(".PageEdit", view);
        assertTrue(errors(model).contains("pageEdit.error.slugInTrash"), "got: " + errors(model));
        verify(weblogger.getWeblogPageManager(), never()).savePage(any(), any(), any());
    }

    @Test
    void aSlugTakenFromTheTitleIsCheckedAgainstTheTrashToo() throws Exception {
        trashedHolder("about-us");
        PageBean bean = new PageBean();
        bean.setTitle("About Us");

        controller.save(requestFor(weblogA), model, bean);

        assertTrue(errors(model).contains("pageEdit.error.slugInTrash"), "got: " + errors(model));
        verify(weblogger.getWeblogPageManager(), never()).savePage(any(), any(), any());
    }

    @Test
    void renamingOntoASlugHeldByTheTrashIsRefused() throws Exception {
        trashedHolder("about");
        WeblogPage mine = pageOn(weblogA, "contact");
        PageBean bean = new PageBean();
        bean.setId(mine.getId());
        bean.setTitle("Contact");
        bean.setSlug("about");

        controller.save(requestFor(weblogA), model, bean);

        assertTrue(errors(model).contains("pageEdit.error.slugInTrash"), "got: " + errors(model));
        assertEquals("contact", mine.getSlug(), "a refused rename leaves the page as it was");
        verify(weblogger.getWeblogPageManager(), never()).savePage(any(), any(), any());
    }

    /** A check that could not run is not one that passed. */
    @Test
    void aTrashCheckThatCannotRunRefusesTheSave() throws Exception {
        when(weblogger.getWeblogPageManager().getPageBySlug(weblogA, "about"))
                .thenThrow(new WebloggerException("database down"));
        PageBean bean = new PageBean();
        bean.setTitle("About");
        bean.setSlug("about");

        controller.save(requestFor(weblogA), model, bean);

        assertTrue(errors(model).contains("generic.error.check.logs"), "got: " + errors(model));
        verify(weblogger.getWeblogPageManager(), never()).savePage(any(), any(), any());
    }

    /** Saving a page under its own slug is not a clash with anything. */
    @Test
    void aLivePageHoldingItsOwnSlugSavesAsBefore() throws Exception {
        WeblogPage mine = pageOn(weblogA, "about");
        when(weblogger.getWeblogPageManager().getPageBySlug(weblogA, "about")).thenReturn(mine);
        PageBean bean = new PageBean();
        bean.setId(mine.getId());
        bean.setTitle("About");
        bean.setSlug("about");

        controller.save(requestFor(weblogA), model, bean);

        assertTrue(errors(model).isEmpty(), "got: " + errors(model));
        verify(weblogger.getWeblogPageManager()).savePage(eq(mine), any(), any());
    }

    // --- revisions (spec 2026-11-15, Part 2) ---

    private com.wikantik.pressroom.pojos.WeblogPageRevision revisionOf(WeblogPage page, String id, String title,
            String content) throws WebloggerException {
        com.wikantik.pressroom.pojos.WeblogPageRevision revision = new com.wikantik.pressroom.pojos.WeblogPageRevision();
        revision.setId(id);
        revision.setPage(page);
        revision.setTitle(title);
        revision.setContent(content);
        when(weblogger.getWeblogPageManager().getRevision(id)).thenReturn(revision);
        return revision;
    }

    /** The revision a save leaves is credited to whoever is signed in. */
    @Test
    void savingCreditsTheSignedInUser() throws Exception {
        WeblogPage mine = pageOn(weblogA, "about");
        PageBean bean = new PageBean();
        bean.setId(mine.getId());
        bean.setTitle("About");
        bean.setSlug("about");

        controller.save(requestFor(weblogA), model, bean);

        verify(weblogger.getWeblogPageManager()).savePage(mine, user, null);
    }

    @Test
    void theEditorListsThePagesRevisions() throws Exception {
        WeblogPage mine = pageOn(weblogA, "about");
        var revision = revisionOf(mine, "r1", "About", "old");
        when(weblogger.getWeblogPageManager().getRevisions(mine)).thenReturn(java.util.List.of(revision));

        controller.edit(mine.getId(), requestFor(weblogA), model);

        assertEquals(java.util.List.of(revision), model.getAttribute("pageRevisions"));
    }

    /** A display path: the box is left out and the editor still opens. */
    @Test
    void aRevisionListThatCannotBeReadLeavesTheEditorUsable() throws Exception {
        WeblogPage mine = pageOn(weblogA, "about");
        when(weblogger.getWeblogPageManager().getRevisions(mine)).thenThrow(new WebloggerException("database down"));

        String view = controller.edit(mine.getId(), requestFor(weblogA), model);

        assertEquals(".PageEdit", view);
        assertEquals(java.util.List.of(), model.getAttribute("pageRevisions"));
    }

    @Test
    void aNewPageHasNoRevisionsToList() throws Exception {
        controller.edit(null, requestFor(weblogA), model);

        assertEquals(java.util.List.of(), model.getAttribute("pageRevisions"));
        verify(weblogger.getWeblogPageManager(), never()).getRevisions(any());
    }

    @Test
    void theDiffShowsWhatChangedEscaped() throws Exception {
        WeblogPage mine = pageOn(weblogA, "about");
        mine.setTitle("About us");
        mine.setContent("line one\n<script>now</script>\n");
        revisionOf(mine, "r1", "About", "line one\n<b>then</b>\n");

        var response = controller.revisionDiff(requestFor(weblogA), mine.getId(), "r1");

        assertEquals(200, response.getStatusCode().value());
        String body = response.getBody();
        assertTrue(body.startsWith("<div class=\"revision-diff\">"), body);
        assertTrue(body.contains("- About") && body.contains("+ About us"), body);
        assertTrue(body.contains("&lt;b&gt;then&lt;/b&gt;") && body.contains("&lt;script&gt;now&lt;/script&gt;"), body);
        assertFalse(body.contains("<script>"), "revision content is shown as source, never rendered");
    }

    @Test
    void aRevisionIdenticalToThePageSaysSo() throws Exception {
        registerMessage("pageEdit.revisionSame", "No difference.");
        WeblogPage mine = pageOn(weblogA, "about");
        mine.setTitle("About");
        mine.setContent("same");
        revisionOf(mine, "r1", "About", "same");

        String body = controller.revisionDiff(requestFor(weblogA), mine.getId(), "r1").getBody();

        assertTrue(body.contains("No difference."), body);
    }

    /** Another page's revision, an unknown one, and any revision of a foreign or trashed page: one 404. */
    @Test
    void theDiffIsOfThisPagesRevisionOnly() throws Exception {
        WeblogPage mine = pageOn(weblogA, "about");
        WeblogPage sibling = pageOn(weblogA, "contact");
        WeblogPage foreign = pageOn(weblogB, "their-about");
        revisionOf(sibling, "sibling-r", "T", "c");
        revisionOf(foreign, "foreign-r", "T", "c");
        revisionOf(mine, "mine-r", "T", "c");

        assertEquals(404, controller.revisionDiff(requestFor(weblogA), mine.getId(), "sibling-r").getStatusCode().value());
        assertEquals(404, controller.revisionDiff(requestFor(weblogA), mine.getId(), "nope").getStatusCode().value());
        assertEquals(404, controller.revisionDiff(requestFor(weblogA), mine.getId(), null).getStatusCode().value());
        assertEquals(404,
                controller.revisionDiff(requestFor(weblogA), foreign.getId(), "foreign-r").getStatusCode().value());
        mine.setStatus(WeblogPage.PubStatus.TRASHED);
        assertEquals(404, controller.revisionDiff(requestFor(weblogA), mine.getId(), "mine-r").getStatusCode().value());
    }

    @Test
    void restoringPutsTheTitleAndContentBackAndNothingElse() throws Exception {
        registerMessage("pageEdit.revisionRestored", "restored");
        WeblogPage mine = pageOn(weblogA, "about-us");
        mine.setTitle("About us");
        mine.setContent("v3");
        mine.setStatus(WeblogPage.PubStatus.PUBLISHED);
        revisionOf(mine, "r1", "About", "v1");
        var redirect = newRedirectAttributes();

        String view = controller.restoreRevision(requestFor(weblogA), mine.getId(), "r1", redirect);

        assertEquals("About", mine.getTitle());
        assertEquals("v1", mine.getContent());
        assertEquals("about-us", mine.getSlug());
        assertEquals(WeblogPage.PubStatus.PUBLISHED, mine.getStatus());
        verify(weblogger.getWeblogPageManager()).restoreRevision(eq(mine), any(), eq(user));
        assertEquals(1, weblogger.flushCount());
        assertEquals(java.util.List.of("restored"), flashMessages(redirect));
        assertEquals("redirect:/pressroom-ui/authoring/pageEdit.rol?weblog=" + weblogA.getHandle()
                + "&id=" + mine.getId(), view);
    }

    @Test
    void restoringAnotherPagesRevisionIsRefusedAndSavesNothing() throws Exception {
        WeblogPage mine = pageOn(weblogA, "about");
        mine.setContent("v3");
        WeblogPage sibling = pageOn(weblogA, "contact");
        revisionOf(sibling, "sibling-r", "T", "theirs");
        var redirect = newRedirectAttributes();

        String view = controller.restoreRevision(requestFor(weblogA), mine.getId(), "sibling-r", redirect);

        assertEquals("v3", mine.getContent());
        assertEquals(java.util.List.of("pageEdit.notFound"), flashErrors(redirect));
        assertEquals("redirect:/pressroom-ui/authoring/pages.rol?weblog=" + weblogA.getHandle(), view);
        verify(weblogger.getWeblogPageManager(), never()).savePage(any(), any(), any());
        verify(weblogger.getWeblogPageManager(), never()).restoreRevision(any(), any(), any());
    }

    @Test
    void restoringOnATrashedPageIsNotFound() throws Exception {
        WeblogPage mine = pageOn(weblogA, "about");
        mine.setContent("v3");
        revisionOf(mine, "r1", "T", "v1");
        mine.setStatus(WeblogPage.PubStatus.TRASHED);
        var redirect = newRedirectAttributes();

        controller.restoreRevision(requestFor(weblogA), mine.getId(), "r1", redirect);

        assertEquals("v3", mine.getContent());
        assertEquals(java.util.List.of("pageEdit.notFound"), flashErrors(redirect));
        verify(weblogger.getWeblogPageManager(), never()).savePage(any(), any(), any());
        verify(weblogger.getWeblogPageManager(), never()).restoreRevision(any(), any(), any());
    }

    @Test
    void aFailedRestoreIsReportedAndDoesNotClaimSuccess() throws Exception {
        WeblogPage mine = pageOn(weblogA, "about");
        revisionOf(mine, "r1", "T", "v1");
        doThrow(new WebloggerException("database down"))
                .when(weblogger.getWeblogPageManager()).restoreRevision(any(), any(), any());
        var redirect = newRedirectAttributes();

        controller.restoreRevision(requestFor(weblogA), mine.getId(), "r1", redirect);

        assertEquals(java.util.List.of("generic.error.check.logs"), flashErrors(redirect));
        assertTrue(flashMessages(redirect).isEmpty());
    }

    // --- keeping a revision (spec 2026-11-15, Part 3) ---

    @Test
    void keepingAndReleasingARevisionGoThroughTheManagerAndSaySo() throws Exception {
        WeblogPage mine = pageOn(weblogA, "about");
        var revision = revisionOf(mine, "r1", "About", "v1");
        var redirect = newRedirectAttributes();

        String view = controller.keepRevision(requestFor(weblogA), mine.getId(), "r1", true, redirect);

        verify(weblogger.getWeblogPageManager()).setRevisionKept(revision, true);
        assertEquals(java.util.List.of("weblogEdit.revisionKept"), flashMessages(redirect));
        assertEquals("redirect:/pressroom-ui/authoring/pageEdit.rol?weblog=" + weblogA.getHandle()
                + "&id=" + mine.getId(), view);

        var second = newRedirectAttributes();
        controller.keepRevision(requestFor(weblogA), mine.getId(), "r1", false, second);

        verify(weblogger.getWeblogPageManager()).setRevisionKept(revision, false);
        assertEquals(java.util.List.of("weblogEdit.revisionUnkept"), flashMessages(second));
    }

    @Test
    void anEleventhKeptRevisionReportsTheLimit() throws Exception {
        WeblogPage mine = pageOn(weblogA, "about");
        var revision = revisionOf(mine, "r1", "About", "v1");
        doThrow(new com.wikantik.pressroom.business.RevisionKeepLimitException())
                .when(weblogger.getWeblogPageManager()).setRevisionKept(revision, true);
        var redirect = newRedirectAttributes();

        controller.keepRevision(requestFor(weblogA), mine.getId(), "r1", true, redirect);

        assertEquals(java.util.List.of("weblogEdit.revisionKeepLimit"), flashErrors(redirect));
        assertTrue(flashMessages(redirect).isEmpty());
    }

    @Test
    void keepingAnotherPagesRevisionOrOnATrashedPageIsNotFound() throws Exception {
        WeblogPage mine = pageOn(weblogA, "about");
        WeblogPage sibling = pageOn(weblogA, "contact");
        revisionOf(sibling, "theirs", "T", "c");
        revisionOf(mine, "mine-r", "T", "c");
        var redirect = newRedirectAttributes();

        String view = controller.keepRevision(requestFor(weblogA), mine.getId(), "theirs", true, redirect);

        assertEquals(java.util.List.of("pageEdit.notFound"), flashErrors(redirect));
        assertEquals("redirect:/pressroom-ui/authoring/pages.rol?weblog=" + weblogA.getHandle(), view);
        mine.setStatus(WeblogPage.PubStatus.TRASHED);
        controller.keepRevision(requestFor(weblogA), mine.getId(), "mine-r", true, newRedirectAttributes());
        verify(weblogger.getWeblogPageManager(), never())
                .setRevisionKept(any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    // --- opening the editor ---

    @Test
    void openingAnExistingPageLoadsItIntoTheForm() throws Exception {
        WeblogPage page = pageOn(weblogA, "about");
        page.setTitle("About Us");
        page.setContent("## Hello");

        String view = controller.edit(page.getId(), requestFor(weblogA), model);

        assertEquals(".PageEdit", view);
        PageBean bean = (PageBean) model.getAttribute("bean");
        assertEquals("About Us", bean.getTitle());
        assertEquals("## Hello", bean.getContent());
        assertEquals("about", bean.getSlug());
    }

    @Test
    void theFrontDoorPageOfTheSiteSpaceIsFlaggedSoTheEditorCanSayWhereItIsServed() throws Exception {
        weblogA.setHandle(Weblog.SITE_SPACE_HANDLE);
        weblogA.setSiteSpace(true);
        WeblogPage home = pageOn(weblogA, "home");
        WeblogPage other = pageOn(weblogA, "about");
        try (com.wikantik.pressroom.config.RuntimeConfigAttachment _ =
                com.wikantik.pressroom.config.RuntimeConfigAttachment.answering(
                        "site.frontpage.page", "home")) {
            controller.edit(home.getId(), requestFor(weblogA), model);
            assertEquals(Boolean.TRUE, model.getAttribute("frontDoorPage"));

            model = newModel();
            controller.edit(other.getId(), requestFor(weblogA), model);
            assertEquals(Boolean.FALSE, model.getAttribute("frontDoorPage"));
        }
    }

    @Test
    void aBlogPageWithTheFrontDoorsSlugIsNotTheFrontDoor() throws Exception {
        WeblogPage home = pageOn(weblogA, "home");
        try (com.wikantik.pressroom.config.RuntimeConfigAttachment _ =
                com.wikantik.pressroom.config.RuntimeConfigAttachment.answering(
                        "site.frontpage.page", "home")) {
            controller.edit(home.getId(), requestFor(weblogA), model);
            assertEquals(Boolean.FALSE, model.getAttribute("frontDoorPage"));
        }
    }

    @Test
    void openingWithNoIdShowsAnEmptyFormDefaultingToDraft() throws Exception {
        String view = controller.edit(null, requestFor(weblogA), model);

        assertEquals(".PageEdit", view);
        PageBean bean = (PageBean) model.getAttribute("bean");
        assertEquals("DRAFT", bean.getStatus());
        assertNull(bean.getId());
    }

    // --- page title ---
    //
    // One route (pageEdit.rol) handles both add and edit, unlike the entry
    // editor's separate entryAdd.rol/entryEdit.rol pair, so getPageTitle()
    // alone (a per-controller-instance method, not per-request) cannot tell
    // them apart -- it always answers "pageEdit.title" ("Edit page"). Both
    // edit() and save() must override the "pageTitle" model attribute that
    // populateCommonModel() seeds from it, the same shape EntryEditController
    // uses to override "actionName" after calling populateCommonModel.

    @Test
    void openingWithNoIdTitlesThePageNewPage() throws Exception {
        controller.edit(null, requestFor(weblogA), model);

        assertEquals("pageEdit.title.new", model.getAttribute("pageTitle"),
                "a new page must not be titled \"Edit page\"");
    }

    @Test
    void openingAnExistingPageTitlesItEditPage() throws Exception {
        WeblogPage page = pageOn(weblogA, "about");

        controller.edit(page.getId(), requestFor(weblogA), model);

        assertEquals("pageEdit.title", model.getAttribute("pageTitle"));
    }

    @Test
    void savingANewPageTitlesTheRedisplayedFormEditPage() throws Exception {
        // Success leaves this POST on the same view (no redirect), but
        // bean.copyFrom (inside save()) has by then populated bean.id from
        // the newly-persisted page -- the title must track that, not the
        // isNew flag captured when the request came in.
        PageBean bean = new PageBean();
        bean.setSlug("about");
        bean.setTitle("About");

        controller.save(requestFor(weblogA), model, bean);

        assertEquals("pageEdit.title", model.getAttribute("pageTitle"),
                "once a new page is persisted the redisplayed form is no longer \"new\"");
    }

    @Test
    void aFailedCreateKeepsTheNewPageTitle() throws Exception {
        doThrow(new WebloggerException("page slug is reserved: feed"))
                .when(weblogger.getWeblogPageManager()).savePage(any(), any(), any());

        PageBean bean = new PageBean();
        bean.setSlug("feed");
        bean.setTitle("Feed");

        controller.save(requestFor(weblogA), model, bean);

        assertEquals("pageEdit.title.new", model.getAttribute("pageTitle"),
                "a create that failed validation has no persisted id, so it is still \"New page\"");
    }

    @Test
    void savingAnExistingPageKeepsTheEditPageTitle() throws Exception {
        WeblogPage existing = pageOn(weblogA, "about");

        PageBean bean = new PageBean();
        bean.setId(existing.getId());
        bean.setSlug("about");
        bean.setTitle("New Title");

        controller.save(requestFor(weblogA), model, bean);

        assertEquals("pageEdit.title", model.getAttribute("pageTitle"));
    }

    // --- saving ---

    @Test
    void savingANewPageCreatesItAsADraftByDefault() throws Exception {
        PageBean bean = new PageBean();
        bean.setSlug("about");
        bean.setTitle("About");
        bean.setContent("Some content");

        String view = controller.save(requestFor(weblogA), model, bean);

        assertEquals(".PageEdit", view);

        org.mockito.ArgumentCaptor<WeblogPage> saved =
                org.mockito.ArgumentCaptor.forClass(WeblogPage.class);
        verify(weblogger.getWeblogPageManager()).savePage(saved.capture(), any(), any());
        assertEquals("about", saved.getValue().getSlug());
        assertEquals("About", saved.getValue().getTitle());
        assertEquals(WeblogPage.PubStatus.DRAFT, saved.getValue().getStatus());
        assertEquals(weblogA, saved.getValue().getWeblog(),
                "a new page must be attached to the action weblog, not left dangling");
        assertEquals(1, weblogger.flushCount());
    }

    @Test
    void savingAnExistingPageUpdatesTheLookedUpPage() throws Exception {
        WeblogPage existing = pageOn(weblogA, "about");
        existing.setTitle("Old Title");

        PageBean bean = new PageBean();
        bean.setId(existing.getId());
        bean.setSlug("about");
        bean.setTitle("New Title");
        bean.setStatus("PUBLISHED");

        String view = controller.save(requestFor(weblogA), model, bean);

        assertEquals(".PageEdit", view);
        assertEquals("New Title", existing.getTitle(),
                "the edit must be applied to the page that was looked up");
        assertEquals(WeblogPage.PubStatus.PUBLISHED, existing.getStatus());
        verify(weblogger.getWeblogPageManager()).savePage(eq(existing), any(), any());
    }

    @Test
    void savingWithAReservedSlugIsAFieldErrorNotA500() throws Exception {
        doThrow(new WebloggerException("page slug is reserved: feed"))
                .when(weblogger.getWeblogPageManager()).savePage(any(), any(), any());

        PageBean bean = new PageBean();
        bean.setSlug("feed");
        bean.setTitle("Feed");

        String view = controller.save(requestFor(weblogA), model, bean);

        assertEquals(".PageEdit", view, "a rejected slug must redisplay the form, not error out");
        assertTrue(errors(model).contains("pageEdit.error.slugReserved"),
                "Expected a slugReserved error, got: " + errors(model));
    }

    @Test
    void aSlugThatClashesNamesTheClashInTheField() throws Exception {
        registerMessage("pageEdit.error.slugIsWeblog", "Taken by {0}.");
        doThrow(new com.wikantik.pressroom.business.SiteSlugClashException(
                "pageEdit.error.slugIsWeblog", "<b>travel</b>"))
                .when(weblogger.getWeblogPageManager()).savePage(any(), any(), any());

        PageBean bean = new PageBean();
        bean.setSlug("travel");
        bean.setTitle("Travel");

        String view = controller.save(requestFor(weblogA), model, bean);

        assertEquals(".PageEdit", view);
        assertTrue(errors(model).stream().anyMatch(e -> e.equals("Taken by &lt;b&gt;travel&lt;/b&gt;.")),
                "the clashing name is shown, escaped: " + errors(model));
        assertFalse(errors(model).contains("pageEdit.error.slugReserved"));
    }

    /**
     * Characterisation test, written before save was split up and passing
     * against the old code: a clash on an existing page still shows that
     * page, and one on a new page shows none.
     */
    @Test
    void aSlugClashRedisplaysAnExistingPageButNoNewOne() throws Exception {
        doThrow(new com.wikantik.pressroom.business.SiteSlugClashException(
                "pageEdit.error.slugIsWeblog", "travel"))
                .when(weblogger.getWeblogPageManager()).savePage(any(), any(), any());
        WeblogPage existing = pageOn(weblogA, "about");
        PageBean edit = new PageBean();
        edit.setId(existing.getId());
        edit.setSlug("travel");
        edit.setTitle("About");

        controller.save(requestFor(weblogA), model, edit);
        assertEquals(existing, model.getAttribute("page"));

        PageBean create = new PageBean();
        create.setSlug("travel");
        create.setTitle("Travel");
        Model fresh = newModel();
        controller.save(requestFor(weblogA), fresh, create);
        assertNull(fresh.getAttribute("page"));
    }

    /** A new page left without a slug is addressed by its title, as a post is. */
    @Test
    void aNewPageWithABlankSlugTakesItsSlugFromTheTitle() throws Exception {
        PageBean bean = new PageBean();
        bean.setSlug("  ");
        bean.setTitle("Start Here, Please");

        controller.save(requestFor(weblogA), model, bean);

        org.mockito.ArgumentCaptor<WeblogPage> saved = org.mockito.ArgumentCaptor.forClass(WeblogPage.class);
        verify(weblogger.getWeblogPageManager()).savePage(saved.capture(), any(), any());
        assertEquals("start-here-please", saved.getValue().getSlug());
    }

    /** Emptying an existing page's slug is a mistake to report, never a silent rename. */
    @Test
    void anExistingPageWithABlankSlugIsNotRenamedFromItsTitle() throws Exception {
        WeblogPage existing = pageOn(weblogA, "about");

        PageBean bean = new PageBean();
        bean.setId(existing.getId());
        bean.setSlug("");
        bean.setTitle("About Us");

        controller.save(requestFor(weblogA), model, bean);

        assertEquals("", existing.getSlug(), "the blank slug reaches the manager, which refuses it");
    }

    @Test
    void savingWithABlankSlugIsAFieldErrorNotA500() throws Exception {
        doThrow(new WebloggerException("page slug is required"))
                .when(weblogger.getWeblogPageManager()).savePage(any(), any(), any());

        PageBean bean = new PageBean();
        bean.setSlug("");
        bean.setTitle("?!");

        String view = controller.save(requestFor(weblogA), model, bean);

        assertEquals(".PageEdit", view);
        assertTrue(errors(model).contains("pageEdit.error.slugInvalid"),
                "Expected a slugInvalid error, got: " + errors(model));
    }

    @Test
    void savingWithASlashInTheSlugIsAFieldErrorNotA500() throws Exception {
        doThrow(new WebloggerException("page slug may not contain '/': a/b"))
                .when(weblogger.getWeblogPageManager()).savePage(any(), any(), any());

        PageBean bean = new PageBean();
        bean.setSlug("a/b");
        bean.setTitle("Slashy");

        String view = controller.save(requestFor(weblogA), model, bean);

        assertEquals(".PageEdit", view);
        assertTrue(errors(model).contains("pageEdit.error.slugInvalid"),
                "Expected a slugInvalid error, got: " + errors(model));
    }

    @Test
    void savingWithAnUnrecognizedStatusSavesAsDraftRatherThanThrowing() throws Exception {
        // A crafted POST can carry any string in bean.status -- the dropdown
        // only ever offers DRAFT/PUBLISHED, but nothing stops a direct POST
        // of bean.status=BOGUS. PubStatus.valueOf throws IllegalArgumentException
        // on anything it does not recognize, and that must not reach the
        // caller as a 500.
        PageBean bean = new PageBean();
        bean.setSlug("about");
        bean.setTitle("About");
        bean.setStatus("BOGUS");

        String view = controller.save(requestFor(weblogA), model, bean);

        assertEquals(".PageEdit", view);
        assertTrue(errors(model).isEmpty(), "Expected no error, got: " + errors(model));

        org.mockito.ArgumentCaptor<WeblogPage> saved =
                org.mockito.ArgumentCaptor.forClass(WeblogPage.class);
        verify(weblogger.getWeblogPageManager()).savePage(saved.capture(), any(), any());
        assertEquals(WeblogPage.PubStatus.DRAFT, saved.getValue().getStatus());
    }

    @Test
    void anUnrelatedSaveFailureIsReportedGenerically() throws Exception {
        doThrow(new WebloggerException("database down"))
                .when(weblogger.getWeblogPageManager()).savePage(any(), any(), any());

        PageBean bean = new PageBean();
        bean.setSlug("about");
        bean.setTitle("About");

        controller.save(requestFor(weblogA), model, bean);

        assertTrue(errors(model).contains("generic.error.check.logs"),
                "Expected a generic failure, got: " + errors(model));
        assertTrue(messages(model).isEmpty(), "A failed save must not also report success");
    }

    @Test
    void aRejectedSlugOnAnExistingPageRedisplaysThatPage() throws Exception {
        // The blank-slug/new-page variants above never carry a "page" model
        // attribute back, since there is nothing looked up yet to redisplay;
        // an edit of an existing page must still show it after a rejected save.
        WeblogPage existing = pageOn(weblogA, "about");
        doThrow(new WebloggerException("page slug is reserved: feed"))
                .when(weblogger.getWeblogPageManager()).savePage(any(), any(), any());

        PageBean bean = new PageBean();
        bean.setId(existing.getId());
        bean.setSlug("feed");
        bean.setTitle("About");

        String view = controller.save(requestFor(weblogA), model, bean);

        assertEquals(".PageEdit", view);
        assertEquals(existing, model.getAttribute("page"),
                "the looked-up page must still be on the model after a rejected save");
    }

    @Test
    void anUnexpectedRuntimeExceptionDuringSaveIsReportedGenericallyToo() throws Exception {
        // The WebloggerException catch above handles the expected failure
        // modes (reserved/blank/malformed slug); this is the catch-all for
        // anything else savePage might throw, matching EntryEditController's
        // shape -- nothing here may turn into a 500.
        WeblogPage existing = pageOn(weblogA, "about");
        doThrow(new IllegalStateException("something unrelated broke"))
                .when(weblogger.getWeblogPageManager()).savePage(any(), any(), any());

        PageBean bean = new PageBean();
        bean.setId(existing.getId());
        bean.setSlug("about");
        bean.setTitle("About");

        String view = controller.save(requestFor(weblogA), model, bean);

        assertEquals(".PageEdit", view);
        assertTrue(errors(model).contains("generic.error.check.logs"),
                "Expected a generic failure, got: " + errors(model));
        assertEquals(existing, model.getAttribute("page"),
                "the looked-up page must still be on the model after the catch-all path too");
    }

    // --- SEO card's canonical URL ---

    @Test
    void aNonHttpCanonicalUrlIsRejectedAndNothingIsSaved() throws Exception {
        // A stored javascript:/data:/file: URL would be emitted straight into
        // <link rel="canonical">, og:url and JSON-LD mainEntityOfPage -- this
        // is the front door that keeps one from ever being saved in the
        // first place (PageModel#getCanonicalUrl is the back door, for rows
        // that predate this check).
        PageBean bean = new PageBean();
        bean.setSlug("about");
        bean.setTitle("About");
        bean.setCanonicalUrl("data:text/html,x");

        String view = controller.save(requestFor(weblogA), model, bean);

        assertEquals(".PageEdit", view, "a rejected canonical URL must redisplay the form");
        assertTrue(errors(model).contains("entryEdit.canonicalUrlInvalid"),
                "Expected a canonicalUrlInvalid error, got: " + errors(model));
        verify(weblogger.getWeblogPageManager(), never()).savePage(any(), any(), any());
    }

    @Test
    void anHttpsCanonicalUrlIsAccepted() throws Exception {
        PageBean bean = new PageBean();
        bean.setSlug("about");
        bean.setTitle("About");
        bean.setCanonicalUrl("https://example.com/x");

        controller.save(requestFor(weblogA), model, bean);

        assertTrue(errors(model).isEmpty(), "Expected no error, got: " + errors(model));
        verify(weblogger.getWeblogPageManager()).savePage(any(), any(), any());
    }

    @Test
    void aBlankCanonicalUrlIsAccepted() throws Exception {
        PageBean bean = new PageBean();
        bean.setSlug("about");
        bean.setTitle("About");
        bean.setCanonicalUrl("");

        controller.save(requestFor(weblogA), model, bean);

        assertTrue(errors(model).isEmpty(), "Expected no error, got: " + errors(model));
        verify(weblogger.getWeblogPageManager()).savePage(any(), any(), any());
    }

    // --- the social image: this weblog's, an image, outside private directories ---

    @Test
    void aSocialImageOfAnotherWeblogIsRefusedAndNothingIsSaved() throws Exception {
        imageOn(weblogB, "media-foreign", false);
        PageBean bean = new PageBean();
        bean.setSlug("about");
        bean.setTitle("About");
        bean.setOgImageId("media-foreign");

        String view = controller.save(requestFor(weblogA), model, bean);

        assertEquals(".PageEdit", view);
        assertTrue(errors(model).contains("weblogEdit.error.ogImageUnusable"),
                "Expected the social image to be refused, got: " + errors(model));
        verify(weblogger.getWeblogPageManager(), never()).savePage(any(), any(), any());
    }

    @Test
    void aSocialImageInAPrivateDirectoryIsRefusedOnAnExistingPage() throws Exception {
        WeblogPage page = pageOn(weblogA, "about");
        imageOn(weblogA, "media-private", true);
        PageBean bean = new PageBean();
        bean.setId(page.getId());
        bean.setSlug("about");
        bean.setTitle("About");
        bean.setOgImageId("media-private");

        controller.save(requestFor(weblogA), model, bean);

        assertTrue(errors(model).contains("weblogEdit.error.ogImageUnusable"), errors(model).toString());
        assertNull(page.getOgImageId(), "a refused save must leave the page as it was");
        assertEquals(page, model.getAttribute("page"), "the refused form still shows the page it edits");
        verify(weblogger.getWeblogPageManager(), never()).savePage(any(), any(), any());
    }

    @Test
    void aPublicImageOfThisWeblogIsSaved() throws Exception {
        imageOn(weblogA, "media-own", false);
        PageBean bean = new PageBean();
        bean.setSlug("about");
        bean.setTitle("About");
        bean.setOgImageId("media-own");

        controller.save(requestFor(weblogA), model, bean);

        assertTrue(errors(model).isEmpty(), "Expected no error, got: " + errors(model));
        verify(weblogger.getWeblogPageManager()).savePage(any(), any(), any());
    }

    @Test
    void anUnchangedStoredSocialImageIsNotRechecked() throws Exception {
        WeblogPage page = pageOn(weblogA, "about");
        page.setOgImageId("media-deleted");
        PageBean bean = new PageBean();
        bean.setId(page.getId());
        bean.setSlug("about");
        bean.setTitle("About");
        bean.setOgImageId("media-deleted");

        controller.save(requestFor(weblogA), model, bean);

        assertTrue(errors(model).isEmpty(), "Expected no error, got: " + errors(model));
        verify(weblogger.getWeblogPageManager()).savePage(any(), any(), any());
    }

    /** An image in a (private or public) directory of {@code owner}. */
    private void imageOn(Weblog owner, String id, boolean privateDirectory) throws WebloggerException {
        com.wikantik.pressroom.pojos.MediaFileDirectory directory =
                new com.wikantik.pressroom.pojos.MediaFileDirectory();
        directory.setWeblog(owner);
        directory.setPrivate(privateDirectory);
        com.wikantik.pressroom.pojos.MediaFile image = new com.wikantik.pressroom.pojos.MediaFile();
        image.setId(id);
        image.setDirectory(directory);
        image.setWeblog(owner);
        image.setContentType("image/png");
        when(weblogger.getMediaFileManager().getMediaFile(id)).thenReturn(image);
    }

    // --- SEO card's social-image thumbnail preview ---

    @Test
    void anOgImageBelongingToThisWeblogGetsAThumbnailPreview() throws Exception {
        com.wikantik.pressroom.pojos.MediaFile mediaFile =
                new com.wikantik.pressroom.pojos.MediaFile();
        mediaFile.setId("media-1");
        mediaFile.setWeblog(weblogA);
        mediaFile.setContentType("image/jpeg");
        when(weblogger.getMediaFileManager().getMediaFile("media-1")).thenReturn(mediaFile);
        when(weblogger.getUrlStrategy().getMediaFileThumbnailURL(weblogA, "media-1", true))
                .thenReturn("https://example.com/thumb.jpg");

        PageBean bean = new PageBean();
        bean.setSlug("about");
        bean.setTitle("About");
        bean.setOgImageId("media-1");

        String view = controller.save(requestFor(weblogA), model, bean);

        assertEquals(".PageEdit", view);
        assertEquals("https://example.com/thumb.jpg", model.getAttribute("ogImageThumbnailUrl"));
    }

    @Test
    void anOgImageBelongingToAnotherWeblogGetsNoThumbnailPreview() throws Exception {
        com.wikantik.pressroom.pojos.MediaFile foreign =
                new com.wikantik.pressroom.pojos.MediaFile();
        foreign.setId("media-2");
        foreign.setWeblog(weblogB);
        when(weblogger.getMediaFileManager().getMediaFile("media-2")).thenReturn(foreign);

        PageBean bean = new PageBean();
        bean.setSlug("about");
        bean.setTitle("About");
        bean.setOgImageId("media-2");

        controller.save(requestFor(weblogA), model, bean);

        assertNull(model.getAttribute("ogImageThumbnailUrl"),
                "an og image belonging to another weblog must not render a preview");
    }

    @Test
    void thePreviewFollowsTheFilesDirectoryOwnerLikeEveryOtherOwnershipCheck() throws Exception {
        // WeblogOwnership.mediaFile: a file's weblog is its directory's.
        com.wikantik.pressroom.pojos.MediaFileDirectory theirs =
                new com.wikantik.pressroom.pojos.MediaFileDirectory();
        theirs.setWeblog(weblogB);
        com.wikantik.pressroom.pojos.MediaFile file = new com.wikantik.pressroom.pojos.MediaFile();
        file.setId("media-4");
        file.setDirectory(theirs);
        file.setWeblog(weblogA);
        when(weblogger.getMediaFileManager().getMediaFile("media-4")).thenReturn(file);
        when(weblogger.getUrlStrategy().getMediaFileThumbnailURL(weblogA, "media-4", true))
                .thenReturn("https://example.com/thumb.jpg");
        WeblogPage page = pageOn(weblogA, "about");
        page.setOgImageId("media-4");

        controller.edit(page.getId(), requestFor(weblogA), model);

        assertNull(model.getAttribute("ogImageThumbnailUrl"),
                "a file in another weblog's directory gets no preview");
    }

    @Test
    void aFailedOgImageLookupSkipsThePreviewRatherThanFailingTheWholePage() throws Exception {
        when(weblogger.getMediaFileManager().getMediaFile("media-3"))
                .thenThrow(new WebloggerException("database down"));

        PageBean bean = new PageBean();
        bean.setSlug("about");
        bean.setTitle("About");
        bean.setOgImageId("media-3");

        String view = controller.save(requestFor(weblogA), model, bean);

        assertEquals(".PageEdit", view, "a failed thumbnail lookup must not stop the save from redisplaying");
        assertNull(model.getAttribute("ogImageThumbnailUrl"));
    }

    // --- checkbox field-marker binding (real Spring WebDataBinder) ---
    //
    // Every test above calls controller.save(...) directly with a hand-built
    // PageBean, bypassing Spring's WebDataBinder entirely -- so none of them
    // would have caught a bug in the marker convention itself. These two
    // build the exact ServletRequestDataBinder Spring MVC would construct
    // for a POST to pageEdit!save.rol (running it through
    // BaseController#initBeanBinder, the real @InitBinder) and bind a
    // request carrying PageEdit.jsp's actual marker input name -- read
    // straight out of the JSP so a regression to the wrong name fails here,
    // not only in the browser suite.

    @Test
    void uncheckingShowInNavBindsToFalseThroughTheRealBinder() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/pressroom-ui/authoring/pageEdit!save.rol");
        request.setParameter("bean.slug", "about");
        request.setParameter("bean.title", "About Us");
        // The box was unchecked: the browser omits "bean.showInNav" entirely,
        // submitting only the marker PageEdit.jsp always renders.
        request.setParameter(showInNavMarkerName(), "on");

        PageBean bean = new PageBean();
        ServletRequestDataBinder binder = new ServletRequestDataBinder(bean, "bean");
        controller.initBeanBinder(binder);
        binder.bind(request);

        assertEquals(false, bean.getShowInNav(),
                "an unchecked box must bind to false -- PageBean defaults to "
                        + "true, so a marker name that does not round-trip "
                        + "through BaseController's \"bean.\" field-default "
                        + "prefix leaves nav on forever (see "
                        + "BaseController#initBeanBinder)");
    }

    @Test
    void checkingShowInNavBindsToTrueThroughTheRealBinder() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/pressroom-ui/authoring/pageEdit!save.rol");
        request.setParameter("bean.slug", "about");
        request.setParameter("bean.title", "About Us");
        request.setParameter("bean.showInNav", "true");
        request.setParameter(showInNavMarkerName(), "on");

        PageBean bean = new PageBean();
        bean.setShowInNav(false);
        ServletRequestDataBinder binder = new ServletRequestDataBinder(bean, "bean");
        controller.initBeanBinder(binder);
        binder.bind(request);

        assertEquals(true, bean.getShowInNav());
    }

    /**
     * The {@code name} of PageEdit.jsp's showInNav field-marker hidden
     * input, read from the JSP itself rather than hardcoded, so these tests
     * fail if the marker is ever renamed back to the broken
     * {@code "_bean.showInNav"} (see BaseController#initBeanBinder).
     */
    private static String showInNavMarkerName() throws IOException {
        Path jsp = Paths.get("src/main/webapp/WEB-INF/jsps/editor/PageEdit.jsp");
        String content = Files.readString(jsp, StandardCharsets.UTF_8);
        Matcher matcher = Pattern.compile("name=\"(_[^\"]*[Ss]howInNav[^\"]*)\"").matcher(content);
        assertTrue(matcher.find(), "PageEdit.jsp must carry a showInNav field-marker hidden input");
        return matcher.group(1);
    }

    @Test
    void theModelAttributeFactoryHandsSpringAFreshBean() {
        // @ModelAttribute("bean") is what Spring calls to seed the form on a
        // GET that supplies no bean of its own; every test above builds a
        // PageBean by hand instead; this is the seam itself.
        assertNotNull(controller.getBean());
    }

    // --- PageBean ---

    @Test
    void beanCopiesRoundTrip() {
        WeblogPage page = new WeblogPage();
        page.setId("page-1");
        page.setWeblog(weblogA);
        page.setSlug("about");
        page.setTitle("About");
        page.setContent("body text");
        page.setStatus(WeblogPage.PubStatus.PUBLISHED);
        page.setShowInNav(false);
        page.setNavOrder(3);
        page.setMetaTitle("Meta Title");
        page.setSearchDescription("desc");
        page.setCanonicalUrl("https://example.com/about");
        page.setNoindex(true);
        page.setOgImageId("media-1");

        PageBean bean = new PageBean();
        bean.copyFrom(page);

        assertEquals("page-1", bean.getId());
        assertEquals("about", bean.getSlug());
        assertEquals("About", bean.getTitle());
        assertEquals("body text", bean.getContent());
        assertEquals("PUBLISHED", bean.getStatus());
        assertEquals(false, bean.getShowInNav());
        assertEquals(3, bean.getNavOrder());
        assertEquals("Meta Title", bean.getMetaTitle());
        assertEquals("desc", bean.getSearchDescription());
        assertEquals("https://example.com/about", bean.getCanonicalUrl());
        assertEquals(true, bean.getNoindex());
        assertEquals("media-1", bean.getOgImageId());

        WeblogPage target = new WeblogPage();
        bean.copyTo(target);

        assertEquals("about", target.getSlug());
        assertEquals("About", target.getTitle());
        assertEquals("body text", target.getContent());
        assertEquals(WeblogPage.PubStatus.PUBLISHED, target.getStatus());
        assertEquals(false, target.getShowInNav());
        assertEquals(3, target.getNavOrder());
        assertEquals("Meta Title", target.getMetaTitle());
        assertEquals("desc", target.getSearchDescription());
        assertEquals("https://example.com/about", target.getCanonicalUrl());
        assertEquals(true, target.getNoindex());
        assertEquals("media-1", target.getOgImageId());
    }

    @Test
    void copyToDefaultsABlankStatusToDraftRatherThanThrowing() {
        PageBean bean = new PageBean();
        bean.setStatus(null);
        WeblogPage page = new WeblogPage();

        bean.copyTo(page);

        assertEquals(WeblogPage.PubStatus.DRAFT, page.getStatus());
    }

    @Test
    void copyToDefaultsAnUnrecognizedStatusToDraftRatherThanThrowing() {
        // The dropdown only ever offers DRAFT/PUBLISHED, but the bean is
        // bound from raw POST data, so PubStatus.valueOf's
        // IllegalArgumentException on anything else must be caught here, not
        // just the blank case.
        PageBean bean = new PageBean();
        bean.setStatus("BOGUS");
        WeblogPage page = new WeblogPage();

        bean.copyTo(page);

        assertEquals(WeblogPage.PubStatus.DRAFT, page.getStatus());
    }

    @Test
    void everySeoFieldSetterRoundTripsIndependentlyOfCopyFromAndCopyTo() {
        // beanCopiesRoundTrip above exercises these getters, but only through
        // copyFrom, which assigns the underlying fields directly rather than
        // through the bean's own setters -- Spring's data binder is what
        // actually calls them on a real POST, so each one needs its own
        // direct call to be reachable at all.
        PageBean bean = new PageBean();

        bean.setNavOrder(5);
        bean.setMetaTitle("A title");
        bean.setSearchDescription("A description");
        bean.setCanonicalUrl("https://example.com/canonical");
        bean.setNoindex(true);
        bean.setOgImageId("media-9");

        assertEquals(5, bean.getNavOrder());
        assertEquals("A title", bean.getMetaTitle());
        assertEquals("A description", bean.getSearchDescription());
        assertEquals("https://example.com/canonical", bean.getCanonicalUrl());
        assertEquals(true, bean.getNoindex());
        assertEquals("media-9", bean.getOgImageId());
    }

    // --- fixtures ---


    // ------------------------------------------------------- live preview

    /**
     * The page editor previews through the same pipeline the published page
     * renders with -- shortcodes, then Markdown, then the sanitizer. A
     * Markdown library in the browser could not expand {@code [gallery]} or
     * {@code [map]}, and a preview that disagreed with the page would mislead
     * exactly where an author needs to trust it.
     */
    @Test
    void previewRunsUnsavedTextThroughTheRealPipeline() {
        var response = controller.pageEditPreview(requestFor(weblogA), null,
                "## Opening hours\n\nWe open **early**.");

        assertEquals(200, response.getStatusCode().value());
        String html = response.getBody();
        assertTrue(html.contains("<h2 id=\"opening-hours\">Opening hours</h2>"), html);
        assertTrue(html.contains("<strong>early</strong>"), html);
    }

    @Test
    void previewSanitizesWhatItRenders() {
        // commonmark passes raw HTML through by design, and the preview frame
        // is same-origin with the admin page.
        var response = controller.pageEditPreview(requestFor(weblogA), null,
                "before\n\n<script>alert(1)</script>\n");

        assertFalse(response.getBody().contains("<script"), response.getBody());
    }

    @Test
    void previewOfAPageBelongingToAnotherWeblogIsNotFound() throws Exception {
        // Same ownership rule as edit() and save(): the text comes from the
        // request, but a supplied id must name a page on the action weblog.
        // Without this, previewing is a way to ask what another weblog's
        // shortcodes resolve to -- its media, its geo, its slug.
        WeblogPage foreign = pageOn(weblogB, "their-about");

        var response = controller.pageEditPreview(requestFor(weblogA), foreign.getId(), "anything");

        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    void previewOfAnOwnedPageRendersInThatPagesOwnContext() throws Exception {
        // The renderer takes a ShortcodeContext, and the slug is part of it
        // ([cta]'s UTM campaign). A preview built on a scratch page with no
        // slug would quietly render different links than the saved page does.
        WeblogPage owned = pageOn(weblogA, "about");

        var response = controller.pageEditPreview(requestFor(weblogA), owned.getId(),
                "[cta href=\"https://example.com/book\" label=\"Book\"]");

        assertEquals(200, response.getStatusCode().value());
        // The sanitizer re-encodes "=" inside an href as &#61;, so compare
        // against the decoded form rather than pinning that encoding here.
        assertTrue(response.getBody().replace("&#61;", "=").contains("utm_campaign=about"),
                "the page's own slug must reach the shortcode context, got: "
                        + response.getBody());
    }

    // --------------------------------------------------- the preview shell

    /**
     * The preview pane frames the weblog's own theme rather than dumping the
     * rendered fragment into a div, so the page needs the shell's URL -- the
     * same attribute {@code EntryEditController} publishes, through the same
     * {@code BaseController} helper, so the two editors cannot drift.
     */
    @Test
    void theEditorIsToldWhereThePreviewShellLives() throws Exception {
        // The context URL is a process-global static, so pin it rather than
        // asserting against whatever an earlier test class left behind.
        String previous = WebloggerRuntimeConfig.getRelativeContextURL();
        WebloggerRuntimeConfig.setRelativeContextURL("");
        try {
            controller.edit(null, requestFor(weblogA), model);

            assertEquals("/pressroom-ui/authoring/preview/" + WEBLOG_HANDLE + "/?shell=true",
                    model.getAttribute("previewShellURL"));
        } finally {
            WebloggerRuntimeConfig.setRelativeContextURL(previous);
        }
    }

    @Test
    void thePreviewShellUrlCarriesTheServletContextPath() throws Exception {
        // An absolute-root iframe src under a servlet prefix loads the site
        // root instead -- the trap ContactShortcode already hit.
        String previous = WebloggerRuntimeConfig.getRelativeContextURL();
        WebloggerRuntimeConfig.setRelativeContextURL("/pressroom");
        try {
            controller.edit(null, requestFor(weblogA), model);

            assertEquals("/pressroom/pressroom-ui/authoring/preview/" + WEBLOG_HANDLE + "/?shell=true",
                    model.getAttribute("previewShellURL"));
        } finally {
            WebloggerRuntimeConfig.setRelativeContextURL(previous);
        }
    }

    private HttpServletRequest requestFor(Weblog weblog) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getLocale()).thenReturn(Locale.US);
        when(req.getAttribute("authenticatedUser")).thenReturn(user);
        when(req.getAttribute("actionWeblog")).thenReturn(weblog);
        return req;
    }

    /** A page owned by {@code weblog}, resolvable by its global id. */
    private WeblogPage pageOn(Weblog weblog, String slug) throws WebloggerException {
        WeblogPage page = new WeblogPage();
        page.setId("page-" + slug);
        page.setWeblog(weblog);
        page.setSlug(slug);
        when(weblogger.getWeblogPageManager().getPage(page.getId())).thenReturn(page);
        return page;
    }

    // --- errors point at the field they name (B8) ---

    @Test
    void aReservedSlugMarksTheSlugField() throws Exception {
        doThrow(new WebloggerException("page slug is reserved: feed"))
                .when(weblogger.getWeblogPageManager()).savePage(any(), any(), any());
        PageBean bean = new PageBean();
        bean.setSlug("feed");
        bean.setTitle("Feed");

        controller.save(requestFor(weblogA), model, bean);

        assertEquals(List.of("page_bean_slug"), invalidFields(model));
    }

    @Test
    void aRefusedCanonicalUrlMarksTheSeoFieldAndNotTheSlug() throws Exception {
        // The canonical URL lives in the collapsed SEO drawer, which is
        // exactly the field a top-of-page banner is worst at pointing at.
        PageBean bean = new PageBean();
        bean.setSlug("about");
        bean.setTitle("About");
        bean.setCanonicalUrl("data:text/html,x");

        controller.save(requestFor(weblogA), model, bean);

        assertEquals(List.of("seo_canonicalUrl"), invalidFields(model));
    }
}

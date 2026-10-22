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

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.pojos.MediaFile;
import org.apache.roller.weblogger.pojos.MediaFileDirectory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ui.Model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link MediaFileViewController} and the shared operations it
 * inherits from {@link MediaFileBase}.
 *
 * <p>The destructive operations here — delete, delete-folder, bulk delete, bulk
 * move — are the ones worth pinning. A bulk delete that silently skips a file
 * still reports success; a move that does not check the current directory
 * churns every file in the list. The directory-name validation matters because
 * folder names become path segments.
 */
class MediaFileViewControllerTest extends EditorControllerTestSupport {

    private MediaFileViewController controller;
    private Model model;
    private MediaFileDirectory defaultDirectory;
    private MediaFileDirectory targetDirectory;

    @BeforeEach
    void setUp() throws Exception {
        controller = prepare(new MediaFileViewController());
        model = newModel();

        defaultDirectory = directory("dir-1", "default");
        targetDirectory = directory("dir-2", "photos");

        when(weblogger.getMediaFileManager().getDefaultMediaFileDirectory(weblog))
                .thenReturn(defaultDirectory);
        when(weblogger.getMediaFileManager().getMediaFileDirectory("dir-1"))
                .thenReturn(defaultDirectory);
        when(weblogger.getMediaFileManager().getMediaFileDirectories(weblog))
                .thenReturn(List.of(defaultDirectory, targetDirectory));
    }

    // --- browsing ---

    /**
     * The view page's own form posts back to the browse URL: the sort-by
     * menu's onchange handler and the edit modal's {@code onEditSuccess()}
     * callback both call {@code document.mediaFileViewForm.submit()}, and the
     * form is declared {@code method="post"}. Struts answered any verb; a
     * GET-only Spring mapping turns every one of those submits into a 405 --
     * GalleryIT's caption journey caught it live. The route must therefore
     * declare both verbs, the same dual-verb {@code @RequestMapping} shape as
     * entryAddWithMediaFile.rol (which RouteCoverageTest already understands).
     */
    @Test
    void theViewFormCanPostBackToTheBrowseUrl() throws Exception {
        java.lang.reflect.Method execute = MediaFileViewController.class.getMethod(
                "execute", jakarta.servlet.http.HttpServletRequest.class, Model.class,
                String.class, String.class, String.class);
        org.springframework.web.bind.annotation.RequestMapping mapping =
                execute.getAnnotation(org.springframework.web.bind.annotation.RequestMapping.class);
        assertTrue(mapping != null,
                "execute must be mapped with @RequestMapping so it can declare both verbs");
        assertEquals(List.of("/mediaFileView.rol"), List.of(mapping.value()));
        Set<org.springframework.web.bind.annotation.RequestMethod> verbs =
                new LinkedHashSet<>(List.of(mapping.method()));
        assertTrue(verbs.contains(org.springframework.web.bind.annotation.RequestMethod.GET),
                "browsing is a GET; got " + verbs);
        assertTrue(verbs.contains(org.springframework.web.bind.annotation.RequestMethod.POST),
                "the view form posts back to this URL; got " + verbs);
    }

    @Test
    void browsingWithNoDirectoryChosenShowsTheDefaultOne() {
        String view = controller.execute(request, model, null, null, null);

        assertEquals(".MediaFileView", view);
        assertEquals(defaultDirectory, model.getAttribute("currentDirectory"));
        assertEquals("dir-1", model.getAttribute("directoryId"));
    }

    @Test
    void browsingByDirectoryNameResolvesThatDirectory() throws Exception {
        when(weblogger.getMediaFileManager().getMediaFileDirectoryByName(weblog, "photos"))
                .thenReturn(targetDirectory);

        controller.execute(request, model, null, "photos", null);

        assertEquals(targetDirectory, model.getAttribute("currentDirectory"));
    }

    @Test
    void anExplicitDirectoryIdWinsOverADirectoryName() throws Exception {
        // Both arrive from the same page; the id is the unambiguous one.
        when(weblogger.getMediaFileManager().getMediaFileDirectory("dir-2"))
                .thenReturn(targetDirectory);

        controller.execute(request, model, "dir-2", "default", null);

        assertEquals(targetDirectory, model.getAttribute("currentDirectory"));
        verify(weblogger.getMediaFileManager(), never()).getMediaFileDirectoryByName(any(), any());
    }

    @Test
    void anUnrecognisedSortOrderFallsBackToSortingByName() {
        // The sort key is echoed back into the page as the selected dropdown
        // value, so it must be normalised rather than passed through.
        controller.execute(request, model, null, null, "; DROP TABLE");

        assertEquals("name", model.getAttribute("sortBy"),
                "An unknown sort option must be normalised to the default");
    }

    @Test
    void theChosenSortOrderIsPreservedWhenItIsRecognised() {
        controller.execute(request, model, null, null, "date_uploaded");

        assertEquals("date_uploaded", model.getAttribute("sortBy"));
    }

    @Test
    void filesAreOrderedByTheChosenSortKey() throws Exception {
        // The page has no client-side sorting, so this ordering is what the user
        // sees. Each key has its own comparator and they are easy to transpose.
        // Chosen so the two orderings genuinely disagree: by name alpha comes
        // first, by content type beta ("application/pdf") does.
        MediaFile alpha = mediaFile("file-a", "alpha.jpg");
        MediaFile beta = mediaFile("file-b", "beta.pdf");
        alpha.setContentType("image/jpeg");
        beta.setContentType("application/pdf");
        defaultDirectory.getMediaFiles().add(beta);
        defaultDirectory.getMediaFiles().add(alpha);

        controller.execute(request, model, null, null, "name");
        assertEquals(List.of(alpha, beta), model.getAttribute("childFiles"),
                "The default ordering must be alphabetical by name");

        model = newModel();
        controller.execute(request, model, null, null, "type");
        assertEquals(List.of(beta, alpha), model.getAttribute("childFiles"),
                "Sorting by type must order on content type, not fall back to name");
    }

    @Test
    void theSearchFormOffersEveryFilterOptionTheBeanCanMap() throws Exception {
        // MediaFileSearchBean maps five size comparisons, five file types and
        // three units; an option missing from these lists is a filter the user
        // simply cannot reach.
        controller.execute(request, model, null, null, null);

        assertEquals(5, ((List<?>) model.getAttribute("fileTypes")).size());
        assertEquals(5, ((List<?>) model.getAttribute("sizeFilterTypes")).size());
        assertEquals(3, ((List<?>) model.getAttribute("sizeUnits")).size());
        assertEquals(3, ((List<?>) model.getAttribute("sortOptions")).size());
    }

    @Test
    void destructiveOperationsAreCommitted() throws Exception {
        // Without a flush the delete never reaches the database, yet the page
        // still reports success.
        MediaFile file = mediaFile("file-1", "photo.jpg");
        when(weblogger.getMediaFileManager().getMediaFile("file-1")).thenReturn(file);

        controller.delete(request, model, "dir-1", "file-1", null);

        assertTrue(weblogger.flushCount() > 0, "A delete must be committed");
    }

    @Test
    void aFailureLoadingTheDirectoryIsReportedRatherThanBlowingUpThePage() throws Exception {
        when(weblogger.getMediaFileManager().getDefaultMediaFileDirectory(weblog))
                .thenThrow(new WebloggerException("database down"));

        String view = controller.execute(request, model, null, null, null);

        assertEquals(".MediaFileView", view);
        assertTrue(errors(model).contains("MediaFile.error.view"),
                "Expected a view error, got: " + errors(model));
    }

    // --- creating directories ---

    @Test
    void creatingADirectoryWithNoNameIsRefused() {
        controller.createNewDirectory(request, model, null, "", null);

        assertTrue(errors(model).contains("mediaFile.error.view.dirNameEmpty"),
                "Expected an empty-name error, got: " + errors(model));
    }

    @Test
    void creatingADirectoryWithASlashInTheNameIsRefused() throws Exception {
        // Directory names become path segments under the weblog's upload root.
        controller.createNewDirectory(request, model, null, "photos/2024", null);

        assertTrue(errors(model).contains("mediaFile.error.view.dirNameInvalid"),
                "Expected an invalid-name error, got: " + errors(model));
        verify(weblogger.getMediaFileManager(), never()).createMediaFileDirectory(any(), any());
    }

    @Test
    void creatingADirectoryThatAlreadyExistsIsReportedAndNotDuplicated() throws Exception {
        weblog.getMediaFileDirectories().add(targetDirectory);

        controller.createNewDirectory(request, model, null, "photos", null);

        verify(weblogger.getMediaFileManager(), never()).createMediaFileDirectory(any(), any());
        assertTrue(errors(model).contains("mediaFile.directoryCreate.error.exists"),
                "A name collision is a refusal -- the folder was NOT created -- so it has to "
                        + "render in the error banner, not the green one: " + errors(model));
        assertFalse(messages(model).contains("mediaFile.directoryCreate.error.exists"),
                "The collision notice must not also appear among the successes");
    }

    @Test
    void aDirectoryCollisionEscapesTheSubmittedNameInTheErrorMessage() throws Exception {
        // The submitted directory name is user-typed and lands in
        // messages.jsp's deliberately-raw sink -- an unescaped name is
        // stored HTML in the error banner.
        registerMessage("mediaFile.directoryCreate.error.exists", "exists:{0}");
        // No literal "/" -- createNewDirectory refuses that shape as an
        // invalid name (dirNameInvalid) before it ever reaches the collision
        // check this test is pinning.
        weblog.getMediaFileDirectories().add(directory("dir-3", "<script>photos"));

        controller.createNewDirectory(request, model, null, "<script>photos", null);

        assertTrue(errors(model).contains("exists:&lt;script&gt;photos"),
                "Expected the directory name HTML-escaped, got: " + errors(model));
    }

    @Test
    void creatingADirectorySwitchesTheViewToTheNewFolder() throws Exception {
        MediaFileDirectory created = directory("dir-3", "trips");
        when(weblogger.getMediaFileManager().createMediaFileDirectory(weblog, "trips"))
                .thenReturn(created);
        when(weblogger.getMediaFileManager().getMediaFileDirectory("dir-3")).thenReturn(created);

        controller.createNewDirectory(request, model, null, "trips", null);

        assertTrue(messages(model).contains("mediaFile.directoryCreate.success"),
                "Expected a success notice, got: " + messages(model));
        assertEquals(created, model.getAttribute("currentDirectory"),
                "After creating a folder the user must be looking at it, not back at the default");
    }

    @Test
    void creatingADirectoryEscapesTheSubmittedNameInTheSuccessMessage() throws Exception {
        // The submitted directory name is user-typed and lands in
        // messages.jsp's deliberately-raw sink, the same as the collision
        // error above -- an unescaped name here is stored HTML in the
        // success banner.
        registerMessage("mediaFile.directoryCreate.success", "created:{0}");
        // No literal "/" -- createNewDirectory refuses that shape as an
        // invalid name (dirNameInvalid) before it ever reaches the create
        // this test is pinning, same constraint the collision test above
        // works around.
        MediaFileDirectory created = directory("dir-3", "<script>trips");
        when(weblogger.getMediaFileManager().createMediaFileDirectory(weblog, "<script>trips"))
                .thenReturn(created);
        when(weblogger.getMediaFileManager().getMediaFileDirectory("dir-3")).thenReturn(created);

        controller.createNewDirectory(request, model, null, "<script>trips", null);

        assertTrue(messages(model).contains("created:&lt;script&gt;trips"),
                "Expected the directory name HTML-escaped, got: " + messages(model));
    }

    // --- deleting ---

    @Test
    void deletingAFileRemovesItAndConfirms() throws Exception {
        MediaFile file = mediaFile("file-1", "photo.jpg");
        when(weblogger.getMediaFileManager().getMediaFile("file-1")).thenReturn(file);

        controller.delete(request, model, "dir-1", "file-1", null);

        verify(weblogger.getMediaFileManager()).removeMediaFile(weblog, file);
        assertTrue(messages(model).contains("mediaFile.delete.success"),
                "Expected a delete confirmation, got: " + messages(model));
    }

    @Test
    void aFailedDeleteIsReportedRatherThanConfirmed() throws Exception {
        when(weblogger.getMediaFileManager().getMediaFile("file-1"))
                .thenThrow(new WebloggerException("no such file"));

        controller.delete(request, model, "dir-1", "file-1", null);

        assertTrue(errors(model).contains("mediaFile.delete.error.single"),
                "A single-file delete must report the singular copy, not the bulk "
                        + "\"selected media files\" one, got: " + errors(model));
        assertTrue(messages(model).isEmpty(),
                "A failed delete must not also report success");
    }

    @Test
    void bulkDeleteRemovesEverySelectedFile() throws Exception {
        MediaFile first = mediaFile("file-1", "a.jpg");
        MediaFile second = mediaFile("file-2", "b.jpg");
        when(weblogger.getMediaFileManager().getMediaFile("file-1")).thenReturn(first);
        when(weblogger.getMediaFileManager().getMediaFile("file-2")).thenReturn(second);

        controller.deleteSelected(request, model, "dir-1", new String[]{"file-1", "file-2"}, null);

        verify(weblogger.getMediaFileManager()).removeMediaFile(weblog, first);
        verify(weblogger.getMediaFileManager()).removeMediaFile(weblog, second);
    }

    @Test
    void bulkDeleteSkipsIdsThatNoLongerResolve() throws Exception {
        // A stale page can post an id that has already been deleted; that must
        // not take out the rest of the batch with an NPE.
        MediaFile survivor = mediaFile("file-2", "b.jpg");
        when(weblogger.getMediaFileManager().getMediaFile("file-1")).thenReturn(null);
        when(weblogger.getMediaFileManager().getMediaFile("file-2")).thenReturn(survivor);

        controller.deleteSelected(request, model, "dir-1", new String[]{"file-1", "file-2"}, null);

        verify(weblogger.getMediaFileManager()).removeMediaFile(weblog, survivor);
    }

    @Test
    void bulkDeleteWithNothingSelectedRemovesNothing() throws Exception {
        controller.deleteSelected(request, model, "dir-1", new String[0], null);

        verify(weblogger.getMediaFileManager(), never()).removeMediaFile(any(), any());
    }

    @Test
    void deletingAFolderSendsTheUserBackToTheDefaultFolder() throws Exception {
        // The folder they were looking at is gone, so the view has to land
        // somewhere that still exists.
        when(weblogger.getMediaFileManager().getMediaFileDirectory("dir-2"))
                .thenReturn(targetDirectory);

        controller.deleteFolder(request, model, "dir-2", null, null);

        verify(weblogger.getMediaFileManager()).removeMediaFileDirectory(targetDirectory);
        assertTrue(messages(model).contains("mediaFile.deleteFolder.success"),
                "Expected a folder-delete confirmation, got: " + messages(model));
        assertEquals(defaultDirectory, model.getAttribute("currentDirectory"));
    }

    @Test
    void deletingAFolderWithNoIdDoesNothing() throws Exception {
        controller.deleteFolder(request, model, null, null, null);

        verify(weblogger.getMediaFileManager(), never()).removeMediaFileDirectory(any());
    }

    // --- moving ---

    @Test
    void movingSelectedFilesRelocatesThemToTheChosenFolder() throws Exception {
        MediaFile file = mediaFile("file-1", "photo.jpg");
        file.setDirectory(defaultDirectory);
        when(weblogger.getMediaFileManager().getMediaFile("file-1")).thenReturn(file);
        when(weblogger.getMediaFileManager().getMediaFileDirectory("dir-2"))
                .thenReturn(targetDirectory);

        controller.moveSelected(request, model, "dir-1", new String[]{"file-1"}, "dir-2", null);

        verify(weblogger.getMediaFileManager()).moveMediaFile(file, targetDirectory);
        assertTrue(messages(model).contains("mediaFile.move.success"),
                "Expected a move confirmation, got: " + messages(model));
    }

    @Test
    void movingAFileIntoTheFolderItIsAlreadyInIsANoOp() throws Exception {
        // Without this check every "move" would rewrite the file on disk, and
        // the success message would be reported for a move that did nothing.
        MediaFile file = mediaFile("file-1", "photo.jpg");
        file.setDirectory(defaultDirectory);
        when(weblogger.getMediaFileManager().getMediaFile("file-1")).thenReturn(file);
        when(weblogger.getMediaFileManager().getMediaFileDirectory("dir-1"))
                .thenReturn(defaultDirectory);

        controller.moveSelected(request, model, "dir-1", new String[]{"file-1"}, "dir-1", null);

        verify(weblogger.getMediaFileManager(), never()).moveMediaFile(any(), any());
        assertTrue(messages(model).isEmpty(),
                "Nothing moved, so nothing should be reported: " + messages(model));
    }

    @Test
    void aFailedMoveIsReported() throws Exception {
        MediaFile file = mediaFile("file-1", "photo.jpg");
        file.setDirectory(defaultDirectory);
        when(weblogger.getMediaFileManager().getMediaFile("file-1")).thenReturn(file);
        when(weblogger.getMediaFileManager().getMediaFileDirectory("dir-2"))
                .thenReturn(targetDirectory);
        org.mockito.Mockito.doThrow(new WebloggerException("disk full"))
                .when(weblogger.getMediaFileManager()).moveMediaFile(any(), any());

        controller.moveSelected(request, model, "dir-1", new String[]{"file-1"}, "dir-2", null);

        assertTrue(errors(model).contains("mediaFile.move.errors"),
                "Expected a move error, got: " + errors(model));
    }

    // --- searching ---

    @Test
    void searchingWithNoCriteriaAtAllIsRefused() {
        // An unrestricted search would return the whole library and paginate
        // through it; the form requires at least one filter.
        MediaFileSearchBean bean = new MediaFileSearchBean();

        String view = controller.search(request, model, bean, "dir-1", null);

        assertEquals(".MediaFileView", view);
        assertTrue(errors(model).contains("MediaFile.error.search.empty"),
                "Expected an empty-search error, got: " + errors(model));
    }

    @Test
    void searchingByNameProducesAPagerOfResults() throws Exception {
        MediaFileSearchBean bean = new MediaFileSearchBean();
        bean.setName("photo");
        when(weblogger.getMediaFileManager().searchMediaFiles(any(), any()))
                .thenReturn(List.of(mediaFile("file-1", "photo.jpg")));

        controller.search(request, model, bean, "dir-1", null);

        assertTrue(errors(model).isEmpty(), "Search should have succeeded: " + errors(model));
        assertEquals(1, ((org.apache.roller.weblogger.ui.controllers.pagers.MediaFilePager)
                model.getAttribute("pager")).getItems().size());
    }

    @Test
    void aFullPageOfResultsIsTrimmedAndFlaggedAsHavingMore() throws Exception {
        // The manager is asked for PAGE_SIZE+1 rows precisely so the controller
        // can tell whether a next page exists; the extra row must not be shown.
        MediaFileSearchBean bean = new MediaFileSearchBean();
        bean.setName("photo");
        java.util.List<MediaFile> overflowing = new java.util.ArrayList<>();
        for (int i = 0; i <= MediaFileSearchBean.PAGE_SIZE; i++) {
            overflowing.add(mediaFile("file-" + i, "photo" + i + ".jpg"));
        }
        when(weblogger.getMediaFileManager().searchMediaFiles(any(), any())).thenReturn(overflowing);

        controller.search(request, model, bean, "dir-1", null);

        org.apache.roller.weblogger.ui.controllers.pagers.MediaFilePager pager =
                (org.apache.roller.weblogger.ui.controllers.pagers.MediaFilePager)
                        model.getAttribute("pager");
        assertEquals(MediaFileSearchBean.PAGE_SIZE, pager.getItems().size(),
                "The lookahead row must be trimmed off before display");
    }

    @Test
    void aFailedSearchIsReported() throws Exception {
        MediaFileSearchBean bean = new MediaFileSearchBean();
        bean.setName("photo");
        when(weblogger.getMediaFileManager().searchMediaFiles(any(), any()))
                .thenThrow(new WebloggerException("index corrupt"));

        controller.search(request, model, bean, "dir-1", null);

        assertEquals(1, errors(model).size(),
                "Expected the search failure to be surfaced, got: " + errors(model));
    }

    // --- directory privacy ---

    @Test
    void togglingPrivacyFlipsTheFlagAndConfirms() throws Exception {
        when(weblogger.getMediaFileManager().getMediaFileDirectory("dir-2"))
                .thenReturn(targetDirectory);
        assertTrue(!targetDirectory.isPrivate());

        controller.togglePrivate(request, model, "dir-2", null);
        assertTrue(targetDirectory.isPrivate(),
                "the toggle must flip a public directory to private");
        assertTrue(messages(model).contains("mediaFile.privacy.updated"));

        controller.togglePrivate(request, model, "dir-2", null);
        assertTrue(!targetDirectory.isPrivate(),
                "a second toggle must flip it back");
    }

    /**
     * Rendered pages and the sitemap expire only through
     * {@code weblog.lastModified} -- {@code CacheManager.invalidate} reaches
     * neither -- so a toggle that does not save the weblog leaves a private
     * directory's images in every cached gallery and in the cached sitemap.
     */
    @Test
    void togglingPrivacySavesTheWeblogSoCachedPagesAndTheSitemapExpire() throws Exception {
        when(weblogger.getMediaFileManager().getMediaFileDirectory("dir-2"))
                .thenReturn(targetDirectory);

        controller.togglePrivate(request, model, "dir-2", null);

        verify(weblogger.getWeblogManager()).saveWeblog(weblog);
    }

    @Test
    void aDirectoryFromAnotherWeblogsPrivacyCannotBeToggled() throws Exception {
        MediaFileDirectory foreign = directory("dir-x", "theirs");
        org.apache.roller.weblogger.pojos.Weblog other =
                new org.apache.roller.weblogger.pojos.Weblog();
        other.setId("weblog-2");
        other.setHandle("otherblog");
        foreign.setWeblog(other);
        when(weblogger.getMediaFileManager().getMediaFileDirectory("dir-x"))
                .thenReturn(foreign);

        controller.togglePrivate(request, model, "dir-x", null);

        assertTrue(!foreign.isPrivate(), "a cross-weblog directoryId must be refused");
        assertTrue(errors(model).contains("mediaFile.privacy.error"),
                "the refusal must be visible rather than silent: " + errors(model));
    }

    @Test
    void aPrivacyToggleThatBlowsUpReportsRatherThanClaimingSuccess() throws Exception {
        when(weblogger.getMediaFileManager().getMediaFileDirectory("dir-2"))
                .thenThrow(new WebloggerException("directory read failed"));

        controller.togglePrivate(request, model, "dir-2", null);

        assertTrue(errors(model).contains("mediaFile.privacy.error"),
                "a failed toggle must surface an error: " + errors(model));
        assertTrue(!messages(model).contains("mediaFile.privacy.updated"),
                "a failed toggle must not also confirm success: " + messages(model));
    }

    // --- cross-weblog ids ---
    //
    // Every id on this screen arrives from the client and every lookup behind
    // it (getMediaFile / getMediaFileDirectory) is a global by-id lookup, so
    // the weblog boundary has to be re-established in the controller. An
    // editor on weblog A holding a weblog B id must get nothing: no model
    // population, and no mutation.

    @Test
    void aDirectoryFromAnotherWeblogIsNotLoadedIntoTheView() throws Exception {
        MediaFileDirectory foreign = foreignDirectory("dir-x", "theirs");
        foreign.getMediaFiles().add(mediaFile("file-x", "their-photo.jpg"));
        when(weblogger.getMediaFileManager().getMediaFileDirectory("dir-x")).thenReturn(foreign);

        controller.execute(request, model, "dir-x", null, null);

        assertNull(model.getAttribute("currentDirectory"),
                "a foreign directory must not become the browsed directory");
        assertNull(model.getAttribute("childFiles"),
                "a foreign directory's file listing must not be rendered");
        assertTrue(errors(model).contains("MediaFile.error.view"),
                "the refusal must be visible rather than silent: " + errors(model));
    }

    @Test
    void aFolderFromAnotherWeblogCannotBeDeleted() throws Exception {
        MediaFileDirectory foreign = foreignDirectory("dir-x", "theirs");
        when(weblogger.getMediaFileManager().getMediaFileDirectory("dir-x")).thenReturn(foreign);

        controller.deleteFolder(request, model, "dir-x", null, null);

        verify(weblogger.getMediaFileManager(), never()).removeMediaFileDirectory(any());
        assertTrue(errors(model).contains("mediaFile.deleteFolder.error"),
                "Expected the folder delete to be refused, got: " + errors(model));
        assertTrue(messages(model).isEmpty(),
                "a refused delete must not also report success: " + messages(model));
    }

    @Test
    void aFileFromAnotherWeblogCannotBeDeleted() throws Exception {
        when(weblogger.getMediaFileManager().getMediaFile("file-x"))
                .thenReturn(foreignMediaFile("file-x", "their-photo.jpg"));

        controller.delete(request, model, "dir-1", "file-x", null);

        verify(weblogger.getMediaFileManager(), never()).removeMediaFile(any(), any());
        assertTrue(errors(model).contains("mediaFile.delete.error.single"),
                "A refused single-file delete must report the singular copy, got: "
                        + errors(model));
    }

    @Test
    void bulkDeleteSkipsFilesFromAnotherWeblog() throws Exception {
        MediaFile mine = mediaFile("file-1", "mine.jpg");
        when(weblogger.getMediaFileManager().getMediaFile("file-1")).thenReturn(mine);
        when(weblogger.getMediaFileManager().getMediaFile("file-x"))
                .thenReturn(foreignMediaFile("file-x", "theirs.jpg"));

        controller.deleteSelected(request, model, "dir-1", new String[]{"file-1", "file-x"}, null);

        verify(weblogger.getMediaFileManager()).removeMediaFile(weblog, mine);
        verify(weblogger.getMediaFileManager(), org.mockito.Mockito.times(1))
                .removeMediaFile(any(), any());
    }

    @Test
    void filesCannotBeMovedIntoAnotherWeblogsFolder() throws Exception {
        MediaFile mine = mediaFile("file-1", "mine.jpg");
        mine.setDirectory(defaultDirectory);
        when(weblogger.getMediaFileManager().getMediaFile("file-1")).thenReturn(mine);
        when(weblogger.getMediaFileManager().getMediaFileDirectory("dir-x"))
                .thenReturn(foreignDirectory("dir-x", "theirs"));

        controller.moveSelected(request, model, "dir-1", new String[]{"file-1"}, "dir-x", null);

        verify(weblogger.getMediaFileManager(), never()).moveMediaFile(any(), any());
        assertTrue(errors(model).contains("mediaFile.move.errors"),
                "Expected the move to be refused, got: " + errors(model));
    }

    // --- helpers ---

    private org.apache.roller.weblogger.pojos.Weblog otherWeblog() {
        org.apache.roller.weblogger.pojos.Weblog other =
                new org.apache.roller.weblogger.pojos.Weblog();
        other.setId("weblog-2");
        other.setHandle("otherblog");
        return other;
    }

    private MediaFileDirectory foreignDirectory(String id, String name) {
        MediaFileDirectory dir = directory(id, name);
        dir.setWeblog(otherWeblog());
        return dir;
    }

    private MediaFile foreignMediaFile(String id, String name) {
        org.apache.roller.weblogger.pojos.Weblog other = otherWeblog();
        MediaFileDirectory theirDirectory = directory("dir-foreign", "default");
        theirDirectory.setWeblog(other);
        MediaFile file = mediaFile(id, name);
        file.setWeblog(other);
        file.setDirectory(theirDirectory);
        return file;
    }

    private MediaFileDirectory directory(String id, String name) {
        MediaFileDirectory dir = new MediaFileDirectory();
        dir.setId(id);
        dir.setName(name);
        dir.setWeblog(weblog);
        Set<MediaFile> files = new LinkedHashSet<>(Collections.emptySet());
        dir.setMediaFiles(files);
        return dir;
    }

    private MediaFile mediaFile(String id, String name) {
        MediaFile file = new MediaFile();
        file.setId(id);
        file.setName(name);
        file.setWeblog(weblog);
        file.setContentType("image/jpeg");
        return file;
    }
}

package com.wikantik.pressroom.ui.controllers.editor;

import java.util.LinkedHashSet;
import java.util.List;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.pojos.MediaFile;
import com.wikantik.pressroom.pojos.MediaFileDirectory;
import com.wikantik.pressroom.pojos.WeblogCategory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ui.Model;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Characterisation test (spec 2026-11-20-jsp-rules-and-view-models-design,
 * Task 3): written against the code before the category and media-folder
 * rules moved into {@code CategoryRules} and {@code MediaRules}, and passing
 * on it. It pins what the screens answer today (model errors with their
 * arguments, the marked field, the view) for every case the move touches.
 * Cases the task changes on purpose are named "today" and are rewritten, new
 * expectation first, in the commit that changes them.
 */
class CategoryAndFolderScreensCharacterisationTest extends EditorControllerTestSupport {

    private static final String LIST_REDIRECT =
            "redirect:/pressroom-ui/authoring/categories.rol?weblog=" + WEBLOG_HANDLE;

    private CategoryEditController edit;
    private CategoryRemoveController remove;
    private MediaFileViewController folders;
    private MediaFileEditController fileEdit;
    private Model model;
    private RedirectAttributes redirectAttributes;
    private CategoryBean bean;
    private WeblogCategory travel;
    private MediaFileDirectory photos;

    @BeforeEach
    void setUp() throws Exception {
        edit = prepare(new CategoryEditController());
        remove = prepare(new CategoryRemoveController());
        folders = prepare(new MediaFileViewController());
        fileEdit = prepare(new MediaFileEditController());
        model = newModel();
        redirectAttributes = newRedirectAttributes();
        bean = new CategoryBean();
        travel = category("c1", "Travel");
        registerMessage("categoryForm.error.duplicateName", "dup:{0}");
        registerMessage("mediaFile.directoryCreate.error.exists", "exists:{0}");
        registerMessage("MediaFile.error.duplicateName", "taken:{0}");
        registerMessage("mediaFile.directoryCreate.success", "created:{0}");

        photos = directory("d1", "photos");
        when(weblogger.getMediaFileManager().getDefaultMediaFileDirectory(weblog))
                .thenReturn(directory("d0", "default"));
        when(weblogger.getMediaFileManager().getMediaFileDirectory("d1")).thenReturn(photos);
        when(weblogger.getMediaFileManager().getMediaFileDirectories(weblog)).thenReturn(List.of(photos));
        weblog.getMediaFileDirectories().add(photos);
    }

    private WeblogCategory category(String id, String name) throws Exception {
        WeblogCategory made = new WeblogCategory();
        made.setId(id);
        made.setName(name);
        made.setWeblog(weblog);
        weblog.getWeblogCategories().add(made);
        when(weblogger.getWeblogEntryManager().getWeblogCategory(id)).thenReturn(made);
        when(weblogger.getWeblogEntryManager().getWeblogCategoryByName(weblog, name)).thenReturn(made);
        return made;
    }

    private MediaFileDirectory directory(String id, String name) {
        MediaFileDirectory dir = new MediaFileDirectory();
        dir.setId(id);
        dir.setName(name);
        dir.setWeblog(weblog);
        dir.setMediaFiles(new LinkedHashSet<>());
        return dir;
    }

    private void add(String name) {
        bean.setName(name);
        assertEquals(LIST_REDIRECT, edit.categoryAddSave(request, model, bean, redirectAttributes));
    }

    private void rename(String id, String name) {
        bean.setId(id);
        bean.setName(name);
        assertEquals(LIST_REDIRECT, edit.categoryEditSave(request, model, bean, redirectAttributes));
    }

    // --- category names ---

    @Test
    void aMissingNameOrOneHtmlWouldChangeIsTheInvalidNameErrorOnTheNameField() {
        add(null);
        assertEquals(List.of("categoryForm.error.invalidName"), errors(model));
        assertEquals(List.of("category_bean_name"), invalidFields(model));
        assertEquals(List.of("categoryForm.error.invalidName"), flashErrors(redirectAttributes));
    }

    @Test
    void eachHtmlCharacterMakesTheNameInvalidOnAddAndRename() throws Exception {
        for (String bad : List.of("a&b", "a<b", "a>b", "a\"b")) {
            model = newModel();
            add(bad);
            assertEquals(List.of("categoryForm.error.invalidName"), errors(model), bad);
            model = newModel();
            rename("c1", bad);
            assertEquals(List.of("categoryForm.error.invalidName"), errors(model), bad);
        }
        verify(weblogger.getWeblogEntryManager(), never()).saveWeblogCategory(any());
    }

    /** HTML 4 escaping leaves an apostrophe alone, so the screen takes it: the rule is "unchanged by escapeHtml4". */
    @Test
    void anApostropheIsNotAnHtmlCharacterToTheScreen() {
        add("Can't miss");
        assertTrue(errors(model).isEmpty(), errors(model).toString());
    }

    /** Today a blank name passes the screen's checks and is stored: changed on purpose. */
    @Test
    void todayABlankCategoryNameIsSaved() throws Exception {
        add("   ");
        assertTrue(errors(model).isEmpty(), errors(model).toString());
        verify(weblogger.getWeblogEntryManager()).saveWeblogCategory(any());
    }

    /** Today a name over the column's width passes the screen's checks: changed on purpose. */
    @Test
    void todayANameOver255IsSavedOnAddAndRename() throws Exception {
        add("n".repeat(256));
        assertTrue(errors(model).isEmpty(), errors(model).toString());
        model = newModel();
        rename("c1", "m".repeat(256));
        assertTrue(errors(model).isEmpty(), errors(model).toString());
    }

    @Test
    void aTakenNameIsTheDuplicateErrorNamingItOnAddAndRename() throws Exception {
        category("c2", "Food");
        add("Travel");
        assertEquals(List.of("dup:Travel"), errors(model));
        assertEquals(List.of("category_bean_name"), invalidFields(model));
        model = newModel();
        rename("c1", "Food");
        assertEquals(List.of("dup:Food"), errors(model));
        assertEquals(List.of("category_bean_name"), invalidFields(model));
        verify(weblogger.getWeblogEntryManager(), never()).saveWeblogCategory(any());
    }

    @Test
    void aDuplicateNameIsEscapedInTheMessage() throws Exception {
        // "&" is invalid, so a name escaping to itself cannot hold a markup character: the
        // escaping reaches the message only through a name that already is its own escape.
        category("c2", "Food");
        add("Food");
        assertEquals(List.of("dup:Food"), errors(model));
    }

    @Test
    void renamingToItsOwnNameIsAccepted() throws Exception {
        rename("c1", "Travel");
        assertTrue(errors(model).isEmpty(), errors(model).toString());
        verify(weblogger.getWeblogEntryManager()).saveWeblogCategory(travel);
    }

    @Test
    void aFailedUniquenessLookupOnRenameRefusesWithCheckLogs() throws Exception {
        when(weblogger.getWeblogEntryManager().getWeblogCategoryByName(weblog, "Food"))
                .thenThrow(new WebloggerException("down"));
        rename("c1", "Food");
        assertEquals(List.of("generic.error.check.logs"), errors(model));
        verify(weblogger.getWeblogEntryManager(), never()).saveWeblogCategory(any());
    }

    // --- category removal ---

    @Test
    void theLastCategoryIsRefusedWithItsMessageAndNothingMoves() throws Exception {
        assertEquals(LIST_REDIRECT, remove.remove(request, model, "c1", null, redirectAttributes));
        assertEquals(List.of("categoryForm.error.lastCategory"), flashErrors(redirectAttributes));
        verify(weblogger.getWeblogEntryManager(), never()).removeWeblogCategory(any());
    }

    /** The screen has no in-use check of its own: the manager's refusal is "check the logs". */
    @Test
    void aCategoryInUseWithNoTargetFallsToTheManagersRefusal() throws Exception {
        category("c2", "Food");
        doThrow(new WebloggerException("Cannot remove category with entries"))
                .when(weblogger.getWeblogEntryManager()).removeWeblogCategory(travel);
        remove.remove(request, model, "c1", null, redirectAttributes);
        assertEquals(List.of("generic.error.check.logs"), flashErrors(redirectAttributes));
    }

    /** The screen has no move-to-itself check: it moves the contents onto the category and removes it. */
    @Test
    void aTargetNamingTheCategoryItselfIsNotCheckedByTheScreen() throws Exception {
        category("c2", "Food");
        doThrow(new WebloggerException("Cannot remove category with entries"))
                .when(weblogger.getWeblogEntryManager()).removeWeblogCategory(travel);
        remove.remove(request, model, "c1", "c1", redirectAttributes);
        verify(weblogger.getWeblogEntryManager()).moveWeblogCategoryContents(travel, travel);
        assertEquals(List.of("generic.error.check.logs"), flashErrors(redirectAttributes));
    }

    // --- folder names ---

    private void createFolder(String name) {
        assertEquals(".MediaFileView", folders.createNewDirectory(request, model, null, name, null));
    }

    @Test
    void anEmptyFolderNameIsTheEmptyError() throws Exception {
        createFolder("");
        assertEquals(List.of("mediaFile.error.view.dirNameEmpty"), errors(model));
        assertTrue(invalidFields(model).isEmpty());
        model = newModel();
        createFolder(null);
        assertEquals(List.of("mediaFile.error.view.dirNameEmpty"), errors(model));
        verify(weblogger.getMediaFileManager(), never()).createMediaFileDirectory(any(), any());
    }

    /** Today a leading slash is refused like any other: changed on purpose (the name is accepted without it). */
    @Test
    void todayAnySlashInAFolderNameIsTheInvalidError() throws Exception {
        for (String bad : List.of("/x", "a/b", "x/")) {
            model = newModel();
            createFolder(bad);
            assertEquals(List.of("mediaFile.error.view.dirNameInvalid"), errors(model), bad);
            assertTrue(invalidFields(model).isEmpty());
        }
        verify(weblogger.getMediaFileManager(), never()).createMediaFileDirectory(any(), any());
    }

    @Test
    void aTakenFolderNameIsTheExistsErrorNamingItEscaped() throws Exception {
        createFolder("photos");
        assertEquals(List.of("exists:photos"), errors(model));
        verify(weblogger.getMediaFileManager(), never()).createMediaFileDirectory(any(), any());
    }

    /** Today the screen leaves the reserved name to the manager, whose refusal reads "check the logs": changed. */
    @Test
    void todayDefaultReachesTheManagerWhichRefusesIt() throws Exception {
        when(weblogger.getMediaFileManager().createMediaFileDirectory(weblog, "default"))
                .thenThrow(new WebloggerException("Invalid name!"));
        createFolder("default");
        assertEquals(List.of("generic.error.check.logs"), errors(model));
    }

    /** Today the screen has no length check: changed on purpose. */
    @Test
    void todayAFolderNameOver255ReachesTheManager() throws Exception {
        String longName = "f".repeat(256);
        MediaFileDirectory created = directory("d9", longName);
        when(weblogger.getMediaFileManager().createMediaFileDirectory(weblog, longName)).thenReturn(created);
        when(weblogger.getMediaFileManager().getMediaFileDirectory("d9")).thenReturn(created);
        createFolder(longName);
        assertTrue(errors(model).isEmpty(), errors(model).toString());
        verify(weblogger.getMediaFileManager()).createMediaFileDirectory(weblog, longName);
    }

    @Test
    void aNewFolderIsAnnouncedWithItsNameEscaped() throws Exception {
        MediaFileDirectory created = directory("d3", "<script>trips");
        when(weblogger.getMediaFileManager().createMediaFileDirectory(weblog, "<script>trips")).thenReturn(created);
        when(weblogger.getMediaFileManager().getMediaFileDirectory("d3")).thenReturn(created);
        createFolder("<script>trips");
        assertEquals(List.of("created:&lt;script&gt;trips"), messages(model));
    }

    // --- file names ---

    private MediaFile file(String id, String name) throws Exception {
        MediaFile made = new MediaFile();
        made.setId(id);
        made.setName(name);
        made.setWeblog(weblog);
        made.setDirectory(photos);
        photos.getMediaFiles().add(made);
        when(weblogger.getMediaFileManager().getMediaFile(id)).thenReturn(made);
        return made;
    }

    private String save(String id, String name) {
        MediaFileBean form = new MediaFileBean();
        form.setId(id);
        form.setName(name);
        form.setDirectoryId("d1");
        return fileEdit.save(request, model, form, id, null);
    }

    @Test
    void renamingOntoAnotherFilesNameIsTheDuplicateErrorNamingItEscapedWithNoFieldMarked() throws Exception {
        file("f1", "dog.jpg");
        file("f2", "cat<b>.jpg");
        assertEquals(".MediaFileEdit", save("f1", "cat<b>.jpg"));
        assertEquals(List.of("taken:cat&lt;b&gt;.jpg"), errors(model));
        assertTrue(invalidFields(model).isEmpty());
        verify(weblogger.getMediaFileManager(), never()).updateMediaFile(any(), any());
    }

    @Test
    void aFileKeepingItsOwnNameIsNotADuplicate() throws Exception {
        file("f1", "dog.jpg");
        assertEquals(".MediaFileEditSuccess", save("f1", "dog.jpg"));
    }

    /** Today a blank file name passes the screen's checks and is stored: changed on purpose. */
    @Test
    void todayABlankFileNameIsSaved() throws Exception {
        file("f1", "dog.jpg");
        assertEquals(".MediaFileEditSuccess", save("f1", "  "));
        assertTrue(errors(model).isEmpty());
    }

    /** Today a file name over the column's width passes the screen's checks: changed on purpose. */
    @Test
    void todayAFileNameOver255IsSaved() throws Exception {
        file("f1", "dog.jpg");
        assertEquals(".MediaFileEditSuccess", save("f1", "f".repeat(256)));
        assertTrue(errors(model).isEmpty());
    }
}

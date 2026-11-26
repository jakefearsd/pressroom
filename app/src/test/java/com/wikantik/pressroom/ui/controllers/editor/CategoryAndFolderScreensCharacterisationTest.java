package com.wikantik.pressroom.ui.controllers.editor;

import java.util.LinkedHashSet;
import java.util.List;

import com.wikantik.pressroom.pojos.MediaFile;
import com.wikantik.pressroom.pojos.MediaFileDirectory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ui.Model;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Characterisation test (spec 2026-11-20-jsp-rules-and-view-models-design,
 * Task 3): written against the code before the media-folder
 * rules moved into {@code MediaRules}, and passing
 * on it (its category half went with the category screens when they moved to React). It pins what the screens answer today (model errors with their
 * arguments, the marked field, the view) for every case the move touches.
 * Cases the task changes on purpose are named "today" and are rewritten, new
 * expectation first, in the commit that changes them.
 */
class CategoryAndFolderScreensCharacterisationTest extends EditorControllerTestSupport {

    private MediaFileViewController folders;
    private MediaFileEditController fileEdit;
    private Model model;
    private RedirectAttributes redirectAttributes;
    private MediaFileDirectory photos;

    @BeforeEach
    void setUp() throws Exception {
        folders = prepare(new MediaFileViewController());
        fileEdit = prepare(new MediaFileEditController());
        model = newModel();
        redirectAttributes = newRedirectAttributes();
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

    private MediaFileDirectory directory(String id, String name) {
        MediaFileDirectory dir = new MediaFileDirectory();
        dir.setId(id);
        dir.setName(name);
        dir.setWeblog(weblog);
        dir.setMediaFiles(new LinkedHashSet<>());
        return dir;
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

    @Test
    void aSlashInsideAFolderNameIsTheInvalidErrorWithNoFieldMarked() throws Exception {
        for (String bad : List.of("a/b", "x/", "//x")) {
            model = newModel();
            createFolder(bad);
            assertEquals(List.of("mediaFile.error.view.dirNameInvalid"), errors(model), bad);
            assertTrue(invalidFields(model).isEmpty());
        }
        verify(weblogger.getMediaFileManager(), never()).createMediaFileDirectory(any(), any());
    }

    /** Changed on purpose (spec Task 3, AC3c): the screen refused a leading slash; the name is taken without it. */
    @Test
    void aLeadingSlashIsDroppedAndAPaddedNameTrimmed() throws Exception {
        MediaFileDirectory created = directory("d3", "x");
        when(weblogger.getMediaFileManager().createMediaFileDirectory(weblog, "x")).thenReturn(created);
        when(weblogger.getMediaFileManager().getMediaFileDirectory("d3")).thenReturn(created);
        createFolder(" /x ");
        assertTrue(errors(model).isEmpty(), errors(model).toString());
        assertEquals(List.of("created:x"), messages(model));
        verify(weblogger.getMediaFileManager()).createMediaFileDirectory(weblog, "x");
    }

    @Test
    void aFolderNameOfOnlySpacesOrASlashIsTheEmptyError() throws Exception {
        for (String empty : List.of("   ", "/", " / ")) {
            model = newModel();
            createFolder(empty);
            assertEquals(List.of("mediaFile.error.view.dirNameEmpty"), errors(model), empty);
        }
    }

    @Test
    void aTakenFolderNameIsTheExistsErrorNamingItEscaped() throws Exception {
        createFolder("photos");
        assertEquals(List.of("exists:photos"), errors(model));
        verify(weblogger.getMediaFileManager(), never()).createMediaFileDirectory(any(), any());
    }

    /** Changed on purpose (spec Task 3, AC3c): the reserved name reached the manager, whose refusal read "check the logs". */
    @Test
    void defaultIsTheReservedErrorOnTheNameField() throws Exception {
        createFolder("default");
        assertEquals(List.of("mediaFile.error.view.dirNameReserved"), errors(model));
        assertEquals(List.of("newDirectoryName"), invalidFields(model));
        model = newModel();
        createFolder("/default");
        assertEquals(List.of("mediaFile.error.view.dirNameReserved"), errors(model));
        verify(weblogger.getMediaFileManager(), never()).createMediaFileDirectory(any(), any());
    }

    /** Changed on purpose (spec Task 3, AC3c): the screen had no length check. */
    @Test
    void aFolderNameOver255IsTheTooLongErrorOnTheNameField() throws Exception {
        createFolder("f".repeat(256));
        assertEquals(List.of("mediaFile.error.view.dirNameTooLong"), errors(model));
        assertEquals(List.of("newDirectoryName"), invalidFields(model));
        verify(weblogger.getMediaFileManager(), never()).createMediaFileDirectory(any(), any());
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

    /** Changed on purpose (spec Task 3, AC3d): a blank file name was saved. */
    @Test
    void aBlankFileNameIsAFieldError() throws Exception {
        file("f1", "dog.jpg");
        assertEquals(".MediaFileEdit", save("f1", "  "));
        assertEquals(List.of("MediaFile.error.nameBlank"), errors(model));
        assertEquals(List.of("mfedit_bean_name"), invalidFields(model));
        verify(weblogger.getMediaFileManager(), never()).updateMediaFile(any(), any());
    }

    /** Changed on purpose (spec Task 3, AC3d): a file name over the column's width reached the database. */
    @Test
    void aFileNameOver255IsAFieldError() throws Exception {
        file("f1", "dog.jpg");
        assertEquals(".MediaFileEdit", save("f1", "f".repeat(256)));
        assertEquals(List.of("MediaFile.error.nameTooLong"), errors(model));
        assertEquals(List.of("mfedit_bean_name"), invalidFields(model));
    }
}

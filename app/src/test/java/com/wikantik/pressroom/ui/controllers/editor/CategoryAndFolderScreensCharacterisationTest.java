package com.wikantik.pressroom.ui.controllers.editor;

import java.util.List;

import com.wikantik.pressroom.WebloggerException;
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
 * Characterisation test (spec 2026-11-20-jsp-rules-and-view-models-design; the media-folder and file-name cases went with
 * their screens in the React cutover of wave 2,
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
    private Model model;
    private RedirectAttributes redirectAttributes;
    private CategoryBean bean;
    private WeblogCategory travel;

    @BeforeEach
    void setUp() throws Exception {
        edit = prepare(new CategoryEditController());
        remove = prepare(new CategoryRemoveController());
        model = newModel();
        redirectAttributes = newRedirectAttributes();
        bean = new CategoryBean();
        travel = category("c1", "Travel");
        registerMessage("categoryForm.error.duplicateName", "dup:{0}");
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

    /** Changed on purpose (spec Task 3): a blank name was saved; it is the invalid-name error now. */
    @Test
    void aBlankCategoryNameIsTheInvalidNameErrorOnTheNameField() throws Exception {
        add("   ");
        assertEquals(List.of("categoryForm.error.invalidName"), errors(model));
        assertEquals(List.of("category_bean_name"), invalidFields(model));
        model = newModel();
        rename("c1", "");
        assertEquals(List.of("categoryForm.error.invalidName"), errors(model));
        verify(weblogger.getWeblogEntryManager(), never()).saveWeblogCategory(any());
    }

    /** Changed on purpose (spec Task 3, AC3b): a name over the column's width reached the database. */
    @Test
    void aCategoryNameOver255IsTheTooLongErrorOnTheNameFieldOnAddAndRename() throws Exception {
        add("n".repeat(256));
        assertEquals(List.of("categoryForm.error.nameTooLong"), errors(model));
        assertEquals(List.of("category_bean_name"), invalidFields(model));
        model = newModel();
        rename("c1", "m".repeat(256));
        assertEquals(List.of("categoryForm.error.nameTooLong"), errors(model));
        assertEquals(List.of("category_bean_name"), invalidFields(model));
        verify(weblogger.getWeblogEntryManager(), never()).saveWeblogCategory(any());
    }

    @Test
    void aNameOf255IsAccepted() {
        add("n".repeat(255));
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
}

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
package com.wikantik.pressroom.it;

import com.wikantik.pressroom.it.support.AdminApp;
import com.wikantik.pressroom.it.support.BrowserHealth;
import com.wikantik.pressroom.it.support.Editor;
import com.wikantik.pressroom.it.support.PressroomIT;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;

import static com.codeborne.selenide.Condition.disabled;
import static com.codeborne.selenide.Condition.disappear;
import static com.codeborne.selenide.Condition.enabled;
import static com.codeborne.selenide.Condition.exactText;
import static com.codeborne.selenide.Condition.exist;
import static com.codeborne.selenide.Condition.text;
import static com.codeborne.selenide.Condition.exactValue;
import static com.codeborne.selenide.Condition.value;
import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selenide.$;
import static com.codeborne.selenide.Selenide.$$;
import static com.codeborne.selenide.Selenide.executeJavaScript;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Weblog categories: add, rename, delete, and the entry-moving that a delete
 * has to do when the category is in use.
 *
 * <p>The Categories page had no browser coverage at all, only a route-sweep
 * check that it renders. It rendered fine and did nothing: every control on it
 * -- add, edit, delete -- drove element ids like
 * {@code #categoryEditForm_bean_name} that Struts used to generate and the JSP
 * migration never reproduced. jQuery matched nothing, {@code .val(x)} was a
 * silent no-op, and {@code .val().trim()} threw on undefined. The modals opened
 * empty, Save threw, and Delete posted an empty id.
 *
 * <p>So these tests are written against control names rather than ids: names
 * are what the server binds and cannot drift without the handler noticing.
 */
@ResourceLock(value = PressroomIT.GLOBAL_CONFIG, mode = ResourceAccessMode.READ)
class CategoryIT extends PressroomIT {

    private static final String CATEGORIES = AdminApp.categories(WEBLOG_HANDLE);
    private static final String ENTRY_ADD = AdminApp.newEntry(WEBLOG_HANDLE);

    /** Both category dialogs are the app's one modal shape: the open dialog. */
    private static final String EDIT_MODAL = "[role='dialog']";
    private static final String DELETE_MODAL = "[role='dialog']";

    @Test
    void aCategoryCanBeAddedRenamedAndDeleted() {
        String suffix = nonce();
        String name = "ITCat" + suffix;
        String renamed = "ITCatRenamed" + suffix;

        loginAsAdmin();
        addCategory(name, "Created by CategoryIT");
        assertTrue(categoryNames().contains(name),
                "a category added through the modal must appear in the list: " + categoryNames());

        renameCategory(name, renamed);
        assertTrue(categoryNames().contains(renamed),
                "a renamed category must show its new name: " + categoryNames());
        assertFalse(categoryNames().contains(name),
                "and must not still be listed under the old one");

        deleteCategory(renamed, null);
        assertFalse(categoryNames().contains(renamed),
                "a deleted category must be gone from the list: " + categoryNames());
        logout();
    }

    /**
     * A category holding entries cannot simply vanish; the entries have to go
     * somewhere, and the modal asks where.
     */
    @Test
    void deletingACategoryInUseMovesItsEntriesToTheChosenCategory() {
        String suffix = nonce();
        String doomed = "ITCatUsed" + suffix;
        String title = "IT Category entry " + suffix;

        loginAsAdmin();
        addCategory(doomed, "Holds an entry");
        publishEntryInCategory(title, "Body " + suffix, doomed);
        String entryId = AdminApp.savedEntryId();

        // General is the seeded category; it is what the entry must land in.
        deleteCategory(doomed, "General");

        assertFalse(categoryNames().contains(doomed),
                "the category must be gone once its entries have been moved");
        openPath(AdminApp.entries(WEBLOG_HANDLE));
        AdminApp.row(title).should(exist);
        assertTrue($$("table.pressroomtable").first().getText().contains(title),
                "the entry must survive its category being deleted");
        // ...and it is now IN the chosen category, not left pointing at the deleted one.
        openPath(AdminApp.entry(WEBLOG_HANDLE, entryId));
        AdminApp.awaitEditor();
        assertTrue($("select[name='bean.categoryId']").getSelectedOption().getText().contains("General"),
                "the moved entry did not land in the chosen category");
        logout();
    }

    /**
     * The add dialog says what is wrong before anything is sent: an empty name and a malformed image address
     * leave Save disabled with the reason beside it; the server's refusal of a duplicate name is shown in the
     * dialog, which stays open; and Cancel adds nothing.
     */
    @Test
    void theAddDialogRefusesBadInputAndCancelAddsNothing() {
        String suffix = nonce();
        String name = "ITCatBad" + suffix;

        loginAsAdmin();
        openPath(CATEGORIES);
        $("#addCategoryButton").should(visible).click();
        $(EDIT_MODAL).shouldBe(visible);
        String save = EDIT_MODAL + " .modal-footer .btn-primary";

        // empty name
        $(save).shouldBe(disabled);
        $("#feedback-area-edit").shouldHave(text("Name is required."));

        // a name, but a malformed image address
        $(EDIT_MODAL + " input[name='bean.name']").setValue(name);
        $(EDIT_MODAL + " input[name='bean.image']").setValue("not a url");
        $(save).shouldBe(disabled);
        $("#feedback-area-edit").shouldHave(text("Image URL is invalid"));
        $(EDIT_MODAL + " input[name='bean.image']").setValue("https://example.com/cat-" + suffix + ".png");
        $(save).shouldBe(enabled);

        // Cancel adds nothing
        $(EDIT_MODAL + " .modal-footer .btn-secondary").click();
        $(EDIT_MODAL).should(disappear);
        assertFalse(categoryNames().contains(name), "Cancel added the category anyway: " + categoryNames());

        // a duplicate of the seeded category is refused by the server, in the dialog
        BrowserHealth.current().expectRefusal("/weblogs/" + WEBLOG_HANDLE + "/categories");
        $("#addCategoryButton").click();
        $(EDIT_MODAL + " input[name='bean.name']").setValue("General");
        $(save).click();
        $("#feedback-area-edit").shouldHave(text("already exists"));
        $(EDIT_MODAL).shouldBe(visible);
        $(EDIT_MODAL + " .modal-footer .btn-secondary").click();
        $(EDIT_MODAL).should(disappear);
        assertEquals(1, java.util.Arrays.stream(categoryNames().split(",")).filter("General"::equals).count(),
                "a duplicate General was created: " + categoryNames());
        logout();
    }

    /**
     * Editing keeps what the dialog showed: description and image come back prefilled, a change to them is
     * saved and survives a reload, and the screen says what happened (created, updated, deleted).
     */
    @Test
    void anEditedCategoryKeepsItsDescriptionAndImageAndTheScreenSaysWhatHappened() {
        String suffix = nonce();
        String name = "ITCatEdit" + suffix;
        String image = "https://example.com/cat-" + suffix + ".png";

        loginAsAdmin();
        openPath(CATEGORIES);
        $("#addCategoryButton").should(visible).click();
        $(EDIT_MODAL + " input[name='bean.name']").setValue(name);
        $(EDIT_MODAL + " input[name='bean.description']").setValue("First description");
        $(EDIT_MODAL + " input[name='bean.image']").setValue(image);
        saveCategoryModal();
        $("#messages").shouldHave(text("Category \"" + name + "\" created"));

        // reload: the row shows the description, and the dialog is prefilled from the server
        openPath(CATEGORIES);
        $(rowFor(name)).shouldHave(text("First description"));
        openEditModalFor(name);
        $(EDIT_MODAL + " input[name='bean.description']").shouldHave(value("First description"));
        $(EDIT_MODAL + " input[name='bean.image']").shouldHave(value(image));

        $(EDIT_MODAL + " input[name='bean.description']").setValue("Second description");
        saveCategoryModal();
        $("#messages").shouldHave(text("Category \"" + name + "\" updated"));
        openPath(CATEGORIES);
        $(rowFor(name)).shouldHave(text("Second description"));
        openEditModalFor(name);
        $(EDIT_MODAL + " input[name='bean.image']").shouldHave(value(image));
        $(EDIT_MODAL + " .modal-footer .btn-secondary").click();
        $(EDIT_MODAL).should(disappear);

        // an unused category asks no destination and says it is deleted
        openDeleteModalFor(name);
        $("#category-empty").should(exist);
        AdminApp.confirmDialog();
        $("#messages").shouldHave(text("Category \"" + name + "\" deleted"));
        $(rowFor(name)).shouldNot(exist);
        logout();
    }

    /**
     * A weblog always keeps one category: with only one, the list offers no delete; with two it offers both.
     * On a weblog of its own, since the shared one has other classes' categories.
     */
    @Test
    void theLastCategoryOfAWeblogCannotBeDeleted() {
        String suffix = nonce();
        String handle = "catlastit" + suffix;
        String second = "ITCatSecond" + suffix;
        String screen = AdminApp.categories(handle);

        loginAsAdmin();
        createWeblogOnTheReactScreen(handle, "Categories " + suffix, "journal");
        try {
            openPath(screen);
            $("table#category-table tbody tr").should(exist);
            // A new weblog starts with a few categories: remove them down to one.
            while ($$("table#category-table tbody tr").size() > 1) {
                int before = $$("table#category-table tbody tr").size();
                $$("button.category-delete-btn").first().click();
                AdminApp.confirmDialog();
                $$("table#category-table tbody tr").shouldHave(com.codeborne.selenide.CollectionCondition.size(before - 1));
            }
            assertTrue($$("button.category-delete-btn").isEmpty(), "the only category offers a delete");

            $("#addCategoryButton").click();
            $(EDIT_MODAL + " input[name='bean.name']").setValue(second);
            saveCategoryModal();
            $(rowFor(second)).should(exist);
            assertEquals(2, $$("button.category-delete-btn").size(), "with two categories either may go");
        } finally {
            openPath("/pressroom-ui/app/weblogs/" + handle + "/remove");
            $("button.btn-danger").should(visible).click();
            $("h2.mm_weblog_name").should(exist);
            $$("h2.mm_weblog_name .data").findBy(exactText(handle)).shouldNot(exist);
        }
        logout();
    }

    /**
     * The category being deleted must not be offered as the place to move its
     * own entries to.
     *
     * <p>The select is rendered server-side with every category in it and then
     * filtered by JS. When that filtering silently did nothing -- it targeted a
     * select id that did not exist -- the modal cheerfully offered you the
     * category you were deleting.
     */
    @Test
    void theDeleteModalDoesNotOfferTheCategoryBeingDeleted() {
        String suffix = nonce();
        String doomed = "ITCatSelf" + suffix;

        loginAsAdmin();
        addCategory(doomed, "About to go");
        // Only a category in use asks where its entries go, so give it one.
        publishEntryInCategory("IT Category self " + suffix, "Body " + suffix, doomed);

        openPath(CATEGORIES);
        openDeleteModalFor(doomed);
        assertFalse(moveTargetNames().contains(doomed),
                "the category being deleted was offered as its own move target: "
                        + moveTargetNames());
        assertTrue(moveTargetNames().contains("General"),
                "the other categories must still be offered: " + moveTargetNames());

        // Navigate away rather than clicking "No". Bootstrap ignores hide()
        // while the show transition is still running, and this test reaches the
        // dismiss button within milliseconds of opening the modal -- a timing a
        // real user never hits. deleteCategory reloads the page anyway.
        deleteCategory(doomed, "General");
        logout();
    }

    /**
     * Deleting a category must not leave the weblog unable to save its
     * settings.
     *
     * <p>This used to guard a specific bug: a weblog's "blogger category"
     * (the Blogger XML-RPC API's default posting target, {@code
     * weblog.bloggercatid}) was a raw id with no cascade, so deleting the
     * category it pointed at left the settings page throwing "Error updating
     * configuration" on every save with no way back through the UI. The
     * Blogger API and its column are gone now (W2), so that specific failure
     * mode no longer exists -- but the general shape is still worth
     * covering: delete a category, then confirm the settings page still
     * saves cleanly.
     */
    @Test
    void deletingACategoryLeavesTheWeblogSaveable() {
        String suffix = nonce();
        String extra = "ITCatBlogger" + suffix;

        loginAsAdmin();
        // A second category is needed: the last one cannot be deleted.
        addCategory(extra, "Second category");
        deleteCategory(extra, null);

        openPath("/pressroom-ui/app/weblogs/" + WEBLOG_HANDLE + "/settings");
        // The React screen PATCHes only what changed and saves nothing for an untouched form, so change
        // something: the save must actually reach the server for this test to prove anything.
        $("input[name='bean.tagline']").should(visible).setValue("Saved after deleting " + extra);
        $("button[type='submit'].btn-primary").should(visible).click();

        // A Selenide condition, not a snapshot assertion: it waits for the save
        // to land and captures the page if it never does.
        $("#messages").should(exist);
        assertFalse($$("#errors").size() > 0,
                "saving the weblog settings after a category deletion reported an error: "
                        + ($$("#errors").isEmpty() ? "" : $("#errors").getText()));
        BrowserHealth.current().settle();
        logout();
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Opens the add modal from the sidebar and saves a new category.
     *
     * <p>The sidebar control is a {@code <button>} now (a11y sweep), and so
     * is Categories.jsp's own empty-state add button -- element type can no
     * longer disambiguate the two the way "a[onclick*=...]" once did, so this
     * targets the sidebar one by its id instead.
     */
    private void addCategory(String name, String description) {
        openPath(CATEGORIES);
        $("#addCategoryButton").should(visible).click();
        $(EDIT_MODAL).shouldBe(visible);

        $(EDIT_MODAL + " input[name='bean.name']").setValue(name);
        $(EDIT_MODAL + " input[name='bean.description']").setValue(description);
        saveCategoryModal();

        // Wait for the row, rather than assuming the save landed. The modal
        // closing means the POST returned; it does not mean the reload that
        // follows it has painted, and the next step needs the row to exist.
        $(rowFor(name)).should(exist);
    }

    private void renameCategory(String from, String to) {
        openPath(CATEGORIES);
        openEditModalFor(from);
        $(EDIT_MODAL + " input[name='bean.name']").setValue(to);
        saveCategoryModal();
        $(rowFor(to)).should(exist);
    }

    /**
     * The modal saves over AJAX and then reloads the page, so waiting for the
     * modal to go is waiting for a real server round trip rather than an
     * animation.
     */
    private void saveCategoryModal() {
        $(EDIT_MODAL + " .modal-footer .btn-primary").click();
        $(EDIT_MODAL).shouldNotBe(visible);

        // The list refetches after the save; wait for the table so the assertions that follow are made
        // against a settled page.
        $("table#category-table").should(exist);
        BrowserHealth.current().settle();
    }

    /**
     * Deletes a category through its row's trash control.
     *
     * @param moveTo name of the category to move entries into, or null when the
     *               category is empty and no target is needed
     */
    private void deleteCategory(String name, String moveTo) {
        openPath(CATEGORIES);
        openDeleteModalFor(name);
        if (moveTo != null) {
            $(DELETE_MODAL + " select[name='targetCategoryId']").selectOptionContainingText(moveTo);
        }
        AdminApp.confirmDialog();
        // Waiting for the row to go is waiting for the delete call itself.
        $("table#category-table").should(exist);
        $(rowFor(name)).shouldNot(exist);
        BrowserHealth.current().settle();
    }

    /**
     * Opens the edit modal for a category and checks it arrived prefilled --
     * the modal used to open blank because the JS populated ids that did not
     * exist, so an "edit" silently offered to create something instead.
     */
    private void openEditModalFor(String name) {
        $(rowFor(name) + " button.category-edit-btn").should(visible).click();
        $(EDIT_MODAL).shouldBe(visible);
        $(EDIT_MODAL + " input[name='bean.name']").shouldHave(value(name));
    }

    private void openDeleteModalFor(String name) {
        $(rowFor(name) + " button.category-delete-btn").should(visible).click();
        $(DELETE_MODAL).shouldBe(visible);
    }

    /**
     * A CSS selector for the row of the named category.
     *
     * <p>Rows carry data-category-name so a test can find one without parsing
     * the onclick attributes that drive the modals.
     */
    private String rowFor(String name) {
        return "tr[data-category-name='" + name + "']";
    }

    private String categoryNames() {
        openPath(CATEGORIES);
        $("table.pressroomtable").should(exist);
        String names = executeJavaScript(
                "return Array.from(document.querySelectorAll('tr[data-category-name]'))"
                        + ".map(r => r.getAttribute('data-category-name')).join(',');");
        return names == null ? "" : names;
    }

    private String moveTargetNames() {
        String names = executeJavaScript(
                "return Array.from(document.querySelectorAll("
                        + "\"[role='dialog'] select[name='targetCategoryId'] option\"))"
                        + ".map(o => o.textContent.trim()).join(',');");
        return names == null ? "" : names;
    }

    private void publishEntryInCategory(String title, String body, String categoryName) {
        openPath(ENTRY_ADD);
        AdminApp.write(title, body);
        $("select[name='bean.categoryId']").selectOptionContainingText(categoryName);
        AdminApp.publishButton().click();
        $("#entry_bean_permalink").should(exist);
        assertNotNull(AdminApp.savedEntryId(), "the published entry must be open on its own route");
    }

    private static String nonce() {
        return Long.toString(System.nanoTime(), 36);
    }
}

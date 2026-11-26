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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import com.wikantik.pressroom.it.support.BrowserHealth;
import com.wikantik.pressroom.it.support.Editor;
import com.wikantik.pressroom.it.support.PagesMediaApp;
import com.wikantik.pressroom.it.support.PressroomIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.openqa.selenium.Keys;

import static com.codeborne.selenide.Condition.checked;
import static com.codeborne.selenide.Condition.exist;
import static com.codeborne.selenide.Condition.text;
import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selenide.$;
import static com.codeborne.selenide.Selenide.$$;
import static com.codeborne.selenide.Selenide.switchTo;
import static com.codeborne.selenide.Selenide.webdriver;
import static com.codeborne.selenide.WebDriverConditions.urlContaining;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Static pages: draft-to-published visibility, Markdown and shortcode
 * rendering, and the "show in navigation" toggle.
 *
 * <p>This owns its own weblog on the {@code portfolio} shared theme rather
 * than using the seeded {@code it_weblog}: nav-link coverage needs a theme
 * that renders {@code #showPageLinks}, and owning the weblog keeps this
 * suite honest about the rule that the seeded weblog's theme is never
 * switched -- sidestepped here by not needing to.
 *
 * <p>The draft-is-invisible assertion is a plain anonymous HTTP status check
 * rather than a browser navigation, the same choice {@code AuthoringJourneyIT}
 * makes for a draft entry: navigating a real browser straight at a 404 would
 * record the main document itself as a broken resource and fail the
 * suite's automatic {@code BrowserHealth} check at teardown for a 404 the
 * test asked for on purpose.
 */
@ResourceLock(value = PressroomIT.GLOBAL_CONFIG, mode = ResourceAccessMode.READ)
class PageIT extends PressroomIT {

    private static final String CREATE_WEBLOG = "/pressroom-ui/app/weblogs/new";

    /**
     * The seeded weblog, on {@code journal} -- used by the two editor tests
     * below, which need a theme whose preview shell is known and do not need a
     * weblog of their own. The nav-link test above still owns a
     * {@code portfolio} weblog, for the reason the class comment gives.
     */
    private static final String PAGE_EDIT_ON_SHARED = PagesMediaApp.newPage(WEBLOG_HANDLE);

    /** The shell renders through the theme; journal's prose class is qj-prose. */
    private static final String PREVIEW_ARTICLE = "#previewArticle.qj-prose";

    /** The push is debounced by 400ms and then round-trips to the server. */
    private static final Duration RENDER = Duration.ofSeconds(5);

    private HttpClient http;

    @BeforeEach
    void logIn() {
        loginAsAdmin();
        http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Test
    void aPageGoesFromDraftToPublishedAndCanBeHiddenFromNav() {
        String handle = createWeblog();
        String pageUrl = baseUrl() + "/" + handle + "/about";

        // --- create as a draft, with Markdown and a [cta] in the body ------
        openPath(PagesMediaApp.newPage(handle));
        PagesMediaApp.awaitPageEditor();
        $("#page_bean_slug").setValue("about");
        $("#page_bean_title").setValue("About Us");
        Editor.setText("This page has **bold** text.\n\n"
                        + "[cta href=\"https://example.com/book\" label=\"Book now\" "
                        + "note=\"Free cancellation\"]");
        saveOpenPage();

        assertEquals(404, statusOf(pageUrl),
                "a draft page must not resolve for an anonymous reader, got a page at " + pageUrl);

        // --- publish ----------------------------------------------------------
        openPageByTitle(handle, "About Us");
        $("#page_bean_status").selectOptionByValue("PUBLISHED");
        saveOpenPage();

        String publicBody = getAnonymously(pageUrl);
        assertEquals(200, statusOf(pageUrl), "a published page must resolve for an anonymous reader");
        assertTrue(publicBody.contains("About Us"),
                "the published page must carry its title, got: " + truncate(publicBody));
        assertTrue(publicBody.contains("<strong>bold</strong>"),
                "Markdown in the page body must be rendered, got: " + truncate(publicBody));
        assertTrue(publicBody.contains("class=\"cta-card\"") && publicBody.contains("Book now"),
                "the [cta] shortcode in the page body must render as a CTA card, got: "
                        + truncate(publicBody));

        // --- the Pages list links where the page actually lives -------------
        // The list used to emit "<root>/page/<slug>", the CUSTOM-template
        // route, so an author clicking the URL on their own admin screen got
        // a 404. Asserted by fetching it rather than by matching a string:
        // what makes this a bug is that the link does not resolve.
        openPath(PagesMediaApp.pages(handle));
        PagesMediaApp.awaitPagesList();
        String listedHref = $("tr[data-page-slug='about'] td.data a").getAttribute("href");
        assertTrue(listedHref != null && listedHref.endsWith("/" + handle + "/about"),
                "the Pages list must link a published page at /<handle>/<slug>, got: " + listedHref);
        assertEquals(200, statusOf(listedHref),
                "the URL the Pages list hands an author must resolve, got a 404 from " + listedHref);

        // --- the page appears in the theme's nav, and clicking it arrives ----
        logout();
        openPath("/" + handle + "/");
        $$("li.page-nav-item a").findBy(text("About Us")).should(exist).click();
        webdriver().shouldHave(urlContaining("/" + handle + "/about"));
        $("h1.pf-entry-title").shouldHave(text("About Us"));
        BrowserHealth.current().assertNoBrokenResources();
        BrowserHealth.current().assertNoFailedRequests();

        // --- unticking "show in navigation" hides the link, not the page -----
        loginAsAdmin();
        openPageByTitle(handle, "About Us");
        $("input[name='bean.showInNav']").shouldBe(checked).click();
        saveOpenPage();

        logout();

        openPath("/" + handle + "/");
        $$("li.page-nav-item a").findBy(text("About Us")).shouldNot(exist);
        BrowserHealth.current().assertNoBrokenResources();
        BrowserHealth.current().assertNoFailedRequests();

        assertEquals(200, statusOf(pageUrl),
                "the page URL must keep resolving for a reader even once it is hidden from navigation");
    }

    /**
     * Page revisions, end to end (spec 2026-11-15, Part 2): a save that
     * changes the content keeps what it replaced; the editor lists it, shows
     * the difference, and puts it back, keeping what the restore replaced.
     */
    @Test
    void aChangedPageKeepsARevisionTheEditorCanCompareAndRestore() {
        String handle = createWeblog();

        openPath(PagesMediaApp.newPage(handle));
        PagesMediaApp.awaitPageEditor();
        $("#page_bean_slug").setValue("history");
        $("#page_bean_title").setValue("History");
        Editor.setText("The first version.");
        saveOpenPage();
        $$("#pageRevisionsCard").shouldHave(com.codeborne.selenide.CollectionCondition.size(0));

        Editor.setText("The second version.");
        saveOpenPage();
        $$("#pageRevisionsTable .revision-restore-button")
                .shouldHave(com.codeborne.selenide.CollectionCondition.size(1));

        $(".revision-diff-button").click();
        $("#revisionDiffBody").shouldHave(text("The first version."), RENDER);
        $("#revisionDiffBody").shouldHave(text("The second version."));
        $("#revision-diff-modal .btn-close").click();
        $("#revision-diff-modal").shouldNotBe(visible);

        $(".revision-restore-button").click();
        $("#messages").shouldHave(text("restored"));
        assertTrue(Editor.getText().contains("The first version."),
                "the editor must show the restored content, got: " + Editor.getText());
        $$("#pageRevisionsTable .revision-restore-button")
                .shouldHave(com.codeborne.selenide.CollectionCondition.size(2));
        BrowserHealth.current().assertNoBrokenResources();
        BrowserHealth.current().assertNoFailedRequests();
    }

    /**
     * The page trash, end to end (spec 2026-11-15, Part 1): deleting a
     * published page takes it off the site but keeps it; it comes back as a
     * draft; and only deleting it forever frees its address.
     */
    @Test
    void aDeletedPageWaitsInTheTrashAndComesBackAsADraft() {
        String handle = createWeblog();
        String pageUrl = baseUrl() + "/" + handle + "/terms";
        String row = "tr.trashed-page-row[data-page-slug='terms']";

        openPath(PagesMediaApp.newPage(handle));
        PagesMediaApp.awaitPageEditor();
        $("#page_bean_slug").setValue("terms");
        $("#page_bean_title").setValue("Terms of Use");
        Editor.setText("The terms.");
        $("#page_bean_status").selectOptionByValue("PUBLISHED");
        saveOpenPage();
        assertEquals(200, statusOf(pageUrl), "the published page must resolve before it is trashed");

        // Move to the trash from the Pages screen.
        openPath(PagesMediaApp.pages(handle));
        PagesMediaApp.awaitPagesList();
        $("tr[data-page-slug='terms'] button.page-delete-btn").click();
        PagesMediaApp.confirmDialog();
        $("#messages").shouldHave(text("trash"));
        $$("tr[data-page-slug='terms']").shouldHave(com.codeborne.selenide.CollectionCondition.size(0));
        assertEquals(404, statusOf(pageUrl), "a trashed page must not be served");

        // Its address is held: a new page may not take it, and the editor says why.
        openPath(PagesMediaApp.newPage(handle));
        PagesMediaApp.awaitPageEditor();
        $("#page_bean_slug").setValue("terms");
        $("#page_bean_title").setValue("Terms again");
        Editor.setText("Other terms.");
        // The API refuses the save with a 409 (the address is held): declared, so the health check stays on.
        BrowserHealth.current().expectRefusal("/pages");
        $("#pageSaveButton").click();
        $("#errors").shouldHave(text("trash"));
        BrowserHealth.current().settle();

        // Restore: back on the Pages list, as a draft, still not served.
        openPath(PagesMediaApp.pages(handle));
        PagesMediaApp.awaitPagesList();
        $("a.pages-trash-link").should(exist).click();
        $("#pages-trash-list-marker").should(exist);
        $(row + " button.page-restore-btn").should(exist).click();
        $("#messages").shouldHave(text("draft"));
        $$(row).shouldHave(com.codeborne.selenide.CollectionCondition.size(0));
        openPath(PagesMediaApp.pages(handle));
        PagesMediaApp.awaitPagesList();
        $("tr[data-page-slug='terms']").should(exist);
        assertEquals(404, statusOf(pageUrl), "a restored page is a draft, never republished");

        // Trash it again and delete it forever: the address is free.
        $("tr[data-page-slug='terms'] button.page-delete-btn").click();
        PagesMediaApp.confirmDialog();
        $$("tr[data-page-slug='terms']").shouldHave(com.codeborne.selenide.CollectionCondition.size(0));
        deletePageForeverFromTrash(handle, "terms");
        openPath(PagesMediaApp.newPage(handle));
        PagesMediaApp.awaitPageEditor();
        $("#page_bean_slug").setValue("terms");
        $("#page_bean_title").setValue("Terms, rewritten");
        Editor.setText("New terms.");
        saveOpenPage();
        openPath(PagesMediaApp.pages(handle));
        PagesMediaApp.awaitPagesList();
        $("tr[data-page-slug='terms']").should(exist);
        BrowserHealth.current().assertNoBrokenResources();
        BrowserHealth.current().assertNoFailedRequests();
    }

    /**
     * The page editor previews the way the entry editor does: an iframe onto
     * the weblog's own theme, fed over postMessage as the author types.
     *
     * <p>Mirrors {@code MarkdownPreviewIT.theSplitPaneIsTheThemeAndFollowsTyping}
     * on purpose. A page renders through the same shortcode/Markdown/sanitize
     * pipeline an entry does, so an author writing a page has exactly the same
     * reason to see it set the way it will publish -- and the preview posts to
     * its own endpoint ({@code pageEdit!preview.rol}), which nothing else
     * exercises.
     */
    @Test
    void theSplitPreviewIsTheThemeAndFollowsTyping() {
        openPageEditorInWriteMode();
        Editor.setText("## Field notes\n\nSome *emphasis*.");

        $(Editor.MODE_SPLIT).should(visible).click();
        switchTo().frame($(Editor.PREVIEW_FRAME).shouldBe(visible));
        $("link[rel='stylesheet'][href*='journal']").should(exist);
        $(PREVIEW_ARTICLE + " h2").shouldHave(text("Field notes"), RENDER);
        switchTo().defaultContent();

        Editor.type("\n\nA new paragraph.");
        switchTo().frame($(Editor.PREVIEW_FRAME));
        $(PREVIEW_ARTICLE + " p").shouldHave(text("A new paragraph."), RENDER);
        switchTo().defaultContent();

        // The mode is remembered in localStorage for the whole install, so a
        // test that left preview mode on would hide .editor-write from every
        // later test in this browser.
        $(Editor.MODE_WRITE).click();
    }

    /**
     * Ctrl/Cmd+S saves the open page from inside the editor.
     *
     * <p>The page editor has one Save button, so save and publish collapse to
     * the same action -- but the binding still has to be the editor-level one:
     * CodeMirror's own default keymap owns Mod-Enter, and the document-level
     * handler bails on an already-handled event. Only a real keypress through
     * a real editor can tell you which keymap won.
     */
    @Test
    void ctrlSInsideTheEditorSavesTheOpenPage() {
        String slug = "shortcut" + nonce();

        openPageEditorInWriteMode();
        $("#page_bean_slug").setValue(slug);
        $("#page_bean_title").setValue("Saved by keyboard");
        Editor.type("Saved from the keyboard.");

        $(Editor.CONTENT).sendKeys(Keys.chord(Keys.CONTROL, "s"));

        // One save landed: the add form became the edit form, carrying an id.
        $("#messages").should(exist);
        webdriver().shouldNotHave(urlContaining("/pages/new"));
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Opens the shared weblog's page editor with the write pane showing.
     *
     * <p>The mode control persists in {@code localStorage} and the browser
     * outlives a single test, so a previous Preview click is still in force
     * when the next test opens the page -- and in preview mode
     * {@code .editor-write} is {@code display:none}, where
     * {@code Editor.setText} would wait forever for a pane never shown. Same
     * guard {@code MarkdownPreviewIT} uses, for the same reason.
     */
    private void openPageEditorInWriteMode() {
        openPath(PAGE_EDIT_ON_SHARED);
        PagesMediaApp.awaitPageEditor();
        $(Editor.MODE_WRITE).click();
        $(Editor.ROOT).shouldBe(visible);
    }

    /**
     * Reordering on the Pages screen reorders the blog's navigation: three
     * published pages in the nav (title order to begin with), the last moved
     * up twice with the buttons, the order saved, and the public nav lists
     * them the new way round. Buttons only; the drag shortcut is the same
     * shared script and is not driven here.
     */
    @Test
    void movingAPageUpOnThePagesScreenReordersTheNav() {
        String handle = createWeblog();
        for (String title : new String[] {"Alpha", "Bravo", "Charlie"}) {
            openPath(PagesMediaApp.newPage(handle));
            PagesMediaApp.awaitPageEditor();
            $("#page_bean_slug").setValue(title.toLowerCase(java.util.Locale.ROOT));
            $("#page_bean_title").setValue(title);
            $("#page_bean_status").selectOptionByValue("PUBLISHED");
            saveOpenPage();
        }

        openPath(PagesMediaApp.pages(handle));
        PagesMediaApp.awaitPagesList();
        $("#pagesOrderSave").shouldNotBe(visible);
        $$("tr.page-row").shouldHave(com.codeborne.selenide.CollectionCondition.size(3));

        // Charlie is last: up twice puts it first.
        $("tr[data-page-slug='charlie'] .page-order-up").click();
        $("tr[data-page-slug='charlie'] .page-order-up").click();
        $("#pagesOrderSave").shouldBe(visible).click();
        $("#messages").should(exist);
        BrowserHealth.current().settle();

        // The saved order is what the screen lists now ...
        $$("tr.page-row").first().shouldHave(com.codeborne.selenide.Condition.attribute(
                "data-page-slug", "charlie"));

        // ... and what a reader's navigation shows.
        logout();
        openPath("/" + handle + "/");
        $$("li.page-nav-item a").shouldHave(com.codeborne.selenide.CollectionCondition.texts(
                "Charlie", "Alpha", "Bravo"));
        BrowserHealth.current().assertNoBrokenResources();
        BrowserHealth.current().assertNoFailedRequests();
    }

    /**
     * Creates a weblog owned by the seeded admin on the {@code portfolio}
     * theme -- see the class comment for why it owns a weblog at all.
     */
    private String createWeblog() {
        String handle = "pageit" + nonce();

        createWeblogOnTheReactScreen(handle, "Pages " + handle, "portfolio");
        return handle;
    }

    /** Opens a page for editing from the list, found by its title link text. */
    private void openPageByTitle(String handle, String title) {
        openPath(PagesMediaApp.pages(handle));
        PagesMediaApp.awaitPagesList();
        $$("tr.page-row td a").findBy(text(title)).should(exist).click();
        PagesMediaApp.awaitPageEditor();
    }

    /** Submits the page edit form and waits for the save to land. */
    private void saveOpenPage() {
        PagesMediaApp.savePage();
        BrowserHealth.current().settle();
    }

    /** Status of an anonymous GET -- the public visibility of a URL, not the author's view. */
    private int statusOf(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(20))
                    .GET()
                    .build();
            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode();
        } catch (Exception e) {
            throw new IllegalStateException("Could not GET " + url, e);
        }
    }

    private static String truncate(String text) {
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= 600 ? flat : flat.substring(0, 600) + "...";
    }

    private static String nonce() {
        return Long.toString(System.nanoTime(), 36);
    }
}

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

import java.time.Duration;

import com.codeborne.selenide.WebDriverRunner;
import com.wikantik.pressroom.it.support.Editor;
import com.wikantik.pressroom.it.support.PressroomIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.Keys;

import static com.codeborne.selenide.CollectionCondition.size;
import static com.codeborne.selenide.Condition.exactText;
import static com.codeborne.selenide.Condition.exist;
import static com.codeborne.selenide.Condition.text;
import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selenide.$;
import static com.codeborne.selenide.Selenide.$$;
import static com.codeborne.selenide.Selenide.executeJavaScript;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Search, from writing a post to a reader finding it.
 *
 * <p>Nothing proved search actually worked. The public surface test opens the
 * search page and checks it renders, which it would do just as happily with a
 * broken index behind it -- and the index is a separate store that can drift
 * from the database without anything failing. That mattered the moment Lucene
 * went from 9 to 10.
 *
 * <p>The two directions are both worth holding. An entry that is published and
 * cannot be found is a feature quietly not working; an entry that is deleted
 * and can still be found is worse, because the result links to a page that no
 * longer exists. The second is why entry deletion goes through
 * {@code trashEntryWithIndex} rather than a plain delete.
 *
 * <p>Indexing is asynchronous, so every assertion here polls rather than
 * checking once: the alternative is a test that passes or fails on how busy
 * the machine is.
 */
class SearchIT extends PressroomIT {

    private static final String ENTRY_ADD = "/pressroom-ui/authoring/entryAdd.rol?weblog=" + WEBLOG_HANDLE;

    private static final String PERMALINK = "#entry_bean_permalink";

    /** Generous: a background index operation competes with the whole suite. */
    private static final Duration INDEX_TIMEOUT = Duration.ofSeconds(45);

    /**
     * How long to keep checking after the index first reports an entry gone,
     * to catch a delayed re-add rather than just the eventual removal. See
     * {@link #assertStaysAbsentFromSearch}.
     */
    private static final Duration SUSTAINED_ABSENCE_WINDOW = Duration.ofSeconds(10);

    @BeforeEach
    void logIn() {
        loginAsAdmin();
    }

    @Test
    void aPublishedEntryBecomesFindableAndStopsBeingSoWhenDeleted() {
        String term = "zeppelin" + Long.toString(System.nanoTime(), 36);
        String title = "IT Search " + term;

        String entryId = publishEntry(title, "A post about " + term + " and nothing else.");

        assertTrue(waitForSearchToReport(term, title, true),
                "a published entry never became findable; either indexing did not run "
                        + "or the query path is broken");

        deleteEntry(entryId);

        assertTrue(waitForSearchToReport(term, title, false),
                "a deleted entry is still in the search index, so readers get results "
                        + "linking to a page that no longer exists");

        // waitForSearchToReport returns at the FIRST poll that finds the entry
        // gone, which proves the de-index eventually happened but not that it
        // sticks -- and this wave's de-index seam (BaseController's trash
        // helpers) had exactly that failure mode once already: an
        // asynchronous re-index job re-fetched the entry from the database
        // and put it right back into the index a moment after the caller that
        // removed it had already returned. Keep polling past the first clean
        // result and fail the instant the entry comes back.
        assertStaysAbsentFromSearch(term, title, SUSTAINED_ABSENCE_WINDOW);
    }

    /**
     * Drafts are not published, so they must never surface to a reader through
     * search -- which is a different code path from the draft not appearing on
     * the front page, and so needs its own check.
     */
    @Test
    void anUnpublishedDraftIsNeverFindable() {
        String term = "sequoia" + Long.toString(System.nanoTime(), 36);
        String title = "IT Search draft " + term;

        saveDraft(title, "A draft mentioning " + term + ".");

        // Give indexing the same chance it gets above before concluding the
        // draft stayed out; a pass here must mean "not indexed", not "not yet".
        assertTrue(waitForSearchToReport(term, title, false),
                "an unpublished draft is findable through public search");
        sleepFor(Duration.ofSeconds(5));
        assertTrue(!searchFinds(term, title),
                "an unpublished draft became findable once indexing settled");
    }

    // ------------------------------------------------- the search boxes

    /**
     * Blog A's home page has a search box in its header; typing in it lists
     * A's posts under A's name first, then a post of blog B under "other
     * blogs", naming B.
     */
    @Test
    void searchFromBlogHomeShowsThisBlogThenOtherBlogs() {
        String term = "quokka" + nonce();
        String nameA = "Box Alpha " + nonce();
        String nameB = "Box Beta " + nonce();
        String handleA = createWeblog(nameA);
        String handleB = createWeblog(nameB);
        String titleA = "Alpha " + term;
        String titleB = "Beta " + term;
        publishEntryOn(handleA, titleA, "Alpha writes about " + term + ".");
        publishEntryOn(handleB, titleB, "Beta writes about " + term + ".");
        logout();
        assertTrue(waitForPage(baseUrl() + "/" + handleA + "/search?q=" + term, titleA, titleB),
                "the index never held both posts");

        openPath("/" + handleA + "/");
        $("header form.site-search input[type=search]").shouldBe(visible).setValue(term).sendKeys(Keys.ENTER);

        $("h2.search-group").shouldHave(text(nameA));
        $("section.search-results li.search-hit h3.search-title a").shouldHave(exactText(titleA));
        $("h2.search-group-other").shouldBe(visible);
        $$("h2.search-group-other ~ ol li.search-hit").shouldHave(size(1));
        $("h2.search-group-other ~ ol li.search-hit h3.search-title a").shouldHave(exactText(titleB));
        $("h2.search-group-other ~ ol li.search-hit .search-blog").shouldHave(exactText(nameB));
    }

    /** The front page's masthead box searches every blog: posts of several blogs in one ranked list. */
    @Test
    void searchFromFrontPageListsPostsOfSeveralBlogs() {
        String term = "marmot" + nonce();
        String nameA = "Front Alpha " + nonce();
        String nameB = "Front Beta " + nonce();
        String handleA = createWeblog(nameA);
        String handleB = createWeblog(nameB);
        String titleA = "Alpha " + term;
        String titleB = "Beta " + term;
        publishEntryOn(handleA, titleA, "Alpha writes about " + term + ".");
        publishEntryOn(handleB, titleB, "Beta writes about " + term + ".");
        logout();
        assertTrue(waitForPage(baseUrl() + "/search?q=" + term, titleA, titleB),
                "the site search never listed both posts");

        openPath("/");
        $(".fd-mast form.site-search input[type=search]").shouldBe(visible).setValue(term).sendKeys(Keys.ENTER);

        assertTrue(WebDriverRunner.url().contains("/search?q=" + term),
                "the front page's box must post to the site search, got " + WebDriverRunner.url());
        $$("li.search-hit h3.search-title a").shouldHave(size(2));
        $$("li.search-hit .search-blog").shouldHave(size(2));
        $("section.search-results").shouldHave(text(nameA)).shouldHave(text(nameB));
    }

    /** More than eight matches in this blog: the "show all" link pages that one blog and drops the others. */
    @Test
    void showAllLinkPagesOneBlog() {
        String term = "ocelot" + nonce();
        String nameA = "Many Alpha " + nonce();
        String nameB = "Many Beta " + nonce();
        String handleA = createWeblog(nameA);
        String handleB = createWeblog(nameB);
        for (int i = 0; i < 9; i++) {
            publishEntryOn(handleA, "Many " + i + " " + term, "Post " + i + " about " + term + ".");
        }
        String titleB = "Other " + term;
        publishEntryOn(handleB, titleB, "Beta writes about " + term + ".");
        logout();
        assertTrue(waitForPage(baseUrl() + "/" + handleA + "/search?q=" + term, "Many 8 " + term, titleB),
                "the index never held every post");

        openPath("/" + handleA + "/");
        $("header form.site-search input[type=search]").shouldBe(visible).setValue(term).sendKeys(Keys.ENTER);
        $$("section.search-results ol").first().$$("li.search-hit").shouldHave(size(8));
        $("a.search-all").shouldBe(visible).click();

        assertTrue(WebDriverRunner.url().contains("scope=blog"), "got " + WebDriverRunner.url());
        $$("li.search-hit").shouldHave(size(9));
        $("h2.search-group-other").shouldNot(exist);
        // the page's "more from the site" panel may name B's post; the results must not
        assertFalse($("section.search-results").text().contains(titleB),
                "another blog's post among the results of a one-blog page");
        assertEquals(1, $$("h2.search-group").size(), "one heading on a one-blog page");
    }

    private String createWeblog(String name) {
        loginAsAdmin();
        String handle = "box" + nonce();
        openPath("/pressroom-ui/createWeblog.rol");
        $("#name").should(visible).setValue(name);
        $("#handle").setValue(handle);
        $("#emailAddress").setValue(handle + "@example.invalid");
        $("select[name='theme']").selectOptionByValue("journal");
        clickAndAwaitNewPage($("button[type='submit']"));
        $("#messages").should(exist);
        return handle;
    }

    private void publishEntryOn(String handle, String title, String body) {
        openPath("/pressroom-ui/authoring/entryAdd.rol?weblog=" + handle);
        writeEntry(title, body);
        $("button[formaction$='entryAdd!publish.rol']").click();
        $(PERMALINK).should(exist);
    }

    /** Polls an anonymous GET until the page holds every needle: the index is asynchronous. */
    private boolean waitForPage(String url, String... needles) {
        long deadline = System.currentTimeMillis() + INDEX_TIMEOUT.toMillis();
        do {
            String page = getAnonymously(url);
            boolean all = true;
            for (String needle : needles) {
                all &= page.contains(needle);
            }
            if (all) {
                return true;
            }
            sleepFor(Duration.ofSeconds(2));
        } while (System.currentTimeMillis() < deadline);
        return false;
    }

    private static String nonce() {
        return Long.toString(System.nanoTime(), 36);
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Polls public search until it reports {@code expected}, or the timeout
     * expires.
     *
     * @return whether search reached the expected state
     */
    private boolean waitForSearchToReport(String term, String title, boolean expected) {
        long deadline = System.currentTimeMillis() + INDEX_TIMEOUT.toMillis();
        do {
            if (searchFinds(term, title) == expected) {
                return true;
            }
            sleepFor(Duration.ofSeconds(2));
        } while (System.currentTimeMillis() < deadline);
        return searchFinds(term, title) == expected;
    }

    /**
     * Polls search for the whole window and fails the instant the entry is
     * found, rather than stopping at the first clean poll the way
     * {@link #waitForSearchToReport} does. That early return is right for
     * "did the index eventually catch up" but wrong for "does it stay caught
     * up" -- a delayed re-add would sail through a single check.
     */
    private void assertStaysAbsentFromSearch(String term, String title, Duration window) {
        long deadline = System.currentTimeMillis() + window.toMillis();
        do {
            assertFalse(searchFinds(term, title),
                    "a deleted entry reappeared in search results after having been "
                            + "removed -- the index is not staying de-indexed");
            sleepFor(Duration.ofSeconds(2));
        } while (System.currentTimeMillis() < deadline);
    }

    /**
     * One anonymous search, exactly as a reader's browser would issue it.
     *
     * <p>Matches on the entry TITLE rather than the search term: the results
     * page echoes the query back in its heading and its search-again box, so
     * looking for the term itself reports a hit even when there are none. The
     * journal theme's search page carries only the site header, nav links and
     * a search-again box -- no entry titles -- so a title on this page means
     * a result.
     */
    private boolean searchFinds(String term, String title) {
        String results = getAnonymously(baseUrl() + "/" + WEBLOG_HANDLE + "/search?q=" + term);
        return results.contains(title);
    }

    private String publishEntry(String title, String body) {
        openPath(ENTRY_ADD);
        writeEntry(title, body);
        $("button[formaction$='entryAdd!publish.rol']").click();
        $(PERMALINK).should(exist);
        return entryId();
    }

    private void saveDraft(String title, String body) {
        openPath(ENTRY_ADD);
        writeEntry(title, body);
        $("button[formaction$='entryAdd!saveDraft.rol']").click();
        $("input[name='bean.id']").should(exist);
    }

    private void writeEntry(String title, String body) {
        $("#entry").should(exist);
        $("[name='bean.title']").setValue(title);
        Editor.setText(body);
    }

    private String entryId() {
        String id = $("input[name='bean.id']").getValue();
        assertNotNull(id, "the editor must expose the saved entry's id");
        return id;
    }

    private void deleteEntry(String entryId) {
        openPath("/pressroom-ui/authoring/entries.rol?weblog=" + WEBLOG_HANDLE);
        executeJavaScript("showDeleteModal(arguments[0], 'x');", entryId);
        $("#delete-entry-modal").shouldBe(visible);
        $("#delete-entry-modal button[type='submit']").click();
        $("input[name='selectedEntries'][value='" + entryId + "']").should(
                com.codeborne.selenide.Condition.disappear);
    }

    private static void sleepFor(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for indexing", e);
        }
    }
}

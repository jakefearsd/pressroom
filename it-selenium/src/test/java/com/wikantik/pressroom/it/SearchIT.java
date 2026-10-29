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

import com.wikantik.pressroom.it.support.Editor;
import com.wikantik.pressroom.it.support.PressroomIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.codeborne.selenide.Condition.exist;
import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selenide.$;
import static com.codeborne.selenide.Selenide.executeJavaScript;
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

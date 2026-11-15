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
package com.wikantik.pressroom.business.jpa;

import java.util.Date;

import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.business.WeblogManager;
import com.wikantik.pressroom.business.WeblogPageManager;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The front-door invariant, kept at the write: a blog's front page is cleared
 * in the same save that makes it a non-PUBLISHED page or removes it, in the
 * managed weblog as well as the row, and republishing does not restore it.
 */
class FrontPageInvariantTest {

    private User user;
    private Weblog weblog;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.setupWeblogger();
        user = TestUtils.setupUser("frontpageuser");
        weblog = TestUtils.setupWeblog("frontpageblog", user);
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.endSession(false);
        if (weblogs().getWeblog(weblog.getId()) != null) {
            TestUtils.teardownWeblog(weblog.getId());
        }
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    @Test
    void savingTheFrontPageAsADraftClearsItAndBumpsTheStamp() throws Exception {
        WeblogPage about = savePage("about");
        makeFrontPage(about);
        Date before = managed().getLastModified();
        pause();

        WeblogPage loaded = pages().getPage(about.getId());
        loaded.setStatus(WeblogPage.PubStatus.DRAFT);
        pages().savePage(loaded);
        TestUtils.endSession(true);

        assertNull(managed().getFrontPageId());
        assertTrue(managed().getLastModified().after(before), "the stamp moved");
    }

    @Test
    void removingTheFrontPageClearsItInTheManagedWeblogAndTheRow() throws Exception {
        WeblogPage about = savePage("about");
        makeFrontPage(about);

        Weblog inMemory = managed();
        assertEquals(about.getId(), inMemory.getFrontPageId());
        Date before = inMemory.getLastModified();
        pause();
        pages().removePage(pages().getPage(about.getId()));

        assertNull(inMemory.getFrontPageId(), "the in-memory weblog loses the id");
        TestUtils.endSession(true);
        assertNull(managed().getFrontPageId(), "and so does the row");
        assertTrue(managed().getLastModified().after(before), "the stamp moved");
    }

    @Test
    void removingEveryPageOfTheWeblogClearsTheFrontPage() throws Exception {
        WeblogPage about = savePage("about");
        makeFrontPage(about);

        Weblog inMemory = managed();
        pages().removePages(inMemory);

        assertNull(inMemory.getFrontPageId(), "the in-memory weblog loses the id");
        TestUtils.endSession(true);
        assertNull(managed().getFrontPageId(), "and so does the row");
    }

    /**
     * Removing a whole weblog whose front page is set, end to end on the real
     * database: weblog.front_page_id references one of the pages being
     * removed, and nothing fails on that foreign key. A characterisation test:
     * it passed on its first run, and still passes with removePages' in-memory
     * clear disabled, because V049's ON DELETE SET NULL is what keeps the bulk
     * page delete legal. With the page delete itself disabled it fails on the
     * pages' weblog foreign key, so it does see a removal that cannot finish.
     */
    @Test
    void removingAWeblogWithAFrontPageRemovesTheWeblogAndItsPages() throws Exception {
        WeblogPage about = savePage("about");
        WeblogPage contact = savePage("contact");
        makeFrontPage(about);

        weblogs().removeWeblog(managed());
        TestUtils.endSession(true);

        assertNull(weblogs().getWeblog(weblog.getId()), "the weblog is gone");
        assertNull(pages().getPage(about.getId()), "its front page is gone");
        assertNull(pages().getPage(contact.getId()), "and its other pages");
    }

    @Test
    void savingADifferentPageLeavesTheFrontPage() throws Exception {
        WeblogPage about = savePage("about");
        makeFrontPage(about);
        WeblogPage contact = pages().getPage(savePage("contact").getId());
        contact.setStatus(WeblogPage.PubStatus.DRAFT);
        pages().savePage(contact);
        pages().removePage(pages().getPage(contact.getId()));
        TestUtils.endSession(true);

        assertEquals(about.getId(), managed().getFrontPageId());
    }

    @Test
    void savingTheFrontPagePublishedKeepsItAndRepublishingDoesNotRestoreIt() throws Exception {
        WeblogPage about = savePage("about");
        makeFrontPage(about);
        WeblogPage loaded = pages().getPage(about.getId());
        loaded.setTitle("About us");
        pages().savePage(loaded);
        TestUtils.endSession(true);
        assertEquals(about.getId(), managed().getFrontPageId(), "a published save keeps it");

        loaded = pages().getPage(about.getId());
        loaded.setStatus(WeblogPage.PubStatus.DRAFT);
        pages().savePage(loaded);
        TestUtils.endSession(true);
        loaded = pages().getPage(about.getId());
        loaded.setStatus(WeblogPage.PubStatus.PUBLISHED);
        pages().savePage(loaded);
        TestUtils.endSession(true);

        assertNull(managed().getFrontPageId(), "republishing does not restore it");
    }

    private void makeFrontPage(WeblogPage page) throws Exception {
        Weblog w = managed();
        w.setFrontPageId(page.getId());
        weblogs().saveWeblog(w);
        TestUtils.endSession(true);
    }

    private WeblogPage savePage(String slug) throws Exception {
        WeblogPage page = new WeblogPage();
        page.setWeblog(managed());
        page.setSlug(slug);
        page.setTitle(slug);
        page.setStatus(WeblogPage.PubStatus.PUBLISHED);
        page.setNavOrder(10);
        pages().savePage(page);
        TestUtils.endSession(true);
        return page;
    }

    /** lastModified is millisecond-stamped; let the clock move between writes. */
    private static void pause() throws InterruptedException {
        Thread.sleep(20);
    }

    private Weblog managed() throws Exception {
        return TestUtils.getManagedWebsite(weblog);
    }

    private static WeblogManager weblogs() {
        return TestUtils.weblogger().getWeblogManager();
    }

    private static WeblogPageManager pages() {
        return TestUtils.weblogger().getWeblogPageManager();
    }
}

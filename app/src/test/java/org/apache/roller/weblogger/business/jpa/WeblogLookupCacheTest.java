/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  The ASF licenses this file to You
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
package org.apache.roller.weblogger.business.jpa;

import java.util.Date;
import java.util.List;

import org.apache.roller.weblogger.TestUtils;
import org.apache.roller.weblogger.business.WeblogManager;
import org.apache.roller.weblogger.business.WeblogPageManager;
import org.apache.roller.weblogger.business.themes.ThemeManager;
import org.apache.roller.weblogger.pojos.CustomTemplateRendition;
import org.apache.roller.weblogger.pojos.ThemeTemplate;
import org.apache.roller.weblogger.pojos.ThemeTemplate.ComponentType;
import org.apache.roller.weblogger.pojos.TemplateRendition.RenditionType;
import org.apache.roller.weblogger.pojos.TemplateRendition.TemplateLanguage;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogPage;
import org.apache.roller.weblogger.pojos.WeblogTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The remembered per-weblog answers behind every render: the weblog's
 * template for an action (the stylesheet override above all, usually absent)
 * and its published pages.
 *
 * <p>Each answer is held against the {@code weblog.lastModified} it was read
 * at, so every mutation path below must move that stamp, and a unit of work
 * that has written anything must neither read nor fill the cache: its stamp
 * may be one that never commits, or one that commits with changes its own
 * no-flush queries could not see.
 */
class WeblogLookupCacheTest {

    private User user;
    private Weblog weblog;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.setupWeblogger();
        user = TestUtils.setupUser("lookupcacheuser");
        weblog = TestUtils.setupWeblog("lookupcacheblog", user);
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

    // ------------------------------------------------------ template by action

    @Test
    void anAbsentStylesheetIsRememberedAsAbsent() throws Exception {
        assertNull(stylesheet());
        TestUtils.endSession(false);

        List<String> queries = QueryRecorder.during(() -> assertNull(stylesheet()));

        assertEquals(List.of(), QueryRecorder.on(queries, WeblogTemplate.class),
                "an absent override must not be looked for again: " + queries);
    }

    @Test
    void aRememberedTemplateIsResolvedByIdWithoutAQuery() throws Exception {
        saveStylesheet("body { color: red }");
        assertNotNull(stylesheet());
        TestUtils.endSession(false);

        List<String> queries = QueryRecorder.during(() -> assertNotNull(stylesheet()));

        assertEquals(List.of(), QueryRecorder.sqlOn(queries, "weblog_custom_template"),
                "a remembered override is found by id in the shared cache: " + queries);
    }

    @Test
    void aCreatedStylesheetReplacesARememberedAbsence() throws Exception {
        assertNull(stylesheet());
        TestUtils.endSession(false);

        saveStylesheet("body { color: red }");

        assertNotNull(stylesheet(), "template create must move the stamp");
    }

    @Test
    void anEditedStylesheetIsReadAsSaved() throws Exception {
        saveStylesheet("body { color: red }");
        assertEquals("body { color: red }", stylesheetCode());
        TestUtils.endSession(false);

        WeblogTemplate managed = stylesheet();
        managed.getTemplateRendition(RenditionType.STANDARD).setTemplate("body { color: blue }");
        weblogs().saveTemplateRendition(managed.getTemplateRendition(RenditionType.STANDARD));
        TestUtils.endSession(true);

        assertEquals("body { color: blue }", stylesheetCode(),
                "the cache holds the template's id, never its content");
    }

    @Test
    void aDeletedStylesheetIsGone() throws Exception {
        saveStylesheet("body { color: red }");
        assertNotNull(stylesheet());
        TestUtils.endSession(false);

        weblogs().removeTemplate(stylesheet());
        TestUtils.endSession(true);

        assertNull(stylesheet(), "template delete must move the stamp");
    }

    @Test
    void aThemeSwitchReplacesTheRememberedTemplates() throws Exception {
        ThemeManager themes = TestUtils.weblogger().getThemeManager();
        assertNull(template(ComponentType.WEBLOG));
        ThemeTemplate shared = themes.getTheme(managedWeblog()).getStylesheet();
        assertFalse(shared instanceof WeblogTemplate, "the shared theme's own stylesheet");
        TestUtils.endSession(false);

        // To a custom theme: the import writes the weblog's own templates.
        Weblog managed = managedWeblog();
        themes.importTheme(managed, themes.getTheme("journal"), false);
        managed.setEditorTheme("custom");
        weblogs().saveWeblog(managed);
        TestUtils.endSession(true);

        assertNotNull(template(ComponentType.WEBLOG), "the imported weblog template");
        assertNotNull(template(ComponentType.STYLESHEET), "the imported stylesheet");
        TestUtils.endSession(false);

        // Back to a shared theme, as ThemeEditController does it: the
        // weblog's stylesheet override goes, the shared one shows through.
        managed = managedWeblog();
        weblogs().removeTemplate(weblogs().getTemplateByAction(managed, ComponentType.STYLESHEET));
        managed.setEditorTheme("journal");
        weblogs().saveWeblog(managed);
        TestUtils.endSession(true);

        assertNull(template(ComponentType.STYLESHEET));
        assertFalse(themes.getTheme(managedWeblog()).getStylesheet() instanceof WeblogTemplate,
                "the shared stylesheet again, not a remembered override");
    }

    /**
     * The realistic poisoning: a theme import saves a template and then looks
     * templates up by action in the same unit of work. Those queries never
     * flush, so they cannot see the new row, yet the weblog's in-memory stamp
     * has already moved to the value that is about to commit. Remembered, that
     * pair would answer "absent" for the committed stamp from then on.
     */
    @Test
    void aLookupInTheUnitOfWorkThatSavedIsNotRemembered() throws Exception {
        saveStylesheetWithoutCommit("body { color: red }");
        stylesheet(); // whatever this unit of work sees
        TestUtils.weblogger().flush();
        TestUtils.endSession(false);

        assertNotNull(stylesheet(), "the committed template must be found");
    }

    /**
     * A unit of work that moved the stamp, looked the stylesheet up, and
     * rolled back. Its stamp never commits, but nothing stops a later commit
     * from landing on the same millisecond, which this simulates with a
     * commit that adds a stylesheet; an "absent" remembered from inside the
     * rolled-back unit of work would then hide it.
     */
    @Test
    void aRolledBackUnitOfWorkLeavesNothingBehind() throws Exception {
        weblogs().saveWeblog(managedWeblog());
        assertNull(stylesheet());
        Date rolledBack = managedWeblog().getLastModified();
        TestUtils.endSession(false);

        saveStylesheetWithoutCommit("body { color: red }");
        landTheCommitOn(rolledBack);

        assertNotNull(stylesheet(), "nothing from the rolled-back unit of work is remembered");
    }

    // --------------------------------------------------------- published pages

    @Test
    void thePublishedPagesAreRememberedAndResolvedById() throws Exception {
        savePage("about", WeblogPage.PubStatus.PUBLISHED, 10);
        assertEquals(List.of("about"), publishedSlugs());
        TestUtils.endSession(false);

        List<String> queries = QueryRecorder.during(
                () -> assertEquals(List.of("about"), publishedSlugs()));

        assertEquals(List.of(), QueryRecorder.sqlOn(queries, "roller_weblogpage"),
                "remembered pages are resolved by id in the shared cache: " + queries);
    }

    @Test
    void everyPageMutationReachesThePublishedList() throws Exception {
        assertEquals(List.of(), publishedSlugs());

        WeblogPage about = savePage("about", WeblogPage.PubStatus.PUBLISHED, 20);
        assertEquals(List.of("about"), publishedSlugs(), "page create");

        WeblogPage contact = savePage("contact", WeblogPage.PubStatus.PUBLISHED, 30);
        assertEquals(List.of("about", "contact"), publishedSlugs(), "second page");

        editPage(contact, p -> p.setNavOrder(10));
        assertEquals(List.of("contact", "about"), publishedSlugs(), "page edit (nav order)");

        editPage(about, p -> p.setStatus(WeblogPage.PubStatus.DRAFT));
        assertEquals(List.of("contact"), publishedSlugs(), "unpublish");

        editPage(about, p -> p.setStatus(WeblogPage.PubStatus.PUBLISHED));
        assertEquals(List.of("contact", "about"), publishedSlugs(), "publish");

        pages().removePage(pages().getPage(contact.getId()));
        TestUtils.endSession(true);
        assertEquals(List.of("about"), publishedSlugs(), "page delete");
    }

    /**
     * {@code removePages} deletes in bulk and moves no stamp. A remembered id
     * that no longer resolves is a miss, never a hole in the list.
     */
    @Test
    void aRememberedPageThatNoLongerExistsIsAMiss() throws Exception {
        savePage("about", WeblogPage.PubStatus.PUBLISHED, 10);
        assertEquals(List.of("about"), publishedSlugs());
        TestUtils.endSession(false);

        pages().removePages(managedWeblog());
        TestUtils.endSession(true);

        assertEquals(List.of(), publishedSlugs());
    }

    /** As {@link #aRolledBackUnitOfWorkLeavesNothingBehind}, for the page list. */
    @Test
    void aRolledBackUnitOfWorkLeavesNoPageListBehind() throws Exception {
        savePage("about", WeblogPage.PubStatus.PUBLISHED, 10);

        weblogs().saveWeblog(managedWeblog());
        assertEquals(List.of("about"), publishedSlugs());
        Date rolledBack = managedWeblog().getLastModified();
        TestUtils.endSession(false);

        WeblogPage contact = new WeblogPage();
        contact.setWeblog(managedWeblog());
        contact.setSlug("contact");
        contact.setTitle("Contact");
        contact.setStatus(WeblogPage.PubStatus.PUBLISHED);
        contact.setNavOrder(20);
        pages().savePage(contact);
        landTheCommitOn(rolledBack);

        assertEquals(List.of("about", "contact"), publishedSlugs(),
                "nothing from the rolled-back unit of work is remembered");
    }

    /**
     * An edit made to a managed page and never saved begins no transaction;
     * the lookup's no-flush query reads the committed list, which is what
     * may be remembered.
     */
    @Test
    void anUnsavedEditIsNeverRemembered() throws Exception {
        WeblogPage about = savePage("about", WeblogPage.PubStatus.PUBLISHED, 10);

        pages().getPage(about.getId()).setStatus(WeblogPage.PubStatus.DRAFT);
        publishedSlugs();
        TestUtils.endSession(false);

        assertEquals(List.of("about"), publishedSlugs());
    }

    // ------------------------------------------------------------ weblog delete

    @Test
    void aDeletedWeblogIsForgotten() throws Exception {
        savePage("about", WeblogPage.PubStatus.PUBLISHED, 10);
        publishedSlugs();
        stylesheet();
        TestUtils.endSession(false);
        JPAWeblogManagerImpl weblogManager = (JPAWeblogManagerImpl) weblogs();
        JPAWeblogPageManagerImpl pageManager = (JPAWeblogPageManagerImpl) pages();
        assertTrue(weblogManager.remembersTemplatesOf(weblog.getId()));
        assertTrue(pageManager.remembersPagesOf(weblog.getId()));

        TestUtils.teardownWeblog(weblog.getId());

        assertFalse(weblogManager.remembersTemplatesOf(weblog.getId()));
        assertFalse(pageManager.remembersPagesOf(weblog.getId()));
    }

    // ---------------------------------------------------------------- helpers

    private static WeblogManager weblogs() {
        return TestUtils.weblogger().getWeblogManager();
    }

    private static WeblogPageManager pages() {
        return TestUtils.weblogger().getWeblogPageManager();
    }

    private Weblog managedWeblog() throws Exception {
        return TestUtils.getManagedWebsite(weblog);
    }

    private WeblogTemplate template(ComponentType action) throws Exception {
        return weblogs().getTemplateByAction(managedWeblog(), action);
    }

    private WeblogTemplate stylesheet() throws Exception {
        return template(ComponentType.STYLESHEET);
    }

    private String stylesheetCode() throws Exception {
        WeblogTemplate found = stylesheet();
        assertNotNull(found);
        return found.getTemplateRendition(RenditionType.STANDARD).getTemplate();
    }

    private void saveStylesheet(String css) throws Exception {
        saveStylesheetWithoutCommit(css);
        TestUtils.endSession(true);
    }

    private void saveStylesheetWithoutCommit(String css) throws Exception {
        WeblogTemplate template = new WeblogTemplate();
        template.setWeblog(managedWeblog());
        template.setAction(ComponentType.STYLESHEET);
        template.setName("Stylesheet");
        template.setLink("styles.css");
        template.setDescription("stylesheet");
        template.setHidden(false);
        template.setNavbar(false);
        template.setLastModified(new Date());
        weblogs().saveTemplate(template);
        CustomTemplateRendition rendition =
                new CustomTemplateRendition(template, RenditionType.STANDARD);
        rendition.setTemplate(css);
        rendition.setTemplateLanguage(TemplateLanguage.VELOCITY);
        weblogs().saveTemplateRendition(rendition);
    }

    private WeblogPage savePage(String slug, WeblogPage.PubStatus status, int navOrder)
            throws Exception {
        WeblogPage page = new WeblogPage();
        page.setWeblog(managedWeblog());
        page.setSlug(slug);
        page.setTitle("Title of " + slug);
        page.setContent("Body of " + slug);
        page.setStatus(status);
        page.setShowInNav(true);
        page.setNavOrder(navOrder);
        pages().savePage(page);
        TestUtils.endSession(true);
        return page;
    }

    private interface PageEdit {
        void apply(WeblogPage page);
    }

    private void editPage(WeblogPage page, PageEdit edit) throws Exception {
        WeblogPage managed = pages().getPage(page.getId());
        edit.apply(managed);
        pages().savePage(managed);
        TestUtils.endSession(true);
    }

    /** Slugs of the published pages, in the order the list holds them. */
    private List<String> publishedSlugs() throws Exception {
        return pages().getPublishedPages(managedWeblog()).stream()
                .map(WeblogPage::getSlug).toList();
    }

    /**
     * Commits this unit of work with the weblog's {@code lastModified} set to
     * exactly {@code stamp}: a later save that happens to land on the same
     * millisecond as one that rolled back. (Production code never sets the
     * stamp directly; see {@code Weblog.touchLastModified}.)
     */
    private void landTheCommitOn(Date stamp) throws Exception {
        Weblog managed = managedWeblog();
        managed.setLastModified(stamp);
        QueryRecorder.strategy().store(managed);
        TestUtils.endSession(true);
    }
}

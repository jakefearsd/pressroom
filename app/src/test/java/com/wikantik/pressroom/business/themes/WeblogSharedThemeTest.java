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
package com.wikantik.pressroom.business.themes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.WeblogManager;
import com.wikantik.pressroom.pojos.ThemeTemplate;
import com.wikantik.pressroom.pojos.ThemeTemplate.ComponentType;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * How a weblog on a shared theme resolves its templates: the shared theme's
 * own templates first, the weblog's stored templates as the fallback, and the
 * stylesheet as the one place where the weblog's copy wins.
 *
 * <p>Characterisation tests, written against the existing behaviour and
 * expected to pass immediately. The theme is a real {@link SharedThemeFromDir}
 * built in a temporary directory (so parsing and lookup stay honest); the
 * weblog's template store is a mocked {@link WeblogManager}.
 */
class WeblogSharedThemeTest {

    private final Weblog weblog = new Weblog();
    private final WeblogManager weblogManager = mock(WeblogManager.class);

    private SharedThemeFromDir sharedTheme;
    private WeblogSharedTheme view;

    @TempDir
    Path dir;

    @BeforeEach
    void setUp() throws Exception {
        weblog.setHandle("sharedblog");
        sharedTheme = new SharedThemeFromDir(themeWithStylesheet(dir));
        view = new WeblogSharedTheme(weblog, sharedTheme, weblogManager);
    }

    // --- identity ------------------------------------------------------------

    /**
     * The theme picker and ThemeResourceLoader read the weblog's theme, not
     * the shared one; the view has to answer as the shared theme does.
     */
    @Test
    void theViewAnswersWithTheSharedThemesIdentity() {
        assertEquals("sharedtheme", view.getId());
        assertEquals("Shared Theme", view.getName());
        assertEquals("A theme for sharing", view.getDescription());
        assertEquals(sharedTheme.getLastModified(), view.getLastModified(),
                "template caching keys off this timestamp; it must be the theme's files'");
        assertNotNull(view.getLastModified());
        assertTrue(view.isEnabled());
    }

    @Test
    void viewsSortByTheSharedThemesName(@TempDir Path other) throws Exception {
        SharedThemeFromDir later = new SharedThemeFromDir(
                SharedThemeFixture.write(other, "zzz", "Zulu Theme", "", ""));

        assertTrue(view.compareTo(later) < 0, "\"Shared Theme\" sorts before \"Zulu Theme\"");
        assertTrue(new WeblogSharedTheme(weblog, later, weblogManager).compareTo(sharedTheme) > 0);
    }

    // --- the stylesheet --------------------------------------------------------

    @Test
    void theSharedStylesheetIsUsedWhenTheWeblogHasNoCopy() throws Exception {
        ThemeTemplate stylesheet = view.getStylesheet();

        assertSame(sharedTheme.getStylesheet(), stylesheet);
    }

    /**
     * The one template where the weblog's own copy beats the theme's: the
     * Stylesheet editor saves a per-weblog override while the weblog stays on
     * the shared theme.
     */
    @Test
    void theWeblogsOwnStylesheetOverridesTheSharedOne() throws Exception {
        WeblogTemplate edited = stylesheetOverride();

        assertSame(edited, view.getStylesheet());
    }

    /**
     * An override only counts when the theme has a stylesheet to override; a
     * leftover STYLESHEET row from an earlier theme does not leak into one
     * that ships none.
     */
    @Test
    void aThemeWithNoStylesheetIgnoresAnyStoredOverride(@TempDir Path plain) throws Exception {
        stylesheetOverride();
        SharedThemeFromDir noStylesheet = new SharedThemeFromDir(
                SharedThemeFixture.write(plain, "plain", "Plain", "", ""));

        WeblogSharedTheme plainView = new WeblogSharedTheme(weblog, noStylesheet, weblogManager);

        assertNull(plainView.getStylesheet());
        verify(weblogManager, never()).getTemplateByAction(any(), any());
    }

    // --- lookup by name ------------------------------------------------------

    @Test
    void lookingUpTheStylesheetByNameReturnsTheWeblogsOverride() throws Exception {
        WeblogTemplate edited = stylesheetOverride();

        assertSame(edited, view.getTemplateByName("custom.css"),
                "by name, the stylesheet resolves the same way getStylesheet does");
    }

    @Test
    void aThemeTemplateIsFoundByNameWithoutTouchingTheWeblogsStore() throws Exception {
        ThemeTemplate template = view.getTemplateByName("Weblog");

        assertSame(sharedTheme.getTemplateByName("Weblog"), template);
        verify(weblogManager, never()).getTemplateByName(any(), any());
    }

    /**
     * A name the theme does not define falls to the weblog's own templates,
     * which is how a custom template (and the {@code _page} override
     * PageServlet asks for) reaches a weblog on a shared theme.
     */
    @Test
    void aNameTheThemeDoesNotDefineFallsBackToTheWeblogsOwnTemplate() throws Exception {
        WeblogTemplate own = new WeblogTemplate();
        own.setName("_page");
        when(weblogManager.getTemplateByName(weblog, "_page")).thenReturn(own);

        assertSame(own, view.getTemplateByName("_page"));
    }

    @Test
    void aNameNeitherDefinesIsNotFound() throws Exception {
        assertNull(view.getTemplateByName("nowhere"));
    }

    @Test
    void aNullNameIsNotFoundAndQueriesNothing() throws Exception {
        assertNull(view.getTemplateByName(null));
        verifyNoInteractions(weblogManager);
    }

    // --- lookup by link ------------------------------------------------------

    @Test
    void lookingUpTheStylesheetByLinkReturnsTheWeblogsOverride() throws Exception {
        WeblogTemplate edited = stylesheetOverride();

        assertSame(edited, view.getTemplateByLink("custom.css"),
                "the /page/custom.css request is what actually serves the stylesheet");
    }

    @Test
    void aThemeTemplateIsFoundByLink() throws Exception {
        assertSame(sharedTheme.getTemplateByLink("about"), view.getTemplateByLink("about"));
        verify(weblogManager, never()).getTemplateByLink(any(), any());
    }

    @Test
    void aNullLinkIsNotFoundAndQueriesNothing() throws Exception {
        assertNull(view.getTemplateByLink(null));
        verifyNoInteractions(weblogManager);
    }

    // --- lookup by action ----------------------------------------------------

    /**
     * Action templates come from the theme only. A weblog that once ran a
     * custom theme still has WEBLOG/PERMALINK rows; they must not take over
     * after the switch back to a shared theme.
     */
    @Test
    void actionTemplatesComeFromTheThemeEvenWhenTheWeblogStoresOne() throws Exception {
        WeblogTemplate stale = new WeblogTemplate();
        when(weblogManager.getTemplateByAction(weblog, ComponentType.PERMALINK)).thenReturn(stale);

        ThemeTemplate permalink = view.getTemplateByAction(ComponentType.PERMALINK);

        assertSame(sharedTheme.getTemplateByAction(ComponentType.PERMALINK), permalink);
        verify(weblogManager, never()).getTemplateByAction(weblog, ComponentType.PERMALINK);
    }

    @Test
    void anActionTheThemeDoesNotDefineIsNotFound() throws Exception {
        assertNull(view.getTemplateByAction(ComponentType.TAGSINDEX),
                "no fallback to the weblog's store for actions");
    }

    @Test
    void aNullActionIsNotFound() throws Exception {
        assertNull(view.getTemplateByAction(null));
    }

    @Test
    void theDefaultTemplateIsTheThemesWeblogTemplate() throws Exception {
        assertSame(sharedTheme.getTemplateByAction(ComponentType.WEBLOG),
                view.getDefaultTemplate());
    }

    // --- listing ----------------------------------------------------------------

    /**
     * The list merges the weblog's stored templates with the theme's, keyed
     * by name: the theme's copy replaces a stored template of the same name,
     * the weblog's own extra templates survive, and the result is in name
     * order.
     */
    @Test
    void theTemplateListMergesStoredAndThemeTemplatesWithTheThemeWinningOnName()
            throws Exception {
        WeblogTemplate storedWeblog = new WeblogTemplate();
        storedWeblog.setName("Weblog");
        WeblogTemplate ownPage = new WeblogTemplate();
        ownPage.setName("Aardvark");
        when(weblogManager.getTemplates(weblog)).thenReturn(List.of(storedWeblog, ownPage));

        List<ThemeTemplate> templates = view.getTemplates();

        assertEquals(List.of("Aardvark", "About", "Weblog", "custom.css", "permalink"),
                templates.stream().map(ThemeTemplate::getName).toList(),
                "one entry per name, sorted by name");
        assertSame(ownPage, templates.get(0), "the weblog's own template is kept");
        assertSame(sharedTheme.getTemplateByName("Weblog"), templates.get(2),
                "the theme's template replaces a stored one of the same name");
    }

    /**
     * A display path: the list feeds navigation menus, so a store that cannot
     * be read degrades to the theme's templates rather than failing the page.
     */
    @Test
    void theTemplateListStillHasTheThemesTemplatesWhenTheStoreFails() throws Exception {
        when(weblogManager.getTemplates(weblog)).thenThrow(new WebloggerException("db down"));

        List<ThemeTemplate> templates = view.getTemplates();

        assertEquals(List.of("About", "Weblog", "custom.css", "permalink"),
                templates.stream().map(ThemeTemplate::getName).toList());
    }

    // --- resources -------------------------------------------------------------

    @Test
    void resourcesComeFromTheSharedTheme() {
        assertSame(sharedTheme.getResource("img/logo.png"), view.getResource("img/logo.png"));
        assertNotNull(view.getResource("img/logo.png"));
        assertNull(view.getResource("img/missing.png"));
    }

    @Test
    void aNullResourcePathIsNotFound() {
        assertNull(view.getResource(null));
    }

    // --- fixtures --------------------------------------------------------------

    private WeblogTemplate stylesheetOverride() throws WebloggerException {
        WeblogTemplate edited = new WeblogTemplate();
        edited.setName("custom.css");
        edited.setLink("custom.css");
        edited.setAction(ComponentType.STYLESHEET);
        when(weblogManager.getTemplateByAction(weblog, ComponentType.STYLESHEET)).thenReturn(edited);
        return edited;
    }

    private static String themeWithStylesheet(Path root) throws IOException {
        String stylesheet = "<stylesheet><name>custom.css</name><description>d</description>"
                + "<link>custom.css</link><contentType>text/css</contentType>"
                + "<rendition><contentsFile>custom.css</contentsFile>"
                + "<templateLanguage>velocity</templateLanguage></rendition></stylesheet>";
        String extra = "<resource path=\"img/logo.png\" />"
                + SharedThemeFixture.template("permalink", "permalink", null, "permalink.vm")
                + SharedThemeFixture.template("custom", "About", "about", "about.vm");
        String path = SharedThemeFixture.write(root, "sharedtheme", "Shared Theme",
                "A theme for sharing", stylesheet + extra);
        Files.writeString(root.resolve("custom.css"), "body { color: red }", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("permalink.vm"), "one entry", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("about.vm"), "about us", StandardCharsets.UTF_8);
        Files.createDirectories(root.resolve("img"));
        Files.writeString(root.resolve("img/logo.png"), "png", StandardCharsets.UTF_8);
        return path;
    }

    /** Writes theme directories for the tests in this class. */
    static final class SharedThemeFixture {

        private SharedThemeFixture() {
        }

        static String template(String action, String name, String link, String file) {
            return "<template action=\"" + action + "\">"
                    + "<name>" + name + "</name>"
                    + "<description>d</description>"
                    + (link == null ? "" : "<link>" + link + "</link>")
                    + "<navbar>false</navbar><hidden>false</hidden>"
                    + "<contentType>text/html</contentType>"
                    + "<rendition><contentsFile>" + file + "</contentsFile>"
                    + "<templateLanguage>velocity</templateLanguage></rendition>"
                    + "</template>";
        }

        /**
         * A theme with the given identity, a Weblog template and preview image,
         * plus whatever extra descriptor body the caller supplies (whose files
         * the caller writes).
         */
        static String write(Path root, String id, String name, String description, String body)
                throws IOException {
            String xml = "<weblogtheme><id>" + id + "</id><name>" + name + "</name>"
                    + (description.isEmpty() ? "" : "<description>" + description + "</description>")
                    + "<preview-image path=\"preview.png\" />"
                    + body
                    + template("weblog", "Weblog", null, "weblog.vm")
                    + "</weblogtheme>";
            Files.writeString(root.resolve("theme.xml"), xml, StandardCharsets.UTF_8);
            Files.writeString(root.resolve("weblog.vm"), "$entry.title", StandardCharsets.UTF_8);
            Files.writeString(root.resolve("preview.png"), "png", StandardCharsets.UTF_8);
            return root.toString();
        }
    }
}

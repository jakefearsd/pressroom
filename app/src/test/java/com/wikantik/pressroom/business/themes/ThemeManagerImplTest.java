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

import java.util.ArrayList;
import java.util.List;

import com.wikantik.pressroom.business.MockWeblogger;
import com.wikantik.pressroom.pojos.MediaFileDirectory;
import com.wikantik.pressroom.pojos.ThemeTemplate.ComponentType;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogTemplate;
import com.wikantik.pressroom.pojos.WeblogTheme;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import com.wikantik.pressroom.pojos.MediaFile;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.config.WebloggerConfig;
import com.wikantik.pressroom.pojos.ThemeResource;
import com.wikantik.pressroom.pojos.TemplateRendition.RenditionType;
import com.wikantik.pressroom.util.PressroomMessages;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Theme lookup, and the import that turns a shared theme into a weblog's own
 * templates.
 *
 * <p>{@code importTheme} is the most destructive operation in the admin UI and
 * the themes package had no tests at all. It creates or overwrites a template
 * per theme template, <em>deletes</em> any action template the new theme does
 * not define, and finally flips the weblog to {@code CUSTOM} -- one way, with
 * no route back. Everything about it is silent: it reports success either way,
 * and the damage only shows up as a page that stopped rendering.
 *
 * <p>These tests import the <em>real</em> bundled themes against a mocked
 * persistence tier. That combination is deliberate: theme parsing and template
 * enumeration stay honest (a theme.xml that stopped listing a template fails
 * here), while what would have been written is observable without a database.
 */
class ThemeManagerImplTest {

    private MockWeblogger weblogger;
    private ThemeManagerImpl themeManager;
    private Weblog weblog;

    @BeforeEach
    void setUp() throws Exception {
        weblogger = MockWeblogger.attached();
        themeManager = new ThemeManagerImpl(weblogger.weblogger());
        themeManager.initialize();

        weblog = new Weblog();
        weblog.setHandle("themeblog");
        weblog.setName("Theme Blog");
        weblog.setEditorTheme("journal");

        when(weblogger.mediaFileManager().getDefaultMediaFileDirectory(weblog))
                .thenReturn(new MediaFileDirectory());
    }

    @AfterEach
    void tearDown() {
        weblogger.detach();
    }

    // ---------------------------------------------------------------- lookup

    @Test
    void aBundledThemeIsFoundById() throws Exception {
        SharedTheme theme = themeManager.getTheme("journal");

        assertNotNull(theme);
        assertEquals("journal", theme.getId());
    }

    /**
     * An unknown id throws rather than returning null. Callers pass user input
     * here -- the theme chooser posts whatever was selected -- and a null would
     * surface later as an NPE somewhere unrelated.
     */
    @Test
    void anUnknownThemeIdThrows() {
        assertThrows(ThemeNotFoundException.class, () -> themeManager.getTheme("no-such-theme"));
    }

    @Test
    void everyBundledThemeIsLoadedAndSortedByName() {
        List<SharedTheme> themes = themeManager.getEnabledThemesList();

        List<String> ids = themes.stream().map(SharedTheme::getId).toList();
        assertTrue(ids.containsAll(List.of("journal", "portfolio", "travel", "frontpage")),
                "every theme shipped in the themes directory must be offered: " + ids);

        List<String> names = themes.stream().map(SharedTheme::getName).toList();
        List<String> sorted = new ArrayList<>(names);
        java.util.Collections.sort(sorted);
        assertEquals(sorted, names, "the chooser relies on this list already being in order");
    }

    // ------------------------------------------------- resolving a weblog's theme

    @Test
    void aWeblogOnASharedThemeGetsASharedThemeView() throws Exception {
        WeblogTheme resolved = themeManager.getTheme(weblog);

        assertInstanceOf(WeblogSharedTheme.class, resolved);
    }

    @Test
    void aWeblogOnACustomThemeGetsACustomThemeView() throws Exception {
        weblog.setEditorTheme(WeblogTheme.CUSTOM);

        assertInstanceOf(WeblogCustomTheme.class, themeManager.getTheme(weblog));
    }

    /**
     * A weblog with no theme recorded at all is treated as custom rather than
     * left without one, so its own templates still render.
     */
    @Test
    void aWeblogWithNoThemeRecordedIsTreatedAsCustom() throws Exception {
        weblog.setEditorTheme(null);

        assertInstanceOf(WeblogCustomTheme.class, themeManager.getTheme(weblog));
    }

    @Test
    void aNullWeblogHasNoTheme() throws Exception {
        assertNull(themeManager.getTheme((Weblog) null));
    }

    /**
     * A weblog naming a theme that is no longer on disk -- removed between
     * releases -- resolves to nothing rather than to some other theme.
     */
    @Test
    void aWeblogNamingAThemeThatNoLongerExistsResolvesToNothing() throws Exception {
        weblog.setEditorTheme("deleted-theme");

        assertNull(themeManager.getTheme(weblog));
    }

    // ---------------------------------------------------------------- import

    @Test
    void importingCopiesEveryThemeTemplateOntoTheWeblog() throws Exception {
        SharedTheme journal = themeManager.getTheme("journal");

        themeManager.importTheme(weblog, journal, false);

        ArgumentCaptor<WeblogTemplate> saved = ArgumentCaptor.forClass(WeblogTemplate.class);
        verify(weblogger.weblogManager(), atLeastOnce()).saveTemplate(saved.capture());

        List<String> names = saved.getAllValues().stream().map(WeblogTemplate::getName).toList();
        List<String> expected = journal.getTemplates().stream()
                .map(com.wikantik.pressroom.pojos.ThemeTemplate::getName).toList();
        assertTrue(names.containsAll(expected),
                "every template the theme defines must be written to the weblog. expected "
                        + expected + " got " + names);
        saved.getAllValues().forEach(template ->
                assertEquals(weblog, template.getWeblog(),
                        "each copied template must belong to the importing weblog"));
    }

    /**
     * The point of no return. Nothing else in the admin UI changes a weblog's
     * theme to CUSTOM, and once it is there the weblog stops tracking the
     * shared theme entirely.
     */
    @Test
    void importingSwitchesTheWeblogToCustomAndSavesIt() throws Exception {
        themeManager.importTheme(weblog, themeManager.getTheme("journal"), false);

        assertEquals(WeblogTheme.CUSTOM, weblog.getEditorTheme());
        verify(weblogger.weblogManager()).saveWeblog(weblog);
    }

    /**
     * Importing overwrites in place rather than duplicating: a weblog that
     * already has a Weblog template gets that one updated, not a second one.
     */
    @Test
    void anExistingActionTemplateIsUpdatedRatherThanDuplicated() throws Exception {
        WeblogTemplate existing = new WeblogTemplate();
        existing.setWeblog(weblog);
        existing.setName("Weblog");
        existing.setAction(ComponentType.WEBLOG);
        when(weblogger.weblogManager().getTemplateByAction(weblog, ComponentType.WEBLOG))
                .thenReturn(existing);

        themeManager.importTheme(weblog, themeManager.getTheme("journal"), false);

        ArgumentCaptor<WeblogTemplate> saved = ArgumentCaptor.forClass(WeblogTemplate.class);
        verify(weblogger.weblogManager(), atLeastOnce()).saveTemplate(saved.capture());
        long weblogTemplates = saved.getAllValues().stream()
                .filter(t -> ComponentType.WEBLOG.equals(t.getAction())).count();
        assertEquals(1, weblogTemplates,
                "the existing template must be reused, not joined by a second one");
        assertTrue(saved.getAllValues().contains(existing),
                "and it must be the very object that was already there");
    }

    /**
     * The destructive half. An action template the weblog carries from a
     * previous theme, which the incoming theme does not define, is deleted --
     * otherwise a stale page would keep rendering under the new theme.
     */
    @Test
    void anActionTemplateTheNewThemeDoesNotDefineIsDeleted() throws Exception {
        WeblogTemplate stale = new WeblogTemplate();
        stale.setWeblog(weblog);
        stale.setName("Tag Index");
        stale.setAction(ComponentType.TAGSINDEX);
        when(weblogger.weblogManager().getTemplateByAction(weblog, ComponentType.TAGSINDEX))
                .thenReturn(stale);

        themeManager.importTheme(weblog, themeManager.getTheme("journal"), false);

        verify(weblogger.weblogManager()).removeTemplate(stale);
    }

    /**
     * Deletion is scoped to action templates. A CUSTOM template is the
     * author's own page -- {@code TemplateIT} covers one being served at
     * {@code /<handle>/page/<link>} -- and an import must not take it with it.
     */
    @Test
    void customTemplatesAreNeverDeletedByAnImport() throws Exception {
        themeManager.importTheme(weblog, themeManager.getTheme("journal"), false);

        verify(weblogger.weblogManager(), never())
                .getTemplateByAction(any(), eq(ComponentType.CUSTOM));
    }

    /**
     * {@code skipStylesheet} is what lets a weblog re-import a theme it has
     * already customised without losing its own stylesheet edits. The
     * controller passes it exactly when the weblog is re-importing the theme it
     * is already on.
     */
    @Test
    void skipStylesheetLeavesAnExistingStylesheetUntouched() throws Exception {
        SharedTheme journal = themeManager.getTheme("journal");
        com.wikantik.pressroom.pojos.ThemeTemplate themeStylesheet = journal.getStylesheet();
        assertNotNull(themeStylesheet, "the journal theme is expected to ship a stylesheet");

        WeblogTemplate customised = new WeblogTemplate();
        customised.setWeblog(weblog);
        customised.setName(themeStylesheet.getName());
        customised.setAction(ComponentType.STYLESHEET);
        customised.setDescription("edited by the author");
        when(weblogger.weblogManager().getTemplateByAction(weblog, ComponentType.STYLESHEET))
                .thenReturn(customised);

        themeManager.importTheme(weblog, journal, true);

        assertEquals("edited by the author", customised.getDescription(),
                "the author's stylesheet must survive a re-import");

        ArgumentCaptor<WeblogTemplate> saved = ArgumentCaptor.forClass(WeblogTemplate.class);
        verify(weblogger.weblogManager(), atLeastOnce()).saveTemplate(saved.capture());
        assertFalse(saved.getAllValues().contains(customised),
                "and must not be written back over");
    }

    /** Without the flag, the same re-import does overwrite the stylesheet. */
    @Test
    void withoutSkipStylesheetTheStylesheetIsOverwritten() throws Exception {
        SharedTheme journal = themeManager.getTheme("journal");
        WeblogTemplate customised = new WeblogTemplate();
        customised.setWeblog(weblog);
        customised.setName(journal.getStylesheet().getName());
        customised.setAction(ComponentType.STYLESHEET);
        customised.setDescription("edited by the author");
        when(weblogger.weblogManager().getTemplateByAction(weblog, ComponentType.STYLESHEET))
                .thenReturn(customised);

        themeManager.importTheme(weblog, journal, false);

        ArgumentCaptor<WeblogTemplate> saved = ArgumentCaptor.forClass(WeblogTemplate.class);
        verify(weblogger.weblogManager(), atLeastOnce()).saveTemplate(saved.capture());
        assertTrue(saved.getAllValues().contains(customised),
                "with the flag off the theme's stylesheet replaces the author's");
    }

    /**
     * A weblog with no media directory yet must still import. The manager logs
     * it and carries on -- refusing would make the theme unusable for a weblog
     * that has never uploaded anything.
     */
    @Test
    void importingSurvivesAWeblogWithNoMediaDirectory() throws Exception {
        when(weblogger.mediaFileManager().getDefaultMediaFileDirectory(weblog)).thenReturn(null);

        themeManager.importTheme(weblog, themeManager.getTheme("journal"), false);

        assertEquals(WeblogTheme.CUSTOM, weblog.getEditorTheme());
    }

    @Test
    void registerPngMimeTypeSwallowsAFailureFromTheMap() {
        // The real JDK MimetypesFileTypeMap never throws for this literal,
        // well-formed string, and the static initializer that normally calls
        // this runs at most once per JVM -- registerPngMimeType exists as its
        // own method so a test can drive the failure path with a throwing
        // subclass instead.
        jakarta.activation.MimetypesFileTypeMap throwing = new jakarta.activation.MimetypesFileTypeMap() {
            @Override
            public synchronized void addMimeTypes(String mimeTypes) {
                throw new IllegalStateException("registry unavailable");
            }
        };

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> ThemeManagerImpl.registerPngMimeType(throwing));
    }

    // --- importing a theme's static resources -----------------------------

    /**
     * The half of importTheme that no bundled theme could reach: none of
     * journal, portfolio, travel or frontpage declares a &lt;resource&gt;
     * element, so importing any of them skipped this loop entirely. These build
     * a theme directory that does declare them.
     */
    @Test
    void aThemeResourceIsCopiedIntoTheWeblogsMedia(@TempDir Path dir) throws Exception {
        SharedTheme theme = themeWithResources(dir, "logo.png");

        themeManager.importTheme(weblog, theme, false);

        ArgumentCaptor<MediaFile> saved = ArgumentCaptor.forClass(MediaFile.class);
        verify(weblogger.mediaFileManager())
                .createThemeMediaFile(eq(weblog), saved.capture(), any());

        assertEquals("logo.png", saved.getValue().getName(),
                "a theme's own image has to become a media file, or the imported theme "
                        + "renders without it");
        assertEquals("/logo.png", saved.getValue().getOriginalPath(),
                "a resource at the theme root gets an empty directory part");
    }

    @Test
    void aResourceInASubdirectoryGetsThatDirectoryCreated(@TempDir Path dir) throws Exception {
        SharedTheme theme = themeWithResources(dir, "img/logo.png");
        when(weblogger.mediaFileManager().getMediaFileDirectoryByName(weblog, "/img"))
                .thenReturn(null);
        when(weblogger.mediaFileManager().createMediaFileDirectory(weblog, "/img"))
                .thenReturn(new MediaFileDirectory());

        themeManager.importTheme(weblog, theme, false);

        verify(weblogger.mediaFileManager()).createMediaFileDirectory(weblog, "/img");

        ArgumentCaptor<MediaFile> saved = ArgumentCaptor.forClass(MediaFile.class);
        verify(weblogger.mediaFileManager())
                .createThemeMediaFile(eq(weblog), saved.capture(), any());
        assertEquals("/img/logo.png", saved.getValue().getOriginalPath(),
                "the directory part is kept so the resource resolves where the theme "
                        + "expects it");
    }

    @Test
    void anExistingMediaFileIsReplacedRatherThanDuplicated(@TempDir Path dir) throws Exception {
        SharedTheme theme = themeWithResources(dir, "logo.png");
        MediaFile existing = new MediaFile();
        when(weblogger.mediaFileManager().getMediaFileByOriginalPath(weblog, "/logo.png"))
                .thenReturn(existing);

        themeManager.importTheme(weblog, theme, false);

        verify(weblogger.mediaFileManager()).removeMediaFile(weblog, existing);
        verify(weblogger.mediaFileManager())
                .createThemeMediaFile(eq(weblog), any(), any());
    }

    // --- directory resources and refused resources ---------------------------
    //
    // Characterisation tests (this section and the two below), written against
    // the existing behaviour and expected to pass immediately.

    /**
     * A resource that is a directory becomes a media directory, created only
     * when the weblog does not already have one of that name.
     */
    @Test
    void aDirectoryResourceBecomesAMediaDirectory(@TempDir Path dir) throws Exception {
        SharedTheme theme = themeWithDirectoryResource(dir, "gallery");

        themeManager.importTheme(weblog, theme, false);

        verify(weblogger.mediaFileManager()).createMediaFileDirectory(weblog, "gallery");
        verify(weblogger.mediaFileManager(), never())
                .createThemeMediaFile(any(), any(), any());
    }

    @Test
    void anExistingMediaDirectoryIsLeftAlone(@TempDir Path dir) throws Exception {
        SharedTheme theme = themeWithDirectoryResource(dir, "gallery");
        when(weblogger.mediaFileManager().getMediaFileDirectoryByName(weblog, "gallery"))
                .thenReturn(new MediaFileDirectory());

        themeManager.importTheme(weblog, theme, false);

        verify(weblogger.mediaFileManager(), never()).createMediaFileDirectory(any(), any());
    }

    /**
     * The media manager reports a refused file through the messages it is
     * handed rather than by throwing; the import must not read that silence
     * as success.
     */
    @Test
    void aResourceTheMediaManagerRefusesFailsTheImport(@TempDir Path dir) throws Exception {
        SharedTheme theme = themeWithResources(dir, "logo.png");
        doAnswer(invocation -> {
            invocation.<PressroomMessages>getArgument(2).addError("error.upload.forbiddenFile");
            return null;
        }).when(weblogger.mediaFileManager()).createThemeMediaFile(eq(weblog), any(), any());

        WebloggerException ex = assertThrows(WebloggerException.class,
                () -> themeManager.importTheme(weblog, theme, false));

        assertTrue(ex.getMessage().contains("error.upload.forbiddenFile"),
                "the refusal must surface, named: " + ex.getMessage());
    }

    /**
     * A resource stream that cannot be closed is reported as an import
     * failure too, not swallowed.
     */
    @Test
    void aResourceStreamThatFailsToCloseFailsTheImport() throws Exception {
        ThemeResource resource = mock(ThemeResource.class);
        when(resource.getPath()).thenReturn("logo.png");
        when(resource.isDirectory()).thenReturn(false);
        when(resource.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[0]) {
            @Override
            public void close() throws IOException {
                throw new IOException("disk went away");
            }
        });
        SharedTheme theme = mock(SharedTheme.class);
        when(theme.getName()).thenReturn("Unclosable");
        when(theme.getResources()).thenReturn(List.of(resource));

        WebloggerException ex = assertThrows(WebloggerException.class,
                () -> themeManager.importTheme(weblog, theme, false));

        assertTrue(ex.getMessage().contains("error.closingStream"), ex.getMessage());
    }

    // --- where the themes come from ------------------------------------------

    @Test
    void aMissingThemesDirSettingRefusesToStart() {
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> managerFor("  "));

        assertEquals("couldn't get themes directory from config", ex.getMessage());
    }

    /**
     * A themes dir that is not there fails construction, naming the path
     * with any trailing slash already removed.
     */
    @Test
    void anUnreadableThemesDirRefusesToStart(@TempDir Path dir) {
        String missing = dir.resolve("no-such-themes").toString();

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> managerFor(missing + "/"));

        assertEquals("couldn't access theme dir [" + missing + "]", ex.getMessage());
    }

    /**
     * One broken theme directory costs only that theme: the others still
     * load, and hidden directories are not mistaken for themes.
     */
    @Test
    void aBrokenThemeDirectoryIsSkippedAndTheRestLoad(@TempDir Path dir) throws Exception {
        writeTheme(Files.createDirectories(dir.resolve("good")), "goodtheme", "$entry.title");
        Files.writeString(Files.createDirectories(dir.resolve("broken")).resolve("theme.xml"),
                "<weblogtheme>", StandardCharsets.UTF_8);
        writeTheme(Files.createDirectories(dir.resolve(".hidden")), "hiddentheme", "x");

        ThemeManagerImpl manager = managerFor(dir.toString());
        manager.initialize();

        assertEquals(List.of("goodtheme"),
                manager.getEnabledThemesList().stream().map(SharedTheme::getId).toList());
    }

    // --- reloading a theme from disk -----------------------------------------

    /**
     * Theme reload mode: a theme whose files changed on disk replaces the
     * cached one, so a template edit shows without a restart.
     */
    @Test
    void aThemeChangedOnDiskReplacesTheCachedOne(@TempDir Path dir) throws Exception {
        Path themeDir = Files.createDirectories(dir.resolve("reloadable"));
        writeTheme(themeDir, "reloadable", "old body");
        ThemeManagerImpl manager = managerFor(dir.toString());
        manager.initialize();
        SharedTheme before = manager.getTheme("reloadable");

        Files.writeString(themeDir.resolve("weblog.vm"), "new body", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(themeDir.resolve("weblog.vm"),
                FileTime.from(Instant.now().plusSeconds(60)));

        assertTrue(manager.reLoadThemeFromDisk("reloadable"));
        SharedTheme after = manager.getTheme("reloadable");
        assertNotSame(before, after, "the cached theme must be replaced");
        assertEquals("new body", after.getDefaultTemplate()
                .getTemplateRendition(RenditionType.STANDARD).getTemplate());
    }

    /**
     * A theme that no longer loads from disk (its descriptor broken mid-edit)
     * is not reloaded; the working cached copy stays in service.
     */
    @Test
    void aThemeThatNoLongerLoadsKeepsItsCachedCopy(@TempDir Path dir) throws Exception {
        Path themeDir = Files.createDirectories(dir.resolve("reloadable"));
        writeTheme(themeDir, "reloadable", "body");
        ThemeManagerImpl manager = managerFor(dir.toString());
        manager.initialize();
        SharedTheme before = manager.getTheme("reloadable");

        Files.writeString(themeDir.resolve("theme.xml"), "<weblogtheme>", StandardCharsets.UTF_8);

        assertFalse(manager.reLoadThemeFromDisk("reloadable"));
        assertSame(before, manager.getTheme("reloadable"));
    }

    /** A manager whose themes.dir setting is the given value. */
    private ThemeManagerImpl managerFor(String themesDir) {
        try (MockedStatic<WebloggerConfig> config = mockStatic(WebloggerConfig.class)) {
            config.when(() -> WebloggerConfig.getProperty("themes.dir")).thenReturn(themesDir);
            return new ThemeManagerImpl(weblogger.weblogger());
        }
    }

    /** A minimal loadable theme whose Weblog template has the given body. */
    private static void writeTheme(Path dir, String id, String body) throws IOException {
        String xml = "<weblogtheme><id>" + id + "</id><name>" + id + "</name>"
                + "<preview-image path=\"preview.png\" />"
                + "<template action=\"weblog\"><name>Weblog</name><description>d</description>"
                + "<navbar>false</navbar><hidden>false</hidden>"
                + "<contentType>text/html</contentType>"
                + "<rendition><contentsFile>weblog.vm</contentsFile>"
                + "<templateLanguage>velocity</templateLanguage></rendition></template>"
                + "</weblogtheme>";
        Files.writeString(dir.resolve("theme.xml"), xml, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("weblog.vm"), body, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("preview.png"), "png", StandardCharsets.UTF_8);
    }

    /** A theme declaring one resource that is a directory on disk. */
    private static SharedTheme themeWithDirectoryResource(Path dir, String directory)
            throws Exception {
        themeWithResources(dir);   // writes a resource-free theme into dir
        Files.createDirectories(dir.resolve(directory));
        String xml = Files.readString(dir.resolve("theme.xml"), StandardCharsets.UTF_8)
                .replace("<template ", "<resource path=\"" + directory + "\" /><template ");
        Files.writeString(dir.resolve("theme.xml"), xml, StandardCharsets.UTF_8);
        return new SharedThemeFromDir(dir.toString());
    }

    /** Builds a theme directory that declares the given resource paths. */
    private static SharedTheme themeWithResources(Path dir, String... resourcePaths)
            throws Exception {
        StringBuilder xml = new StringBuilder("<weblogtheme><id>restheme</id>"
                + "<name>Resource Theme</name><preview-image path=\"preview.png\" />");
        for (String path : resourcePaths) {
            xml.append("<resource path=\"").append(path).append("\" />");
        }
        xml.append("<template action=\"weblog\"><name>Weblog</name><description>d</description>")
           .append("<navbar>false</navbar><hidden>false</hidden>")
           .append("<contentType>text/html</contentType>")
           .append("<rendition><contentsFile>weblog.vm</contentsFile>")
           .append("<templateLanguage>velocity</templateLanguage></rendition></template>")
           .append("</weblogtheme>");

        Files.writeString(dir.resolve("theme.xml"), xml.toString(), StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("weblog.vm"), "$entry.title", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("preview.png"), "png", StandardCharsets.UTF_8);
        for (String path : resourcePaths) {
            Path file = dir.resolve(path);
            Files.createDirectories(file.getParent());
            Files.writeString(file, "resource bytes", StandardCharsets.UTF_8);
        }
        return new SharedThemeFromDir(dir.toString());
    }
}

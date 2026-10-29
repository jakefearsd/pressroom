package com.wikantik.pressroom.business.themes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import com.wikantik.pressroom.pojos.ThemeResource;
import com.wikantik.pressroom.pojos.ThemeTemplate;
import com.wikantik.pressroom.pojos.ThemeTemplate.ComponentType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Loading a shared theme off disk.
 *
 * <p>Every previous test of this layer used the four bundled themes, which is
 * why it sat at 44% branch coverage: those four are all well-formed, and every
 * branch here is about what happens when a theme is not. They also all look
 * alike -- none of them declares a {@code <resource>} element, so the whole
 * static-resource half of theme loading (and of ResourceServlet and
 * PreviewResourceServlet downstream) had never run at all.
 *
 * <p>These build a theme directory per test instead, which is what lets the
 * failure paths be reached: a missing descriptor, a stylesheet whose file is
 * not there, a template declaring no standard rendition.
 */
class SharedThemeFromDirTest {

    /** A well-formed descriptor, with whatever body a test needs spliced in. */
    private static String themeXml(String body) {
        return "<weblogtheme>"
                + "<id>testtheme</id>"
                + "<name>Test Theme</name>"
                + "<author>A Tester</author>"
                + "<preview-image path=\"preview.png\" />"
                + body
                + "</weblogtheme>";
    }

    private static String template(String action, String name, String file) {
        return "<template action=\"" + action + "\">"
                + "<name>" + name + "</name>"
                + "<description>d</description>"
                + "<navbar>false</navbar><hidden>false</hidden>"
                + "<contentType>text/html</contentType>"
                + "<rendition><contentsFile>" + file + "</contentsFile>"
                + "<templateLanguage>velocity</templateLanguage></rendition>"
                + "</template>";
    }

    /** Writes a theme directory: theme.xml plus name/content file pairs. */
    private static String themeDir(Path root, String xml, String... files) throws IOException {
        if (xml != null) {
            Files.writeString(root.resolve("theme.xml"), xml, StandardCharsets.UTF_8);
        }
        for (int i = 0; i + 1 < files.length; i += 2) {
            Path file = root.resolve(files[i]);
            Files.createDirectories(file.getParent());
            Files.writeString(file, files[i + 1], StandardCharsets.UTF_8);
        }
        return root.toString();
    }

    /** The smallest theme that loads: one weblog template, nothing else. */
    private static String minimalTheme(Path root) throws IOException {
        return themeDir(root,
                themeXml(template("weblog", "Weblog", "weblog.vm")),
                "weblog.vm", "$entry.title", "preview.png", "not really a png");
    }

    // --- the descriptor ----------------------------------------------------

    @Test
    void aDirectoryWithNoDescriptorIsNotATheme(@TempDir Path dir) throws IOException {
        String path = themeDir(dir, null, "weblog.vm", "x");

        ThemeInitializationException ex = assertThrows(ThemeInitializationException.class,
                () -> new SharedThemeFromDir(path));

        assertTrue(ex.getMessage().contains("theme.xml"),
                "the message must name what was missing: " + ex.getMessage());
    }

    @Test
    void aMalformedDescriptorIsNotATheme(@TempDir Path dir) throws IOException {
        String path = themeDir(dir, "<weblogtheme><id>t</id>", "weblog.vm", "x");

        assertThrows(ThemeInitializationException.class, () -> new SharedThemeFromDir(path),
                "an unclosed descriptor must fail loading, not load a half-theme");
    }

    @Test
    void theDescriptorSuppliesTheThemesIdentity(@TempDir Path dir) throws Exception {
        SharedThemeFromDir theme = new SharedThemeFromDir(minimalTheme(dir));

        assertEquals("testtheme", theme.getId());
        assertEquals("Test Theme", theme.getName());
        assertEquals("A Tester", theme.getAuthor());
        assertTrue(theme.isEnabled(), "a theme that loads is enabled");
    }

    @Test
    void aThemeWithNoDescriptionGetsAPlaceholderRatherThanNull(@TempDir Path dir)
            throws Exception {
        SharedThemeFromDir theme = new SharedThemeFromDir(minimalTheme(dir));

        assertEquals(" ", theme.getDescription(),
                "the admin theme picker renders this straight into the page, so it is "
                        + "given a space rather than left null");
    }

    @Test
    void aDescriptionIsCarriedWhenThereIsOne(@TempDir Path dir) throws Exception {
        String path = themeDir(dir,
                themeXml("<description>A described theme</description>"
                        + template("weblog", "Weblog", "weblog.vm")),
                "weblog.vm", "x", "preview.png", "x");

        assertEquals("A described theme", new SharedThemeFromDir(path).getDescription());
    }

    // --- the preview image -------------------------------------------------

    @Test
    void aPreviewImageIsLoadedWhenItIsThere(@TempDir Path dir) throws Exception {
        SharedThemeFromDir theme = new SharedThemeFromDir(minimalTheme(dir));

        assertNotNull(theme.getPreviewImage(),
                "the theme picker shows this; a theme that has one must expose it");
        assertEquals("preview.png", theme.getPreviewImage().getPath());
    }

    @Test
    void aMissingPreviewImageIsNotFatal(@TempDir Path dir) throws Exception {
        String path = themeDir(dir,
                themeXml(template("weblog", "Weblog", "weblog.vm")),
                "weblog.vm", "x");   // no preview.png written

        SharedThemeFromDir theme = new SharedThemeFromDir(path);

        assertNull(theme.getPreviewImage(),
                "a theme with no preview picture is still a usable theme -- it just does "
                        + "not illustrate itself");
        assertNotNull(theme.getTemplateByAction(ComponentType.WEBLOG),
                "and everything else about it still loaded");
    }

    // --- static resources: the half no bundled theme exercises -------------

    @Test
    void declaredResourcesAreLoaded(@TempDir Path dir) throws Exception {
        String path = themeDir(dir,
                themeXml("<resource path=\"img/logo.png\" />"
                        + "<resource path=\"extra.txt\" />"
                        + template("weblog", "Weblog", "weblog.vm")),
                "weblog.vm", "x", "preview.png", "x",
                "img/logo.png", "pretend png", "extra.txt", "hello");

        SharedThemeFromDir theme = new SharedThemeFromDir(path);

        assertEquals(2, theme.getResources().size(),
                "both declared resources must be loaded: " + theme.getResources());
        ThemeResource logo = theme.getResource("img/logo.png");
        assertNotNull(logo, "a resource is looked up by the path it was declared with");
        assertEquals("img/logo.png", logo.getPath(),
                "and keeps its directory separators, or it collapses into the theme root");
    }

    @Test
    void aResourceDeclaredButNotPresentIsSkippedRatherThanFatal(@TempDir Path dir)
            throws Exception {
        String path = themeDir(dir,
                themeXml("<resource path=\"missing.png\" />"
                        + "<resource path=\"present.png\" />"
                        + template("weblog", "Weblog", "weblog.vm")),
                "weblog.vm", "x", "preview.png", "x", "present.png", "here");

        SharedThemeFromDir theme = new SharedThemeFromDir(path);

        assertNull(theme.getResource("missing.png"),
                "a declared file that is not on disk is dropped");
        assertNotNull(theme.getResource("present.png"),
                "and the ones that ARE there still load -- one bad entry must not cost "
                        + "the theme its other resources");
    }

    // --- the stylesheet ----------------------------------------------------

    @Test
    void aThemeNeedsNoStylesheet(@TempDir Path dir) throws Exception {
        assertNull(new SharedThemeFromDir(minimalTheme(dir)).getStylesheet(),
                "the stylesheet is optional; a theme without one still loads");
    }

    @Test
    void aStylesheetIsLoadedAsATemplate(@TempDir Path dir) throws Exception {
        String path = themeDir(dir,
                themeXml("<stylesheet><name>custom.css</name><description>d</description>"
                        + "<link>custom.css</link><contentType>text/css</contentType>"
                        + "<rendition><contentsFile>custom.css</contentsFile>"
                        + "<templateLanguage>velocity</templateLanguage></rendition>"
                        + "</stylesheet>"
                        + template("weblog", "Weblog", "weblog.vm")),
                "weblog.vm", "x", "preview.png", "x", "custom.css", "body { color: red }");

        ThemeTemplate stylesheet = new SharedThemeFromDir(path).getStylesheet();

        assertNotNull(stylesheet);
        assertEquals("custom.css", stylesheet.getName());
    }

    @Test
    void aStylesheetWhoseFileIsMissingLeavesTheThemeUsable(@TempDir Path dir) throws Exception {
        String path = themeDir(dir,
                themeXml("<stylesheet><name>custom.css</name><description>d</description>"
                        + "<link>custom.css</link><contentType>text/css</contentType>"
                        + "<rendition><contentsFile>custom.css</contentsFile>"
                        + "<templateLanguage>velocity</templateLanguage></rendition>"
                        + "</stylesheet>"
                        + template("weblog", "Weblog", "weblog.vm")),
                "weblog.vm", "x", "preview.png", "x");   // no custom.css

        SharedThemeFromDir theme = new SharedThemeFromDir(path);

        assertNull(theme.getStylesheet(),
                "an unreadable stylesheet is logged and dropped rather than aborting the "
                        + "load -- the theme still renders, just unstyled");
        assertNotNull(theme.getTemplateByAction(ComponentType.WEBLOG));
    }

    // --- templates ---------------------------------------------------------

    @Test
    void templatesAreReachableByActionAndByName(@TempDir Path dir) throws Exception {
        String path = themeDir(dir,
                themeXml(template("weblog", "Weblog", "weblog.vm")
                        + template("permalink", "permalink", "permalink.vm")),
                "weblog.vm", "list", "permalink.vm", "one entry", "preview.png", "x");

        SharedThemeFromDir theme = new SharedThemeFromDir(path);

        assertNotNull(theme.getTemplateByAction(ComponentType.PERMALINK));
        assertNotNull(theme.getTemplateByName("Weblog"));
        assertEquals(2, theme.getTemplates().size());
    }

    @Test
    void theWeblogTemplateIsTheDefault(@TempDir Path dir) throws Exception {
        SharedThemeFromDir theme = new SharedThemeFromDir(minimalTheme(dir));

        assertNotNull(theme.getDefaultTemplate(),
                "something has to render the weblog's front page");
    }

    @Test
    void aTemplateWhoseFileIsMissingFailsTheWholeTheme(@TempDir Path dir) throws Exception {
        String path = themeDir(dir,
                themeXml(template("weblog", "Weblog", "weblog.vm")),
                "preview.png", "x");   // weblog.vm not written

        ThemeInitializationException ex = assertThrows(ThemeInitializationException.class,
                () -> new SharedThemeFromDir(path));

        assertTrue(ex.getMessage().contains("weblog.vm"),
                "unlike a stylesheet or a resource, a missing TEMPLATE file is fatal -- "
                        + "the theme cannot render without it. Message: " + ex.getMessage());
    }

    @Test
    void aTemplateWithNoStandardRenditionFailsTheWholeTheme(@TempDir Path dir) throws Exception {
        String path = themeDir(dir,
                themeXml("<template action=\"weblog\"><name>Weblog</name>"
                        + "<description>d</description><navbar>false</navbar>"
                        + "<hidden>false</hidden><contentType>text/html</contentType>"
                        + "</template>"),
                "preview.png", "x");

        assertThrows(ThemeInitializationException.class, () -> new SharedThemeFromDir(path),
                "a template that declares no way to render itself is not usable");
    }

    // --- further failure shapes and timestamps ------------------------------
    //
    // Characterisation tests, written against the existing behaviour and
    // expected to pass immediately.

    /**
     * A stylesheet whose FILE is missing is dropped (above), but one that
     * declares no standard rendition at all fails the whole theme -- the
     * descriptor itself is wrong, not the disk.
     */
    @Test
    void aStylesheetWithNoStandardRenditionFailsTheWholeTheme(@TempDir Path dir)
            throws Exception {
        String path = themeDir(dir,
                themeXml("<stylesheet><name>custom.css</name><description>d</description>"
                        + "<link>custom.css</link><contentType>text/css</contentType>"
                        + "</stylesheet>"
                        + template("weblog", "Weblog", "weblog.vm")),
                "weblog.vm", "x", "preview.png", "x");

        ThemeInitializationException ex = assertThrows(ThemeInitializationException.class,
                () -> new SharedThemeFromDir(path));

        assertEquals("Error in getting template codes for template", ex.getMessage());
    }

    /**
     * A template path that names a directory cannot be read; like a missing
     * template file, that is fatal and the message names the path.
     */
    @Test
    void aTemplatePathThatIsADirectoryFailsTheWholeTheme(@TempDir Path dir) throws Exception {
        String path = themeDir(dir,
                themeXml(template("weblog", "Weblog", "weblog.vm")),
                "preview.png", "x");
        Files.createDirectories(dir.resolve("weblog.vm"));

        ThemeInitializationException ex = assertThrows(ThemeInitializationException.class,
                () -> new SharedThemeFromDir(path));

        assertTrue(ex.getMessage().contains("weblog.vm"), ex.getMessage());
    }

    /** The same unreadable path on the stylesheet only drops the stylesheet. */
    @Test
    void aStylesheetPathThatIsADirectoryOnlyDropsTheStylesheet(@TempDir Path dir)
            throws Exception {
        String path = themeDir(dir,
                themeXml("<stylesheet><name>custom.css</name><description>d</description>"
                        + "<link>custom.css</link><contentType>text/css</contentType>"
                        + "<rendition><contentsFile>custom.css</contentsFile>"
                        + "<templateLanguage>velocity</templateLanguage></rendition>"
                        + "</stylesheet>"
                        + template("weblog", "Weblog", "weblog.vm")),
                "weblog.vm", "x", "preview.png", "x");
        Files.createDirectories(dir.resolve("custom.css"));

        SharedThemeFromDir theme = new SharedThemeFromDir(path);

        assertNull(theme.getStylesheet());
        assertNotNull(theme.getDefaultTemplate());
    }

    /**
     * The theme is dated by its newest file, and no template is dated earlier
     * than its own file -- theme reload compares these to decide whether the
     * copy on disk is newer.
     *
     * <p>Deliberately no stronger claim about the older template: each
     * template is stamped with the newest time seen <em>so far</em> while
     * loading, and templates load in {@code HashSet} order, so whether the
     * older file's template carries its own time or the newer one varies.
     */
    @Test
    void theThemeIsDatedByItsNewestFileAndNoTemplateByLessThanItsOwn(@TempDir Path dir)
            throws Exception {
        String path = themeDir(dir,
                themeXml(template("weblog", "Weblog", "weblog.vm")
                        + template("permalink", "permalink", "permalink.vm")),
                "weblog.vm", "list", "permalink.vm", "one entry", "preview.png", "x");
        long older = 1_600_000_000_000L;
        long newer = 1_700_000_000_000L;
        Files.setLastModifiedTime(dir.resolve("weblog.vm"), FileTime.fromMillis(older));
        Files.setLastModifiedTime(dir.resolve("permalink.vm"), FileTime.fromMillis(newer));

        SharedThemeFromDir theme = new SharedThemeFromDir(path);

        assertEquals(newer, theme.getLastModified().getTime());
        assertEquals(newer, theme.getTemplateByName("permalink").getLastModified().getTime());
        long weblogDated = theme.getTemplateByName("Weblog").getLastModified().getTime();
        assertTrue(weblogDated == older || weblogDated == newer,
                "the older file's template is dated by its own file or the newest: "
                        + weblogDated);
    }
}

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
package com.wikantik.pressroom.business.shortcodes;

import java.util.Map;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.MediaFileManager;
import com.wikantik.pressroom.business.URLStrategy;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.pojos.MediaFile;
import com.wikantik.pressroom.pojos.MediaFileDirectory;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The built-in {@code [gallery]} shortcode: a justified grid of a media
 * directory's images -- curated order first, lightbox data payload on the
 * anchors -- and null (leave the shortcode as written) whenever the
 * directory cannot be shown: missing, private, or holding no images.
 */
class GalleryShortcodeTest {

    private static final String BASE = "http://example.com/roller/blog/mediaresource/";

    private GalleryShortcode shortcode;
    private Weblog weblog;
    private WeblogEntry entry;
    private Weblogger weblogger;
    private MediaFileManager mediaFileManager;
    private MediaFileDirectory directory;

    @BeforeEach
    void setUp() throws Exception {
        weblog = new Weblog();
        weblog.setHandle("blog");
        entry = new WeblogEntry();
        entry.setWebsite(weblog);

        directory = new MediaFileDirectory();
        directory.setId("dir-1");
        directory.setName("album");
        directory.setWeblog(weblog);

        mediaFileManager = mock(MediaFileManager.class);
        when(mediaFileManager.getMediaFileDirectoryByName(weblog, "album"))
                .thenReturn(directory);

        URLStrategy urls = mock(URLStrategy.class);
        when(urls.getMediaFileURL(eq(weblog), anyString(), eq(true)))
                .thenAnswer(inv -> BASE + inv.getArgument(1));

        weblogger = mock(Weblogger.class);
        when(weblogger.getMediaFileManager()).thenReturn(mediaFileManager);
        when(weblogger.getUrlStrategy()).thenReturn(urls);
        shortcode = new GalleryShortcode(weblogger);
    }

    private MediaFile image(String id, String name, int width, int height) {
        MediaFile file = new MediaFile();
        file.setId(id);
        file.setWeblog(weblog);
        file.setName(name);
        file.setContentType("image/jpeg");
        file.setWidth(width);
        file.setHeight(height);
        directory.getMediaFiles().add(file);
        return file;
    }

    private String render(Map<String, String> attributes) {
        return (shortcode.render(attributes, null, entry));
    }

    // -------------------------------------------------------------- happy path

    @Test
    void emitsAJustifiedGridOfAnchoredImages() {
        image("mf-1", "hawk.jpg", 1200, 800);

        String html = render(Map.of("dir", "album"));

        assertTrue(html.startsWith("<div class=\"jgrid\">"), html);
        assertTrue(html.contains("<figure class=\"ar-150\">"), html);
        assertTrue(html.contains("<a href=\"" + BASE + "mf-1\""), html);
        assertTrue(html.contains(" data-pswp-width=\"1200\" data-pswp-height=\"800\""),
                "the lightbox needs the full-size dimensions on the anchor:\n" + html);
        assertTrue(html.contains("<img src=\"" + BASE + "mf-1\""), html);
        assertTrue(html.contains(" loading=\"lazy\" decoding=\"async\""), html);
        assertTrue(html.endsWith("</div>"), html);
    }

    @Test
    void eligibleImagesCarryTheLadderSrcsetWithAGridSizesHint() {
        image("mf-1", "hawk.jpg", 1200, 800);

        String html = render(Map.of("dir", "album"));

        assertTrue(html.contains(" srcset=\"" + BASE + "mf-1?w=480 480w, "
                + BASE + "mf-1?w=960 960w, " + BASE + "mf-1 1200w\""), html);
        // flex-basis = --ar * row height: 1.5 * 160 mobile, 1.5 * 260 desktop
        assertTrue(html.contains(" sizes=\"(max-width: 640px) 240px, 390px\""), html);
        assertTrue(html.contains(" width=\"1200\" height=\"800\""), html);
    }

    @Test
    void aFormatOutsideTheLadderGetsAPlainImgButStaysInTheGrid() {
        MediaFile gif = image("mf-1", "anim.gif", 600, 400);
        gif.setContentType("image/gif");

        String html = render(Map.of("dir", "album"));

        assertTrue(html.contains("<img src=\"" + BASE + "mf-1\""), html);
        assertFalse(html.contains("srcset"), html);
        assertTrue(html.contains("<figure class=\"ar-150\">"),
                "known dimensions still pack the grid:\n" + html);
    }

    @Test
    void unknownDimensionsSkipArDimensionsAndPswpAttributes() {
        // pre-ladder upload: width/height were never probed
        image("mf-1", "old.jpg", -1, -1);

        String html = render(Map.of("dir", "album"));

        assertTrue(html.contains("<figure>\n"), "no --ar style without dimensions:\n" + html);
        assertFalse(html.contains("--ar"), html);
        assertFalse(html.contains("data-pswp-width"), html);
        assertFalse(html.contains("srcset"), html);
        assertFalse(html.contains(" width=\""), html);
    }

    // ---------------------------------------------------------------- ordering

    @Test
    void curatedSortOrderComesFirstThenUnorderedFilesByName() {
        // curated block: sortOrder ascending, then the never-ordered files
        // (sortOrder null) by name -- the contract on MediaFile#getSortOrder
        image("mf-b", "b.jpg", 100, 100).setSortOrder(null);
        image("mf-c", "c.jpg", 100, 100).setSortOrder(1);
        image("mf-z", "z.jpg", 100, 100).setSortOrder(null);
        image("mf-a", "a.jpg", 100, 100).setSortOrder(2);

        String html = render(Map.of("dir", "album"));

        int c = html.indexOf("mf-c");
        int a = html.indexOf("mf-a");
        int b = html.indexOf("mf-b");
        int z = html.indexOf("mf-z");
        assertTrue(c < a && a < b && b < z,
                "expected curated c(1), a(2), then unordered b, z by name:\n" + html);
    }

    @Test
    void equalSortOrdersFallBackToNameOrder() {
        image("mf-z", "z.jpg", 100, 100).setSortOrder(5);
        image("mf-a", "a.jpg", 100, 100).setSortOrder(5);

        String html = render(Map.of("dir", "album"));
        assertTrue(html.indexOf("mf-a") < html.indexOf("mf-z"), html);
    }

    @Test
    void nonImageFilesAreSkipped() {
        image("mf-1", "hawk.jpg", 100, 100);
        MediaFile pdf = image("mf-2", "notes.pdf", -1, -1);
        pdf.setContentType("application/pdf");

        String html = render(Map.of("dir", "album"));

        assertTrue(html.contains("mf-1"), html);
        assertFalse(html.contains("mf-2"), html);
    }

    // -------------------------------------------------------------- attributes

    @Test
    void theRowAttributeSelectsARowHeightClass() {
        image("mf-1", "hawk.jpg", 1200, 800);

        String html = render(Map.of("dir", "album", "row", "320"));

        // The ratio and row height ride as classes, not inline custom
        // properties: the sanitizer's CSS schema cannot carry a "--ar", so the
        // theme's own stylesheet turns these classes back into one.
        assertTrue(html.startsWith("<div class=\"jgrid jgrid-h320\">"), html);
        // the sizes hint follows the row height: 1.5 * 320
        assertTrue(html.contains(" sizes=\"(max-width: 640px) 240px, 480px\""), html);
    }

    @Test
    void anInvalidOrDefaultRowAttributeLeavesTheCssDefaultAlone() {
        image("mf-1", "hawk.jpg", 1200, 800);

        assertTrue(render(Map.of("dir", "album", "row", "nope"))
                .startsWith("<div class=\"jgrid\">"));
        assertTrue(render(Map.of("dir", "album", "row", "-5"))
                .startsWith("<div class=\"jgrid\">"));
        assertTrue(render(Map.of("dir", "album", "row", "260"))
                .startsWith("<div class=\"jgrid\">"),
                "row=260 is the stylesheet default; emitting it would be noise");
    }

    @Test
    void theMaxAttributeCapsTheGalleryAfterOrdering() {
        image("mf-b", "b.jpg", 100, 100);
        image("mf-a", "a.jpg", 100, 100).setSortOrder(1);

        String html = render(Map.of("dir", "album", "max", "1"));

        assertTrue(html.contains("mf-a"), html);
        assertFalse(html.contains("mf-b"), html);

        String uncapped = render(Map.of("dir", "album", "max", "0"));
        assertTrue(uncapped.contains("mf-a") && uncapped.contains("mf-b"),
                "max=0 means unlimited:\n" + uncapped);
    }

    // ------------------------------------------------- captions and EXIF payload

    @Test
    void theDescriptionBecomesAnEscapedFigcaptionAndDataCaption() {
        image("mf-1", "hawk.jpg", 100, 100).setDescription("A \"red\" hawk");

        String html = render(Map.of("dir", "album"));

        assertTrue(html.contains("<figcaption>A &quot;red&quot; hawk</figcaption>"), html);
        assertTrue(html.contains(" data-caption=\"A &quot;red&quot; hawk\""), html);
    }

    @Test
    void withoutADescriptionThereIsNoFigcaptionOrDataCaption() {
        image("mf-1", "hawk.jpg", 100, 100);
        String html = render(Map.of("dir", "album"));
        assertFalse(html.contains("figcaption"), html);
        assertFalse(html.contains("data-caption"), html);
    }

    @Test
    void exifDataAttributesAreEmittedOnlyWhenPresent() {
        MediaFile photo = image("mf-1", "hawk.jpg", 100, 100);
        photo.setExifCamera("NIKON Z 6");
        photo.setExifAperture("f/2.8");
        photo.setExifIso(400);
        // lens, exposure, focal length: never extracted -- must be skipped

        String html = render(Map.of("dir", "album"));

        assertTrue(html.contains(" data-exif-camera=\"NIKON Z 6\""), html);
        assertTrue(html.contains(" data-exif-aperture=\"f/2.8\""), html);
        assertTrue(html.contains(" data-exif-iso=\"400\""), html);
        assertFalse(html.contains("data-exif-lens"),
                "null EXIF fields must be skipped, not emitted empty:\n" + html);
        assertFalse(html.contains("data-exif-exposure"), html);
        assertFalse(html.contains("data-exif-focal"), html);
    }

    @Test
    void blurhashRidesTheAnchorAndTheImgWithItsAverageColor() {
        image("mf-1", "hawk.jpg", 100, 100)
                .setBlurhash("LEHV6nWB2yk8pyo0adR*.7kCMdnj");

        String html = render(Map.of("dir", "album"));

        assertTrue(html.contains(" data-blurhash=\"LEHV6nWB2yk8pyo0adR*.7kCMdnj\">\n<img"),
                "the anchor carries the blurhash for the lightbox placeholder:\n" + html);
        assertTrue(html.contains(" style=\"background-color:#979695\""),
                "the average color backs the grid tile while it loads:\n" + html);

        // and a hashless file is silent
        directory.getMediaFiles().clear();
        image("mf-2", "old.jpg", 100, 100);
        String html2 = render(Map.of("dir", "album"));
        assertFalse(html2.contains("data-blurhash"), html2);
        assertFalse(html2.contains("background-color"), html2);
    }

    // -------------------------------------------------------------------- alt

    @Test
    void storedAltTextIsUsedInsteadOfTheFileName() {
        // The gallery grid has no per-image shortcode attribute to carry an
        // alt override -- the media file's own alt text, when an editor set
        // one, is the only thing that should beat the filename.
        image("mf-1", "hawk.jpg", 100, 100).setAltText("A red-tailed hawk on a fence post");

        String html = render(Map.of("dir", "album"));

        assertTrue(html.contains(" alt=\"A red-tailed hawk on a fence post\""), html);
        // Not just "not the filename in the alt attribute" -- the filename
        // must not appear anywhere, or a test that only checked the alt text
        // was present would pass even if both strings landed in it.
        assertFalse(html.contains("hawk.jpg"), html);
    }

    /** The description is the figure's caption: it describes the picture better than a filename. */
    @Test
    void withoutStoredAltTextTheDescriptionIsTheAlt() {
        image("mf-1", "IMG_2034.jpg", 100, 100).setDescription("Sunset over the pier");

        String html = render(Map.of("dir", "album"));

        assertTrue(html.contains(" alt=\"Sunset over the pier\""), html);
    }

    @Test
    void blankStoredAltTextFallsBackToTheFileNameRatherThanEmittingAnEmptyAlt() {
        // An author who types alt text and later clears the field leaves ""
        // in the column, not null. Emitting that verbatim would assert the
        // image is purely decorative -- wrong for a photograph, and it would
        // hide the picture from the "missing alt text" marker just as
        // effectively as a real description would, defeating it.
        image("mf-1", "hawk.jpg", 100, 100).setAltText("   ");

        String html = render(Map.of("dir", "album"));

        assertTrue(html.contains(" alt=\"hawk\""), html);
    }

    @Test
    void withoutStoredAltTextOrADescriptionTheFileNameIsUsedWithoutItsExtension() {
        image("mf-1", "hawk.jpg", 100, 100);

        String html = render(Map.of("dir", "album"));

        assertTrue(html.contains(" alt=\"hawk\""), html);
    }

    @Test
    void blankStoredAltTextAndBlankFileNameEmitAWellFormedEmptyAltNeverTheLiteralTextNull() {
        // Regression: firstNonBlank used to return Java null when every
        // candidate was blank, and escape(null) fed straight into
        // StringBuilder#append(String), which appends the four characters
        // "null" -- screen-reader-visible garbage, not an empty attribute.
        // roller_mediafile.name is NOT NULL but an empty string is not
        // excluded, so this is reachable in production.
        image("mf-1", "   ", 100, 100).setAltText("   ");

        String html = render(Map.of("dir", "album"));

        assertTrue(html.contains(" alt=\"\""), html);
        assertFalse(html.contains("alt=\"null\""), html);
        assertFalse(html.contains("null"), html);
    }

    // ------------------------------------------------------- refusal to render

    @Test
    void withoutADirAttributeTheShortcodeIsLeftAsWritten() {
        assertNull(render(Map.of()));
        assertNull(render(Map.of("dir", "  ")));
    }

    @Test
    void anUnknownDirectoryIsLeftAsWritten() throws Exception {
        when(mediaFileManager.getMediaFileDirectoryByName(weblog, "nope")).thenReturn(null);
        assertNull(render(Map.of("dir", "nope")));
    }

    @Test
    void aPrivateDirectoryIsLeftAsWritten() {
        // private directories are never rendered inline; the normal entry
        // render path must not leak their contents.
        image("mf-1", "hawk.jpg", 100, 100);
        directory.setPrivate(true);
        assertNull(render(Map.of("dir", "album")));
    }

    @Test
    void anEmptyOrImagelessDirectoryIsLeftAsWritten() {
        assertNull(render(Map.of("dir", "album")), "empty directory");

        MediaFile pdf = image("mf-2", "notes.pdf", -1, -1);
        pdf.setContentType("application/pdf");
        assertNull(render(Map.of("dir", "album")), "no image files");
    }

    @Test
    void aFailingDirectoryLookupIsLeftAsWrittenRatherThanBreakingTheRender() throws Exception {
        when(mediaFileManager.getMediaFileDirectoryByName(any(), anyString()))
                .thenThrow(new RuntimeException(new WebloggerException("db down")));
        assertNull(render(Map.of("dir", "album")));
    }

    @Test
    void anEntryWithoutAWeblogIsLeftAsWritten() {
        entry.setWebsite(null);
        assertNull(render(Map.of("dir", "album")));
        assertNull((shortcode.render(Map.of("dir", "album"), null, null)));
    }

    // ---------------------------------------------------------- via expander

    @Test
    void theDefaultExpanderShipsWithTheGalleryShortcodeRegistered() {
        image("mf-1", "hawk.jpg", 1200, 800);

        String rendered = (ShortcodeExpander.builtIn(weblogger, mediaFileManager)
                .expand(entry, "look [gallery dir=\"album\"] here"));

        assertTrue(rendered.contains("<div class=\"jgrid\">"), rendered);
        assertFalse(rendered.contains("[gallery"), rendered);
        assertEquals(0, rendered.indexOf("look "), rendered);
        assertTrue(rendered.endsWith(" here"), rendered);
    }

    @Test
    void anUnknownDirectoryLeavesTheShortcodeTextVisibleThroughTheExpander() throws Exception {
        when(mediaFileManager.getMediaFileDirectoryByName(weblog, "ghost")).thenReturn(null);

        String rendered = (ShortcodeExpander.builtIn(weblogger, mediaFileManager)
                .expand(entry, "[gallery dir=\"ghost\"]"));

        assertEquals("[gallery dir=\"ghost\"]", rendered,
                "null from the handler is the SPI's visible-failure signal");
    }
}

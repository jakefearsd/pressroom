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
package org.apache.roller.weblogger.business.shortcodes;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.MediaFileManager;
import org.apache.roller.weblogger.business.URLStrategy;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.pojos.MediaFile;
import org.apache.roller.weblogger.pojos.MediaFileDirectory;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The built-in {@code [image]} shortcode: srcset built from the rendition
 * ladder, honest fallbacks for pre-ladder uploads, and null (leave the
 * shortcode as written) whenever the media file cannot serve an image.
 */
class ImageShortcodeTest {

    private static final String URL = "http://example.com/roller/blog/mediaresource/mf-1";

    private ImageShortcode shortcode;
    private Weblog weblog;
    private WeblogEntry entry;
    private Weblogger weblogger;
    private MediaFileManager mediaFileManager;
    private MediaFile photo;

    @BeforeEach
    void setUp() throws Exception {
        weblog = new Weblog();
        weblog.setHandle("blog");
        entry = new WeblogEntry();
        entry.setWebsite(weblog);

        photo = new MediaFile();
        photo.setId("mf-1");
        photo.setWeblog(weblog);
        photo.setName("hawk.jpg");
        photo.setContentType("image/jpeg");
        photo.setWidth(1200);
        photo.setHeight(800);

        mediaFileManager = mock(MediaFileManager.class);
        when(mediaFileManager.getMediaFile("mf-1")).thenReturn(photo);

        URLStrategy urls = mock(URLStrategy.class);
        when(urls.getMediaFileURL(weblog, "mf-1", true)).thenReturn(URL);

        weblogger = mock(Weblogger.class);
        when(weblogger.getMediaFileManager()).thenReturn(mediaFileManager);
        when(weblogger.getUrlStrategy()).thenReturn(urls);
        shortcode = new ImageShortcode(weblogger);
    }

    private String render(Map<String, String> attributes, String body) {
        return (shortcode.render(attributes, body, entry));
    }

    // -------------------------------------------------------------- happy path

    @Test
    void emitsAResponsiveFigurePictureBlock() {
        String html = render(Map.of("id", "mf-1"), null);

        assertTrue(html.startsWith("<figure class=\"shortcode-image\">"), html);
        assertTrue(html.contains("<picture>"), html);
        assertTrue(html.contains("<source type=\"image/webp\" srcset=\""), html);
        assertTrue(html.contains("<img src=\"" + URL + "\""), html);
        assertTrue(html.contains(" loading=\"lazy\""), html);
        assertTrue(html.endsWith("</figure>"), html);
    }

    @Test
    void srcsetClimbsTheLadderOnlyBelowTheOriginalWidth() {
        // 1200w original: 480 and 960 rungs exist, 1600/2400 were never
        // generated (RenditionSupport never upscales) so they must not be
        // offered; the original itself is the largest candidate.
        String html = render(Map.of("id", "mf-1"), null);

        assertTrue(html.contains(URL + "?w=480 480w"), html);
        assertTrue(html.contains(URL + "?w=960 960w"), html);
        assertFalse(html.contains("?w=1600"), html);
        assertFalse(html.contains("?w=2400"), html);
        assertTrue(html.contains(URL + " 1200w"), html);
    }

    @Test
    void emitsIntrinsicDimensionsToPreventLayoutShift() {
        String html = render(Map.of("id", "mf-1"), null);
        assertTrue(html.contains(" width=\"1200\" height=\"800\""), html);
    }

    @Test
    void neverEmitsTheFocalPointIntoEntryContent() {
        // The focal point's object-position is a theme-side
        // (#showResponsiveImage) affordance only: entry content is bound by
        // the sanitizer contract, and the [image] expansion deliberately
        // stays inside it. Even with the blurhash style present, the focal
        // point must not join it.
        photo.setFocalX(0.3);
        photo.setFocalY(0.7);
        photo.setBlurhash("LKO2?U%2Tw=w]~RBVZRi};RPxuwH");

        String html = render(Map.of("id", "mf-1"), null);

        assertTrue(html.contains("style=\"background-color:"), html);
        assertFalse(html.contains("object-position"), html);
    }

    @Test
    void aFormatOutsideTheLadderGetsAPlainImg() {
        // The ladder only covers jpeg/png. For a gif every ?w= URL silently
        // serves the full-size original, so a srcset -- and especially a
        // <source type="image/webp"> -- would lie to the browser about what
        // those URLs return.
        photo.setContentType("image/gif");

        String html = render(Map.of("id", "mf-1"), null);

        assertTrue(html.contains("<img src=\"" + URL + "\""), html);
        assertFalse(html.contains("srcset"), html);
        assertFalse(html.contains("image/webp"), html);
        assertTrue(html.contains(" width=\"1200\" height=\"800\""),
                "known dimensions still prevent layout shift:\n" + html);
    }

    @Test
    void aPreLadderUploadWithoutWidthMetadataGetsAPlainImg() {
        photo.setWidth(0);
        photo.setHeight(0);

        String html = render(Map.of("id", "mf-1"), null);

        assertTrue(html.contains("<img src=\"" + URL + "\""), html);
        assertFalse(html.contains("srcset"),
                "without width metadata we cannot know which rungs exist; a "
                        + "plain src always renders:\n" + html);
        assertFalse(html.contains("width=\""), html);
    }

    // ------------------------------------------------------- captions and alt

    @Test
    void theCaptionAttributeBecomesAnEscapedFigcaption() {
        String html = render(Map.of("id", "mf-1", "caption", "A \"red\" hawk"), null);
        assertTrue(html.contains("<figcaption>A &quot;red&quot; hawk</figcaption>"), html);
    }

    @Test
    void aBodyBecomesTheFigcaptionVerbatim() {
        // Body text is entry HTML like any other; the downstream sanitizer
        // decides what markup survives, exactly as it does for the rest of
        // the entry.
        String html = render(Map.of("id", "mf-1"), "A <em>proud</em> hawk");
        assertTrue(html.contains("<figcaption>A <em>proud</em> hawk</figcaption>"), html);
    }

    @Test
    void withoutACaptionThereIsNoFigcaption() {
        assertFalse(render(Map.of("id", "mf-1"), null).contains("figcaption"));
    }

    @Test
    void altComesFromTheAttributeAndFallsBackToTheFileNameWithoutItsExtension() {
        assertTrue(render(Map.of("id", "mf-1", "alt", "Hawk in flight"), null)
                .contains(" alt=\"Hawk in flight\""));
        assertTrue(render(Map.of("id", "mf-1"), null).contains(" alt=\"hawk\""));
    }

    /**
     * With no alt attribute and no stored alt text, the caption the author
     * wrote describes the picture better than its filename: a screen reader
     * announcing "IMG_2034.jpg" tells the reader nothing.
     */
    @Test
    void withoutAltTextTheCaptionAttributeIsTheAlt() {
        String html = render(Map.of("id", "mf-1", "caption", "A \"red\" hawk"), null);
        assertTrue(html.contains(" alt=\"A &quot;red&quot; hawk\""), html);
    }

    @Test
    void withoutAltTextABodyCaptionIsTheAltAsPlainText() {
        String html = render(Map.of("id", "mf-1"), "A <em>proud</em>\n hawk");
        assertTrue(html.contains(" alt=\"A proud hawk\""), html);
    }

    @Test
    void storedAltTextBeatsTheCaption() {
        photo.setAltText("A red-tailed hawk");
        String html = render(Map.of("id", "mf-1", "caption", "Day two"), null);
        assertTrue(html.contains(" alt=\"A red-tailed hawk\""), html);
    }

    /** The last resort reads as words: no extension, no underscores or hyphens. */
    @Test
    void theFileNameFallbackDropsTheExtensionAndSeparators() {
        photo.setName("IMG_2034--sunset-pier.final.jpg");
        assertTrue(render(Map.of("id", "mf-1"), null).contains(" alt=\"IMG 2034 sunset pier.final\""));

        photo.setName(".jpg");
        assertTrue(render(Map.of("id", "mf-1"), null).contains(" alt=\".jpg\""),
                "a name that is all extension is kept rather than emptied");
    }

    @Test
    void storedAltTextIsUsedWhenNoShortcodeAttributeIsGiven() {
        // The chain's middle link: an editor who described the photo on the
        // media file itself, but did not repeat it in the shortcode, must get
        // that description in production -- not the bare filename, which
        // tells a screen reader nothing a sighted visitor doesn't already
        // have from looking at the picture.
        photo.setAltText("A red-tailed hawk banking against a clear sky");

        String html = render(Map.of("id", "mf-1"), null);

        assertTrue(html.contains(" alt=\"A red-tailed hawk banking against a clear sky\""), html);
        // Not just "not the filename in the alt attribute" -- the filename
        // must not appear anywhere, or a test that only checked the alt text
        // was present would pass even if both strings landed in it.
        assertFalse(html.contains("hawk.jpg"), html);
    }

    @Test
    void blankStoredAltTextFallsBackToTheFileNameRatherThanEmittingAnEmptyAlt() {
        // An author who types alt text and later clears the field leaves ""
        // in the column, not null. If that were emitted verbatim, alt=""
        // asserts the image is purely decorative -- wrong for a photograph,
        // and it would hide the picture from the "missing alt text" marker
        // just as effectively as a real description would, defeating it.
        photo.setAltText("   ");

        String html = render(Map.of("id", "mf-1"), null);

        assertTrue(html.contains(" alt=\"hawk\""), html);
    }

    @Test
    void anExplicitAltAttributeWinsOverStoredAltText() {
        // The chain's first link beats the second: a caption written for this
        // one placement of the photo (a different crop, a different context)
        // must not be silently overridden by the media file's own general
        // description.
        photo.setAltText("A red-tailed hawk banking against a clear sky");

        String html = render(Map.of("id", "mf-1", "alt", "Hawk in flight over the ridge"), null);

        assertTrue(html.contains(" alt=\"Hawk in flight over the ridge\""), html);
        assertFalse(html.contains("red-tailed"), html);
    }

    @Test
    void anExplicitEmptyAltAttributeIsHonouredAsDecorativeRatherThanFallingThrough() {
        // alt="" is the standard way an author asserts an image is purely
        // decorative. The attribute is PRESENT (attributes.containsKey is
        // true), which must be enough to win outright -- falling through to
        // the stored alt text or the filename here would actively defeat a
        // deliberate choice and put a filename in front of a screen reader.
        photo.setAltText("A red-tailed hawk banking against a clear sky");

        String html = render(Map.of("id", "mf-1", "alt", ""), null);

        assertTrue(html.contains(" alt=\"\""), html);
        assertFalse(html.contains("red-tailed"), html);
        assertFalse(html.contains("hawk.jpg"), html);
    }

    @Test
    void anAbsentAltAttributeFallsThroughToStoredAltTextThenFileName() {
        // The other half of the same distinction: when the "alt" key is not
        // in the attributes map at all (as opposed to present-but-empty),
        // the chain must still fall through normally.
        photo.setAltText("A red-tailed hawk banking against a clear sky");
        assertTrue(render(Map.of("id", "mf-1"), null)
                .contains(" alt=\"A red-tailed hawk banking against a clear sky\""));

        photo.setAltText(null);
        assertTrue(render(Map.of("id", "mf-1"), null).contains(" alt=\"hawk\""));
    }

    @Test
    void blankStoredAltTextAndBlankFileNameEmitAWellFormedEmptyAltNeverTheLiteralTextNull() {
        // Regression: firstNonBlank used to return Java null when every
        // candidate was blank, and escape(null) fed straight into
        // StringBuilder#append(String), which appends the four characters
        // "null" -- screen-reader-visible garbage, not an empty attribute.
        photo.setAltText("   ");
        photo.setName("   ");

        String html = render(Map.of("id", "mf-1"), null);

        assertTrue(html.contains(" alt=\"\""), html);
        assertFalse(html.contains("alt=\"null\""), html);
        assertFalse(html.contains("null"), html);
    }

    // -------------------------------------------------------------- blurhash

    @Test
    void blurhashAppearsOnlyWhenTheMediaFileHasOne() {
        assertFalse(render(Map.of("id", "mf-1"), null).contains("data-blurhash"),
                "files uploaded before the rendition pipeline have no blurhash; "
                        + "the attribute must be omitted, not emitted empty");

        photo.setBlurhash("LEHV6nWB2yk8pyo0adR*.7kCMdnj");
        assertTrue(render(Map.of("id", "mf-1"), null)
                .contains(" data-blurhash=\"LEHV6nWB2yk8pyo0adR*.7kCMdnj\""));
    }

    @Test
    void theBlurhashAverageColorBecomesAJsFreeBackgroundPlaceholder() {
        assertFalse(render(Map.of("id", "mf-1"), null).contains("background-color"),
                "no blurhash, no placeholder color");

        photo.setBlurhash("LEHV6nWB2yk8pyo0adR*.7kCMdnj");
        String html = render(Map.of("id", "mf-1"), null);
        // DC chars "HV6n" of the fixture hash decode to this average color.
        assertTrue(html.contains(" style=\"background-color:#979695\""),
                "the average color must back the reserved image box while it loads:\n" + html);
    }

    // ------------------------------------------------------- refusal to render

    @Test
    void withoutAnIdTheShortcodeIsLeftAsWritten() {
        assertNull(render(Map.of(), null));
        assertNull(render(Map.of("id", "  "), null));
    }

    @Test
    void anUnknownMediaFileIdIsLeftAsWritten() throws Exception {
        when(mediaFileManager.getMediaFile("nope")).thenReturn(null);
        assertNull(render(Map.of("id", "nope"), null));
    }

    @Test
    void aNonImageMediaFileIsLeftAsWritten() {
        photo.setContentType("application/pdf");
        assertNull(render(Map.of("id", "mf-1"), null));
    }

    // ------------------------------------------- only this weblog's public images

    /**
     * The id is global. Before this check any entry could embed any weblog's
     * image by id -- its alt text, filename, dimensions and blurhash, and an
     * advertised URL -- whatever that weblog thought of it.
     */
    @Test
    void anotherWeblogsImageIsLeftAsWritten() throws Exception {
        Weblog other = new Weblog();
        other.setHandle("other");
        MediaFile theirs = image("theirs", other, null);
        when(mediaFileManager.getMediaFile("theirs")).thenReturn(theirs);

        assertNull(render(Map.of("id", "theirs"), null));
    }

    /** The directory's weblog is the owner when it disagrees with the file's own field. */
    @Test
    void theOwnerIsTheDirectorysWeblog() throws Exception {
        Weblog other = new Weblog();
        other.setHandle("other");
        MediaFile filedElsewhere = image("elsewhere", weblog, new MediaFileDirectory(other, "trip", null));
        when(mediaFileManager.getMediaFile("elsewhere")).thenReturn(filedElsewhere);

        assertNull(render(Map.of("id", "elsewhere"), null));
    }

    /** A private directory is never rendered inline, by [gallery] or by [image]. */
    @Test
    void anImageInAPrivateDirectoryIsLeftAsWritten() throws Exception {
        MediaFileDirectory hidden = new MediaFileDirectory(weblog, "drafts", null);
        hidden.setPrivate(true);
        when(mediaFileManager.getMediaFile("hidden")).thenReturn(image("hidden", weblog, hidden));

        assertNull(render(Map.of("id", "hidden"), null));
    }

    @Test
    void thisWeblogsImageInAPublicDirectoryRenders() throws Exception {
        MediaFileDirectory trip = new MediaFileDirectory(weblog, "trip", null);
        when(mediaFileManager.getMediaFile("mine")).thenReturn(image("mine", weblog, trip));

        assertTrue(render(Map.of("id", "mine"), null).startsWith("<figure class=\"shortcode-image\">"));
    }

    /**
     * The render cache keeps an entry's HTML only while everything it read
     * was its own weblog's: a refused foreign id is still reported, so the
     * cache does not keep the refusal.
     */
    @Test
    void aForeignIdIsStillReportedAsAForeignRead() throws Exception {
        Weblog other = new Weblog();
        other.setHandle("other");
        when(mediaFileManager.getMediaFile("theirs")).thenReturn(image("theirs", other, null));
        List<Weblog> reads = new ArrayList<>();

        assertNull(shortcode.render(Map.of("id", "theirs"), null, entry, reads::add));
        assertEquals(List.of(other), reads);
    }

    /** With no weblog to own the image there is nothing it could belong to. */
    @Test
    void outsideAnyWeblogTheShortcodeIsLeftAsWritten() {
        assertNull(shortcode.render(Map.of("id", "mf-1"), null, new WeblogEntry()));
        assertNull(shortcode.render(Map.of("id", "mf-1"), null, null));
    }

    private static MediaFile image(String id, Weblog owner, MediaFileDirectory directory) {
        MediaFile file = new MediaFile();
        file.setId(id);
        file.setWeblog(owner);
        file.setDirectory(directory);
        file.setName(id + ".jpg");
        file.setContentType("image/jpeg");
        return file;
    }

    @Test
    void aFailingMediaLookupIsLeftAsWrittenRatherThanBreakingTheRender() throws Exception {
        when(mediaFileManager.getMediaFile("mf-1"))
                .thenThrow(new WebloggerException("db down"));
        assertNull(render(Map.of("id", "mf-1"), null));
    }

    // ---------------------------------------------------------- via expander

    @Test
    void aCaptionContainingBracketsSurvivesTheParser() {
        // Review Important #2 repro: brackets inside a quoted caption are an
        // ordinary thing to type and must neither truncate the caption nor
        // leak raw shortcode syntax around the figure.
        String rendered = (ShortcodeExpander.builtIn(weblogger, mediaFileManager)
                .expand(entry, "[image id=mf-1 caption=\"Paris [2023]\"] after"));

        assertTrue(rendered.contains("<figcaption>Paris [2023]</figcaption>"), rendered);
        assertTrue(rendered.endsWith(" after"), rendered);
        assertFalse(rendered.contains("caption="), rendered);
        assertFalse(rendered.contains("[image"), rendered);
    }

    @Test
    void theDefaultExpanderShipsWithTheImageShortcodeRegistered() {
        String rendered = (ShortcodeExpander.builtIn(weblogger, mediaFileManager)
                .expand(entry, "look [image id=mf-1 caption=\"A hawk\"] here"));

        assertTrue(rendered.contains("<figure class=\"shortcode-image\">"), rendered);
        assertTrue(rendered.contains("srcset"), rendered);
        assertTrue(rendered.contains("<figcaption>A hawk</figcaption>"), rendered);
        assertFalse(rendered.contains("[image"), rendered);
        assertEquals(0, rendered.indexOf("look "), rendered);
    }
}

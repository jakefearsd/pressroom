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

import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.text.StringEscapeUtils;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.MediaFileManager;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.pojos.MediaFile;
import org.apache.roller.weblogger.pojos.MediaFileDirectory;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The built-in {@code [map]} shortcode: manual pins from the body's
 * {@code [pin ...]} tags, auto pins from a media directory's GPS-bearing
 * photos (with the [gallery] privacy gates copied intact), an entry-geo
 * center for a bare {@code [map]}, and null (leave the shortcode as
 * written) for everything that cannot be shown.
 */
class MapShortcodeTest {

    private MapShortcode shortcode;
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
        directory.setName("trip");
        directory.setWeblog(weblog);

        mediaFileManager = mock(MediaFileManager.class);
        when(mediaFileManager.getMediaFileDirectoryByName(weblog, "trip"))
                .thenReturn(directory);

        weblogger = mock(Weblogger.class);
        when(weblogger.getMediaFileManager()).thenReturn(mediaFileManager);
        shortcode = new MapShortcode(mediaFileManager);
    }

    private MediaFile photo(String name, Double lat, Double lng) {
        MediaFile file = new MediaFile();
        file.setId("mf-" + name);
        file.setWeblog(weblog);
        file.setName(name);
        file.setContentType("image/jpeg");
        file.setGpsLatitude(lat);
        file.setGpsLongitude(lng);
        directory.getMediaFiles().add(file);
        return file;
    }

    private String render(Map<String, String> attributes, String body) {
        return (shortcode.render(attributes, body, entry));
    }

    // ------------------------------------------------------------ manual pins

    @Test
    void manualPinsBecomeAnEncodedSingleLineJsonPayload() {
        String html = render(Map.of(),
                "[pin lat=\"48.8584\" lng=\"2.2945\" label=\"Eiffel Tower\"]\n"
                        + "[pin lat=\"48.8606\" lng=\"2.3376\"]");

        assertEquals("<div class=\"travel-map\" data-pins=\""
                + "[{&quot;lat&quot;:48.8584,&quot;lng&quot;:2.2945,"
                + "&quot;label&quot;:&quot;Eiffel Tower&quot;},"
                + "{&quot;lat&quot;:48.8606,&quot;lng&quot;:2.3376}]\"></div>"
                + "<ol class=\"travel-map-pins\"><li>Eiffel Tower</li></ol>", html);
        assertFalse(html.contains("\n"),
                "the payload must be one line; the sanitizer destroys a tag "
                        + "whose attribute value contains a newline");
    }

    // ------------------------------------------------------- the text fallback

    /**
     * The map is an empty div until Leaflet runs: a screen reader, a feed
     * reader and a reader without JavaScript got nothing, not even the pin
     * labels the author wrote. The labels follow the div as a list, on the
     * same line (a newline would end the commonmark HTML block).
     */
    @Test
    void theLabelledPinsFollowTheMapAsAList() {
        String html = render(Map.of(),
                "[pin lat=\"48.8584\" lng=\"2.2945\" label=\"Eiffel Tower\"]\n"
                        + "[pin lat=\"48.8606\" lng=\"2.3376\"]\n"
                        + "[pin lat=\"48.853\" lng=\"2.3499\" label=\"Fish & <b>Chips</b>\"]");

        assertTrue(html.endsWith("></div><ol class=\"travel-map-pins\"><li>Eiffel Tower</li>"
                + "<li>Fish &amp; &lt;b&gt;Chips&lt;/b&gt;</li></ol>"), html);
        assertFalse(html.contains("\n"), html);
    }

    @Test
    void pinsWithoutLabelsAddNoList() {
        assertFalse(render(Map.of(), "[pin lat=\"1\" lng=\"2\"]").contains("<ol"));
    }

    @Test
    void routeAndZoomAttributesRideAlongAsDataAttributes() {
        String html = render(Map.of("route", "true", "zoom", "13"),
                "[pin lat=\"1\" lng=\"2\"][pin lat=\"3\" lng=\"4\"]");

        assertTrue(html.contains(" data-zoom=\"13\""), html);
        assertTrue(html.endsWith(" data-route=\"true\"></div>"), html);
    }

    @Test
    void aRouteNeedsAtLeastTwoPins() {
        String html = render(Map.of("route", "true"), "[pin lat=\"1\" lng=\"2\"]");
        assertFalse(html.contains("data-route"),
                "a polyline through one pin is meaningless:\n" + html);
    }

    @Test
    void anInvalidZoomIsSkippedNotEmitted() {
        assertFalse(render(Map.of("zoom", "big"), "[pin lat=\"1\" lng=\"2\"]")
                .contains("data-zoom"));
        assertFalse(render(Map.of("zoom", "-3"), "[pin lat=\"1\" lng=\"2\"]")
                .contains("data-zoom"));
    }

    @Test
    void aScriptPayloadInAPinLabelIsJsonThenHtmlEscaped() {
        String html = render(Map.of(),
                "[pin lat=\"1\" lng=\"2\" label='\"><script>alert(1)</script>']");

        assertFalse(html.contains("<script"), html);
        assertTrue(html.contains("\\&quot;&gt;&lt;script&gt;"),
                "the label must be JSON-escaped then HTML-encoded:\n" + html);
    }

    @Test
    void aLiteralEntityNameLabelCannotFabricateExtraPins() throws Exception {
        // Neither escapeJson nor htmlEncodeApexesAndTags touches '&', so
        // without ampersand escaping the author-typed 6-char literal text
        // "&quot;" would survive both escape passes untouched -- and the
        // BROWSER's attribute decoding would then turn it into a real quote
        // inside the JSON layer, letting a label fabricate whole pins.
        // The exact review PoC:
        String evil = "&quot;},{&quot;lat&quot;:0,&quot;lng&quot;:0,"
                + "&quot;label&quot;:&quot;evil";
        String html = render(Map.of(),
                "[pin lat=\"1\" lng=\"2\" label=\"" + evil + "\"]");

        JsonNode pins = browserParsedPins(html);
        assertEquals(1, pins.size(),
                "the entity-text label fabricated a second pin: " + pins);
        assertEquals(evil, pins.get(0).get("label").asString(),
                "the label must round-trip as the author's literal text: " + pins);
        assertEquals(1.0, pins.get(0).get("lat").asDouble(), pins.toString());
    }

    @Test
    void anAmpersandLabelRoundTripsThroughBrowserDecodingAndJsonParse() throws Exception {
        String html = render(Map.of(),
                "[pin lat=\"1\" lng=\"2\" label=\"Fish & Chips\"]");

        JsonNode pins = browserParsedPins(html);
        assertEquals(1, pins.size(), pins.toString());
        assertEquals("Fish & Chips", pins.get(0).get("label").asString(), pins.toString());
    }

    /**
     * What T3's initialiser sees: the browser HTML-decodes the attribute
     * value into {@code dataset.pins}, then {@code JSON.parse} reads it.
     */
    private static JsonNode browserParsedPins(String html) throws Exception {
        String attribute = StringUtils.substringBetween(html, "data-pins=\"", "\"");
        String json = StringEscapeUtils.unescapeHtml4(attribute);
        return new ObjectMapper().readTree(json);
    }

    @Test
    void aMalformedPinAmongValidOnesIsDropped() {
        String html = render(Map.of(),
                "[pin lat=\"1\" lng=\"2\" label=\"good\"][pin lat=\"oops\" lng=\"2\"]");
        assertTrue(html.contains("&quot;label&quot;:&quot;good&quot;"), html);
        assertFalse(html.contains("oops"), html);
    }

    @Test
    void aBodyWhereEveryPinIsMalformedIsLeftAsWritten() {
        // rendering a pinless map would hide the author's mistake
        assertNull(render(Map.of(), "[pin lat=\"north\" lng=\"east\"]"));
        assertNull(render(Map.of(), "[pin]"));
    }

    // ---------------------------------------------------------- bare map/center

    @Test
    void aBareMapCentersOnTheEntryCoordinates() {
        entry.setGeoLatitude(64.1466);
        entry.setGeoLongitude(-21.9426);

        String html = render(Map.of(), null);

        assertEquals("<div class=\"travel-map\" data-center=\"64.1466,-21.9426\"></div>",
                html);
    }

    @Test
    void aBareMapWithoutEntryCoordinatesIsLeftAsWritten() {
        assertNull(render(Map.of(), null), "no geo fields at all");

        entry.setGeoLatitude(64.1466);
        assertNull(render(Map.of(), null), "latitude alone is not a center");

        entry.setGeoLatitude(null);
        entry.setGeoLongitude(-21.9426);
        assertNull(render(Map.of(), null), "longitude alone is not a center");
    }

    @Test
    void outOfRangeEntryCoordinatesAreNotACenter() {
        entry.setGeoLatitude(91.0);
        entry.setGeoLongitude(0.0);
        assertNull(render(Map.of(), null));
    }

    @Test
    void aPinlessBodyFallsBackToTheEntryCenter() {
        entry.setGeoLatitude(1.0);
        entry.setGeoLongitude(2.0);
        String html = render(Map.of(), "just some prose, no pin tags");
        assertEquals("<div class=\"travel-map\" data-center=\"1.0,2.0\"></div>", html);
    }

    @Test
    void pinnedMapsAlsoCarryTheEntryCenterWhenItIsSet() {
        entry.setGeoLatitude(1.0);
        entry.setGeoLongitude(2.0);
        String html = render(Map.of(), "[pin lat=\"3\" lng=\"4\"]");
        assertTrue(html.contains(" data-center=\"1.0,2.0\""), html);
        assertTrue(html.contains(" data-pins=\""), html);
    }

    @Test
    void aNullEntryRendersNoCenterAndNoAutoMap() {
        assertNull((shortcode.render(Map.of(), null, null)));
        assertNull((shortcode.render(
                Map.of("auto", "trip"), null, null)));
        // manual pins need no entry context at all
        String html = (shortcode.render(
                Map.of(), "[pin lat=\"1\" lng=\"2\"]", null));
        assertTrue(html.contains("data-pins"), html);
    }

    // -------------------------------------------------------------- auto maps

    @Test
    void autoMapsPinEveryGpsBearingImageInGalleryOrder() {
        photo("b.jpg", 10.0, 20.0).setSortOrder(null);
        photo("a.jpg", 30.0, 40.0).setSortOrder(1);

        String html = render(Map.of("auto", "trip"), null);

        // curated sortOrder first (a), then unordered by name (b) --
        // GalleryMarkup.GALLERY_ORDER, so pins match the gallery's order
        int a = html.indexOf("&quot;lat&quot;:30.0");
        int b = html.indexOf("&quot;lat&quot;:10.0");
        assertTrue(a >= 0 && b >= 0 && a < b, html);
        assertTrue(html.contains("&quot;label&quot;:&quot;a.jpg&quot;"), html);
    }

    @Test
    void anAutoPinPrefersTheDescriptionOverTheFileName() {
        photo("dscf1001.jpg", 1.0, 2.0).setDescription("Reynisfjara beach");

        String html = render(Map.of("auto", "trip"), null);

        assertTrue(html.contains("&quot;label&quot;:&quot;Reynisfjara beach&quot;"), html);
        assertFalse(html.contains("dscf1001"), html);
    }

    @Test
    void gpsLessAndNonImageFilesAreNotPinned() {
        photo("located.jpg", 1.0, 2.0);
        photo("stripped.jpg", null, null);
        photo("half.jpg", 1.0, null);
        MediaFile pdf = photo("map.pdf", 3.0, 4.0);
        pdf.setContentType("application/pdf");

        String html = render(Map.of("auto", "trip"), null);

        assertTrue(html.contains("&quot;label&quot;:&quot;located.jpg&quot;"), html);
        assertFalse(html.contains("stripped"), html);
        assertFalse(html.contains("half"), html);
        assertFalse(html.contains("map.pdf"), html);
    }

    @Test
    void autoIgnoresTheBodyPins() {
        photo("located.jpg", 1.0, 2.0);
        String html = render(Map.of("auto", "trip"), "[pin lat=\"9\" lng=\"9\"]");
        assertFalse(html.contains("9"), "auto resolves the directory, not the body:\n" + html);
    }

    // -------------------------------------------- auto refusals (the gates)

    @Test
    void aPrivateDirectoryIsLeftAsWritten() {
        // a private directory's photo coordinates are location metadata;
        // pinning them on a public map leaks what the private flag protects
        photo("secret.jpg", 1.0, 2.0);
        directory.setPrivate(true);
        assertNull(render(Map.of("auto", "trip"), null));
    }

    @Test
    void anUnknownDirectoryIsLeftAsWritten() throws Exception {
        when(mediaFileManager.getMediaFileDirectoryByName(weblog, "nope")).thenReturn(null);
        assertNull(render(Map.of("auto", "nope"), null));
    }

    @Test
    void aDirectoryWithZeroGpsBearingImagesIsLeftAsWritten() {
        // the shipped default strips GPS at upload (uploads.exif.stripGps),
        // so this is the common case on existing installs
        photo("stripped.jpg", null, null);
        assertNull(render(Map.of("auto", "trip"), null));
    }

    @Test
    void aFailingDirectoryLookupIsLeftAsWrittenRatherThanBreakingTheRender()
            throws Exception {
        when(mediaFileManager.getMediaFileDirectoryByName(any(), anyString()))
                .thenThrow(new RuntimeException(new WebloggerException("db down")));
        assertNull(render(Map.of("auto", "trip"), null));
    }

    @Test
    void anAutoMapRefusalDoesNotFallBackToTheEntryCenter() {
        // the author asked for a specific directory; a centered-but-empty
        // map would hide that it could not be shown
        entry.setGeoLatitude(1.0);
        entry.setGeoLongitude(2.0);
        directory.setPrivate(true);
        photo("secret.jpg", 3.0, 4.0);
        assertNull(render(Map.of("auto", "trip"), null));
    }

    // ---------------------------------------------------------- via expander

    @Test
    void theDefaultExpanderShipsWithTheMapShortcodeRegistered() {
        String rendered = (ShortcodeExpander.builtIn(weblogger, mediaFileManager)
                .expand(entry, "go [map][pin lat=\"1\" lng=\"2\" label=\"X\"][/map] now"));

        assertTrue(rendered.contains("<div class=\"travel-map\""), rendered);
        assertFalse(rendered.contains("[map]"), rendered);
        assertFalse(rendered.contains("[pin"), rendered);
        assertTrue(rendered.startsWith("go "), rendered);
        assertTrue(rendered.endsWith(" now"), rendered);
    }

    @Test
    void anUnrenderableMapLeavesTheShortcodeTextVisibleThroughTheExpander() {
        String rendered = (ShortcodeExpander.builtIn(weblogger, mediaFileManager)
                .expand(entry, "[map auto=\"trip\"]"));

        assertEquals("[map auto=\"trip\"]", rendered,
                "null from the handler is the SPI's visible-failure signal");
    }
}

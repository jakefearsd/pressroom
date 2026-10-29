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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.commons.text.StringEscapeUtils;
import com.wikantik.pressroom.business.MediaFileManager;
import com.wikantik.pressroom.pojos.MediaFile;
import com.wikantik.pressroom.pojos.MediaFileDirectory;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.util.HTMLSanitizer;

/**
 * The built-in {@code [map]} shortcode: a Leaflet map placeholder
 * {@code <div class="travel-map">} whose pins ride a single-line JSON
 * payload in {@code data-pins} (the theme-side {@code #showMapAssets} macro
 * initialises the map from {@code dataset.pins}; the sanitizer never lets a
 * {@code <script>} through entry content, so there is deliberately no
 * script here), followed by an {@code <ol class="travel-map-pins">} of the
 * labelled pins as the map's text fallback.
 *
 * <p>Three source forms:
 * <ul>
 *   <li><b>Manual pins</b> -- {@code [map]} ... {@code [/map]} with
 *       {@code [pin lat=".." lng=".." label=".."]} child tags in the body,
 *       parsed by {@link MapPins} (the shared parser the JSON-LD itinerary
 *       emission also uses). {@code [pin]} is not a registered shortcode,
 *       so the expander hands the body over with the pin tags intact.</li>
 *   <li><b>Auto map</b> -- {@code [map auto="DirName"]}: resolves the named
 *       media directory exactly like {@code [gallery dir=..]}, keeps the
 *       image files that retained GPS coordinates (off by default:
 *       {@code uploads.exif.stripGps}), orders them with
 *       {@link GalleryMarkup#GALLERY_ORDER} so pins match the gallery's
 *       visual order, and labels each pin with the file's description (or
 *       name). The body is ignored in this form.</li>
 *   <li><b>Bare {@code [map]}</b> -- no pins, no {@code auto}: centers on
 *       the entry's geo coordinates (the Wave 3 SEO fields) when both are
 *       set.</li>
 * </ul>
 *
 * <p>Optional attributes: {@code zoom="13"} (positive integer, becomes
 * {@code data-zoom}) and {@code route="true"} (becomes
 * {@code data-route="true"} when there are at least two pins to draw a
 * polyline through).
 *
 * <p>Returns null -- the expander's "leave the shortcode text exactly as
 * written" failure signal -- when the auto directory is missing,
 * {@linkplain MediaFileDirectory#isPrivate() private} (its photos'
 * coordinates are location metadata; pinning them on a public map would
 * leak exactly what the private flag protects), unresolvable, or holds no
 * GPS-bearing images; when every manual pin is malformed; and when a bare
 * map has no entry coordinates to center on.
 */
public class MapShortcode implements ShortcodeHandler {

    private static final Logger log = LoggerFactory.getLogger(MapShortcode.class);

    private final MediaFileManager mediaFiles;

    /**
     * @param mediaFiles the manager {@code [map auto=..]} resolves its
     *                   directory through; never dereferenced at
     *                   construction, so a lazy proxy is fine
     */
    public MapShortcode(MediaFileManager mediaFiles) {
        this.mediaFiles = mediaFiles;
    }

    @Override
    public String getName() {
        return "map";
    }

    @Override
    public ShortcodeCard getCard() {
        return CARD;
    }

    /**
     * Shared with the pin collector below, which registers a second handler
     * under the same name and would otherwise have to invent a card of its own.
     */
    private static final ShortcodeCard CARD = ShortcodeCard.snippet(
            "map", "shortcode.map.label",
            "[map zoom=\"12\"]\n[pin lat=\"48.8584\" lng=\"2.2945\" label=\"Eiffel Tower\"]\n"
                    + "[pin lat=\"48.8606\" lng=\"2.3376\" label=\"Louvre\"]\n[/map]");

    @Override
    public String render(Map<String, String> attributes, String body, ShortcodeContext content) {
        return render(attributes, body, content, ShortcodeReads.IGNORED);
    }

    /** Reports a failed directory lookup to {@code reads}: a render that could not look is not kept. */
    @Override
    @SuppressFBWarnings(
            value = "IMPROPER_UNICODE",
            justification = "Reads the author's route attribute as true in any case; it decides only "
                    + "whether the author's own map draws a route.")
    public String render(Map<String, String> attributes, String body, ShortcodeContext content,
                         ShortcodeReads reads) {
        List<MapPins.Pin> pins = resolvePins(attributes, body, content, mediaFiles, reads);
        if (pins == null) {
            return null;
        }

        String center = centerOf(content);
        if (pins.isEmpty() && center == null) {
            log.debug("[map] shortcode with no pins and no entry coordinates"
                    + " to center on; leaving it as written");
            return null;
        }

        StringBuilder html = new StringBuilder(128);
        html.append("<div class=\"travel-map\"");
        if (!pins.isEmpty()) {
            // ONE line, HTML-encoded quotes: a newline inside the attribute
            // value makes the sanitizer destroy the whole tag, and its
            // encoder is idempotent over &quot; so this survives
            // byte-identical (pinned by HTMLSanitizerTest.TravelMapMarkup).
            html.append(" data-pins=\"").append(escape(pinsJson(pins))).append('"');
        }
        if (center != null) {
            html.append(" data-center=\"").append(center).append('"');
        }
        int zoom = parsePositiveInt(attributes.get("zoom"));
        if (zoom > 0) {
            html.append(" data-zoom=\"").append(zoom).append('"');
        }
        if ("true".equalsIgnoreCase(attributes.get("route")) && pins.size() >= 2) {
            html.append(" data-route=\"true\"");
        }
        html.append("></div>");
        appendPinList(html, pins);
        return html.toString();
    }

    /**
     * The labelled pins as an {@code <ol class="travel-map-pins">} after the
     * map: without it a screen reader, a feed reader or a reader without
     * JavaScript got an empty div, and none of the places the author named.
     * The map bootstrap may hide it visually once the map is drawn. Same line
     * as the div: a newline would end the commonmark HTML block around it.
     */
    @SuppressFBWarnings(
            value = "POTENTIAL_XML_INJECTION",
            justification = "Each label goes through escape() (&, quotes and angle brackets encoded) before it "
                    + "is appended as list-item text, and the expanded entry then passes through "
                    + "HTMLSanitizer, the one content boundary.")
    private static void appendPinList(StringBuilder html, List<MapPins.Pin> pins) {
        List<String> labels = pins.stream().map(MapPins.Pin::label).filter(StringUtils::isNotBlank).toList();
        if (labels.isEmpty()) {
            return;
        }
        html.append("<ol class=\"travel-map-pins\">");
        for (String label : labels) {
            html.append("<li>").append(escape(StringUtils.normalizeSpace(label))).append("</li>");
        }
        html.append("</ol>");
    }

    /**
     * The pins one {@code [map]} tag resolves to, in source order, or null
     * when the tag must stay visible as the author wrote it (the expander's
     * failure signal): an unusable {@code auto} directory, or a manual map
     * whose every {@code [pin]} is malformed. An empty list means "no pins,
     * but nothing went wrong" -- a bare {@code [map]} that will centre on the
     * entry's coordinates.
     *
     * <p>Shared with the JSON-LD head emission via {@link #pinsInEntry} so a
     * TouristTrip itinerary is always exactly the map readers see -- including
     * the private-directory gate, which must not be bypassed just because the
     * consumer is a {@code <script>} tag rather than a {@code <div>}.
     *
     * <p>Deliberately null, not an empty list, on the malformed-pins path:
     * {@code render()}'s {@code if (pins == null) return null;} is a
     * three-state contract with the caller -- null means "hard failure, leave
     * the shortcode visible", empty means "valid, but nothing to draw (a bare
     * map that may still centre on entry coordinates)". Collapsing the two by
     * returning {@code List.of()} here would silently render a pinless map
     * for a directory the author clearly meant to fill with pins, which is
     * exactly the "hides the mistake" outcome the inline comment below
     * refuses.
     */
    @SuppressWarnings("PMD.ReturnEmptyCollectionRatherThanNull")
    public static List<MapPins.Pin> resolvePins(Map<String, String> attributes,
            String body, ShortcodeContext content, MediaFileManager mediaFiles, ShortcodeReads reads) {
        String auto = StringUtils.trimToNull(attributes.get("auto"));
        if (auto != null) {
            return autoPins(auto, content, mediaFiles, reads);
        }
        List<MapPins.Pin> pins = MapPins.parse(body);
        if (pins.isEmpty() && Strings.CI.contains(body, "[pin")) {
            // the author clearly tried to write pins and every one is
            // malformed: leave the shortcode visible rather than
            // rendering a pinless map that hides the mistake
            log.debug("[map] shortcode with only malformed pins; leaving it as written");
            return null;
        }
        return pins;
    }

    /**
     * Every pin of every {@code [map]} in an entry's RAW text, concatenated in
     * source order -- the TouristTrip itinerary, built by the same machinery
     * that builds the page.
     *
     * <p>It walks the text with a real {@link ShortcodeExpander} carrying a
     * single collecting handler rather than a fourth {@code [map]}-matching
     * regex: tag grammar, quoted attributes, nesting and the
     * {@code [[map ...]]} escape form then behave identically on both sides by
     * construction. The collector returns null so nothing is rewritten; the
     * expanded text is discarded and only the pins are kept.
     */
    public static List<MapPins.Pin> pinsInEntry(ShortcodeContext content, MediaFileManager mediaFiles) {
        if (content == null || content.getRawText() == null) {
            return List.of();
        }
        List<MapPins.Pin> collected = new ArrayList<>();
        ShortcodeExpander collector = new ShortcodeExpander(List.of(new ShortcodeHandler() {
            @Override
            public String getName() {
                return "map";
            }

            @Override
            public ShortcodeCard getCard() {
                return CARD;
            }

            @Override
            public String render(Map<String, String> attributes, String body, ShortcodeContext c) {
                List<MapPins.Pin> pins = resolvePins(attributes, body, c, mediaFiles, ShortcodeReads.IGNORED);
                if (pins != null) {
                    collected.addAll(pins);
                }
                return null;
            }
        }));
        collector.expand(content, content.getRawText());
        return List.copyOf(collected);
    }

    /**
     * The GPS-bearing images of the named directory as ordered pins, or
     * null when the directory cannot be shown -- the same gates as
     * {@link GalleryShortcode}: missing or private directory, resolution
     * failure, or nothing to render.
     *
     * <p>Deliberately null throughout, not an empty list: this method's
     * result becomes {@link #resolvePins}'s return value verbatim on the
     * {@code auto} path, and that method's own javadoc explains why an empty
     * list would mean something different (and worse) than null here -- see
     * {@link #resolvePins}.
     */
    @SuppressWarnings("PMD.ReturnEmptyCollectionRatherThanNull")
    private static List<MapPins.Pin> autoPins(String directoryName, ShortcodeContext content,
            MediaFileManager mediaFiles, ShortcodeReads reads) {
        Weblog weblog = content == null ? null : content.getWeblog();
        if (weblog == null) {
            return null;
        }
        List<MapPins.Pin> pins;
        try {
            MediaFileDirectory directory = mediaFiles.getMediaFileDirectoryByName(weblog, directoryName);
            if (directory == null) {
                log.debug("[map] shortcode auto directory \"{}\" does not exist in weblog {}",
                        directoryName, weblog.getHandle());
                return null;
            }
            if (directory.isPrivate()) {
                // A private directory's photo coordinates are location
                // metadata; a public map pinning them would leak exactly
                // what the private flag protects (same gate as [gallery]).
                log.debug("[map] shortcode auto directory \"{}\" is private; not mapping it",
                        directoryName);
                return null;
            }
            pins = directory.getMediaFiles().stream()
                    .filter(MediaFile::isImageFile)
                    .filter(mf -> mf.getGpsLatitude() != null && mf.getGpsLongitude() != null)
                    .sorted(GalleryMarkup.GALLERY_ORDER)
                    .map(mf -> new MapPins.Pin(mf.getGpsLatitude(), mf.getGpsLongitude(),
                            StringUtils.defaultIfBlank(
                                    StringUtils.trimToNull(mf.getDescription()),
                                    StringUtils.trimToNull(mf.getName()))))
                    .toList();
        } catch (Exception e) {
            // Null leaves the [map] text in the page on purpose: a visible broken tag tells the author
            // something is wrong, an empty block would not. Only the log says why.
            log.warn("[map] shortcode could not resolve auto directory \"{}\"",
                    directoryName, e);
            reads.readFrom(null);
            return null;
        }
        if (pins.isEmpty()) {
            // GPS is stripped at upload by default (uploads.exif.stripGps);
            // an empty map would just be confusing, so stay visible instead.
            log.debug("[map] shortcode auto directory \"{}\" holds no GPS-bearing images;"
                    + " leaving it as written", directoryName);
            return null;
        }
        return pins;
    }

    /**
     * {@code "lat,lng"} from the entry's geo fields, or null when unset or
     * invalid. The geo fields are entry-only -- not part of
     * {@link ShortcodeContext} -- so a page (or any future implementation)
     * has nothing to center on here; that is unchanged behaviour, since
     * every {@code [map]} today renders from a {@link WeblogEntry}.
     */
    private static String centerOf(ShortcodeContext content) {
        if (!(content instanceof WeblogEntry entry)) {
            return null;
        }
        Double lat = entry.getGeoLatitude();
        Double lng = entry.getGeoLongitude();
        if (lat == null || lng == null
                || !Double.isFinite(lat) || !Double.isFinite(lng)
                || Math.abs(lat) > 90 || Math.abs(lng) > 180) {
            return null;
        }
        return lat + "," + lng;
    }

    /**
     * The pins as a single-line JSON array:
     * {@code [{"lat":48.8584,"lng":2.2945,"label":"Eiffel Tower"},...]},
     * label JSON-escaped and omitted when absent. The theme-side
     * initialiser and any other consumer read it via {@code dataset.pins}.
     */
    private static String pinsJson(List<MapPins.Pin> pins) {
        StringBuilder json = new StringBuilder(64 * pins.size());
        json.append('[');
        for (int i = 0; i < pins.size(); i++) {
            MapPins.Pin pin = pins.get(i);
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"lat\":").append(pin.lat())
                    .append(",\"lng\":").append(pin.lng());
            if (pin.label() != null) {
                json.append(",\"label\":\"")
                        .append(StringEscapeUtils.escapeJson(pin.label())).append('"');
            }
            json.append('}');
        }
        return json.append(']').toString();
    }

    private static int parsePositiveInt(String value) {
        if (StringUtils.isBlank(value)) {
            return 0;
        }
        try {
            return Math.max(Integer.parseInt(value.trim()), 0);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * HTML-encodes the JSON payload for the {@code data-pins} attribute:
     * {@code &} FIRST, then quotes/apexes/tags. The ampersand pass is
     * load-bearing -- {@code htmlEncodeApexesAndTags} leaves ampersands
     * alone, so without it an author-typed literal {@code &quot;} (six
     * plain characters) in a label would survive both the JSON and the
     * HTML escape passes untouched and the browser's attribute decoding
     * would turn it into a REAL quote inside the JSON that
     * {@code JSON.parse(dataset.pins)} reads, letting a label fabricate
     * whole pins. Escaping {@code &} to {@code &amp;} first means browser
     * decoding restores exactly the author's literal text, and the
     * encoder's own {@code &quot;}/{@code &lt;} output (produced after
     * this pass) is never double-encoded. The sanitizer's re-encode also
     * leaves {@code &} alone, so the payload still survives byte-identical
     * (pinned by HTMLSanitizerTest.TravelMapMarkup).
     */
    private static String escape(String value) {
        return HTMLSanitizer.htmlEncodeApexesAndTags(value.replace("&", "&amp;"));
    }
}

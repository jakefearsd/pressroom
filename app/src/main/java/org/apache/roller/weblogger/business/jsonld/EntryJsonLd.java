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
package org.apache.roller.weblogger.business.jsonld;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.commons.text.StringEscapeUtils;
import org.apache.roller.util.DateUtil;
import org.apache.roller.weblogger.business.shortcodes.FaqBlocks;
import org.apache.roller.weblogger.business.MediaFileManager;
import org.apache.roller.weblogger.business.shortcodes.MapPins;
import org.apache.roller.weblogger.business.shortcodes.MapShortcode;
import org.apache.roller.weblogger.pojos.JsonLdType;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.apache.roller.weblogger.util.Utilities;

import static org.apache.roller.weblogger.business.jsonld.JsonLdWriter.put;

/**
 * Builds the typed schema.org JSON-LD object a permalink emits when its author
 * picked a travel type in the editor's SEO card.
 *
 * <p><b>Where this runs.</b> {@code #showSeoHead} (weblog.vm) reaches this
 * through {@code $utils.travelJsonLd(...)}. Every permalink emits its
 * {@code BlogPosting} block unchanged, whatever the type: a travel post is
 * still a blog post, and its {@code datePublished}/{@code dateModified}/
 * {@code author}/{@code headline} are what feeds and search rely on. A non-null
 * return here is emitted as a SECOND {@code application/ld+json} block after
 * it -- consumers accept several per page, and the two are associated by both
 * carrying the same canonical {@code url}/{@code mainEntityOfPage}. Null means
 * "no second block". Null and {@link JsonLdType#BLOG_POSTING} are treated
 * identically on purpose: {@code EntryBean.copyTo} normalizes a blank selection
 * to {@code BLOG_POSTING}, so entries written before this field existed and
 * entries re-saved through the editor must both keep rendering exactly as
 * before.
 *
 * <p><b>Why the whole block is built in Java.</b> The fact base allowed either
 * a Velocity-side assembly from escaped fragments or a Java-side emission of
 * the finished object. Java won for three reasons: the itinerary and FAQ
 * bodies are variable-length arrays that Velocity would have to comma-join by
 * hand (one stray comma and crawlers drop the block); every value goes through
 * exactly one escape path here; and the result is unit-testable without a
 * servlet, a theme or a database.
 *
 * <p><b>Escaping.</b> Every string is written through
 * {@link StringEscapeUtils#escapeJson}, the same helper {@code $utils.escapeJson}
 * uses, which also escapes {@code /} so a value containing {@code </script>}
 * cannot terminate the inline script element it is emitted into.
 *
 * <p><b>Where each type's data comes from.</b>
 * <ul>
 *   <li>{@code name}/{@code description}/{@code image}/{@code url} are passed
 *       in by the macro, already resolved by the same plumbing that feeds the
 *       Open Graph tags (meta title else entry title, search description,
 *       featured/og image, canonical URL) -- but RAW, never pre-escaped, so
 *       this class is the only place that escapes them.</li>
 *   <li>{@code TouristAttraction.geo} comes from the entry's geo columns.</li>
 *   <li>{@code TouristTrip.itinerary} is derived from the entry's {@code [map]}
 *       pins in source order via {@link MapShortcode#pinsInEntry} -- the same
 *       resolution, and the same private-directory/failure gates, that produce
 *       the map readers see. {@code #showSeoHead} runs before entry content is
 *       rendered, so the head side re-parses the raw entry text; sharing the
 *       resolver is what keeps map and itinerary from drifting.</li>
 *   <li>{@code FAQPage.mainEntity} comes from {@link FaqBlocks#parse} over the
 *       same raw text, for the same reason.</li>
 *   <li>{@code Event} dates and venue come from the entry's event columns.</li>
 * </ul>
 *
 * <p><b>When the second block is refused</b> (null return, the BlogPosting
 * block stands alone): an {@code Event} with no start date and an
 * {@code FAQPage} with no parseable {@code [faq]} pairs. Both are structurally
 * invalid without that data, and shipping invalid structured data is worse
 * than shipping nothing extra. A {@code TouristAttraction} with no coordinates
 * and a {@code TouristTrip} with no pins are still valid (only {@code name} is
 * required), so those emit without the optional property.
 *
 * <p><b>Honest caveat.</b> Since August 2023 Google restricts FAQ rich results
 * to well-known authoritative government and health sites. The {@code FAQPage}
 * markup here is valid and machine-readable, but it will not produce FAQ rich
 * snippets for a travel blog.
 */
public final class EntryJsonLd {

    private static final Logger log = LoggerFactory.getLogger(EntryJsonLd.class);

    /** Tags whose removal must leave a word boundary behind. */
    private static final Pattern LINE_BREAKING_TAG = Pattern.compile(
            "<\\s*/?(?:br|p|div|li|ul|ol|h[1-6]|blockquote|tr|dt|dd)\\b[^>]*>",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");

    private EntryJsonLd() {
        // static use only
    }

    /**
     * The typed JSON-LD object for {@code entry} as a single JSON string, to be
     * emitted alongside the permalink's BlogPosting block -- or null when the
     * entry has no travel type (or lacks the data its type structurally
     * requires) and the BlogPosting block should stand alone.
     *
     * @param entry       the entry being rendered; null yields null
     * @param name        resolved display name, RAW (unescaped) text
     * @param description resolved description, RAW text; blank means absent
     * @param imageUrl    resolved absolute image URL; blank means absent
     * @param url         resolved canonical URL; blank means absent
     * @param mediaFiles  the media manager a {@code [map auto=..]} resolves its
     *                    directory through, so the itinerary is exactly the
     *                    map readers see
     */
    public static String build(WeblogEntry entry, String name, String description,
            String imageUrl, String url, MediaFileManager mediaFiles) {

        JsonLdType type = entry == null ? null : entry.getJsonLdType();
        // TECH_ARTICLE is not a second object: it changes the type of the
        // permalink's one article block (UtilitiesModel.articleSchemaType).
        if (type == null || type == JsonLdType.BLOG_POSTING || type == JsonLdType.TECH_ARTICLE) {
            return null;
        }

        Map<String, Object> node = new LinkedHashMap<>();
        node.put("@context", "https://schema.org");
        node.put("@type", type.getSchemaType());
        put(node, "name", StringUtils.trimToNull(name));
        put(node, "description", StringUtils.trimToNull(description));
        put(node, "image", StringUtils.trimToNull(imageUrl));
        // url AND mainEntityOfPage, both the canonical URL. The sibling
        // BlogPosting block on the same page carries mainEntityOfPage too, and
        // matching values are how a consumer associates the two objects
        // without either having to nest the other.
        put(node, "url", StringUtils.trimToNull(url));
        put(node, "mainEntityOfPage", StringUtils.trimToNull(url));

        switch (type) {
            case TOURIST_ATTRACTION -> put(node, "geo", geoOf(entry));
            case TOURIST_TRIP -> put(node, "itinerary", itineraryOf(entry, mediaFiles));
            case EVENT -> {
                if (!addEvent(node, entry)) {
                    return null;
                }
            }
            case FAQ_PAGE -> {
                List<Object> questions = questionsOf(entry);
                if (questions.isEmpty()) {
                    log.debug("Entry {} is typed FAQ_PAGE but has no"
                            + " parseable [faq] pairs; emitting no second block", entry.getId());
                    return null;
                }
                node.put("mainEntity", questions);
            }
            default -> { /* BLOG_POSTING and TECH_ARTICLE already returned above */ }
        }
        return JsonLdWriter.write(node);
    }

    // ------------------------------------------------------------ per type

    /**
     * {@code GeoCoordinates} from the entry's geo columns, or null unless both
     * are set, finite and in range -- the same validity rule
     * {@code MapShortcode} applies before using them as a map centre.
     *
     * <p>Deliberately null, not an empty map: the only caller is
     * {@code put(node, "geo", geoOf(entry))}, which treats null as "omit this
     * optional property" (see {@link #put}). An empty map would instead be
     * inserted as {@code "geo":{}}, which is not valid {@code GeoCoordinates}
     * -- see the class javadoc's "TouristAttraction with no coordinates ...
     * still valid" note and {@code EntryJsonLdTest}'s
     * {@code assertFalse(...has("geo"))} assertions, which pin the omission.
     */
    @SuppressWarnings("PMD.ReturnEmptyCollectionRatherThanNull")
    private static Map<String, Object> geoOf(WeblogEntry entry) {
        Double lat = entry.getGeoLatitude();
        Double lng = entry.getGeoLongitude();
        if (lat == null || lng == null
                || !Double.isFinite(lat) || !Double.isFinite(lng)
                || Math.abs(lat) > 90 || Math.abs(lng) > 180) {
            return null;
        }
        return coordinates(lat, lng);
    }

    private static Map<String, Object> coordinates(double lat, double lng) {
        Map<String, Object> geo = new LinkedHashMap<>();
        geo.put("@type", "GeoCoordinates");
        geo.put("latitude", lat);
        geo.put("longitude", lng);
        return geo;
    }

    /**
     * The entry's map pins as an ordered {@code ItemList} of {@code Place}s, or
     * null when the entry has no resolvable pins (a TouristTrip without an
     * itinerary is still valid). A pin the author gave no label keeps its
     * position and coordinates but has no {@code name}: inventing one from the
     * numbers would be fabrication.
     *
     * <p>Deliberately null, not an empty map: same reasoning as {@link #geoOf}
     * -- the caller's {@link #put} treats null as "omit this optional
     * property", and an empty {@code "itinerary":{}} would be emitted instead
     * if this returned {@code Map.of()}. {@code EntryJsonLdTest}'s
     * {@code aTouristTripWithNoPinsStillEmitsWithoutAnItinerary} pins the
     * omission.
     */
    @SuppressWarnings("PMD.ReturnEmptyCollectionRatherThanNull")
    private static Map<String, Object> itineraryOf(WeblogEntry entry, MediaFileManager mediaFiles) {
        List<MapPins.Pin> pins = MapShortcode.pinsInEntry(entry, mediaFiles);
        if (pins.isEmpty()) {
            return null;
        }
        List<Object> places = new ArrayList<>(pins.size());
        for (MapPins.Pin pin : pins) {
            Map<String, Object> place = new LinkedHashMap<>();
            place.put("@type", "Place");
            put(place, "name", StringUtils.trimToNull(pin.label()));
            place.put("geo", coordinates(pin.lat(), pin.lng()));
            places.add(place);
        }
        Map<String, Object> itinerary = new LinkedHashMap<>();
        itinerary.put("@type", "ItemList");
        itinerary.put("numberOfItems", places.size());
        itinerary.put("itemListElement", places);
        return itinerary;
    }

    /**
     * Adds {@code startDate}, optional {@code endDate} and an optional
     * name-only {@code Place} location. Returns false when there is no start
     * date -- schema.org requires one, so the caller emits no second block at
     * all rather than an Event that cannot validate.
     *
     * <p>The venue is emitted as {@code {"@type":"Place","name":..}} and
     * nothing more: the editor collects a venue name, not an address, and
     * fabricating a {@code PostalAddress} from it would be a lie.
     */
    private static boolean addEvent(Map<String, Object> node, WeblogEntry entry) {
        Timestamp start = entry.getEventStart();
        if (start == null) {
            log.debug("Entry {} is typed EVENT but has no start date;"
                    + " emitting no second block", entry.getId());
            return false;
        }
        node.put("startDate", DateUtil.formatIso8601(start));
        if (entry.getEventEnd() != null) {
            node.put("endDate", DateUtil.formatIso8601(entry.getEventEnd()));
        }
        String venue = StringUtils.trimToNull(entry.getEventLocation());
        if (venue != null) {
            Map<String, Object> place = new LinkedHashMap<>();
            place.put("@type", "Place");
            place.put("name", venue);
            node.put("location", place);
        }
        return true;
    }

    /**
     * The entry's {@code [faq]} pairs as {@code Question}/{@code Answer}
     * objects, in source order; empty when there are none.
     *
     * <p>Both sides are reduced to plain text -- tags stripped, HTML entities
     * decoded, whitespace collapsed -- before JSON escaping. {@code Question.name} may not contain
     * markup at all, and treating the answer the same way keeps one rule
     * instead of two; the rendered {@code <dl>} on the page is where the
     * author's formatting lives.
     */
    private static List<Object> questionsOf(WeblogEntry entry) {
        List<Object> questions = new ArrayList<>();
        for (FaqBlocks.Qa pair : FaqBlocks.parse(entry.getText())) {
            String question = plainText(pair.question());
            String answer = plainText(pair.answer());
            if (question == null || answer == null) {
                continue;
            }
            Map<String, Object> accepted = new LinkedHashMap<>();
            accepted.put("@type", "Answer");
            accepted.put("text", answer);
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("@type", "Question");
            node.put("name", question);
            node.put("acceptedAnswer", accepted);
            questions.add(node);
        }
        return questions;
    }

    /**
     * Markup stripped, entities decoded and whitespace collapsed, or null when
     * nothing is left.
     *
     * <p>Line-breaking elements become a space first so two paragraphs do not
     * fuse into one word; everything else is simply removed, so
     * {@code "Yes, <strong>always</strong>."} reads as written instead of
     * gaining a space before the full stop.
     */
    private static String plainText(String html) {
        if (html == null) {
            return null;
        }
        String spaced = LINE_BREAKING_TAG.matcher(html).replaceAll(" ");
        String text = StringEscapeUtils.unescapeHtml4(Utilities.removeHTML(spaced, false));
        return StringUtils.trimToNull(WHITESPACE_RUN.matcher(text).replaceAll(" "));
    }
}

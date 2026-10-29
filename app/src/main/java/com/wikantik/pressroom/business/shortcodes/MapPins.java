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
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Parses {@code [pin lat=".." lng=".." label=".."]} tags out of raw entry
 * text into ordered {@link Pin} records.
 *
 * <p><b>This parser is the single source of truth for manual map pins.</b>
 * Two emitters call it and must never re-implement it: the {@code [map]}
 * shortcode ({@link MapShortcode}, which turns the pins into the
 * {@code data-pins} payload readers see) and the JSON-LD head emission
 * (Wave 3 T4, which turns the same pins into a {@code TouristTrip}
 * itinerary). Because {@code #showSeoHead} runs before entry content is
 * rendered, the head side re-parses the <em>raw</em> {@code entry.getText()}
 * -- sharing this parser is what keeps the map on the page and the itinerary
 * in the head from drifting.
 *
 * <p>{@code [pin]} is deliberately NOT a registered shortcode: the expander
 * passes unknown names through unchanged, so pin tags survive inside a
 * {@code [map]} body for this parser to read (and render as literal text if
 * the author strands one outside a map).
 *
 * <p>Syntax, matching the expander's attribute rules: {@code lat} and
 * {@code lng} are required decimal degrees ({@code -90..90} /
 * {@code -180..180}); {@code label} is optional display text. Values may be
 * double-quoted, single-quoted, or bare; the tag may be written
 * self-closing. A malformed pin -- missing, unparseable, non-finite, or
 * out-of-range coordinates -- is skipped (logged at debug), never guessed
 * at; the valid pins around it still parse. {@code [[pin ...]]} is the
 * escape form for mentioning the syntax in prose and is never a pin.
 */
public final class MapPins {

    private static final Logger log = LoggerFactory.getLogger(MapPins.class);

    /**
     * A {@code [pin ...]} tag: same attribute-section grammar as
     * {@link ShortcodeExpander}'s OPEN_TAG, anchored to the {@code pin} name
     * ({@code [pins ...]} is a different, unknown tag).
     */
    private static final Pattern PIN_TAG = Pattern.compile(
            "\\[pin((?:\\s(?:[^\\]\"']|\"[^\"]*\"|'[^']*')*)?/?)\\]",
            Pattern.CASE_INSENSITIVE);

    /** Same attribute grammar as the expander: double-quoted, single-quoted, or bare. */
    private static final Pattern ATTRIBUTE = Pattern.compile(
            "([\\w-]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\\]]+))");

    /**
     * One map pin, in entry-source order. {@code label} is the author's
     * display text, raw and unescaped -- every emitter escapes for its own
     * context (HTML-encoded into {@code data-pins}, JSON-escaped into the
     * itinerary) -- or null when the author gave none.
     */
    public record Pin(double lat, double lng, String label) {
    }

    private MapPins() {
        // static use only
    }

    /**
     * Every well-formed pin in {@code text}, in source order. Accepts raw
     * entry text or a single {@code [map]} body alike; returns an empty list
     * (never null) when there is nothing to find.
     */
    public static List<Pin> parse(String text) {
        if (text == null || !text.toLowerCase(Locale.ROOT).contains("[pin")) {
            return List.of();
        }
        List<Pin> pins = new ArrayList<>();
        Matcher matcher = PIN_TAG.matcher(text);
        while (matcher.find()) {
            if (matcher.start() > 0 && text.charAt(matcher.start() - 1) == '[') {
                // [[pin ...]]: an escaped mention of the syntax, not a pin
                continue;
            }
            Pin pin = toPin(matcher.group(1));
            if (pin != null) {
                pins.add(pin);
            }
        }
        return pins;
    }

    @SuppressFBWarnings(
            value = "IMPROPER_UNICODE",
            justification = "Lower-cases an author's attribute names (lat, lng, label) to pick a field of "
                    + "the pin; it decides only how the author's own map content is parsed.")
    private static Pin toPin(String attributeText) {
        String lat = null;
        String lng = null;
        String label = null;
        // strip a self-close slash so it never reads as part of a bare value
        String section = attributeText == null ? "" : attributeText.stripTrailing();
        if (section.endsWith("/")) {
            section = section.substring(0, section.length() - 1);
        }
        Matcher attributes = ATTRIBUTE.matcher(section);
        while (attributes.find()) {
            String value = attributes.group(2) != null ? attributes.group(2)
                    : attributes.group(3) != null ? attributes.group(3)
                    : attributes.group(4);
            switch (attributes.group(1).toLowerCase(Locale.ROOT)) {
                case "lat" -> lat = value;
                case "lng" -> lng = value;
                case "label" -> label = value;
                default -> { /* unknown attribute: ignored */ }
            }
        }

        Double latitude = parseCoordinate(lat, 90);
        Double longitude = parseCoordinate(lng, 180);
        if (latitude == null || longitude == null) {
            log.debug("Skipping malformed [pin] (lat={}, lng={})", lat, lng);
            return null;
        }
        return new Pin(latitude, longitude, StringUtils.trimToNull(label));
    }

    /** The parsed coordinate, or null when missing, unparseable, or outside +-bound. */
    private static Double parseCoordinate(String value, double bound) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        double parsed;
        try {
            parsed = Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
        if (!Double.isFinite(parsed) || Math.abs(parsed) > bound) {
            return null;
        }
        return parsed;
    }
}

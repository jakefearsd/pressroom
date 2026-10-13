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

package org.apache.roller.weblogger.ui.controllers;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.TimeZone;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.text.StringEscapeUtils;

/**
 * The rules that turn an author's raw entry input into stored values.
 *
 * <p>Shared by {@code EntryBean} (the JSP editor) and the automation API so
 * the two cannot drift. Two rules matter more than the rest:
 *
 * <ul>
 *   <li>An entry title is stored HTML-escaped. This is the only place raw
 *       author input becomes escaped markup for an entry, which is why
 *       {@code WeblogEntry.getTitle()} returns entity-escaped text and every
 *       theme emits {@code $entry.title} bare. A PAGE title is the opposite --
 *       stored raw, escaped at render -- so copying this rule to the page side
 *       double-encodes and copying the page rule here is stored XSS.</li>
 *   <li>A pubtime is wall-clock time in the WEBLOG's zone. Blank means
 *       "publish now"; an unparseable non-blank value throws, so a mistyped
 *       time blocks the save instead of silently publishing immediately.</li>
 *   <li>An event time is wall-clock time in the WEBLOG's zone, like a pubtime.</li>
 * </ul>
 */
public final class EntryFieldRules {

    private EntryFieldRules() {
        // static rules only
    }

    /** {@code weblogentry.anchor} is varchar(255) (V002__baseline_schema.sql). */
    public static final int ANCHOR_MAX_LENGTH = 255;

    /**
     * Whether a caller may choose {@code anchor} as an entry's slug. Stricter
     * than the anchors Roller generates or has stored, which may carry other
     * characters: a chosen anchor is refused, never normalised, so the URL is
     * exactly the one the caller asked for.
     */
    public static boolean isValidAnchor(String anchor) {
        if (anchor == null || anchor.length() > ANCHOR_MAX_LENGTH) {
            return false;
        }
        // A single pass, not a regex: a nested quantifier reads to SpotBugs as ReDoS,
        // and this needs no backtracking. Lower-case letters and digits, in words
        // joined by one '-' or '_'; the anchor may neither start nor end with a separator.
        boolean atWordStart = true;
        for (char c : anchor.toCharArray()) {
            if (c == '-' || c == '_') {
                if (atWordStart) {
                    return false;
                }
                atWordStart = true;
            } else if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                atWordStart = false;
            } else {
                return false;
            }
        }
        return !atWordStart;
    }

    /**
     * HTML-escapes an entry title for storage. Null-safe: a null title
     * (never entered) stays null rather than becoming the literal string
     * {@code "null"}.
     */
    public static String escapeTitle(String rawTitle) {
        return StringEscapeUtils.escapeHtml4(rawTitle);
    }

    /**
     * Parses a {@code datetime-local} wall-clock string -- what an
     * {@code <input type="datetime-local">} submits -- as time in {@code
     * zone}. A blank value means "no time chosen" and returns null, which
     * callers read as "publish now". A non-blank value that will not parse
     * throws {@link DateTimeParseException} rather than being silently
     * discarded: a mistyped pubtime must surface as a validation error, not
     * quietly publish "now".
     */
    public static Timestamp parsePubTime(String wallClock, TimeZone zone) {
        if (StringUtils.isBlank(wallClock)) {
            return null;
        }
        LocalDateTime local = LocalDateTime.parse(wallClock.trim());
        return Timestamp.from(local.atZone(zone.toZoneId()).toInstant());
    }

    private static final DateTimeFormatter WALL_CLOCK =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm", Locale.ROOT);

    /** An event's start or end: wall-clock time in the weblog's zone, the format a pubtime takes. */
    public static Timestamp parseEventTime(String wallClock, TimeZone zone) {
        return parsePubTime(wallClock, zone);
    }

    /** {@code instant} as wall-clock time in {@code zone}, to the minute; "" for null. */
    public static String formatWallClock(Timestamp instant, TimeZone zone) {
        if (instant == null) {
            return "";
        }
        return instant.toInstant().atZone(zone.toZoneId()).toLocalDateTime().format(WALL_CLOCK);
    }

    /** True when both are set and the end is before the start. */
    public static boolean endsBeforeStart(Timestamp start, Timestamp end) {
        return start != null && end != null && end.before(start);
    }
}

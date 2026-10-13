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
package org.apache.roller.weblogger.business.comments;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.roller.weblogger.pojos.ReaderComment;

/**
 * The posting rules that need no database: the body's limits, the link count
 * and the status a new top-level comment gets.
 */
public final class CommentRules {

    /** The longest body, in characters (code points), after trimming. */
    public static final int MAX_BODY = 4000;

    /** A trusted reader's comment with more {@code http(s)} URLs than this is held. */
    public static final int MAX_LINKS_BEFORE_HOLD = 2;

    // A substring count, not a URL parse: it errs toward holding (a body that merely repeats
    // http:// is held; a bare www.x.com is not counted), is easy to explain to a blog owner, and
    // gives a spammer no parser to shape input for. A held comment is delayed, not lost.
    private static final Pattern HTTP_LINK = Pattern.compile("https?://", Pattern.CASE_INSENSITIVE);

    /** Control characters (C0, DEL, C1) except tab, newline and carriage return. */
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cc}&&[^\\t\\n\\r]]");

    private CommentRules() {
    }

    /**
     * The body as stored: control characters other than tab and line breaks
     * dropped (PostgreSQL refuses a NUL in text), then trimmed of (Unicode)
     * whitespace at both ends; null becomes "". Format characters such as RTL
     * marks and zero-width spaces stay: the renderer shows them as text.
     */
    public static String normalizeBody(String raw) {
        return raw == null ? "" : CONTROL.matcher(raw).replaceAll("").strip();
    }

    /** How many {@code http://} and {@code https://} occur in the body, in any case. */
    public static int countHttpLinks(String body) {
        Matcher m = HTTP_LINK.matcher(body);
        int links = 0;
        while (m.find()) {
            links++;
        }
        return links;
    }

    /**
     * Why a normalised body may not be stored, or null when it may: it must be
     * non-empty and at most {@link #MAX_BODY} characters, counted as code
     * points so that an emoji is one character.
     */
    public static CommentRefusal bodyRefusal(String normalized) {
        if (normalized.isEmpty()) {
            return CommentRefusal.EMPTY_BODY;
        }
        if (normalized.codePointCount(0, normalized.length()) > MAX_BODY) {
            return CommentRefusal.BODY_TOO_LONG;
        }
        return null;
    }

    /**
     * A comment its reader deleted while it was HIDDEN (ruling R5): kept as a
     * HIDDEN row with its body erased, so the owner's distrust survives. It is
     * never published again and shows as "deleted by its author".
     */
    public static boolean isTombstone(ReaderComment c) {
        return c.getStatus() == ReaderComment.Status.HIDDEN && (c.getBody() == null || c.getBody().isEmpty());
    }

    /**
     * The status of a new top-level comment: an owner's is published; a
     * trusted reader's is published when it carries at most
     * {@link #MAX_LINKS_BEFORE_HOLD} links; anything else is held.
     */
    public static ReaderComment.Status statusFor(boolean owner, boolean trusted, String body) {
        if (owner || (trusted && countHttpLinks(body) <= MAX_LINKS_BEFORE_HOLD)) {
            return ReaderComment.Status.PUBLISHED;
        }
        return ReaderComment.Status.PENDING;
    }
}

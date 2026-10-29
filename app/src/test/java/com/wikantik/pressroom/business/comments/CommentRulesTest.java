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
package com.wikantik.pressroom.business.comments;

import com.wikantik.pressroom.pojos.ReaderComment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pure posting rules: status, links, the body, and the outcome's shape. */
class CommentRulesTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "true|false|plain|PUBLISHED", "false|true|plain|PUBLISHED", "false|false|plain|PENDING",
        "false|true|http://a https://b|PUBLISHED", "false|true|http://a https://b HTTP://c|PENDING",
        "true|false|http://a http://b http://c|PUBLISHED"})
    void statusFor(boolean owner, boolean trusted, String body, ReaderComment.Status expected) {
        assertEquals(expected, CommentRules.statusFor(owner, trusted, body));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "plain text|0", "see http://a.example|1", "HTTPS://A and hTtP://b|2",
        "http://a https://b HTTP://c|3", "www.example.com and ftp://x|0", "http:/not-a-link|0"})
    void countHttpLinks(String body, int expected) {
        assertEquals(expected, CommentRules.countHttpLinks(body));
    }

    @Test
    void bodyLimits() {
        assertEquals("", CommentRules.normalizeBody(null));
        assertEquals("", CommentRules.normalizeBody("  "));
        assertEquals("", CommentRules.normalizeBody(" \t\r\n　 "), "Unicode whitespace trims too");
        assertEquals("a  b", CommentRules.normalizeBody("\n a  b \n"), "only the ends are trimmed");

        assertSame(CommentRefusal.EMPTY_BODY, CommentRules.bodyRefusal(""));
        assertNull(CommentRules.bodyRefusal("x".repeat(CommentRules.MAX_BODY)));
        assertSame(CommentRefusal.BODY_TOO_LONG, CommentRules.bodyRefusal("x".repeat(CommentRules.MAX_BODY + 1)));
        // Characters, not UTF-16 units: 4000 emoji are 4000 characters.
        assertNull(CommentRules.bodyRefusal("😀".repeat(CommentRules.MAX_BODY)));
        assertSame(CommentRefusal.BODY_TOO_LONG,
                CommentRules.bodyRefusal("😀".repeat(CommentRules.MAX_BODY) + "x"));
    }

    /**
     * PostgreSQL refuses a NUL in a text column, so a body carrying one was a
     * 500. Control characters other than tab and line breaks go; format
     * characters (RTL marks, zero-width spaces) stay for the renderer to show.
     */
    @Test
    void controlCharactersAreDroppedFormatCharactersKept() {
        assertEquals("ab", CommentRules.normalizeBody("a\u0000b"));
        assertEquals("", CommentRules.normalizeBody("\u0000 \u0007"));
        assertSame(CommentRefusal.EMPTY_BODY, CommentRules.bodyRefusal(CommentRules.normalizeBody("\u0000 \u0007")));
        assertEquals("ab", CommentRules.normalizeBody("a\u007F\u0085\u009Fb"), "DEL and C1 controls go too");
        assertEquals("a\tb\nc\r\nd", CommentRules.normalizeBody("a\tb\nc\r\nd"), "tab and line breaks stay");
        assertEquals("a\u200Bb\u202Ec\u200Fd", CommentRules.normalizeBody("a\u200Bb\u202Ec\u200Fd"),
                "format characters stay");
    }

    @Test
    void anOutcomeIsAStoredCommentOrARefusalNeverBothOrNeither() {
        ReaderComment c = new ReaderComment();
        CommentOutcome stored = CommentOutcome.stored(c);
        assertTrue(stored.ok());
        assertSame(c, stored.comment());
        assertNull(stored.refusal());

        CommentOutcome refused = CommentOutcome.refused(CommentRefusal.CLOSED);
        assertFalse(refused.ok());
        assertNull(refused.comment());
        assertSame(CommentRefusal.CLOSED, refused.refusal());

        assertThrows(IllegalArgumentException.class, () -> new CommentOutcome(null, null));
        assertThrows(IllegalArgumentException.class, () -> new CommentOutcome(c, CommentRefusal.CLOSED));
    }

    /** Ruling R5's tombstone: HIDDEN with its body erased. One rule for the service and every page. */
    @Test
    void aTombstoneIsAHiddenCommentWithNoBody() {
        assertTrue(CommentRules.isTombstone(comment(ReaderComment.Status.HIDDEN, "")));
        assertTrue(CommentRules.isTombstone(comment(ReaderComment.Status.HIDDEN, null)));
        assertFalse(CommentRules.isTombstone(comment(ReaderComment.Status.HIDDEN, "text")));
        assertFalse(CommentRules.isTombstone(comment(ReaderComment.Status.PUBLISHED, "")));
        assertFalse(CommentRules.isTombstone(comment(ReaderComment.Status.PENDING, "")));
    }

    private static ReaderComment comment(ReaderComment.Status status, String body) {
        ReaderComment c = new ReaderComment();
        c.setStatus(status);
        c.setBody(body);
        return c;
    }
}

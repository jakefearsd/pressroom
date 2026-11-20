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

package com.wikantik.pressroom.util;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test utilities.
 *
 * <p>Everything in {@link Utilities} runs over user-supplied text -- entry
 * bodies, comments, tags, referer URLs -- so the interesting cases are the
 * hostile ones: nulls, empty strings, unterminated markup and characters
 * outside ASCII. Each test below states what breaks in Pressroom if the
 * behaviour changes, because these are static helpers with dozens of callers
 * and no other safety net.
 */
public class UtilitiesTest  {

    @Test
    public void testExtractHTML() {
        String test = "<a>keep me</a>";
        String expect = "<a></a>";
        String result = Utilities.extractHTML(test);
        assertEquals(expect, result);
    }

    @Test
    public void testRemoveHTML() {
        String test = "<br><br><p>a <b>bold</b> sentence with a <a href=\"http://example.com\">link</a></p>";
        String expect = "a bold sentence with a link";
        String result = Utilities.removeHTML(test, false);
        assertEquals(expect, result);
    }

    @Test
    public void testTruncateNicely1() {
        String test = "blah blah blah blah blah";
        String expect = "blah blah blah";
        String result = Utilities.truncateNicely(test, 11, 15, "");
        assertEquals(expect, result);
    }

    @Test
    public void testTruncateNicely2() {
        String test = "<p><b>blah1 blah2</b> <i>blah3 blah4 blah5</i></p>";
        String expect = "<p><b>blah1 blah2</b> <i>blah3</i></p>";
        String result = Utilities.truncateNicely(test, 15, 20, "");
        assertEquals(expect, result);
    }

    // ------------------------------------------------------------------ HTML

    @Nested
    class HtmlEscaping {

        @Test
        public void unescapeReversesTheCommonEntities() {
            assertEquals("<b>&\"'", Utilities.unescapeHTML("&lt;b&gt;&amp;&quot;&#39;"));
        }
    }

    @Nested
    class RemoveHtml {

        @Test
        public void nullBecomesEmptyStringRatherThanNull() {
            // Templates concatenate the result directly; returning null here
            // would print "null" in a rendered page.
            assertEquals("", Utilities.removeHTML(null, true));
            assertEquals("", Utilities.removeHTML(null));
        }

        @Test
        public void textWithoutTagsIsReturnedUntouchedIncludingWhitespace() {
            // The no-tag fast path returns the input verbatim -- note it does
            // NOT trim, unlike the path that strips tags.
            assertEquals("  plain  ", Utilities.removeHTML("  plain  ", true));
        }

        @Test
        public void unterminatedTagAtTheEndIsKeptVerbatim() {
            // A '<' with no closing '>' cannot be a tag, so the text is kept
            // as written: no tag was removed, so no space stands in for one.
            assertEquals("a<b", Utilities.removeHTML("a<b", true));
            assertEquals("I <3 tea", Utilities.removeHTML("I <3 tea", true));
            assertEquals("x  a<b", Utilities.removeHTML("<b>x</b> a<b", true));
        }

        /**
         * Weblog.setTagline cleans every value the database mapping loads, so
         * a second pass that changed anything rewrote the stored tagline on
         * the next save: one more space before a stray '<' per save.
         */
        @Test
        public void cleaningTwiceChangesNothingTheFirstPassDidNot() {
            for (String input : new String[] {"a<b", "Fish < Chips", "<i>x</i> y<z", "a<b>c</b>d", "<<b>"}) {
                String once = Utilities.removeHTML(input, true);
                assertEquals(once, Utilities.removeHTML(once, true), input);
            }
        }

        @Test
        public void tagsAreReplacedByASpaceOnlyWhenNotAtTheStart() {
            // The "insert a space" rule is skipped for a tag at position 0,
            // which is what keeps excerpts from starting with a blank.
            assertEquals("a c", Utilities.removeHTML("<b>a</b>c", true));
            assertEquals("ac", Utilities.removeHTML("<b>a</b>c", false));
        }
    }

    @Nested
    class ExtractHtml {

        @Test
        public void nullBecomesEmptyString() {
            assertEquals("", Utilities.extractHTML(null));
        }

        @Test
        public void textWithoutTagsIsReturnedUnchanged() {
            assertEquals("plain", Utilities.extractHTML("plain"));
        }

        @Test
        public void unterminatedTagIsDropped() {
            // Only complete "<...>" runs are kept; a dangling '<' would
            // otherwise be re-emitted into the truncated excerpt and corrupt
            // the surrounding markup.
            assertEquals("", Utilities.extractHTML("a<b href"));
        }

        @Test
        public void aStrayAngleBracketInsideATagIsNotRescanned() {
            // "<a<>" is one malformed tag, not two: scanning must continue
            // after the '>' it consumed. Restarting earlier would emit the
            // fragment twice and double the markup in an excerpt.
            assertEquals("<a<>", Utilities.extractHTML("<a<>"));
        }

        @Test
        public void keepsEveryTagAndDiscardsEveryCharacterBetweenThem() {
            assertEquals("<p><b></b></p>", Utilities.extractHTML("<p>text<b>bold</b>more</p>"));
        }
    }

    @Nested
    class AddNofollow {

        @Test
        public void addsRelNofollowToALinkThatLacksIt() {
            // This is the whole point of the method: it is exposed to themes as
            // $utils.addNofollow so any reader-supplied HTML a template chooses
            // to run it over cannot pass PageRank through an unmarked link.
            assertEquals("<p>x <a href=\"http://e.com\" rel=\"nofollow\">link</a></p>",
                    Utilities.addNofollow("<p>x <a href=\"http://e.com\">link</a></p>"));
        }

        @Test
        public void leavesALinkThatAlreadyHasRelNofollowAlone() {
            // Adding a second rel attribute produces invalid HTML and browsers
            // then honour only the first one.
            String alreadyTagged = "<p>x <a href=\"http://e.com\" rel=\"nofollow\">link</a></p>";
            assertEquals(alreadyTagged, Utilities.addNofollow(alreadyTagged));
        }

        @Test
        public void handlesSeveralLinksInOneBody() {
            assertEquals("<a href=\"http://a\" rel=\"nofollow\">a</a> and <a href=\"http://b\" rel=\"nofollow\">b</a>",
                    Utilities.addNofollow("<a href=\"http://a\">a</a> and <a href=\"http://b\">b</a>"));
        }

        @Test
        public void isCaseInsensitiveAboutTheAnchorTag() {
            assertEquals("<A href=\"http://e.com\" rel=\"nofollow\">link</a>",
                    Utilities.addNofollow("<A href=\"http://e.com\">link</a>"));
        }

        @Test
        public void nullAndEmptyPassStraightThrough() {
            assertNull(Utilities.addNofollow(null));
            assertEquals("", Utilities.addNofollow(""));
        }

        @Test
        public void textWithoutLinksIsUnchanged() {
            assertEquals("no links here", Utilities.addNofollow("no links here"));
        }
    }

    @Nested
    class TransformToHtmlSubset {

        @Test
        public void nullIsPassedThrough() {
            assertNull(Utilities.transformToHTMLSubset(null));
        }

        @Test
        public void unescapesTheWhitelistedTagsAndNothingElse() {
            // This runs on comments after escapeHTML, so only the tags on the
            // whitelist may come back to life. "&lt;script&gt;" must stay
            // escaped -- that is the security boundary of HTMLSubsetPlugin.
            assertEquals("<b>bold</b> <i>it</i> <p>para</p>",
                    Utilities.transformToHTMLSubset("&lt;b&gt;bold&lt;/b&gt; &lt;i&gt;it&lt;/i&gt; &lt;p&gt;para&lt;/p&gt;"));
            assertEquals("&lt;script&gt;alert(1)&lt;/script&gt;",
                    Utilities.transformToHTMLSubset("&lt;script&gt;alert(1)&lt;/script&gt;"));
        }

        @Test
        public void normalisesEveryFormOfBreakTag() {
            assertEquals("<br /><br /><br />",
                    Utilities.transformToHTMLSubset("&lt;br&gt;&lt;br/&gt;&lt;BR /&gt;"));
        }

        @Test
        public void restoresAnchorsWithTheirHrefAndQuotes() {
            assertEquals("<a href=\"http://e.com\">x</a>",
                    Utilities.transformToHTMLSubset("&lt;a href=&quot;http://e.com&quot;&gt;x&lt;/a&gt;"));
        }

        @Test
        public void unwindsDoubleEscapedBracketsAndNumericEntities() {
            // A comment that was escaped twice would otherwise show the reader
            // literal "&amp;lt;" text.
            assertEquals("&lt;x&gt; &#233;", Utilities.transformToHTMLSubset("&amp;lt;x&amp;gt; &amp;#233;"));
        }

        @Test
        public void handlesListsAndBlockquotes() {
            assertEquals("<blockquote><ul><li>a</li></ul></blockquote>",
                    Utilities.transformToHTMLSubset(
                            "&lt;blockquote&gt;&lt;ul&gt;&lt;li&gt;a&lt;/li&gt;&lt;/ul&gt;&lt;/blockquote&gt;"));
        }
    }

    // -------------------------------------------------------------- truncate

    @Nested
    class Truncation {

        @Test
        public void truncateLeavesShortTextAloneAndDoesNotAppendTheSuffix() {
            // Off-by-one guard: a string exactly as long as 'upper' is not
            // truncated, so no ellipsis may appear.
            assertEquals("0123456789", Utilities.truncateText("0123456789", 5, 10, "..."));
            assertEquals("0123456789", Utilities.truncateNicely("0123456789", 5, 10, "..."));
        }

        @Test
        public void aSpaceExactlyAtTheLowerLimitIsStillAGoodPlaceToCut() {
            // Boundary between the two cutting strategies: the space sits at
            // index 5 with lower == 5, so the word-boundary branch applies and
            // the excerpt ends at the word, not mid-word at the upper limit.
            assertEquals("abcde...", Utilities.truncateText("abcde fghijklmno", 5, 10, "..."));
            assertEquals("abcde...", Utilities.truncateNicely("abcde fghijklmno", 5, 10, "..."));
        }

        @Test
        public void truncateTextKeepsTheOriginalStringWhenItFits() {
            // truncateText differs from truncate here: when no truncation is
            // needed it returns the *original* string, markup included.
            assertEquals("<p>short</p>", Utilities.truncateText("<p>short</p>", 5, 100, "..."));
            assertEquals("blah blah...", Utilities.truncateText("<p>blah blah blah blah</p>", 5, 10, "..."));
        }

        @Test
        public void truncateNicelyClosesTheTagsItCutsThrough() {
            // The point of "nicely": the excerpt must not leave an <i> open or
            // the rest of the page inherits the styling.
            assertEquals("<p><b>blah1 blah2</b> <i>blah3...</i></p>",
                    Utilities.truncateNicely("<p><b>blah1 blah2</b> <i>blah3 blah4 blah5</i></p>", 15, 20, "..."));
        }

        @Test
        public void truncateNicelyLeavesShortTextAlone() {
            assertEquals("short", Utilities.truncateNicely("short", 5, 10, "..."));
        }

        @Test
        public void truncateNicelyCountsCharactersNotBytesForAccentedText() {
            // "café naïve résumé stuff" is 23 chars but 26 UTF-8 bytes; cutting
            // on bytes would split a character and produce mojibake.
            assertEquals("café naïve...", Utilities.truncateNicely("café naïve résumé stuff", 5, 12, "..."));
        }
    }

    // --------------------------------------------------------------- strings

    @Nested
    class StringHelpers {

        @Test
        public void autoformatTurnsNewlinesIntoBreaks() {
            assertEquals("a<br />b", Utilities.autoformat("a\nb"));
        }

        @Test
        public void replaceNonAlphanumericKeepsLettersAndDigitsInAnyScript() {
            // Character.isLetterOrDigit is unicode-aware, so accented and CJK
            // letters survive; only punctuation and spaces are substituted.
            assertEquals("a_b_c_", Utilities.replaceNonAlphanumeric("a b-c!"));
            assertEquals("café_naïve", Utilities.replaceNonAlphanumeric("café naïve"));
            assertEquals("a-b", Utilities.replaceNonAlphanumeric("a b", '-'));
            assertEquals("", Utilities.replaceNonAlphanumeric(""));
        }

        @Test
        public void stringArrayToStringJoinsWithTheDelimiter() {
            assertEquals("a,b,c", Utilities.stringArrayToString(new String[]{"a", "b", "c"}, ","));
            assertEquals("a", Utilities.stringArrayToString(new String[]{"a"}, ","));
            assertEquals("", Utilities.stringArrayToString(new String[0], ","));
        }

        @Test
        public void stringArrayToStringSwallowsLeadingEmptyElements() {
            // Known quirk: the join decides "am I first?" by asking whether the
            // buffer is still empty, so a leading "" produces no delimiter.
            // Harmless for the callers that exist (cache keys built from tags,
            // which are never empty) but it means the join is not reversible.
            assertEquals("b", Utilities.stringArrayToString(new String[]{"", "b"}, ","));
            assertEquals("b", Utilities.stringListToString(Arrays.asList("", "b"), ","));
        }

        @Test
        public void stringListToStringJoinsWithTheDelimiter() {
            assertEquals("a,b", Utilities.stringListToString(Arrays.asList("a", "b"), ","));
            assertEquals("", Utilities.stringListToString(List.of(), ","));
        }

        @Test
        public void stringToStringArrayDropsEmptyTokens() {
            assertArrayEquals(new String[]{"a", "b"}, Utilities.stringToStringArray("a,,b", ","));
        }

        @Test
        public void stringToStringListRoundTripsAPermissionActionList() {
            // PressroomPermission stores its actions as a comma separated column
            // and reads them back through this pair.
            List<String> actions = Utilities.stringToStringList("edit_draft,post,admin", ",");
            assertEquals(List.of("edit_draft", "post", "admin"), actions);
            assertEquals("edit_draft,post,admin", Utilities.stringListToString(actions, ","));
        }

        @Test
        public void stringToStringListRejectsNullRatherThanReturningAnEmptyList() {
            // Documented sharp edge: StringUtils.split(null) hands back null and
            // Arrays.asList then throws. Callers must not pass null.
            assertThrows(NullPointerException.class, () -> Utilities.stringToStringList(null, ","));
        }

    }

    // ------------------------------------------------------------------ tags

    @Nested
    class Tags {

        @Test
        public void stripInvalidTagCharactersDropsQuotesCommasAndSpaces() {
            // Commas and spaces are the tag separators and quotes break the
            // generated markup, so a tag may contain none of them.
            assertEquals("abc", Utilities.stripInvalidTagCharacters("a\"b,c"));
            assertEquals("ab", Utilities.stripInvalidTagCharacters("a b"));
        }

        @Test
        public void stripInvalidTagCharactersKeepsThePrintableAsciiRange() {
            // The filter keeps characters 33 ('!') through 126 ('~'); both ends
            // are inclusive, and narrowing the range would silently rewrite
            // tags that contain punctuation such as "c!" or "a~b".
            assertEquals("a!b~c", Utilities.stripInvalidTagCharacters("a!b~c"));
        }

        @Test
        public void stripInvalidTagCharactersKeepsNonAsciiLetters() {
            // Tags are user-facing text: dropping accented or CJK characters
            // would silently rename a reader's tag.
            assertEquals("café中", Utilities.stripInvalidTagCharacters("café中"));
        }

        @Test
        public void stripInvalidTagCharactersRejectsNull() {
            assertThrows(NullPointerException.class, () -> Utilities.stripInvalidTagCharacters(null));
        }

        @Test
        public void normalizeTagLowercasesUsingTheSuppliedLocale() {
            // The Turkish dotless i is the classic locale trap: "TITLE" becomes
            // "tıtle" under tr, so tags must be normalised with an explicit
            // locale or the same tag stops matching itself.
            assertEquals("hello", Utilities.normalizeTag("HeLLo", null));
            assertEquals("title", Utilities.normalizeTag("TITLE", Locale.ENGLISH));
            assertEquals("tıtle", Utilities.normalizeTag("TITLE", Locale.of("tr")));
        }

        @Test
        public void splitStringAsTagsAcceptsEverySeparatorTheEditorEmits() {
            assertEquals(List.of("a", "b", "c", "d", "e"), Utilities.splitStringAsTags("a b\tc\nd,e"));
        }

        @Test
        public void splitStringAsTagsReturnsEmptyListForNullOrBlank() {
            // Saving an entry with an empty tag field must not throw.
            assertTrue(Utilities.splitStringAsTags(null).isEmpty());
            assertTrue(Utilities.splitStringAsTags("   ").isEmpty());
        }
    }

    // -------------------------------------------------------------- encoding

    @Nested
    class Encoding {

        @Test
        public void base64EncodesEveryPaddingLength() {
            // The hand-rolled encoder has a separate branch per remainder;
            // one input of each length mod 3 covers all three.
            assertEquals("YQ==", Utilities.toBase64("a".getBytes(StandardCharsets.UTF_8)));
            assertEquals("YWI=", Utilities.toBase64("ab".getBytes(StandardCharsets.UTF_8)));
            assertEquals("YWJj", Utilities.toBase64("abc".getBytes(StandardCharsets.UTF_8)));
            assertEquals("", Utilities.toBase64(new byte[0]));
        }

        @Test
        public void base64TreatsBytesAsUnsigned() {
            // Bytes above 0x7f are negative in Java; without the & 0xFF masks
            // the cache keys built from this would collide or throw.
            assertEquals("//79", Utilities.toBase64(new byte[]{(byte) 0xff, (byte) 0xfe, (byte) 0xfd}));
        }

    }

    // ----------------------------------------------------------------- files

    // ----------------------------------------------------------------- email

    @Test
    public void contentTypeIsDerivedFromTheFileExtension() {
        // PNG is missing from the JDK's default mime table, which is why
        // Utilities registers it by hand -- if that registration is dropped,
        // uploaded PNGs are served as application/octet-stream and download
        // instead of rendering.
        assertEquals("image/png", Utilities.getContentTypeFromFileName("photo.PNG"));
        assertEquals("image/png", Utilities.getContentTypeFromFileName("photo.png"));
        assertEquals("text/plain", Utilities.getContentTypeFromFileName("notes.txt"));
        assertEquals("application/octet-stream", Utilities.getContentTypeFromFileName("mystery.zzz"));
    }

    @Test
    public void registerPngMimeTypeSwallowsAFailureFromTheMap() {
        // The real JDK MimetypesFileTypeMap never throws for this literal,
        // well-formed string -- registerPngMimeType exists as its own method
        // so a test can drive the failure path with a throwing subclass
        // instead.
        jakarta.activation.MimetypesFileTypeMap throwing = new jakarta.activation.MimetypesFileTypeMap() {
            @Override
            public synchronized void addMimeTypes(String mimeTypes) {
                throw new IllegalStateException("registry unavailable");
            }
        };

        assertDoesNotThrow(() -> Utilities.registerPngMimeType(throwing));
    }
}

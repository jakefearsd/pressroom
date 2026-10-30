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
import org.junit.jupiter.api.io.TempDir;

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
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        public void escapesTheFiveCharactersThatCanBreakOutOfMarkup() {
            // These four substitutions are the whole XSS defence for anything
            // rendered through escapeHTML; losing any one of them lets markup
            // through into a page.
            assertEquals("&amp;", Utilities.escapeHTML("&"));
            assertEquals("&quot;", Utilities.escapeHTML("\""));
            assertEquals("&lt;", Utilities.escapeHTML("<"));
            assertEquals("&gt;", Utilities.escapeHTML(">"));
            assertEquals("&lt;script&gt;alert(&quot;x&quot;)&lt;/script&gt;",
                    Utilities.escapeHTML("<script>alert(\"x\")</script>"));
        }

        @Test
        public void escapesAmpersandsFirstSoNothingIsDoubleEscaped() {
            // "&lt;" must come out as "&amp;lt;" -- if the ampersand were
            // escaped last, the '<' produced by an earlier rule would be
            // escaped again and the text would drift on every round trip.
            assertEquals("&amp;lt;", Utilities.escapeHTML("&lt;"));
        }

        @Test
        public void leavesAmpersandsAloneWhenAskedTo() {
            assertEquals("a & b &lt;c&gt;", Utilities.escapeHTML("a & b <c>", false));
        }

        @Test
        public void nbspIsOnlyCollapsedWhenAmpersandsAreLeftAlone() {
            // Documented quirk, not an accident: escapeHTML escapes '&' before
            // it looks for "&nbsp;", so with escaping on the entity survives as
            // "&amp;nbsp;". Callers that want the space must pass false.
            assertEquals("a&amp;nbsp;b", Utilities.escapeHTML("a&nbsp;b"));
            assertEquals("a b", Utilities.escapeHTML("a&nbsp;b", false));
        }

        @Test
        public void nullSurvivesEscaping() {
            // Callers pass straight from getters that may be null (e.g. an
            // entry summary); escapeHTML must not be the thing that throws.
            assertNull(Utilities.escapeHTML(null));
        }

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
            // A '<' with no closing '>' cannot be a tag, so the remainder is
            // appended as-is rather than silently swallowing the rest of the
            // text.
            assertEquals("a <b", Utilities.removeHTML("a<b", true));
        }

        @Test
        public void tagsAreReplacedByASpaceOnlyWhenNotAtTheStart() {
            // The "insert a space" rule is skipped for a tag at position 0,
            // which is what keeps excerpts from starting with a blank.
            assertEquals("a c", Utilities.removeHTML("<b>a</b>c", true));
            assertEquals("ac", Utilities.removeHTML("<b>a</b>c", false));
        }

        @Test
        public void removeAndEscapeStripsTagsThenEscapesWhatIsLeft() {
            assertEquals("a &amp; b", Utilities.removeAndEscapeHTML("<p>a & b</p>"));
            assertEquals("", Utilities.removeAndEscapeHTML(null));
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
        public void truncateStripsMarkupAndCutsAtAWordBoundary() {
            assertEquals("blah blah blah...", Utilities.truncate("blah blah blah blah blah", 11, 15, "..."));
        }

        @Test
        public void truncateLeavesShortTextAloneAndDoesNotAppendTheSuffix() {
            // Off-by-one guard: a string exactly as long as 'upper' is not
            // truncated, so no ellipsis may appear.
            assertEquals("0123456789", Utilities.truncate("0123456789", 5, 10, "..."));
            assertEquals("0123456789", Utilities.truncateText("0123456789", 5, 10, "..."));
            assertEquals("0123456789", Utilities.truncateNicely("0123456789", 5, 10, "..."));
        }

        @Test
        public void aSpaceExactlyAtTheLowerLimitIsStillAGoodPlaceToCut() {
            // Boundary between the two cutting strategies: the space sits at
            // index 5 with lower == 5, so the word-boundary branch applies and
            // the excerpt ends at the word, not mid-word at the upper limit.
            assertEquals("abcde...", Utilities.truncate("abcde fghijklmno", 5, 10, "..."));
            assertEquals("abcde...", Utilities.truncateText("abcde fghijklmno", 5, 10, "..."));
            assertEquals("abcde...", Utilities.truncateNicely("abcde fghijklmno", 5, 10, "..."));
        }

        @Test
        public void truncateCutsAtUpperWhenThereIsNoSpaceAfterLower() {
            // Long unbroken text (a URL, or a CJK sentence with no spaces) must
            // still be cut, otherwise excerpts grow without bound.
            assertEquals("aaaaaaaaaa...", Utilities.truncate("aaaaaaaaaaaaaaaaaaaa", 5, 10, "..."));
        }

        @Test
        public void truncateRaisesUpperToLowerWhenCallerSuppliesThemBackwards() {
            // upper < lower is a caller bug; the method repairs it rather than
            // throwing a StringIndexOutOfBoundsException from substring().
            // With upper raised to 10 the last space sits at 9, below 'lower',
            // so the cut happens at the hard limit and keeps that space.
            assertEquals("aaaa bbbb ...", Utilities.truncate("aaaa bbbb cccc dddd", 10, 5, "..."));
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
        public void stripJsessionIdRemovesTheSegmentAndKeepsTheQueryString() {
            // Referer URLs arrive with the container's session id glued on;
            // keeping it would fragment referer statistics per session.
            assertEquals("http://e.com/x?a=1",
                    Utilities.stripJsessionId("http://e.com/x;jsessionid=ABC?a=1"));
            assertEquals("http://e.com/x",
                    Utilities.stripJsessionId("http://e.com/x;jsessionid=ABC"));
            assertEquals("http://e.com/x", Utilities.stripJsessionId("http://e.com/x"));
        }

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
        public void removeNonAlphanumericKeepsPeriodsBecausePageLinksNeedThem() {
            assertEquals("a.bcd", Utilities.removeNonAlphanumeric("a.b c-d!"));
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

        @Test
        public void intArrayRoundTrip() {
            assertEquals("1,2,3", Utilities.intArrayToString(new int[]{1, 2, 3}));
            assertArrayEquals(new int[]{1, 2, 3}, Utilities.stringToIntArray("1,2,3", ","));
            assertEquals("", Utilities.intArrayToString(new int[0]));
            assertEquals("-1,0", Utilities.intArrayToString(new int[]{-1, 0}));
        }

        @Test
        public void stringToIntArrayThrowsOnNonNumericInput() {
            assertThrows(NumberFormatException.class, () -> Utilities.stringToIntArray("1,x", ","));
        }

        @Test
        public void stringToIntReturnsZeroForAnythingItCannotParse() {
            // Used on request parameters, where garbage is normal; a zero is
            // the documented fallback, an exception would be a 500 page.
            assertEquals(42, Utilities.stringToInt("42"));
            assertEquals(-42, Utilities.stringToInt("-42"));
            assertEquals(0, Utilities.stringToInt("abc"));
            assertEquals(0, Utilities.stringToInt(""));
            assertEquals(0, Utilities.stringToInt(null));
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

        @Test
        public void encodeStringAndDecodeStringRoundTrip() throws IOException {
            assertEquals("aGVsbG8gd29ybGQ=", Utilities.encodeString("hello world"));
            assertEquals("hello world", Utilities.decodeString("aGVsbG8gd29ybGQ="));
            assertEquals("café", Utilities.decodeString(Utilities.encodeString("café")));
        }

        @Test
        public void encodePasswordProducesAStableLowercaseHexDigest() {
            // The digest is stored in the database, so the exact format is a
            // compatibility contract: any change locks every user out.
            assertEquals("5f4dcc3b5aa765d61d8327deb882cf99", Utilities.encodePassword("password", "MD5"));
            assertEquals(32, Utilities.encodePassword("", "MD5").length());
        }

        @Test
        public void encodePasswordPadsDigestBytesBelowSixteenToTwoHexDigits() {
            // "hi" hashes to a digest containing a 0x0b byte; without the
            // zero-padding branch the hex string would be 31 chars long and
            // would not match what was stored previously.
            assertEquals(32, Utilities.encodePassword("hi", "MD5").length());
            assertEquals("49f68a5c8493ec2c0bf489821c21fc3b", Utilities.encodePassword("hi", "MD5"));
        }

        @Test
        public void encodePasswordDoesNotPadAByteThatIsExactlySixteen() {
            // Boundary of the padding rule: MD5("entry") starts with the byte
            // 0x10, which is two hex digits already. Padding it would produce a
            // 33 character digest that matches no stored password.
            assertEquals("1043bfc77febe75fafec0c4309faccf1", Utilities.encodePassword("entry", "MD5"));
        }

        @Test
        public void encodePasswordFallsBackToPlaintextForAnUnknownAlgorithm() {
            // Misconfiguration returns the password unchanged rather than
            // throwing -- surprising, but callers depend on it, so pin it.
            assertEquals("password", Utilities.encodePassword("password", "NO-SUCH-ALGORITHM"));
        }

        @Test
        public void hexEncodeTurnsEmailIntoPercentEscapes() {
            assertEquals("%61%40%62%2e%63%6f%6d", Utilities.hexEncode("a@b.com"));
        }

        @Test
        public void hexEncodeAndEncodeEmailPassNullAndEmptyThrough() {
            assertNull(Utilities.hexEncode(null));
            assertEquals("", Utilities.hexEncode(""));
            assertNull(Utilities.encodeEmail(null));
        }

        @Test
        public void encodeEmailObfuscatesPlaintextAddresses() {
            assertEquals("a-AT-b-DOT-com", Utilities.encodeEmail("a@b.com"));
        }
    }

    // ----------------------------------------------------------------- files

    @Nested
    class Streams {

        @Test
        public void streamToStringReadsEveryLine() throws IOException {
            String text = "line one" + System.lineSeparator() + "line two" + System.lineSeparator();
            InputStream in = new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
            assertEquals(text, Utilities.streamToString(in));
        }

        @Test
        public void streamToStringReturnsEmptyStringForAnEmptyStream() {
            // The AJAX comment endpoint feeds a request body straight in; an
            // empty POST must produce "" rather than an exception.
            assertEquals("", assertDoesNotThrowIOException(new ByteArrayInputStream(new byte[0])));
        }

        private String assertDoesNotThrowIOException(InputStream in) {
            try {
                return Utilities.streamToString(in);
            } catch (IOException e) {
                throw new AssertionError("streamToString threw on an empty stream", e);
            }
        }

        @Test
        public void copyInputToOutputCopiesMoreThanOneBuffer() throws IOException {
            // The copy loop is chunked at 8kb, so anything smaller would never
            // exercise the second iteration.
            byte[] data = new byte[20000];
            for (int i = 0; i < data.length; i++) {
                data[i] = (byte) (i % 251);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Utilities.copyInputToOutput(new ByteArrayInputStream(data), out);
            assertArrayEquals(data, out.toByteArray());
        }

        @Test
        public void copyInputToOutputStopsAfterTheRequestedByteCount() throws IOException {
            // Uploads are copied with an explicit length; copying past it would
            // let one upload bleed into the next.
            byte[] data = "0123456789".getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Utilities.copyInputToOutput(new ByteArrayInputStream(data), out, 4);
            assertEquals("0123", out.toString(StandardCharsets.UTF_8));
        }

        @Test
        public void copyInputToOutputStopsAtEndOfStreamEvenIfMoreWasPromised() throws IOException {
            byte[] data = "abc".getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Utilities.copyInputToOutput(new ByteArrayInputStream(data), out, 1000);
            assertEquals("abc", out.toString(StandardCharsets.UTF_8));
        }

        @Test
        public void copyInputToOutputClosesBothStreamsWhenItFinishes() throws IOException {
            // Media uploads go through here. A stream left open holds a file
            // descriptor (and on Windows a lock on the file) until GC, so a
            // busy site runs out of handles.
            TrackingInput in = new TrackingInput("data".getBytes(StandardCharsets.UTF_8));
            TrackingOutput out = new TrackingOutput();
            Utilities.copyInputToOutput(in, out);
            assertTrue(in.closed, "input stream was left open");
            assertTrue(out.closed, "output stream was left open");

            TrackingInput in2 = new TrackingInput("data".getBytes(StandardCharsets.UTF_8));
            TrackingOutput out2 = new TrackingOutput();
            Utilities.copyInputToOutput(in2, out2, 4);
            assertTrue(in2.closed, "input stream was left open by the counted copy");
            assertTrue(out2.closed, "output stream was left open by the counted copy");
        }

        @Test
        public void aFailedReadClosesBothStreamsAndSaysWhichSideFailed() {
            // A half-finished upload must not leak the descriptors it opened.
            TrackingOutput out = new TrackingOutput();
            FailingInput failing = new FailingInput();

            IOException thrown = assertThrows(IOException.class,
                    () -> Utilities.copyInputToOutput(failing, out, 10));

            assertTrue(thrown.getMessage().contains("Reading input stream"),
                    "A failed copy must name the side that failed so the log points at the "
                            + "right thing. Message was: " + thrown.getMessage());
            assertTrue(failing.closed, "the input stream must be closed when the copy aborts");
            assertTrue(out.closed, "the output stream must be closed when the copy aborts");
        }

        @Test
        public void aFailedWriteClosesBothStreamsAndSaysWhichSideFailed() {
            // More than one 8kb buffer, so the write actually reaches the
            // underlying stream during the copy rather than at close time.
            TrackingInput in = new TrackingInput(new byte[20000]);
            FailingOutput out = new FailingOutput();

            IOException thrown = assertThrows(IOException.class,
                    () -> Utilities.copyInputToOutput(in, out, 20000));

            assertTrue(thrown.getMessage().contains("Writing output stream"),
                    "Message was: " + thrown.getMessage());
            assertTrue(in.closed, "the input stream must be closed when the copy aborts");
            assertTrue(out.closed, "the output stream must be closed when the copy aborts");
        }

        @Test
        public void aWriteThatOnlyFailsWhenFlushedIsStillReported() {
            // A copy smaller than the 8kb buffer succeeds all the way to
            // close(), where the flush fails. Swallowing that would report a
            // successful upload of a file that was never written.
            IOException thrown = assertThrows(IOException.class,
                    () -> Utilities.copyInputToOutput(
                            new TrackingInput("data".getBytes(StandardCharsets.UTF_8)),
                            new FailingOutput(), 4));

            assertTrue(thrown.getMessage().contains("Closing file streams"),
                    "Message was: " + thrown.getMessage());
        }

        @Test
        public void aFailedReadWhoseCleanupCloseAlsoFailsStillReportsTheReadFailure() {
            // The read failure is what matters to the caller; a second,
            // unrelated failure while cleaning up must not replace it or go
            // unswallowed.
            IOException thrown = assertThrows(IOException.class,
                    () -> Utilities.copyInputToOutput(
                            new FailingInputThatWontClose(), new ByteArrayOutputStream(), 10));

            assertTrue(thrown.getMessage().contains("Reading input stream"),
                    "Message was: " + thrown.getMessage());
        }

        @Test
        public void aFailedWriteWhoseCleanupCloseAlsoFailsStillReportsTheWriteFailure() {
            IOException thrown = assertThrows(IOException.class,
                    () -> Utilities.copyInputToOutput(
                            new TrackingInput(new byte[20000]), new FailingOutputThatWontClose(), 20000));

            assertTrue(thrown.getMessage().contains("Writing output stream"),
                    "Message was: " + thrown.getMessage());
        }

        @Test
        public void aFailedFinalCloseOnTheTwoArgOverloadIsReported() {
            // copyInputToOutput(in, out) -- no byte count -- has its own final
            // close(), distinct from the byte-counted overload above.
            IOException thrown = assertThrows(IOException.class,
                    () -> Utilities.copyInputToOutput(
                            new FailingCloseInput("data".getBytes(StandardCharsets.UTF_8)),
                            new ByteArrayOutputStream()));

            assertTrue(thrown.getMessage().contains("Closing file streams"),
                    "Message was: " + thrown.getMessage());
        }

        @Test
        public void copyFileReproducesTheFileByteForByte(@TempDir Path tmp) throws IOException {
            Path from = tmp.resolve("from.txt");
            Files.writeString(from, "contents café", StandardCharsets.UTF_8);
            Path to = tmp.resolve("to.txt");

            Utilities.copyFile(from.toFile(), to.toFile());

            assertArrayEquals(Files.readAllBytes(from), Files.readAllBytes(to));
        }

        @Test
        public void copyFileReportsWhichSideFailedToOpen(@TempDir Path tmp) {
            File missing = tmp.resolve("does-not-exist").toFile();
            IOException thrown = assertThrows(IOException.class,
                    () -> Utilities.copyFile(missing, tmp.resolve("out").toFile()));
            assertTrue(thrown.getMessage().contains("input stream"),
                    "copyFile must say which stream it failed to open, otherwise a failed "
                            + "theme or upload copy is undiagnosable. Message was: " + thrown.getMessage());
        }

        @Test
        public void copyFileReportsAnUnwritableDestination(@TempDir Path tmp) throws IOException {
            // The destination here is an existing directory, which cannot be
            // opened for writing. The source stream has already been opened at
            // that point and must be released before the error propagates.
            Path source = tmp.resolve("source.txt");
            Files.writeString(source, "x");
            File directory = tmp.resolve("subdir").toFile();
            assertTrue(directory.mkdir());

            IOException thrown = assertThrows(IOException.class,
                    () -> Utilities.copyFile(source.toFile(), directory));

            assertTrue(thrown.getMessage().contains("output stream"),
                    "Message was: " + thrown.getMessage());
        }

        private static class TrackingInput extends ByteArrayInputStream {
            private boolean closed;

            TrackingInput(byte[] data) {
                super(data);
            }

            @Override
            public void close() throws IOException {
                closed = true;
                super.close();
            }
        }

        private static class TrackingOutput extends ByteArrayOutputStream {
            private boolean closed;

            @Override
            public void close() throws IOException {
                closed = true;
                super.close();
            }
        }

        /** Fails on every read, and remembers whether it was closed. */
        private static class FailingInput extends InputStream {
            private boolean closed;

            @Override
            public int read() throws IOException {
                throw new IOException("disk gone");
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                throw new IOException("disk gone");
            }

            @Override
            public void close() {
                closed = true;
            }
        }

        /** Fails on every write, and remembers whether it was closed. */
        private static class FailingOutput extends OutputStream {
            private boolean closed;

            @Override
            public void write(int b) throws IOException {
                throw new IOException("disk full");
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                throw new IOException("disk full");
            }

            @Override
            public void close() {
                closed = true;
            }
        }

        /** Fails on every read, AND fails to close -- for the read-failure cleanup catch. */
        private static class FailingInputThatWontClose extends InputStream {
            @Override
            public int read() throws IOException {
                throw new IOException("disk gone");
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                throw new IOException("disk gone");
            }

            @Override
            public void close() throws IOException {
                throw new IOException("stuck open");
            }
        }

        /** Fails on every write, AND fails to close -- for the write-failure cleanup catch. */
        private static class FailingOutputThatWontClose extends OutputStream {
            @Override
            public void write(int b) throws IOException {
                throw new IOException("disk full");
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                throw new IOException("disk full");
            }

            @Override
            public void close() throws IOException {
                throw new IOException("stuck open");
            }
        }

        /** Reads normally but always fails to close -- for the final-close catch. */
        private static class FailingCloseInput extends ByteArrayInputStream {
            FailingCloseInput(byte[] data) {
                super(data);
            }

            @Override
            public void close() throws IOException {
                throw new IOException("stuck open");
            }
        }
    }

    // ----------------------------------------------------------------- email

    @Nested
    class EmailValidation {

        @Test
        public void acceptsOrdinaryAddresses() {
            assertTrue(Utilities.isValidEmailAddress("a@b.com"));
            assertTrue(Utilities.isValidEmailAddress("first.last+tag@sub.example.co.uk"));
        }

        @Test
        public void rejectsAddressesWithoutBothANameAndADomain() {
            // A bare "albert" is a legal InternetAddress but never what a
            // commenter meant, so the extra name/domain check must stay.
            assertFalse(Utilities.isValidEmailAddress("albert"));
            assertFalse(Utilities.isValidEmailAddress("@b.com"));
            assertFalse(Utilities.isValidEmailAddress("a@"));
            assertFalse(Utilities.isValidEmailAddress("a@b@c.com"));
        }

        @Test
        public void rejectsNullEmptyAndMalformedInput() {
            assertFalse(Utilities.isValidEmailAddress(null));
            assertFalse(Utilities.isValidEmailAddress(""));
            assertFalse(Utilities.isValidEmailAddress("a b@c.com"));
        }
    }

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

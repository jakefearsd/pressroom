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

package org.apache.roller.weblogger.ui.rendering.model;

import jakarta.servlet.http.HttpServletRequest;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.pojos.GlobalPermission;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogPermission;
import org.apache.roller.weblogger.pojos.wrapper.WeblogWrapper;
import org.apache.roller.weblogger.ui.rendering.util.ParsedRequest;
import org.apache.roller.weblogger.ui.rendering.util.WeblogRequest;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.apache.roller.weblogger.business.UserManager;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;

/**
 * Unit tests for {@link UtilitiesModel}, the {@code $utils} object every
 * Velocity theme has access to.
 *
 * <p>These methods are the last line of defence before user-supplied text is
 * written into a page or a feed: {@code $utils.escapeXML} alone appears ~58
 * times across the bundled themes and {@code $utils.escapeHTML} another ~11.
 * A regression here does not throw — Velocity renders a failed reference as
 * empty text — so it shows up as silently mangled or, worse, silently
 * un-escaped output on every blog using the theme.
 *
 * <p>The tests below therefore pin the exact output for the cases that decide
 * correctness: null, empty, exactly at a truncation limit, one character over
 * it, embedded markup, embedded quotes and non-ASCII text.
 */
class UtilitiesModelTest {

    /** Weblog constructor args are handle/creator/name/desc/email/theme/locale/timeZone. */
    private static Weblog weblog(String locale, String timeZone) {
        return new Weblog("testblog", "testuser", "Test Blog", "a test blog",
                "blog@example.com", "journal", locale, timeZone);
    }

    private static UtilitiesModel modelFor(Weblog weblog) {
        WeblogRequest request = new WeblogRequest();
        request.setWeblog(weblog);
        return modelFor(request);
    }

    private static UtilitiesModel modelFor(ParsedRequest request) {
        return modelFor(request, null);
    }

    /** A model whose permission checks go to {@code weblogger}'s user manager. */
    private static UtilitiesModel modelFor(ParsedRequest request, Weblogger weblogger) {
        Map<String, Object> initData = new HashMap<>();
        initData.put("parsedRequest", request);
        if (weblogger != null) {
            initData.put(ModelLoader.WEBLOGGER, weblogger);
        }
        UtilitiesModel model = new UtilitiesModel();
        try {
            model.init(initData);
        } catch (WebloggerException ex) {
            throw new IllegalStateException("UtilitiesModel.init rejected valid init data", ex);
        }
        return model;
    }

    /** A UtilitiesModel wired to a plain en_US / UTC weblog. */
    private static UtilitiesModel model() {
        return modelFor(weblog("en_US", "UTC"));
    }

    /** A Date fixed against UTC, for the formatters that take an explicit zone. */
    private static Date utc(String isoInstant) {
        return Date.from(Instant.parse(isoInstant));
    }

    /**
     * A Date at noon local time. The DateUtil-based formatters use the JVM's
     * default time zone, so the expected calendar day is only stable if the
     * instant is built in that same zone.
     */
    private static Date localNoon(int year, int month, int day) {
        return Date.from(LocalDateTime.of(year, month, day, 12, 0)
                .atZone(ZoneId.systemDefault()).toInstant());
    }

    // ------------------------------------------------------------------ init

    @Test
    void modelIsRegisteredUnderTheNameThemesUse() {
        assertEquals("utils", new UtilitiesModel().getModelName(),
                "Themes reference this model as $utils. Renaming it silently blanks "
                        + "every $utils.* reference in every theme.");
    }

    @Test
    void initWithoutAParsedRequestFails() {
        UtilitiesModel model = new UtilitiesModel();
        WebloggerException thrown = assertThrows(WebloggerException.class,
                () -> model.init(new HashMap<>()),
                "init() must reject init data with no 'parsedRequest' rather than "
                        + "leaving the model half-built for the renderer to trip over.");
        assertTrue(thrown.getMessage().contains("parsedRequest"),
                "The failure should name the missing key so the cause is obvious in a "
                        + "render error log; was: " + thrown.getMessage());
    }

    @Test
    void initAcceptsARequestThatIsNotAWeblogRequest() throws Exception {
        // Not every rendered page belongs to a weblog. init() must not require a
        // WeblogRequest, and the parts of the model that need no weblog must
        // still work when it is absent.
        HttpServletRequest servletRequest = mock(HttpServletRequest.class);
        ParsedRequest plain = new ParsedRequest(mock(Weblogger.class), servletRequest) {
        };

        UtilitiesModel model = modelFor(plain);

        assertFalse(model.isUserAuthenticated(),
                "An anonymous request must not report an authenticated user.");
        assertEquals("&lt;b&gt;", model.escapeHTML("<b>"),
                "Escaping needs no weblog and must keep working without one.");
    }

    // -------------------------------------------------------------- escaping

    @Test
    void escapeHTMLEscapesTheCharactersThatCanCloseATag() {
        assertEquals("&lt;script&gt;alert(&quot;x&quot;)&lt;/script&gt;",
                model().escapeHTML("<script>alert(\"x\")</script>"),
                "$utils.escapeHTML must neutralise <, > and \" or a comment body can "
                        + "inject a script tag into every page that renders it.");
        assertEquals("&amp;lt;", model().escapeHTML("&lt;"),
                "The ampersand must be escaped too, otherwise text that already looks "
                        + "like an entity is decoded a second time by the browser.");
    }

    /**
     * Documents a sharp edge rather than blessing it: commons-text's HTML 4
     * escaper has no entity for the apostrophe, so {@code escapeHTML} leaves it
     * alone. Text placed inside a <em>single</em>-quoted attribute —
     * {@code <a title='$utils.escapeHTML($x)'>} — can therefore still break out
     * of the attribute. Themes must use double quotes with escapeHTML, or use
     * {@link UtilitiesModel#escapeXML} which does escape it (see next test).
     */
    @Test
    void escapeHTMLDoesNotEscapeTheApostrophe() {
        assertEquals("it's", model().escapeHTML("it's"),
                "escapeHtml4 has no &apos; entity. If this ever starts escaping, the "
                        + "warning above about single-quoted attributes can be deleted.");
    }

    @Test
    void escapeXMLEscapesTheApostropheAndLeavesNonAsciiAlone() {
        assertEquals("&apos;", model().escapeXML("'"),
                "XML 1.1 escaping covers the apostrophe, which is why feeds and "
                        + "single-quoted attributes should use $utils.escapeXML.");
        assertEquals("caf&amp;eacute;", model().escapeXML("caf&eacute;"),
                "escapeXML must escape the ampersand of text that already looks like "
                        + "an entity.");
        // Feeds are served as UTF-8, so accented characters must survive as
        // themselves rather than being turned into HTML entities that an XML
        // parser does not know.
        assertEquals("café", model().escapeXML("café"),
                "escapeXML must not convert non-ASCII characters to named entities — "
                        + "&eacute; is not defined in XML and would break the feed.");
    }

    @Test
    void escapeHTMLConvertsNonAsciiToNamedEntities() {
        // The difference from escapeXML above is deliberate and load-bearing.
        assertEquals("caf&eacute;", model().escapeHTML("café"),
                "escapeHTML uses the HTML4 entity table, so é becomes &eacute;.");
    }

    @Test
    void escapeJavaScriptNeutralisesAScriptCloseTag() {
        // Inside <script>var s = '$utils.escapeJavaScript($x)';</script> the
        // sequence </script> would end the script element even inside a string
        // literal. Escaping the slash is what prevents that.
        assertEquals("<\\/script>", model().escapeJavaScript("</script>"),
                "escapeJavaScript must escape the forward slash so a value cannot "
                        + "close the surrounding <script> element.");
        assertEquals("he said \\\"hi\\\"", model().escapeJavaScript("he said \"hi\""),
                "Double quotes must be escaped or the string literal ends early.");
        assertEquals("line1\\nline2", model().escapeJavaScript("line1\nline2"),
                "A raw newline is a syntax error inside a JavaScript string literal.");
    }

    @Test
    void escapeJsonProducesValidJsonAndNeutralisesAScriptCloseTag() {
        // #showSeoHead embeds user-controlled values inside the JSON-LD
        // <script type="application/ld+json"> block. escapeJavaScript is not
        // usable there: it escapes the apostrophe as \' which is invalid JSON.
        assertEquals("he said \\\"hi\\\"", model().escapeJson("he said \"hi\""),
                "Double quotes must be escaped or the JSON string literal ends early.");
        assertEquals("it's", model().escapeJson("it's"),
                "The apostrophe must NOT be escaped: \\' is invalid JSON and makes "
                        + "every parser reject the whole JSON-LD block.");
        assertEquals("<\\/script>", model().escapeJson("</script>"),
                "The forward slash must be escaped so a value containing </script> "
                        + "cannot terminate the surrounding inline script element.");
        assertEquals("line1\\nline2", model().escapeJson("line1\nline2"),
                "A raw newline is invalid inside a JSON string literal.");
        assertNull(model().escapeJson(null), "escapeJson(null) must return null");
    }

    @Test
    void unescapingIsTheInverseOfEscaping() {
        String raw = "<b>Tom & \"Jerry\"</b> café";
        assertEquals(raw, model().unescapeHTML(model().escapeHTML(raw)),
                "unescapeHTML must round-trip escapeHTML, or stored escaped content "
                        + "cannot be edited back into its original form.");
        assertEquals(raw, model().unescapeXML(model().escapeXML(raw)),
                "unescapeXML must round-trip escapeXML.");
        assertEquals(raw, model().unescapeJavaScript(model().escapeJavaScript(raw)),
                "unescapeJavaScript must round-trip escapeJavaScript.");
    }

    @Test
    void escapersPassNullAndEmptyStringsThroughUnchanged() {
        // Velocity hands us nulls whenever a reference is undefined, which is
        // routine. Throwing here would abort the whole template.
        UtilitiesModel model = model();
        assertNull(model.escapeHTML(null), "escapeHTML(null) must return null");
        assertNull(model.unescapeHTML(null), "unescapeHTML(null) must return null");
        assertNull(model.escapeXML(null), "escapeXML(null) must return null");
        assertNull(model.unescapeXML(null), "unescapeXML(null) must return null");
        assertNull(model.escapeJavaScript(null), "escapeJavaScript(null) must return null");
        assertNull(model.unescapeJavaScript(null), "unescapeJavaScript(null) must return null");

        assertEquals("", model.escapeHTML(""), "escapeHTML(\"\") must return \"\"");
        assertEquals("", model.escapeXML(""), "escapeXML(\"\") must return \"\"");
        assertEquals("", model.escapeJavaScript(""), "escapeJavaScript(\"\") must return \"\"");
    }

    // ------------------------------------------------------------ truncation

    @Test
    void truncateLeavesAStringThatIsExactlyAtTheLimitAlone() {
        // "hello world" is 11 characters; upper == 11 must not truncate. Off by
        // one here would clip the last word off every excerpt on a blog.
        assertEquals("hello world", model().truncate("hello world", 5, 11, "..."),
                "A string exactly at the upper limit must be returned untouched.");
    }

    @Test
    void truncateCutsAtTheLastSpaceWhenOneCharacterOverTheLimit() {
        assertEquals("hello...", model().truncate("hello world", 5, 10, "..."),
                "One character over the limit must truncate at the last space at or "
                        + "before 'upper', then append the suffix.");
    }

    @Test
    void truncateCutsMidWordWhenThereIsNoSpaceAfterTheLowerBound() {
        // No space at all, so there is nowhere nicer to cut than 'upper'.
        assertEquals("abcdefghij...", model().truncate("abcdefghijk", 5, 10, "..."),
                "With no space past the lower bound the cut falls at the upper bound.");
    }

    @Test
    void truncateReturnsNullForNullAndEmptyForEmpty() {
        assertNull(model().truncate(null, 5, 10, "..."),
                "truncate(null) must return null, not the string \"null\", which is "
                        + "what a theme would otherwise print.");
        assertEquals("", model().truncate("", 5, 10, "..."),
                "truncate(\"\") must return the empty string.");
    }

    /**
     * Pins a real inconsistency in {@code $utils.truncate}: its javadoc says
     * "Strips HTML and truncates", but HTML is only stripped when the text is
     * long enough to need truncating. A short value comes back with its markup
     * intact. Themes that reach for truncate() as a sanitiser get no sanitising
     * at all on short input — they must escape the result themselves.
     */
    @Test
    void truncateOnlyStripsHtmlWhenItActuallyTruncates() {
        String html = "<b>hello</b> world";

        assertEquals(html, model().truncate(html, 5, 11, "..."),
                "Short input comes back verbatim, markup and all — truncate() is not "
                        + "a sanitiser. If this ever changes, themes that rely on the "
                        + "markup surviving need reviewing.");
        assertEquals("hello...", model().truncate(html, 5, 10, "..."),
                "Long input is HTML-stripped before truncation, so the same call can "
                        + "return markup or plain text depending only on length.");
    }

    @Test
    void truncateNicelyKeepsTheTagsAroundTheTruncatedText() {
        // Unlike truncate(), truncateNicely re-attaches the closing tags it cut
        // through, so it cannot leave a page with an unbalanced <b>.
        assertEquals("<b>hello...</b>",
                model().truncateNicely("<b>hello</b> world", 5, 10, "..."),
                "truncateNicely must re-append the trailing markup so the surrounding "
                        + "page structure stays balanced.");
    }

    @Test
    void truncateNicelyLeavesShortTextUntouched() {
        assertEquals("<b>hi</b>", model().truncateNicely("<b>hi</b>", 5, 50, "..."),
                "Text under the limit must not gain a suffix.");
    }

    @Test
    void truncateTreatsAnUpperBoundBelowTheLowerBoundAsTheLowerBound() {
        // Guards a theme author's typo: $utils.truncate($text, 5, 2, "...")
        // must not throw StringIndexOutOfBoundsException mid-render.
        assertEquals("hello...", model().truncateText("hello world", 5, 2, "..."),
                "When upper < lower the lower bound wins; the call must not throw.");
    }

    @Test
    void truncateAndTruncateTextAreTheSameFunction() {
        // truncate() delegates to truncateText(); themes use both names.
        String text = "the quick brown fox jumps over the lazy dog";
        assertEquals(model().truncateText(text, 10, 20, ">>"),
                model().truncate(text, 10, 20, ">>"),
                "$utils.truncate and $utils.truncateText must stay interchangeable.");
        assertEquals("the quick brown fox>>", model().truncate(text, 10, 20, ">>"),
                "Pins the shared result so the assertion above cannot pass by both "
                        + "sides being equally broken.");
    }

    @Test
    void truncationCountsUnicodeCharactersNotBytes() {
        // "café au lait" is 12 characters but 13 UTF-8 bytes. Counting bytes
        // would cut a character early and could split a multi-byte sequence.
        assertEquals("café au lait", model().truncate("café au lait", 5, 12, "..."),
                "A 12-character string must fit inside a 12-character limit even when "
                        + "some of those characters are multi-byte in UTF-8.");
    }

    // ------------------------------------------------------------ HTML utils

    @Test
    void removeHTMLReplacesTagsWithSpacesByDefault() {
        assertEquals("a c d", model().removeHTML("a<b>c</b>d"),
                "The default must insert a space where a tag was, or adjacent words "
                        + "run together in feed summaries.");
        assertEquals("acd", model().removeHTML("a<b>c</b>d", false),
                "With addSpace=false the tags are removed without a separator.");
    }

    @Test
    void removeHTMLTurnsNullIntoEmptyStringSoTemplatesPrintNothing() {
        assertEquals("", model().removeHTML(null),
                "A null must render as nothing rather than blowing up the template.");
    }

    @Test
    void autoformatTurnsNewlinesIntoLineBreaks() {
        assertEquals("a<br />b", model().autoformat("a\nb"),
                "$utils.autoformat is how a plain-text block (e.g. an error page's stack "
                        + "trace) keeps its line breaks.");
        assertNull(model().autoformat(null), "autoformat(null) must return null");
    }

    /**
     * {@code rel="nofollow"} is what makes a link spam-planted into
     * reader-supplied HTML unprofitable; it is exposed to themes as
     * {@code $utils.addNofollow}. Both branches are asserted because the
     * condition guarding them was inverted until this test was written.
     */
    @Test
    void addNofollowMarksLinksSoLinkSpamEarnsNoPageRank() {
        assertEquals("<a href=\"http://spam.example.com\" rel=\"nofollow\">x</a>",
                model().addNofollow("<a href=\"http://spam.example.com\">x</a>"),
                "An unmarked link must come back carrying rel=\"nofollow\".");
        assertEquals("<a href=\"http://x.example.com\" rel=\"nofollow\">x</a>",
                model().addNofollow("<a href=\"http://x.example.com\" rel=\"nofollow\">x</a>"),
                "A link that is already marked must not gain a duplicate attribute.");
        assertEquals("no links here", model().addNofollow("no links here"),
                "Text without links must pass through untouched.");
        assertNull(model().addNofollow(null), "addNofollow(null) must return null");
    }

    @Test
    void transformToHTMLSubsetRestoresOnlyTheWhitelistedTags() {
        // The input is fully escaped text; only whitelisted tags are turned back
        // into markup. Anything else must stay escaped and inert.
        assertEquals("<b>bold</b> &lt;script&gt;",
                model().transformToHTMLSubset("&lt;b&gt;bold&lt;/b&gt; &lt;script&gt;"),
                "<b> is on the whitelist and <script> is not; a script tag must stay "
                        + "escaped or this method becomes an XSS vector.");
        assertNull(model().transformToHTMLSubset(null),
                "transformToHTMLSubset(null) must return null");
    }

    // ---------------------------------------------------------- string utils

    @Test
    void emptinessChecksTreatNullAndEmptyAlike() {
        UtilitiesModel model = model();
        assertTrue(model.isEmpty(null), "isEmpty(null) must be true");
        assertTrue(model.isEmpty(""), "isEmpty(\"\") must be true");
        assertFalse(model.isEmpty(" "), "A blank-but-not-empty string is not empty");
        assertFalse(model.isNotEmpty(null), "isNotEmpty(null) must be false");
        assertTrue(model.isNotEmpty("x"), "isNotEmpty(\"x\") must be true");
    }

    @Test
    void stringHelpersDelegateWithTheirDocumentedNullBehaviour() {
        UtilitiesModel model = model();
        assertArrayEquals(new String[]{"a", "b"}, model.split("a,b", ","),
                "split must break on the given separator");
        assertNull(model.split(null, ","), "split(null) must return null");
        assertTrue(model.equals(null, null), "equals(null, null) must be true");
        assertFalse(model.equals("a", null), "equals(\"a\", null) must be false");
        assertTrue(model.equals("a", "a"), "equals(\"a\", \"a\") must be true");
        assertTrue(model.isAlphanumeric("abc123"), "abc123 is alphanumeric");
        assertFalse(model.isAlphanumeric("abc 123"), "a space is not alphanumeric");
        assertArrayEquals(new String[]{"a", "b"}, model.stripAll(new String[]{" a ", "b "}),
                "stripAll must trim every element");
        assertEquals("hel", model.left("hello", 3), "left must take the first n chars");
        assertEquals("hello", model.left("hello", 99),
                "left must not throw when n exceeds the length");
        assertNull(model.left(null, 3), "left(null) must return null");
        assertEquals("a-b-c", model.replace("a.b.c", ".", "-"),
                "replace must replace every occurrence");
        assertEquals("a-b.c", model.replace("a.b.c", ".", "-", 1),
                "the 4-argument replace must stop after maxCount replacements");
    }

    // -------------------------------------------------------- URL/mail utils

    @Test
    void encodeProducesFormUrlEncodingAndDecodeReversesIt() {
        UtilitiesModel model = model();
        // Query-string encoding: space becomes '+', '/' becomes %2F. Themes use
        // $utils.encode when building search links out of user input.
        assertEquals("a+b%2Fc", model.encode("a b/c"),
                "encode must percent-encode the slash and use '+' for a space.");
        assertEquals("caf%C3%A9", model.encode("café"),
                "Non-ASCII must be encoded as its UTF-8 bytes.");
        assertEquals("a b/c", model.decode("a+b%2Fc"), "decode must reverse encode");
        assertNull(model.encode(null), "encode(null) must return null");
        assertNull(model.decode(null), "decode(null) must return null");
    }

    @Test
    void hexEncodeObfuscatesTextAndLeavesNullAndEmptyAlone() {
        UtilitiesModel model = model();
        assertEquals("%61%62", model.hexEncode("ab"),
                "hexEncode must emit %-prefixed hex per byte; it is what makes a "
                        + "mailto: link unreadable to address harvesters.");
        assertNull(model.hexEncode(null), "hexEncode(null) must return null");
        assertEquals("", model.hexEncode(""), "hexEncode(\"\") must return \"\"");
    }

    @Test
    void encodeEmailObfuscatesPlainAddressesAndNullIsSafe() {
        assertEquals("this-AT-email-DOT-com", model().encodeEmail("this@email.com"),
                "A bare address must be rewritten so it is not machine-harvestable.");
        assertNull(model().encodeEmail(null), "encodeEmail(null) must return null");
    }

    @Test
    void toBase64EncodesBytes() {
        assertEquals("aGk=", model().toBase64("hi".getBytes(StandardCharsets.UTF_8)),
                "toBase64 must produce standard Base64, padding included.");
    }

    // ----------------------------------------------------------- date format

    @Test
    void formatDateUsesTheWeblogsTimeZone() {
        // 23:30 UTC is 18:30 the same day in New York. Getting the zone wrong
        // shifts every displayed post date, sometimes by a whole day.
        UtilitiesModel model = modelFor(weblog("en_US", "America/New_York"));
        assertEquals("2024-01-15 18:30",
                model.formatDate(utc("2024-01-15T23:30:00Z"), "yyyy-MM-dd HH:mm"),
                "Dates must be rendered in the weblog's configured time zone.");
    }

    @Test
    void formatDateHonoursAnExplicitTimeZoneOverride() {
        UtilitiesModel model = modelFor(weblog("en_US", "America/New_York"));
        assertEquals("2024-01-15 23:30",
                model.formatDate(utc("2024-01-15T23:30:00Z"), "yyyy-MM-dd HH:mm",
                        TimeZone.getTimeZone("UTC")),
                "An explicit time zone argument must win over the weblog setting.");
    }

    @Test
    void aNullTimeZoneOverrideFallsBackToTheServerDefault() {
        // The three-argument form is public, so a theme can pass null. That must
        // mean "no override" rather than blowing up inside SimpleDateFormat.
        UtilitiesModel model = modelFor(weblog("en_US", "America/New_York"));
        Date noon = Date.from(LocalDateTime.of(2024, 1, 15, 12, 0)
                .atZone(ZoneId.systemDefault()).toInstant());

        assertEquals("2024-01-15", model.formatDate(noon, "yyyy-MM-dd", null),
                "A null time zone must leave the formatter on the JVM default rather "
                        + "than throwing.");
    }

    @Test
    void formatDateUsesTheWeblogsLocaleForMonthNames() {
        UtilitiesModel model = modelFor(weblog("fr", "UTC"));
        assertEquals("janvier",
                model.formatDate(utc("2024-01-15T12:00:00Z"), "MMMM",
                        TimeZone.getTimeZone("UTC")),
                "Month and day names must come from the weblog's locale, not from the "
                        + "server's default locale.");
    }

    /**
     * Documents a trap for theme authors: a null date does <em>not</em> render
     * as empty, it renders the format string itself. A theme that writes
     * {@code $utils.formatDate($entry.updateTime, "yyyy-MM-dd")} for an entry
     * with no update time prints the literal text "yyyy-MM-dd" on the page.
     */
    @Test
    void formatDateWithANullDateEchoesTheFormatString() {
        assertEquals("yyyy-MM-dd",
                model().formatDate(null, "yyyy-MM-dd", TimeZone.getTimeZone("UTC")),
                "Long-standing behaviour: a null date yields the pattern itself, so "
                        + "themes have to guard the null themselves.");
        assertNull(model().formatDate(utc("2024-01-15T12:00:00Z"), null),
                "A null format yields null.");
    }

    @Test
    void eightCharacterDatesArePlainYyyyMMdd() {
        // The 8-char stamp is what /date/YYYYMMDD archive URLs are built from,
        // so it has to keep matching what WeblogPageRequest parses back out.
        assertEquals("20240115", model().format8charsDate(localNoon(2024, 1, 15)),
                "Archive URLs parse this back out; the format must stay YYYYMMDD.");
    }

    @Test
    void iso8601TimestampsCarryAColonInTheZoneOffset() {
        // SimpleDateFormat's 'Z' emits "+0100"; DateUtil splices in the colon
        // afterwards because Atom requires "+01:00". Losing that splice produces
        // feeds that strict readers reject.
        String iso = model().formatIso8601Date(localNoon(2024, 1, 15));
        assertTrue(iso.matches("2024-01-15T12:00:00[+-]\\d{2}:\\d{2}"),
                "Atom needs yyyy-MM-ddTHH:mm:ss+HH:MM; got: " + iso);
    }

    @Test
    void iso8601DayIsJustTheCalendarDay() {
        assertEquals("2024-01-15", model().formatIso8601Day(localNoon(2024, 1, 15)),
                "The day-only ISO format must be yyyy-MM-dd with no time part.");
    }

    @Test
    void rfc822DatesUseEnglishMonthNamesWhateverTheServerLocale() {
        // RSS mandates English abbreviations; DateUtil pins Locale.US for this
        // reason (ROL-725). A server running under a French default locale must
        // still emit "Jan", not "janv.".
        String rfc = model().formatRfc822Date(localNoon(2024, 1, 15));
        assertTrue(rfc.startsWith("Mon, 15 Jan 2024 12:00:00 "),
                "RSS requires an RFC-822 date with English month/day names; got: " + rfc);
    }

    @Test
    void nullDatesFormatAsEmptyStringInFeedFormats() {
        // Feeds print these unconditionally, so null must not become "null".
        UtilitiesModel model = model();
        assertEquals("", model.formatIso8601Date(null), "ISO-8601 of null must be empty");
        assertEquals("", model.formatIso8601Day(null), "ISO-8601 day of null must be empty");
        assertEquals("", model.formatRfc822Date(null), "RFC-822 of null must be empty");
        assertEquals("", model.format8charsDate(null), "8-char date of null must be empty");
    }

    @Test
    void getNowReturnsTheCurrentTime() {
        long before = System.currentTimeMillis();
        Date now = UtilitiesModel.getNow();
        long after = System.currentTimeMillis();
        assertNotNull(now, "getNow() must never return null");
        assertTrue(now.getTime() >= before && now.getTime() <= after,
                "getNow() must return the current time; " + now.getTime()
                        + " is outside [" + before + ", " + after + "]");
    }

    // --------------------------------------------------------------- authnz

    @Test
    void anonymousRequestsAreNotAuthenticatedAndHaveNoUser() {
        UtilitiesModel model = modelFor(weblog("en_US", "UTC"));
        assertFalse(model.isUserAuthenticated(),
                "A request with no principal must not look logged in — themes hide "
                        + "their edit links behind this.");
        assertNull(model.getAuthenticatedUser(),
                "There is no user to wrap for an anonymous request.");
    }

    @Test
    void authenticatedRequestsExposeTheWrappedUser() {
        User user = new User();
        user.setUserName("bob");
        user.setScreenName("Bob");
        WeblogRequest request = new WeblogRequest();
        request.setWeblog(weblog("en_US", "UTC"));
        request.setAuthenticUser("bob");
        request.setUser(user);

        UtilitiesModel model = modelFor(request);

        assertTrue(model.isUserAuthenticated(), "A request with a principal is logged in");
        assertNotNull(model.getAuthenticatedUser(), "The logged-in user must be exposed");
        // Screen name rather than user name: UserWrapper.getUserName() hides the
        // real login behind the screen name when user.hideUserNames is set, so
        // asserting on it here would really be asserting on that config flag.
        assertEquals("Bob", model.getAuthenticatedUser().getScreenName(),
                "The wrapper must expose the user the request authenticated as.");
    }

    /**
     * A facade whose user manager answers the weblog-permission checks the
     * model now issues itself (it used to ask {@code Weblog.hasUserPermission},
     * an entity getter): POST -> {@code author}, ADMIN -> {@code admin}.
     */
    private static Weblogger permissionOracle(User user, boolean author, boolean admin) {
        try {
            Weblogger weblogger = mock(Weblogger.class);
            UserManager users = mock(UserManager.class);
            when(weblogger.getUserManager()).thenReturn(users);
            when(users.checkPermission(
                    argThat(p -> p instanceof WeblogPermission && p.hasAction(WeblogPermission.POST)), eq(user)))
                    .thenReturn(author);
            when(users.checkPermission(
                    argThat(p -> p instanceof WeblogPermission && p.hasAction(WeblogPermission.ADMIN)), eq(user)))
                    .thenReturn(admin);
            return weblogger;
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    /** A facade whose user manager cannot answer any permission check. */
    private static Weblogger failingPermissionOracle() {
        try {
            Weblogger weblogger = mock(Weblogger.class);
            UserManager users = mock(UserManager.class);
            when(weblogger.getUserManager()).thenReturn(users);
            when(users.checkPermission(any(), any()))
                    .thenThrow(new IllegalStateException("database is down"));
            return weblogger;
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    /** A facade whose user manager answers the site-level login check with {@code holdsLogin}. */
    private static Weblogger loginOracle(User user, boolean holdsLogin) {
        try {
            Weblogger weblogger = mock(Weblogger.class);
            UserManager users = mock(UserManager.class);
            when(weblogger.getUserManager()).thenReturn(users);
            when(users.checkPermission(argThat(p -> p instanceof GlobalPermission
                    && p.hasAction(GlobalPermission.LOGIN)), eq(user))).thenReturn(holdsLogin);
            return weblogger;
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static WeblogRequest signedInAs(User user) {
        WeblogRequest request = new WeblogRequest();
        request.setWeblog(weblog("en_US", "UTC"));
        request.setAuthenticUser(user.getUserName());
        request.setUser(user);
        return request;
    }

    @Test
    void isUserAuthorIsTrueForAUserHoldingLogin() {
        User user = new User();
        user.setUserName("bob");
        assertTrue(modelFor(signedInAs(user), loginOracle(user, true)).isUserAuthor(),
                "A user with the global login action can use the authoring UI.");
    }

    @Test
    void isUserAuthorIsFalseForAReader() {
        User reader = new User();
        reader.setUserName("reader-abcdefghij");
        assertFalse(modelFor(signedInAs(reader), loginOracle(reader, false)).isUserAuthor(),
                "A reader holds no login action, so the themes must not offer the menu.");
    }

    @Test
    void isUserAuthorIsFalseForAnonymousWithoutAskingAnyone() throws Exception {
        Weblogger weblogger = loginOracle(new User(), true);
        WeblogRequest request = new WeblogRequest();
        request.setWeblog(weblog("en_US", "UTC"));
        assertFalse(modelFor(request, weblogger).isUserAuthor());
        verify(weblogger.getUserManager(), never()).checkPermission(any(), any());
    }

    @Test
    void isUserAuthorFailsClosedWhenTheCheckCannotRun() {
        User user = new User();
        user.setUserName("bob");
        assertFalse(modelFor(signedInAs(user), failingPermissionOracle()).isUserAuthor(),
                "A check that could not run must not report an author.");
    }

    @Test
    void authorisationChecksAreSkippedEntirelyForAnonymousVisitors() throws Exception {
        // The weblog must not even be consulted: an anonymous visitor can never
        // be an author, and a permission lookup here would be a wasted database
        // round-trip on every anonymous page view.
        Weblogger weblogger = mock(Weblogger.class);
        UserManager users = mock(UserManager.class);
        when(weblogger.getUserManager()).thenReturn(users);
        WeblogWrapper wrapper = WeblogWrapper.wrap(weblog("en_US", "UTC"), null, weblogger);

        WeblogRequest request = new WeblogRequest();
        request.setWeblog(weblog("en_US", "UTC"));
        UtilitiesModel model = modelFor(request, weblogger);

        assertFalse(model.isUserAuthorizedToAuthor(wrapper),
                "An anonymous visitor must never be reported as an author.");
        assertFalse(model.isUserAuthorizedToAdmin(wrapper),
                "An anonymous visitor must never be reported as an admin.");
        verify(users, never()).checkPermission(any(), any());
    }

    @Test
    void authorisationChecksAskTheWeblogWhenTheVisitorIsLoggedIn() {
        User user = new User();
        user.setUserName("bob");

        Weblogger weblogger = permissionOracle(user, true, false);
        WeblogWrapper wrapper = WeblogWrapper.wrap(weblog("en_US", "UTC"), null, weblogger);

        WeblogRequest request = new WeblogRequest();
        request.setWeblog(weblog("en_US", "UTC"));
        request.setAuthenticUser("bob");
        request.setUser(user);
        UtilitiesModel model = modelFor(request, weblogger);

        assertTrue(model.isUserAuthorizedToAuthor(wrapper),
                "POST permission must make $utils.isUserAuthorizedToAuthor true.");
        assertFalse(model.isUserAuthorizedToAdmin(wrapper),
                "Lacking ADMIN permission must not unlock the admin menu.");
    }

    @Test
    void authorisationChecksReportTheOppositeAnswerJustAsFaithfully() {
        // The mirror image of the test above: a logged-in reader with no POST
        // permission must not be shown edit links, and a blog admin must be.
        User user = new User();
        user.setUserName("carol");

        Weblogger weblogger = permissionOracle(user, false, true);
        WeblogWrapper wrapper = WeblogWrapper.wrap(weblog("en_US", "UTC"), null, weblogger);

        WeblogRequest request = new WeblogRequest();
        request.setWeblog(weblog("en_US", "UTC"));
        request.setAuthenticUser("carol");
        request.setUser(user);
        UtilitiesModel model = modelFor(request, weblogger);

        assertFalse(model.isUserAuthorizedToAuthor(wrapper),
                "Being logged in is not authorisation; without POST permission the "
                        + "edit links must stay hidden.");
        assertTrue(model.isUserAuthorizedToAdmin(wrapper),
                "ADMIN permission must make $utils.isUserAuthorizedToAdmin true.");
    }

    @Test
    void aFailedPermissionLookupDeniesAccessRatherThanBreakingThePage() {
        // Permission checks go to the database. If that fails mid-render the
        // page must still render — but it must fail closed, not open.
        User user = new User();
        user.setUserName("bob");

        Weblogger weblogger = failingPermissionOracle();
        WeblogWrapper wrapper = WeblogWrapper.wrap(weblog("en_US", "UTC"), null, weblogger);

        WeblogRequest request = new WeblogRequest();
        request.setWeblog(weblog("en_US", "UTC"));
        request.setAuthenticUser("bob");
        request.setUser(user);

        assertFalse(modelFor(request, weblogger).isUserAuthorizedToAuthor(wrapper),
                "A permission lookup that blows up must deny access, not grant it.");
    }

    @Test
    void aFailedAdminPermissionLookupAlsoDeniesAccess() {
        // The admin check is a separate method with its own catch block, and it
        // guards more than the author check does.
        User user = new User();
        user.setUserName("bob");

        Weblogger weblogger = failingPermissionOracle();
        WeblogWrapper wrapper = WeblogWrapper.wrap(weblog("en_US", "UTC"), null, weblogger);

        WeblogRequest request = new WeblogRequest();
        request.setWeblog(weblog("en_US", "UTC"));
        request.setAuthenticUser("bob");
        request.setUser(user);

        assertFalse(modelFor(request, weblogger).isUserAuthorizedToAdmin(wrapper),
                "A failed admin permission lookup must deny access, not grant it.");
    }
}

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

package com.wikantik.pressroom.ui.rendering.util;

import static org.mockito.Mockito.mock;
import com.wikantik.pressroom.business.Weblogger;
import java.util.Locale;

import com.wikantik.pressroom.pojos.Weblog;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link WeblogRequest}, the first stage of URL parsing that
 * every weblog-facing request goes through.
 *
 * <p>{@code WeblogRequest} splits {@code /<handle>[/<locale>][/extra/path]}
 * into three fields and hands the remainder to its subclasses. Two failure
 * modes matter operationally: parsing a legitimate URL as invalid takes a live
 * blog offline with a 404/500, and parsing an odd URL too leniently could point
 * a reader at the wrong weblog. Every test below pins one of those two edges.
 *
 * <p>No servlet container and no bootstrapped business tier is required: the
 * constructor performs pure string work and only {@code getWeblog()} would
 * reach the database, which these tests avoid or pre-populate.
 */
class WeblogRequestTest {

    private static final String PAGE_SERVLET = "/roller-ui/rendering/page";

    private static WeblogRequest parse(String pathInfo) throws InvalidRequestException {
        return new WeblogRequest(mock(Weblogger.class), MockRequest.with(PAGE_SERVLET, pathInfo));
    }

    // ---------------------------------------------------------------- handle

    @Test
    void nullPathInfoParsesToAnEmptyRequestRatherThanThrowing() throws Exception {
        // The bare servlet URL with no trailing path is a legitimate request --
        // the rendering servlets handle it themselves -- so parsing must not
        // reject it. If this starts throwing, /roller-ui/rendering/page returns
        // a 404 instead of whatever the servlet decided.
        WeblogRequest request = parse(null);

        assertNull(request.getWeblogHandle(), "No path means no weblog handle");
        assertNull(request.getLocale(), "No path means no locale");
        assertNull(request.getPathInfo(), "No path means no leftover path info");
    }

    @Test
    void bareSlashIsTreatedAsNoPathAtAll() throws Exception {
        // "/" trims to length 1, which is the exact boundary of the
        // `path.trim().length() > 1` guard. One character shorter than a
        // one-letter handle, so nothing should be extracted.
        WeblogRequest request = parse("/");

        assertNull(request.getWeblogHandle(),
                "\"/\" carries no handle; if a handle appears here the guard "
                        + "boundary in WeblogRequest was moved");
    }

    @Test
    void whitespaceOnlyPathIsTreatedAsNoPathAtAll() throws Exception {
        WeblogRequest request = parse("   ");

        assertNull(request.getWeblogHandle(),
                "Whitespace trims away to nothing and must not become a handle");
    }

    @Test
    void singleCharacterHandleIsAccepted() throws Exception {
        // "/x" trims to length 2, one past the guard boundary. This is the
        // shortest URL that must still resolve a weblog.
        WeblogRequest request = parse("/x");

        assertEquals("x", request.getWeblogHandle(),
                "A one-character handle is legal and must survive parsing");
    }

    @Test
    void handleOnlyPathLeavesNoLocaleAndNoPathInfo() throws Exception {
        WeblogRequest request = parse("/myblog");

        assertEquals("myblog", request.getWeblogHandle());
        assertNull(request.getLocale(), "No locale segment was supplied");
        assertNull(request.getPathInfo(), "Nothing follows the handle");
    }

    @Test
    void trailingSlashAfterHandleIsIgnored() throws Exception {
        // Readers and feed clients routinely append a slash. It must resolve to
        // exactly the same weblog as the unslashed form.
        WeblogRequest request = parse("/myblog/");

        assertEquals("myblog", request.getWeblogHandle(),
                "A trailing slash must not change which weblog is resolved");
        assertNull(request.getPathInfo(),
                "A trailing slash must not leave an empty path info behind");
    }

    @Test
    void doubleTrailingSlashLeavesNoPathInfo() throws Exception {
        // Only one trailing slash is stripped, so "myblog//" splits into
        // ["myblog", ""] -- the empty remainder must not become path info, or
        // subclasses would try to parse "" as a context.
        WeblogRequest request = parse("/myblog//");

        assertEquals("myblog", request.getWeblogHandle());
        assertNull(request.getPathInfo(),
                "An empty remainder must stay null rather than becoming \"\"");
    }

    @Test
    void handleContainingASpaceIsRejected() {
        // getPathInfo() arrives percent-decoded, so a request for
        // /roller-ui/rendering/page/my%20blog reaches this code as "/my blog".
        InvalidRequestException ex = assertThrows(InvalidRequestException.class,
                () -> parse("/my blog"),
                "A decoded space makes the handle non-alphanumeric and must be rejected");

        assertTrue(ex.getMessage().contains("not a valid weblog request"),
                "The message should say the request is not a valid weblog request "
                        + "so the servlet can map it to a 404; got: " + ex.getMessage());
    }

    @Test
    void parentDirectoryTraversalIsRejectedAtTheHandle() {
        // ".." is not alphanumeric, so a traversal attempt aimed at the handle
        // position dies here rather than reaching the filesystem.
        assertThrows(InvalidRequestException.class, () -> parse("/../../etc/passwd"),
                "A \"..\" first segment must never be accepted as a weblog handle");
    }

    @Test
    void emptyHandleSegmentIsRejected() {
        // "//resource" leaves an empty first segment. Accepting it would mean
        // looking up a weblog with an empty handle.
        assertThrows(InvalidRequestException.class, () -> parse("//resource"),
                "An empty first segment is not a handle and must be rejected");
    }

    @Test
    void hyphenatedHandleIsRejected() {
        // Documents a deliberate narrowing: handles are alphanumeric only, so a
        // hyphen in the first segment is a bad request rather than a lookup.
        assertThrows(InvalidRequestException.class, () -> parse("/my-blog"),
                "Hyphens are not alphanumeric, so \"my-blog\" is not a handle");
    }

    @Test
    void underscoreHandleIsAcceptedBecauseStorageAcceptsIt() {
        // The two layers used to disagree: JPAWeblogManagerImpl.isAlphanumeric()
        // permits '_' (its javadoc says so outright) while this parser used
        // StringUtils.isAlphanumeric(), which does not. A weblog whose handle
        // contained an underscore could be created and stored, then answer 404
        // at every public URL forever. The parser now matches storage.
        assertDoesNotThrowParsing("/my_blog",
                "a handle the persistence layer accepts must be reachable by URL");
    }

    @Test
    void hyphenHandleIsStillRejected() {
        // Widening to '_' was deliberate and narrow; '-' is not permitted by the
        // persistence layer either, so it stays rejected.
        assertThrows(InvalidRequestException.class, () -> parse("/my-blog"),
                "'-' is not accepted by the persistence layer, so it must not be "
                        + "accepted here either");
    }

    /**
     * The site space's handle has a hyphen on purpose (no blog can take it), so
     * only a page request and the two asset requests admit it: the page
     * servlet is where site pages (and the theme stylesheet) render, the
     * resource and media servlets serve the site's files, and all three refuse
     * a direct request for the site space. Every other rendering servlet
     * (feed, search) still reads it as no handle at all.
     */
    @Test
    void onlyPageAndAssetRequestsAdmitTheSiteSpaceHandle() throws InvalidRequestException {
        assertThrows(InvalidRequestException.class, () -> parse("/site-space"),
                "a plain weblog request (feed, search) never reaches the site space");
        assertEquals(Weblog.SITE_SPACE_HANDLE, new WeblogResourceRequest(mock(Weblogger.class),
                MockRequest.with("/roller-ui/rendering/resources", "/site-space/img/logo.png"))
                .getWeblogHandle());
        assertEquals(Weblog.SITE_SPACE_HANDLE, new WeblogMediaResourceRequest(mock(Weblogger.class),
                MockRequest.with("/roller-ui/rendering/media-resources", "/site-space/abc123"))
                .getWeblogHandle());
        WeblogPageRequest page = new WeblogPageRequest(mock(Weblogger.class),
                MockRequest.with(PAGE_SERVLET, "/site-space/about"));
        assertEquals(Weblog.SITE_SPACE_HANDLE, page.getWeblogHandle());
        assertEquals("about", page.getPageSlug());
        assertThrows(InvalidRequestException.class, () -> new WeblogPageRequest(mock(Weblogger.class),
                        MockRequest.with(PAGE_SERVLET, "/other-space/about")),
                "the site space's handle, not hyphens in general");
    }

    @Test
    void nonAsciiLettersAreAcceptedInAHandle() {
        // isAlphanumeric() is Unicode-aware, not ASCII-only. Recording this so
        // that a future switch to an ASCII-only check is a visible decision.
        assertDoesNotThrowParsing("/café",
                "Unicode letters currently count as alphanumeric in a handle");
    }

    // ---------------------------------------------------------------- locale

    @Test
    void twoLetterSecondSegmentIsReadAsALocale() throws Exception {
        WeblogRequest request = parse("/myblog/en");

        assertEquals("en", request.getLocale(),
                "\"en\" is a language-only locale segment");
        assertNull(request.getPathInfo(), "Nothing follows the locale");
    }

    @Test
    void languageAndCountrySegmentIsReadAsALocaleAndTheRestIsPathInfo() throws Exception {
        WeblogRequest request = parse("/myblog/en_US/entry/hello");

        assertEquals("myblog", request.getWeblogHandle());
        assertEquals("en_US", request.getLocale());
        assertEquals("entry/hello", request.getPathInfo(),
                "Everything past the locale is handed to the subclass unparsed");
    }

    @Test
    void nonLocaleSecondSegmentBecomesPathInfoInFull() throws Exception {
        // "entry" is five characters, the same length as "en_US", so this is the
        // case where a length-only locale check would misfire and swallow the
        // context segment -- which would silently turn a permalink into a
        // homepage.
        WeblogRequest request = parse("/myblog/entry/hello");

        assertNull(request.getLocale(),
                "\"entry\" is not a locale; treating it as one would lose the permalink");
        assertEquals("entry/hello", request.getPathInfo());
    }

    @Test
    void fiveLetterSegmentWithoutASeparatorIsNotALocale() throws Exception {
        WeblogRequest request = parse("/myblog/enUSA/rest");

        assertNull(request.getLocale(), "\"enUSA\" has no '_' separator");
        assertEquals("enUSA/rest", request.getPathInfo());
    }

    @Test
    void isLocaleAcceptsOnlyTwoAndFiveCharacterForms() {
        WeblogRequest request = new WeblogRequest();

        // Accepted forms.
        assertTrue(request.isLocale("en"), "language-only locale");
        assertTrue(request.isLocale("EN"), "capitalisation is deliberately not checked");
        assertTrue(request.isLocale("en_US"), "language_COUNTRY locale");

        // Rejected on length: 1, 3, 4 and 6 characters bracket both accepted
        // lengths, so an off-by-one in the length test shows up here.
        assertFalse(request.isLocale("e"), "one character is too short");
        assertFalse(request.isLocale("eng"), "three characters is neither form");
        assertFalse(request.isLocale("en_U"), "four characters is neither form");
        assertFalse(request.isLocale("en_USA"), "six characters is too long");

        // Rejected on shape at the accepted lengths.
        assertFalse(request.isLocale("e_US"), "language part must be two characters");
        assertFalse(request.isLocale("_____"), "separators alone are not a locale");
        assertFalse(request.isLocale(null), "null must not blow up or pass");
    }

    @Test
    void localeInstanceIsBuiltFromTheLanguageOnlySegment() throws Exception {
        WeblogRequest request = parse("/myblog/fr");

        assertEquals(Locale.of("fr"), request.getLocaleInstance());
    }

    @Test
    void localeInstanceIsBuiltFromTheLanguageAndCountrySegment() throws Exception {
        WeblogRequest request = parse("/myblog/fr_CA");

        Locale locale = request.getLocaleInstance();
        assertEquals("fr", locale.getLanguage());
        assertEquals("CA", locale.getCountry());
    }

    @Test
    void localeInstanceFallsBackToTheWeblogDefaultWhenTheUrlHasNoLocale() throws Exception {
        WeblogRequest request = parse("/myblog");
        Weblog weblog = new Weblog();
        weblog.setLocale("de_DE");
        request.setWeblog(weblog);

        Locale locale = request.getLocaleInstance();

        assertEquals("de", locale.getLanguage(),
                "With no locale in the URL the weblog's own locale must be used, "
                        + "otherwise every blog renders in the server default language");
        assertEquals("DE", locale.getCountry());
    }

    @Test
    void localeInstanceIsComputedOnceAndCached() throws Exception {
        WeblogRequest request = parse("/myblog/fr_CA");

        assertSameInstanceTwice(request);
    }

    @Test
    void explicitlySetLocaleInstanceWins() throws Exception {
        WeblogRequest request = parse("/myblog/fr_CA");
        request.setLocaleInstance(Locale.JAPAN);

        assertEquals(Locale.JAPAN, request.getLocaleInstance(),
                "A locale injected by a servlet must not be recomputed from the URL");
    }

    // ---------------------------------------------------------------- weblog

    @Test
    void weblogLookupIsSkippedEntirelyWhenThereIsNoHandle() throws Exception {
        // getWeblog() reached the static locator, which threw if the business
        // tier is not bootstrapped. Guarding on the handle keeps a pathless
        // request from ever getting that far.
        WeblogRequest request = parse(null);

        assertNull(request.getWeblog(),
                "With no handle there is nothing to look up and no reason to "
                        + "touch the business tier");
    }

    @Test
    void injectedWeblogIsReturnedWithoutALookup() throws Exception {
        WeblogRequest request = parse("/myblog");
        Weblog weblog = new Weblog();
        weblog.setHandle("myblog");
        request.setWeblog(weblog);

        assertEquals(weblog, request.getWeblog(),
                "An already-resolved weblog must be reused, not looked up again");
    }

    @Test
    void settersOverrideWhateverWasParsed() throws Exception {
        WeblogRequest request = parse("/myblog/en/rest");
        request.setWeblogHandle("other");
        request.setLocale("ja");
        request.setPathInfo("changed");

        assertEquals("other", request.getWeblogHandle());
        assertEquals("ja", request.getLocale());
        assertEquals("changed", request.getPathInfo());
    }

    private static void assertSameInstanceTwice(WeblogRequest request) {
        Locale first = request.getLocaleInstance();
        Locale second = request.getLocaleInstance();
        assertTrue(first == second,
                "getLocaleInstance() must cache; a fresh Locale each call means "
                        + "the null-check guard was lost");
    }

    private static void assertDoesNotThrowParsing(String pathInfo, String why) {
        try {
            WeblogRequest request = parse(pathInfo);
            assertEquals(pathInfo.substring(1), request.getWeblogHandle(), why);
        } catch (InvalidRequestException ex) {
            throw new AssertionError(why + " -- but parsing threw: " + ex.getMessage(), ex);
        }
    }
}

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
import java.util.List;

import com.wikantik.pressroom.config.WebloggerConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link WeblogPageRequest}, which turns
 * {@code /pressroom-ui/rendering/page/<handle>/<context>/<value>} into the entry,
 * date, category, custom page or tag set that PageServlet will render.
 *
 * <p>This is the busiest parser in the application -- every human page view
 * goes through it. The interesting cases are the ones where a slightly wrong
 * URL must produce a clean {@link InvalidRequestException} (which PageServlet
 * turns into a 404) rather than a half-populated request that renders the wrong
 * thing.
 */
class WeblogPageRequestTest {

    private static final String PAGE_SERVLET = "/pressroom-ui/rendering/page";

    /** The configured ceiling on intersecting tag queries, read the same way the parser reads it. */
    private static final int MAX_TAGS =
            WebloggerConfig.getIntProperty("tags.queries.maxIntersectionSize", 3);

    private static WeblogPageRequest parse(String pathInfo, String... params)
            throws InvalidRequestException {
        return new WeblogPageRequest(mock(Weblogger.class), MockRequest.with(PAGE_SERVLET, pathInfo, params));
    }

    // ----------------------------------------------------------- destination

    @Test
    void requestAimedAtAnotherServletIsRejected() {
        // WeblogPageRequest is also the base class for the preview request, so
        // the destination check is what keeps a preview URL from being rendered
        // as a live page (and vice versa).
        assertThrows(InvalidRequestException.class,
                () -> new WeblogPageRequest(mock(Weblogger.class), 
                        MockRequest.with("/pressroom-ui/rendering/feed", "/myblog")),
                "A feed URL must not parse as a page request");
    }

    @Test
    void requestWithNoServletPathIsRejected() {
        assertThrows(InvalidRequestException.class,
                () -> new WeblogPageRequest(mock(Weblogger.class), MockRequest.with(null, "/myblog")),
                "A null servlet path cannot match the page servlet and must be rejected");
    }

    @Test
    void destinationCheckAcceptsOnlyTheExactPageServletPath() throws Exception {
        WeblogPageRequest request = parse("/myblog");

        assertTrue(request.isValidDestination(PAGE_SERVLET));
        assertFalse(request.isValidDestination(PAGE_SERVLET + "/extra"),
                "A longer path is a different servlet, not the page servlet");
        assertFalse(request.isValidDestination(null));
    }

    // -------------------------------------------------------------- contexts

    @Test
    void weblogHomepageHasNoContextAndCountsAsAWebsiteHit() throws Exception {
        WeblogPageRequest request = parse("/myblog");

        assertNull(request.getContext(), "The homepage has no context segment");
        assertTrue(request.isWebsitePageHit(),
                "The homepage is the hit type PageServlet records against the weblog itself");
        assertFalse(request.isOtherPageHit(),
                "The homepage must not also be counted as an 'other' page hit");
    }

    @Test
    void permalinkContextExtractsTheAnchor() throws Exception {
        WeblogPageRequest request = parse("/myblog/entry/hello-world");

        assertEquals("entry", request.getContext());
        assertEquals("hello-world", request.getWeblogAnchor());
        assertTrue(request.isOtherPageHit());
        assertFalse(request.isWebsitePageHit(),
                "A permalink is not a homepage hit");
    }

    @Test
    void anchorIsDecodedASecondTimeAfterTheContainerHasAlreadyDecodedIt() throws Exception {
        // The container hands over an already-decoded path, and this parser
        // decodes again. That is what lets an anchor containing a literal '+'
        // round-trip: it is published as %2B, arrives here as "%2B", and the
        // second decode restores the '+'.
        WeblogPageRequest request = parse("/myblog/entry/c%2B%2B-tips");

        assertEquals("c++-tips", request.getWeblogAnchor(),
                "%2B in the decoded path must survive the second decode as '+'");
    }

    @Test
    void malformedPercentEscapeInAnAnchorIsRejectedAsAnInvalidRequest() {
        // "50%" reaches this code when the browser correctly requests
        // .../entry/50%25. The second decode cannot parse a bare '%'. Callers
        // are documented to expect InvalidRequestException, and PageServlet's
        // referrer path catches only that, so an unchecked IllegalArgumentException
        // escaping here would surface as a 500.
        InvalidRequestException ex = assertThrows(InvalidRequestException.class,
                () -> parse("/myblog/entry/50%"),
                "A bare '%' must be reported as an invalid request, not as an "
                        + "unchecked IllegalArgumentException from URLDecoder");

        assertTrue(ex.getMessage().contains("invalid URL encoding"),
                "The message should name the encoding problem; got: " + ex.getMessage());
    }

    @Test
    void categoryContextIsDecoded() throws Exception {
        WeblogPageRequest request = parse("/myblog/category/Java %26 Friends");

        assertEquals("category", request.getContext());
        assertEquals("Java & Friends", request.getWeblogCategoryName());
        assertTrue(request.isOtherPageHit());
    }

    @Test
    void customPageContextExtractsThePageLink() throws Exception {
        WeblogPageRequest request = parse("/myblog/page/about");

        assertEquals("about", request.getWeblogPageName());
        assertTrue(request.isOtherPageHit(),
                "A custom page view is a real page view and should be counted");
    }

    @Test
    void customPageThatLooksLikeAnAssetIsNotCountedAsAPageHit() throws Exception {
        // Stylesheets and scripts are served through the same /page/ context.
        // Counting them would inflate every weblog's hit count by however many
        // assets its theme happens to reference.
        WeblogPageRequest request = parse("/myblog/page/custom.css");

        assertEquals("custom.css", request.getWeblogPageName());
        assertFalse(request.isOtherPageHit(),
                "A page link containing a '.' is an asset and must not be counted as a hit");
    }

    @Test
    void unknownContextWithAValueIsRejected() {
        assertThrows(InvalidRequestException.class, () -> parse("/myblog/bogus/value"),
                "An unrecognised context must be a clean 404, not a blank page");
    }

    @Test
    void aSingleUnreservedSegmentParsesAsACandidatePageSlug() throws Exception {
        // /myblog/bogus no longer 404s during parsing. A bare, non-reserved
        // segment is a candidate page slug: getPageSlug() records it (no
        // database access), and getWeblogPageContent() resolves it lazily,
        // on first call, against the database -- see both methods' javadoc.
        // A slug naming no published page still ends in a 404, just later,
        // once the servlet asks and gets nothing back (PageRoutingTest
        // covers that end to end); this parser-level test has no database
        // behind it to resolve against.
        WeblogPageRequest request = parse("/myblog/bogus");

        assertEquals("bogus", request.getContext());
        assertEquals("bogus", request.getPageSlug());
        assertTrue(request.isOtherPageHit());
    }

    @Test
    void aReservedWordAloneIsRejectedWithoutTryingToResolveAsAPage() {
        // "entry" with nothing after it is not a permalink (that needs an
        // anchor) and must not be mistaken for a page slug either -- a page
        // could never be saved under that slug in the first place
        // (ReservedSlugs, enforced at save time), so treating it as a
        // candidate would only ever resolve to nothing, more slowly.
        assertThrows(InvalidRequestException.class, () -> parse("/myblog/entry"),
                "A reserved word alone is rejected outright, not deferred");
    }

    @Test
    void tagsContextWithNoTagsIsTheOnlyContextAllowedToBeEmpty() throws Exception {
        // /tags with no tags is the tag index page, so unlike every other
        // context it must not throw.
        WeblogPageRequest request = parse("/myblog/tags");

        assertEquals("tags", request.getContext());
        assertNull(request.getTags(), "No tags were supplied, so none are parsed");
    }

    // ------------------------------------------------------------------ tags

    @Test
    void tagsContextSplitsOnPlusSigns() throws Exception {
        WeblogPageRequest request = parse("/myblog/tags/java+testing");

        assertEquals(List.of("java", "testing"), request.getTags());
        assertTrue(request.isOtherPageHit());
    }

    @Test
    void exactlyTheMaximumNumberOfTagsIsAccepted() throws Exception {
        WeblogPageRequest request = parse("/myblog/tags/" + tagPath(MAX_TAGS));

        assertEquals(MAX_TAGS, request.getTags().size(),
                "The configured maximum of " + MAX_TAGS + " intersecting tags must be "
                        + "allowed; rejecting it would break the documented limit");
    }

    @Test
    void oneTagOverTheMaximumIsRejected() {
        // The tag intersection query cost grows with each extra tag, so this
        // ceiling is a denial-of-service guard, not a cosmetic limit.
        assertThrows(InvalidRequestException.class,
                () -> parse("/myblog/tags/" + tagPath(MAX_TAGS + 1)),
                "One tag past the configured maximum of " + MAX_TAGS + " must be rejected");
    }

    @Test
    void tagsQueryParameterOnACustomPageIsHonouredAndBounded() throws Exception {
        WeblogPageRequest request = parse("/myblog/page/tagcloud", "tags", "java testing");

        assertEquals(List.of("java", "testing"), request.getTags(),
                "A custom page may filter by tags through the query string");

        assertThrows(InvalidRequestException.class,
                () -> parse("/myblog/page/tagcloud", "tags", tagQuery(MAX_TAGS + 1)),
                "The tag ceiling applies to the query-parameter form too");
    }

    // ------------------------------------------------------------------ date

    @Test
    void eightCharacterDateIsAccepted() throws Exception {
        WeblogPageRequest request = parse("/myblog/date/20240115");

        assertEquals("20240115", request.getWeblogDate());
        assertTrue(request.isOtherPageHit());
    }

    @Test
    void sixCharacterDateIsAccepted() throws Exception {
        WeblogPageRequest request = parse("/myblog/date/202401");

        assertEquals("202401", request.getWeblogDate());
    }

    @Test
    void dateLengthsAdjacentToTheAcceptedOnesAreRejected() {
        // 5/7/9 bracket the two legal lengths on both sides. An off-by-one in
        // the length check would let a truncated date through, and the pagers
        // downstream parse it positionally -- "2024011" would silently become a
        // different day.
        assertThrows(InvalidRequestException.class, () -> parse("/myblog/date/20240"),
                "5 digits is not a legal date");
        assertThrows(InvalidRequestException.class, () -> parse("/myblog/date/2024011"),
                "7 digits is not a legal date");
        assertThrows(InvalidRequestException.class, () -> parse("/myblog/date/202401150"),
                "9 digits is not a legal date");
    }

    @Test
    void nonNumericDateIsRejected() {
        assertThrows(InvalidRequestException.class, () -> parse("/myblog/date/2024jan"),
                "A date must be all digits");
    }

    @Test
    void dateQueryParameterIsValidatedTheSameWayAsThePathForm() throws Exception {
        WeblogPageRequest valid = parse(null, "date", "20240115");
        assertEquals("20240115", valid.getWeblogDate());

        assertThrows(InvalidRequestException.class, () -> parse(null, "date", "nonsense"),
                "The legacy ?date= form must be validated as strictly as /date/");
    }

    // ------------------------------------------------------- query parameters

    @Test
    void entryParameterSetsTheAnchorOnTheHomepage() throws Exception {
        WeblogPageRequest request = parse(null, "entry", "hello-world");

        assertEquals("hello-world", request.getWeblogAnchor());
    }

    @Test
    void legacyAnchorParameterIsAcceptedWhenEntryIsAbsent() throws Exception {
        WeblogPageRequest request = parse(null, "anchor", "hello-world");

        assertEquals("hello-world", request.getWeblogAnchor(),
                "The pre-permalink ?anchor= form must keep working for old links");
    }

    @Test
    void emptyEntryParameterLeavesTheAnchorUnsetInsteadOfBlank() throws Exception {
        // An empty anchor would send an empty string to
        // getWeblogEntryByAnchor(); leaving it null keeps the request a
        // homepage request.
        WeblogPageRequest request = parse(null, "entry", "");

        assertNull(request.getWeblogAnchor(),
                "An empty ?entry= must not become an empty anchor lookup");
    }

    @Test
    void categoryParameterIsIgnoredOnceAnAnchorIsKnown() throws Exception {
        // A permalink and a category listing are different pages; honouring
        // both would leave the request describing two views at once.
        WeblogPageRequest request = parse(null, "entry", "hello", "cat", "Java");

        assertEquals("hello", request.getWeblogAnchor());
        assertNull(request.getWeblogCategoryName(),
                "With an anchor present the category parameter must be ignored");
    }

    @Test
    void categoryParameterIsDecodedWhenNoAnchorIsPresent() throws Exception {
        WeblogPageRequest request = parse(null, "cat", "Java %26 Friends");

        assertEquals("Java & Friends", request.getWeblogCategoryName());
    }

    @Test
    void queryParametersAreIgnoredOnPathBasedViews() throws Exception {
        // Mixing path-style and query-style addressing would give two URLs for
        // the same content and make caching keys ambiguous, so the parser
        // deliberately ignores params once a path context is in play.
        WeblogPageRequest request = parse("/myblog/entry/from-path", "cat", "Java",
                "date", "20240115");

        assertEquals("from-path", request.getWeblogAnchor());
        assertNull(request.getWeblogCategoryName(),
                "?cat= must be ignored on a path-based permalink");
        assertNull(request.getWeblogDate(),
                "?date= must be ignored on a path-based permalink");
    }

    @Test
    void queryParametersAreHonouredOnCustomPagesForBackwardsCompatibility() throws Exception {
        WeblogPageRequest request = parse("/myblog/page/archive", "cat", "Java");

        assertEquals("archive", request.getWeblogPageName());
        assertEquals("Java", request.getWeblogCategoryName(),
                "Custom pages are the documented exception that still accepts query params");
    }

    // ------------------------------------------------------------- page index

    @Test
    void pageNumberIsReadFromTheQueryString() throws Exception {
        assertEquals(3, parse("/myblog", "page", "3").getPageNum());
    }

    @Test
    void pageNumberDefaultsToZeroWhenAbsent() throws Exception {
        assertEquals(0, parse("/myblog").getPageNum(),
                "No ?page= means the first page");
    }

    @Test
    void unparseablePageNumbersFallBackToTheFirstPage() throws Exception {
        // These are what crawlers and broken templates actually send. Each must
        // land on page 0 rather than throwing, because a bad pager index is not
        // a reason to refuse to render the weblog.
        assertEquals(0, parse("/myblog", "page", "abc").getPageNum(), "letters");
        assertEquals(0, parse("/myblog", "page", "").getPageNum(), "empty value");
        assertEquals(0, parse("/myblog", "page", "3.5").getPageNum(), "decimal");
        assertEquals(0, parse("/myblog", "page", "99999999999999999999").getPageNum(),
                "a value past Integer.MAX_VALUE must not overflow into a real page");
    }

    @Test
    void negativePageNumbersAreCarriedThroughForThePagerToClamp() throws Exception {
        // The parser does not clamp; AbstractWeblogEntriesPager does, with
        // Math.max(0, page). Pinning the split here means a change on either
        // side shows up as a failure rather than as a negative SQL offset.
        assertEquals(-5, parse("/myblog", "page", "-5").getPageNum(),
                "Negative page numbers reach the pager unchanged and are clamped there");
    }

    // ---------------------------------------------------------- custom params

    @Test
    void builtInParametersAreStrippedFromTheTemplateVisibleMap() throws Exception {
        WeblogPageRequest request = parse("/myblog/page/search",
                "entry", "e", "anchor", "a", "date", "20240115", "cat", "c",
                "page", "1", "tags", "java", "myOwnParam", "kept");

        assertEquals(List.of("myOwnParam"), List.copyOf(request.getCustomParams().keySet()),
                "customParams is what template authors see; leaking Pressroom's own "
                        + "parameters into it would let templates key off internals");
    }

    @Test
    void customParamsIsEmptyRatherThanNullWhenNoParametersWereSent() throws Exception {
        assertTrue(parse("/myblog").getCustomParams().isEmpty(),
                "Templates iterate this map, so it must never be null");
    }

    /** A path-style tag list such as "t0+t1+t2". */
    private static String tagPath(int count) {
        return String.join("+", tagNames(count));
    }

    /** A query-parameter tag list such as "t0 t1 t2". */
    private static String tagQuery(int count) {
        return String.join(" ", tagNames(count));
    }

    private static List<String> tagNames(int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> "t" + i)
                .toList();
    }

    // --------------------------------------------- edges found by coverage

    /**
     * A path of nothing but whitespace is the homepage, not a context named
     * " ". The blank check is separate from the null check and had never been
     * exercised.
     */
    @Test
    void aBlankPathIsTheHomepage() throws InvalidRequestException {
        WeblogPageRequest request = parse("/myblog/   ");

        assertNull(request.getContext(), "whitespace names no context");
        assertNull(request.getPageSlug(), "and is not taken for a page slug");
    }

    /**
     * "entry" present but empty suppresses "anchor" rather than falling through
     * to it: the parser commits to "entry" the moment it is present at all.
     * Worth pinning because the obvious simplification -- take the first
     * non-empty of the two -- quietly changes it.
     */
    @Test
    void anEmptyEntryParamDoesNotFallThroughToAnchor() throws InvalidRequestException {
        WeblogPageRequest request = parse(null, "entry", "", "anchor", "my-post");

        assertNull(request.getWeblogAnchor(),
                "entry was present, so anchor is never consulted, and entry was empty, "
                        + "so nothing is set");
    }

    @Test
    void anEmptyAnchorParamSetsNothing() throws InvalidRequestException {
        WeblogPageRequest request = parse(null, "anchor", "");

        assertNull(request.getWeblogAnchor(), "an empty anchor names no entry");
    }

    @Test
    void anAnchorParamIsUsedWhenEntryIsAbsent() throws InvalidRequestException {
        WeblogPageRequest request = parse(null, "anchor", "my-post");

        assertEquals("my-post", request.getWeblogAnchor());
    }

    // ------------------------------------------------------- date validity

    @Test
    void aSixDigitDateIsAMonth() throws InvalidRequestException {
        assertEquals("200511", parse("/myblog/date/200511").getWeblogDate());
    }

    @Test
    void anEightDigitDateIsADay() throws InvalidRequestException {
        assertEquals("20051110", parse("/myblog/date/20051110").getWeblogDate());
    }

    @Test
    void aDateOfAnyOtherLengthIsRejected() {
        assertThrows(InvalidRequestException.class, () -> parse("/myblog/date/2005"),
                "four digits is neither a month nor a day");
        assertThrows(InvalidRequestException.class, () -> parse("/myblog/date/2005111"),
                "and seven is neither either");
    }

    @Test
    void aNonNumericDateIsRejected() {
        assertThrows(InvalidRequestException.class, () -> parse("/myblog/date/2005XX"),
                "a date is all digits or it is not a date");
    }

    @Test
    void aDateQueryParamIsValidatedTheSameWayAsAPathDate() {
        assertThrows(InvalidRequestException.class, () -> parse(null, "date", "2005XX"),
                "the query-param route must not be a way around the path route's check");
    }
}

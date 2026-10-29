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
 * Unit tests for {@link WeblogFeedRequest}.
 *
 * <p>Feed URLs are the ones machines fetch on a schedule, so a parsing change
 * here breaks every subscriber at once and nobody reports it. The path form is
 * rigid -- exactly {@code /<type>/<format>}, both alphanumeric -- and the query
 * string carries the optional filters.
 */
class WeblogFeedRequestTest {

    private static final String FEED_SERVLET = "/roller-ui/rendering/feed";

    private static final int MAX_TAGS =
            WebloggerConfig.getIntProperty("tags.queries.maxIntersectionSize", 3);

    private static WeblogFeedRequest parse(String pathInfo, String... params)
            throws InvalidRequestException {
        return new WeblogFeedRequest(mock(Weblogger.class), MockRequest.with(FEED_SERVLET, pathInfo, params));
    }

    // ----------------------------------------------------------- destination

    @Test
    void requestAimedAtAnotherServletIsRejected() {
        assertThrows(InvalidRequestException.class,
                () -> new WeblogFeedRequest(mock(Weblogger.class), 
                        MockRequest.with("/roller-ui/rendering/page", "/myblog/entries/rss")),
                "A page URL must not parse as a feed request");
    }

    @Test
    void requestWithNoServletPathIsRejected() {
        assertThrows(InvalidRequestException.class,
                () -> new WeblogFeedRequest(mock(Weblogger.class), MockRequest.with(null, "/myblog/entries/rss")),
                "A null servlet path cannot match the feed servlet");
    }

    // ------------------------------------------------------------- path form

    @Test
    void typeAndFormatAreTakenFromTheTwoPathSegments() throws Exception {
        WeblogFeedRequest request = parse("/myblog/entries/rss");

        assertEquals("myblog", request.getWeblogHandle());
        assertEquals("entries", request.getType());
        assertEquals("rss", request.getFormat());
    }

    @Test
    void localeSegmentIsConsumedBeforeTypeAndFormat() throws Exception {
        // /myblog/en_US/entries/atom is a real published feed URL. If the locale
        // were not stripped first, the path would look like three segments and
        // the feed would 404 for every localised weblog.
        WeblogFeedRequest request = parse("/myblog/en_US/entries/atom");

        assertEquals("en_US", request.getLocale());
        assertEquals("entries", request.getType());
        assertEquals("atom", request.getFormat());
    }

    @Test
    void feedRequestWithoutAPathIsRejected() {
        assertThrows(InvalidRequestException.class, () -> parse("/myblog"),
                "A feed URL must name a type and a format");
    }

    @Test
    void singleCharacterPathIsRejected() {
        // The guard is `pathInfo.trim().length() > 1`; "x" sits exactly on it.
        assertThrows(InvalidRequestException.class, () -> parse("/myblog/x"),
                "A one-character remainder cannot contain both a type and a format");
    }

    @Test
    void tooFewOrTooManyPathSegmentsAreRejected() {
        assertThrows(InvalidRequestException.class, () -> parse("/myblog/entries"),
                "One segment is a type with no format");
        assertThrows(InvalidRequestException.class, () -> parse("/myblog/entries/rss/extra"),
                "Three segments is not a feed path");
    }

    @Test
    void nonAlphanumericTypeOrFormatIsRejected() {
        // Both halves are used to pick a template by name, so anything outside
        // [A-Za-z0-9] is refused before it gets near template resolution.
        assertThrows(InvalidRequestException.class, () -> parse("/myblog/ent-ries/rss"),
                "A hyphen makes the type non-alphanumeric");
        assertThrows(InvalidRequestException.class, () -> parse("/myblog/entries/r.ss"),
                "A dot makes the format non-alphanumeric");
        assertThrows(InvalidRequestException.class, () -> parse("/myblog/../rss"),
                "A traversal attempt must not reach template resolution");
    }

    // ------------------------------------------------------------ parameters

    @Test
    void categoryParameterKeepsALiteralPlusSign() throws Exception {
        // A category genuinely named "R+D" arrives as "R+D". Plain decoding
        // would turn the '+' into a space and quietly select a different (or
        // no) category, so the parser pre-escapes it first.
        WeblogFeedRequest request = parse("/myblog/entries/rss", "cat", "R+D");

        assertEquals("R+D", request.getWeblogCategoryName(),
                "A '+' in a category name must survive as '+', not become a space");
    }

    @Test
    void categoryParameterIsPercentDecoded() throws Exception {
        WeblogFeedRequest request = parse("/myblog/entries/rss", "cat", "Java%20Tips");

        assertEquals("Java Tips", request.getWeblogCategoryName());
    }

    @Test
    void malformedPercentEscapeInTheCategoryIsRejectedAsAnInvalidRequest() {
        assertThrows(InvalidRequestException.class,
                () -> parse("/myblog/entries/rss", "cat", "100%"),
                "A bare '%' must produce InvalidRequestException, not an unchecked "
                        + "IllegalArgumentException out of the constructor");
    }

    @Test
    void tagsParameterIsSplitOnWhitespace() throws Exception {
        WeblogFeedRequest request = parse("/myblog/entries/rss", "tags", "java testing");

        assertEquals(List.of("java", "testing"), request.getTags());
    }

    @Test
    void exactlyTheMaximumNumberOfTagsIsAccepted() throws Exception {
        WeblogFeedRequest request = parse("/myblog/entries/rss", "tags", tags(MAX_TAGS));

        assertEquals(MAX_TAGS, request.getTags().size(),
                "The configured ceiling of " + MAX_TAGS + " tags must itself be allowed");
    }

    @Test
    void oneTagOverTheMaximumIsRejected() {
        assertThrows(InvalidRequestException.class,
                () -> parse("/myblog/entries/rss", "tags", tags(MAX_TAGS + 1)),
                "One past the ceiling of " + MAX_TAGS + " must be rejected");
    }

    @Test
    void categoryAndTagsTogetherAreRejected() {
        // The two filters would have to be ANDed, which the entry query does not
        // support; refusing is better than silently honouring one of them.
        assertThrows(InvalidRequestException.class,
                () -> parse("/myblog/entries/rss", "cat", "Java", "tags", "testing"),
                "A feed may filter by category or by tags, never both");
    }

    @Test
    void emptyTagsParameterDoesNotConflictWithACategory() throws Exception {
        // "?tags=&cat=Java" is what an HTML form with an empty tag box submits.
        // It parses to zero tags, which must not trip the both-filters check.
        WeblogFeedRequest request = parse("/myblog/entries/rss", "tags", "", "cat", "Java");

        assertTrue(request.getTags().isEmpty());
        assertEquals("Java", request.getWeblogCategoryName(),
                "An empty tags parameter is no filter at all and must not veto the category");
    }

    @Test
    void excerptsFlagIsReadAsABoolean() throws Exception {
        assertTrue(parse("/myblog/entries/rss", "excerpts", "true").isExcerpts());
        assertFalse(parse("/myblog/entries/rss", "excerpts", "false").isExcerpts());
        assertFalse(parse("/myblog/entries/rss", "excerpts", "yes").isExcerpts(),
                "Only \"true\" enables excerpts; anything else means full content");
        assertFalse(parse("/myblog/entries/rss").isExcerpts(),
                "Feeds carry full content unless asked otherwise");
    }

    @Test
    void pageNumberIsReadAndBadValuesFallBackToTheFirstPage() throws Exception {
        assertEquals(2, parse("/myblog/entries/rss", "page", "2").getPage());
        assertEquals(0, parse("/myblog/entries/rss").getPage(), "absent means first page");
        assertEquals(0, parse("/myblog/entries/rss", "page", "abc").getPage(), "letters");
        assertEquals(0, parse("/myblog/entries/rss", "page", "99999999999999999999").getPage(),
                "a value past Integer.MAX_VALUE must not overflow into a real page");
    }

    @Test
    void searchTermIsDecodedAndBlankTermsAreIgnored() throws Exception {
        assertEquals("hello world",
                parse("/myblog/entries/rss", "q", "hello%20world").getTerm());
        assertNull(parse("/myblog/entries/rss", "q", "   ").getTerm(),
                "A whitespace-only query is not a search and must not become a term");
        assertNull(parse("/myblog/entries/rss").getTerm());
    }

    private static String tags(int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> "t" + i)
                .reduce((a, b) -> a + " " + b)
                .orElse("");
    }
}

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

import org.junit.jupiter.api.Test;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the URL helpers behind every link the URL strategy builds.
 *
 * <p>These decide what a permalink, a tag page and a feed URL look like, so
 * they are effectively a compatibility contract: change the encoding and
 * previously published links stop resolving.
 */
public class URLUtilitiesTest {

    @Test
    public void buildsAQueryStringWithTheFirstParameterAfterAQuestionMark() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("cat", "news");
        params.put("page", "2");
        assertEquals("?cat=news&page=2", URLUtilities.getQueryString(params));
    }

    @Test
    public void aSingleParameterStillGetsTheQuestionMark() {
        assertEquals("?cat=news", URLUtilities.getQueryString(Map.of("cat", "news")));
    }

    @Test
    public void anEmptyParameterMapProducesNoQueryStringAtAll() {
        // Appending a bare "?" to a permalink would create a second URL for
        // the same page, which search engines treat as duplicate content.
        assertEquals("", URLUtilities.getQueryString(Map.of()));
    }

    @Test
    public void aNullParameterMapGivesNullSoCallersCanSkipTheSuffix() {
        assertNull(URLUtilities.getQueryString(null));
    }

    /**
     * The value is encoded, and that is a correctness property before it is a
     * security one.
     *
     * <p>This method used to concatenate values raw, which meant any character
     * that is structural in a query string silently rewrote the query rather
     * than travelling inside it. Three separate corruptions, all invisible
     * until someone typed the wrong character into a filter box:
     *
     * <ul>
     *   <li>{@code &} starts the next parameter, so a search for {@code R&D}
     *       arrived as {@code bean.text=R} plus a stray parameter named
     *       {@code D}, and the list searched for "R".</li>
     *   <li>{@code #} starts the fragment, which the browser never sends, so
     *       EVERY parameter after it -- including the status filter -- was
     *       dropped on the way to the server.</li>
     *   <li>{@code +} is form encoding for a space, so a category named
     *       {@code R+D} came back as {@code R D} and matched nothing.</li>
     * </ul>
     *
     * <p>Escaping at the output site (fn:escapeXml on the href) does not fix
     * any of this: it turns {@code &} into {@code &amp;}, which the browser
     * decodes straight back to {@code &} before it ever builds the request.
     * That escape is still correct for the attribute context -- it is defence
     * against markup injection, not a repair for the parameter split.
     */
    @Test
    public void aValueCarryingQueryPunctuationSurvivesInsteadOfRewritingTheQuery() {
        String hostile = "R&D #1+x";

        String queryString = URLUtilities.getQueryString(Map.of("bean.text", hostile));

        assertTrue(queryString.startsWith("?bean.text="), queryString);
        String encodedValue = queryString.substring("?bean.text=".length());
        assertFalse(encodedValue.contains("&"), "a raw & splits the query: " + queryString);
        assertFalse(encodedValue.contains("#"), "a raw # truncates the query: " + queryString);
        assertEquals(hostile, URLDecoder.decode(encodedValue, StandardCharsets.UTF_8),
                "the value must decode back to exactly what the caller passed");
    }

    /**
     * Keys go through the same encoding. No key in this codebase needs it today
     * -- {@code URLEncoder} leaves letters, digits, {@code .}, {@code -} and
     * {@code _} alone, which covers every one of them, so {@code
     * bean.categoryName} is untouched -- but a map key is not always a literal
     * a developer chose, and the rule "everything structural is escaped" is
     * cheaper to keep than to re-derive.
     */
    @Test
    public void keysAreEncodedTooAndOrdinaryDottedKeysAreUnchangedByIt() {
        assertEquals("?bean.categoryName=news",
                URLUtilities.getQueryString(Map.of("bean.categoryName", "news")));
        assertEquals("?a%26b=c", URLUtilities.getQueryString(Map.of("a&b", "c")));
    }

    /**
     * A space is {@code +} rather than {@code %20}: this is form encoding, the
     * same convention {@link URLUtilities#encode} already used and the one the
     * servlet container decodes a query string with.
     */
    @Test
    public void aSpaceInAValueBecomesPlusTheWayFormEncodingRequires() {
        assertEquals("?cat=Cinque+Terre", URLUtilities.getQueryString(Map.of("cat", "Cinque Terre")));
    }

    @Test
    public void encodeUsesFormEncodingIncludingPlusForSpace() {
        // URLEncoder is form encoding, not path encoding: a space becomes '+'
        // rather than %20. Everything that consumes these URLs decodes them
        // the same way, so the pair is self-consistent -- but it is worth
        // pinning, because switching to %20 would silently change every tag
        // link Roller has ever published.
        assertEquals("a+b", URLUtilities.encode("a b"));
        assertEquals("a%26b", URLUtilities.encode("a&b"));
        assertEquals("a%2Fb", URLUtilities.encode("a/b"));
    }

    @Test
    public void encodeAndDecodeRoundTripNonAsciiText() {
        // Tags and category names are user text in any script; the UTF-8
        // round trip is what keeps a Cyrillic tag page reachable.
        String original = "café 中文 & more";
        assertEquals(original, URLUtilities.decode(URLUtilities.encode(original)));
    }

    @Test
    public void encodePathLeavesTheSlashesIntact() {
        // Encoding the separators would turn a folder path into one long
        // filename and break media file URLs.
        assertEquals("/a+b/c%26d", URLUtilities.encodePath("/a b/c&d"));
        assertEquals("plain", URLUtilities.encodePath("plain"));
        assertEquals("", URLUtilities.encodePath(""));
    }

    @Test
    public void encodePathKeepsTrailingAndDoubledSeparators() {
        assertEquals("a/", URLUtilities.encodePath("a/"));
        assertEquals("a//b", URLUtilities.encodePath("a//b"));
    }

    @Test
    public void encodedTagsAreJoinedWithPlusSigns() {
        // The tag URL syntax is /tags/foo+bar, so the join character is part
        // of the route and not just cosmetic.
        assertEquals("java+pressroom", URLUtilities.getEncodedTagsString(List.of("java", "pressroom")));
        assertEquals("java", URLUtilities.getEncodedTagsString(List.of("java")));
    }

    @Test
    public void tagsAreIndividuallyEncodedBeforeBeingJoined() {
        assertEquals("a+b+c%26d", URLUtilities.getEncodedTagsString(List.of("a b", "c&d")));
    }

    @Test
    public void noTagsMeansAnEmptyStringRatherThanNull() {
        // The result is concatenated straight into a URL.
        assertEquals("", URLUtilities.getEncodedTagsString(null));
        assertEquals("", URLUtilities.getEncodedTagsString(List.of()));
    }
}

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

package com.wikantik.pressroom.ui.controllers;

import java.util.List;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.WeblogEntryManager;
import com.wikantik.pressroom.business.WeblogPageManager;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogPage;
import com.wikantik.pressroom.ui.controllers.RuleViolation.Kind;
import com.wikantik.pressroom.ui.restapi.ProblemCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** {@link PageRules}: each slug and strand rule with no controller in the test. */
class PageRulesTest {

    private Weblog weblog;
    private WeblogPageManager pages;
    private WeblogEntryManager entries;

    @BeforeEach
    void setUp() {
        weblog = new Weblog();
        weblog.setHandle("myblog");
        pages = mock(WeblogPageManager.class);
        entries = mock(WeblogEntryManager.class);
    }

    private static void is(RuleViolation v, String key, ProblemCode code, Kind kind, String... codeArgs) {
        assertEquals("slug", v.field());
        assertEquals(key, v.messageKey());
        assertEquals(code, v.code());
        assertEquals(kind, v.kind());
        assertEquals(List.of(codeArgs), v.codeArgs());
    }

    private WeblogPage page(String id, WeblogPage.PubStatus status) {
        WeblogPage page = new WeblogPage();
        page.setId(id);
        page.setStatus(status);
        return page;
    }

    // --- shape ---

    @Test
    void aGoodSlugPasses() {
        assertNull(PageRules.slugShapeProblem("about"));
        assertNull(PageRules.slugShapeProblem("  about  "));
        assertNull(PageRules.slugShapeProblem("s".repeat(255)));
    }

    @Test
    void aBlankOrMissingSlugIsTheReservedCodeWordedInvalidOnScreen() {
        is(PageRules.slugShapeProblem("   "), "pageEdit.error.slugInvalid", ProblemCode.SLUG_RESERVED,
                Kind.INVALID, "   ");
        is(PageRules.slugShapeProblem(null), "pageEdit.error.slugInvalid", ProblemCode.SLUG_RESERVED,
                Kind.INVALID, "null");
    }

    @Test
    void aReservedNameOrALocaleIsReserved() {
        is(PageRules.slugShapeProblem("Feed"), "pageEdit.error.slugReserved", ProblemCode.SLUG_RESERVED,
                Kind.INVALID, "Feed");
        is(PageRules.slugShapeProblem("en_US"), "pageEdit.error.slugReserved", ProblemCode.SLUG_RESERVED,
                Kind.INVALID, "en_US");
    }

    @Test
    void aSlashIsRefusedQuotingTheSlugAsSent() {
        is(PageRules.slugShapeProblem(" a/b "), "pageEdit.error.slugInvalid", ProblemCode.SLUG_HAS_SLASH,
                Kind.INVALID, " a/b ");
    }

    @Test
    void aSlugOverItsColumnIsTooLongOnceTrimmed() {
        RuleViolation tooLong = PageRules.slugShapeProblem("s".repeat(256));
        is(tooLong, "pageEdit.error.slugTooLong", ProblemCode.FIELD_TOO_LONG, Kind.INVALID, "slug", "255");
        // The screen's text takes the limit from the column width, not a literal 255 in the bundle (0.16.3).
        assertEquals(List.of(ColumnWidths.PAGE_SLUG), tooLong.args());
        assertNull(PageRules.slugShapeProblem(" " + "s".repeat(255) + " "));
    }

    @Test
    void aNewPageWithNoSlugTakesItsTitles() {
        assertEquals("about-us", PageRules.defaultSlug("About Us"));
        assertEquals("", PageRules.defaultSlug("!!!"));
        assertEquals("", PageRules.defaultSlug(null));
    }

    // --- holders ---

    @Test
    void aSlugNobodyHoldsIsFree() throws Exception {
        assertNull(PageRules.slugHolderProblem(weblog, "about", null, pages));
    }

    @Test
    void aLivePageHoldingTheSlugIsTaken() throws Exception {
        when(pages.getPageBySlug(weblog, "about")).thenReturn(page("p9", WeblogPage.PubStatus.PUBLISHED));
        is(PageRules.slugHolderProblem(weblog, "about", "p1", pages), "pageEdit.error.slugTaken",
                ProblemCode.SLUG_IN_USE, Kind.CONFLICT, "about");
    }

    @Test
    void aTrashedPageHoldingTheSlugSaysSo() throws Exception {
        when(pages.getPageBySlug(weblog, "about")).thenReturn(page("t1", WeblogPage.PubStatus.TRASHED));
        is(PageRules.slugHolderProblem(weblog, "about", null, pages), "pageEdit.error.slugInTrash",
                ProblemCode.SLUG_HELD_BY_TRASH, Kind.CONFLICT, "about");
    }

    @Test
    void thePageItselfHoldingItsSlugIsNotAClash() throws Exception {
        when(pages.getPageBySlug(weblog, "about")).thenReturn(page("p1", WeblogPage.PubStatus.PUBLISHED));
        assertNull(PageRules.slugHolderProblem(weblog, "about", "p1", pages));
    }

    @Test
    void aFailedLookupThrowsFromTheHolderRule() throws Exception {
        when(pages.getPageBySlug(weblog, "about")).thenThrow(new WebloggerException("down"));
        assertThrows(WebloggerException.class, () -> PageRules.slugHolderProblem(weblog, "about", null, pages));
    }

    // --- the screens' composite ---

    @Test
    void theCompositeJudgesShapeFirstAndLooksNothingUpForABadSlug() throws Exception {
        is(PageRules.slugProblem(weblog, "a/b", null, pages), "pageEdit.error.slugInvalid",
                ProblemCode.SLUG_HAS_SLASH, Kind.INVALID, "a/b");
        verify(pages, never()).getPageBySlug(weblog, "a/b");
    }

    @Test
    void theCompositeLooksTheTrimmedSlugUp() throws Exception {
        when(pages.getPageBySlug(weblog, "about")).thenReturn(page("p9", WeblogPage.PubStatus.PUBLISHED));
        is(PageRules.slugProblem(weblog, " about ", null, pages), "pageEdit.error.slugTaken",
                ProblemCode.SLUG_IN_USE, Kind.CONFLICT, "about");
        assertNull(PageRules.slugProblem(weblog, "free", null, pages));
    }

    /** No violation stands in for "could not check": the screen refuses the save itself. */
    @Test
    void theCompositeThrowsWhenTheLookupFails() throws Exception {
        when(pages.getPageBySlug(weblog, "about")).thenThrow(new WebloggerException("down"));
        assertThrows(WebloggerException.class, () -> PageRules.slugProblem(weblog, "about", null, pages));
    }

    // --- strands ---

    private static void isStrand(RuleViolation v, String key, ProblemCode code, List<?> args, String... codeArgs) {
        assertEquals("strand", v.field());
        assertEquals(key, v.messageKey());
        assertEquals(code, v.code());
        assertEquals(Kind.INVALID, v.kind());
        assertEquals(args, v.args());
        assertEquals(List.of(codeArgs), v.codeArgs());
    }

    @Test
    void aSitePageCannotBeAStrandButMayStayPlain() throws Exception {
        weblog.setSiteSpace(true);
        isStrand(PageRules.strandProblem(weblog, null, true, entries), "pageEdit.error.strandSitePage",
                ProblemCode.STRAND_SITE_PAGE, List.of());
        assertNull(PageRules.strandProblem(weblog, null, false, entries));
        assertNull(PageRules.strandProblem(weblog, null, null, entries));
    }

    @Test
    void aStrandWithPartsCannotBeUnset() throws Exception {
        WeblogPage strand = page("p1", WeblogPage.PubStatus.PUBLISHED);
        strand.setStrand(true);
        when(entries.getStrandPartCount(strand)).thenReturn(3L);
        isStrand(PageRules.strandProblem(weblog, strand, false, entries), "pageEdit.error.strandHasParts",
                ProblemCode.STRAND_HAS_PARTS, List.of(3L), "3", "{id}");
    }

    @Test
    void aStrandWithNoPartsAndEveryOtherCombinationPass() throws Exception {
        WeblogPage strand = page("p1", WeblogPage.PubStatus.PUBLISHED);
        strand.setStrand(true);
        when(entries.getStrandPartCount(strand)).thenReturn(0L);
        assertNull(PageRules.strandProblem(weblog, strand, false, entries));
        assertNull(PageRules.strandProblem(weblog, strand, true, entries));
        assertNull(PageRules.strandProblem(weblog, strand, null, entries));
        // A page that is not a strand has no parts to count, and neither has one not yet created.
        WeblogPage plain = page("p2", WeblogPage.PubStatus.PUBLISHED);
        assertNull(PageRules.strandProblem(weblog, plain, false, entries));
        assertNull(PageRules.strandProblem(weblog, null, false, entries));
        verify(entries, never()).getStrandPartCount(plain);
    }

    @Test
    void aPartCountThatCannotBeReadThrows() throws Exception {
        WeblogPage strand = page("p1", WeblogPage.PubStatus.PUBLISHED);
        strand.setStrand(true);
        when(entries.getStrandPartCount(strand)).thenThrow(new WebloggerException("down"));
        assertThrows(WebloggerException.class, () -> PageRules.strandProblem(weblog, strand, false, entries));
    }
}

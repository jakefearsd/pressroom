package com.wikantik.pressroom.business.search.lucene;

import org.apache.lucene.index.Term;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Handle and locale are keyword fields indexed verbatim, so their query term
 * must not pass through the (stemming, stop-word) text analyzer: with
 * EnglishAnalyzer a handle "searchother" became "searchoth" and a handle
 * "the" vanished, which dropped the weblog filter altogether.
 */
class IndexUtilTest {

    @Test
    void keywordTermsAreNotStemmedOrDropped() {
        assertEquals(new Term("handle", "searchother"), IndexUtil.handleTerm("searchother"));
        assertEquals(new Term("handle", "the"), IndexUtil.handleTerm("the"));
        assertEquals(new Term("locale", "en_us"), IndexUtil.getTerm("locale", "en_US"));
    }

    /** Handles are case-sensitive (two blogs may differ only by case), so the term keeps the case. */
    @Test
    void aHandleTermKeepsItsCase() {
        assertEquals(new Term("handle", "MixedCase"), IndexUtil.handleTerm(" MixedCase "));
        assertNull(IndexUtil.handleTerm("  "));
        assertNull(IndexUtil.handleTerm(null));
    }

    @Test
    void nothingToMatchGivesNoTerm() {
        assertNull(IndexUtil.getTerm("locale", null));
        assertNull(IndexUtil.getTerm(null, "x"));
        assertNull(IndexUtil.getTerm("locale", "  "));
    }
}

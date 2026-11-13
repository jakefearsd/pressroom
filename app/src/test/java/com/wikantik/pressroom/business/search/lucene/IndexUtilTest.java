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
        assertEquals(new Term("handle", "searchother"), IndexUtil.getTerm("handle", "searchother"));
        assertEquals(new Term("handle", "the"), IndexUtil.getTerm("handle", "the"));
        assertEquals(new Term("locale", "en_us"), IndexUtil.getTerm("locale", "en_US"));
    }

    @Test
    void nothingToMatchGivesNoTerm() {
        assertNull(IndexUtil.getTerm("handle", null));
        assertNull(IndexUtil.getTerm(null, "x"));
        assertNull(IndexUtil.getTerm("handle", "  "));
    }
}

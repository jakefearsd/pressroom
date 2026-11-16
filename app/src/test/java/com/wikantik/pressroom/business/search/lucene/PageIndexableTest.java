package com.wikantik.pressroom.business.search.lucene;

import com.wikantik.pressroom.pojos.WeblogPage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which pages belong in the search index. Characterisation: the rule names
 * PUBLISHED, so it held for TRASHED the moment that status existed; pinned so
 * a later status-by-exclusion rewrite cannot index the trash.
 */
class PageIndexableTest {

    private static WeblogPage page(WeblogPage.PubStatus status, boolean noindex) {
        WeblogPage page = new WeblogPage();
        page.setStatus(status);
        page.setNoindex(noindex);
        return page;
    }

    @Test
    void onlyAPublishedPageWithoutNoindexIsIndexable() {
        assertTrue(IndexOperation.isIndexable(page(WeblogPage.PubStatus.PUBLISHED, false)));
        assertFalse(IndexOperation.isIndexable(page(WeblogPage.PubStatus.PUBLISHED, true)));
        assertFalse(IndexOperation.isIndexable(page(WeblogPage.PubStatus.DRAFT, false)));
        assertFalse(IndexOperation.isIndexable(page(WeblogPage.PubStatus.TRASHED, false)));
        assertFalse(IndexOperation.isIndexable(null));
    }
}

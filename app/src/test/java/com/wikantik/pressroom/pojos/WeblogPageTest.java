package com.wikantik.pressroom.pojos;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A page renders through the same pipeline an entry does, and satisfies the
 * same ShortcodeContext, so every shortcode works on it.
 */
class WeblogPageTest {

    private static WeblogPage page(String content) {
        Weblog weblog = new Weblog();
        weblog.setHandle("maiia");

        WeblogPage page = new WeblogPage();
        page.setWeblog(weblog);
        page.setSlug("about");
        page.setTitle("About");
        page.setContent(content);
        return page;
    }

    @Test
    void aPageIsAShortcodeContext() {
        WeblogPage page = page("hello");

        assertEquals("maiia", page.getWeblog().getHandle());
        assertEquals("about", page.getSlug(), "a page's slug is its slug");
        assertEquals("hello", page.getRawText());
    }

    @Test
    void aNewPageStartsAsADraft() {
        assertEquals(WeblogPage.PubStatus.DRAFT, new WeblogPage().getStatus(),
                "publishing must be a deliberate act");
    }

    @Test
    void aNewPageShowsInNavByDefault() {
        assertTrue(new WeblogPage().getShowInNav());
    }

    @Test
    void aNewPageIsNotAStrandAndNeverReadsNull() {
        WeblogPage page = new WeblogPage();
        assertEquals(Boolean.FALSE, page.getStrand());
        page.setStrand(Boolean.TRUE);
        assertEquals(Boolean.TRUE, page.getStrand());
        page.setStrand(null);
        assertEquals(Boolean.FALSE, page.getStrand(), "null reads as not a strand");
    }

    @Test
    void aSlugFromATitleIsItsLowerCasedWordsJoinedByHyphens() {
        assertEquals("validation-alpha", WeblogPage.slugFromTitle("Validation Alpha"));
        assertEquals("maiia-s-bio", WeblogPage.slugFromTitle("  Maiia's   Bio! "));
        assertEquals("über-uns-2026", WeblogPage.slugFromTitle("Über uns (2026)"));
    }

    @Test
    void aTitleWithNoLetterOrDigitGivesNoSlug() {
        assertEquals("", WeblogPage.slugFromTitle(null));
        assertEquals("", WeblogPage.slugFromTitle("   "));
        assertEquals("", WeblogPage.slugFromTitle("?!/ --"));
    }
}

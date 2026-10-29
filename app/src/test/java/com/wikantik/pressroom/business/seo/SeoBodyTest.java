package com.wikantik.pressroom.business.seo;

import java.util.List;
import java.util.Map;
import java.util.Set;
import com.wikantik.pressroom.business.MarkdownRenderer;
import com.wikantik.pressroom.business.seo.EntrySeoChecks.Finding;
import com.wikantik.pressroom.util.HTMLSanitizer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The body checks of spec 2026-10-24 section 6 (acceptance criterion 7),
 * over Markdown rendered and sanitized the way an entry's body is, so the
 * heading ids are the ones {@code HeadingAnchorExtension} really writes.
 */
class SeoBodyTest {

    private static final String RELATIVE = "/coast/entry/";
    private static final String ABSOLUTE = "https://blog.example.com/coast/entry/";
    private static final SeoBody.Site SITE = new SeoBody.Site(List.of(RELATIVE, ABSOLUTE), Set.of("ferry"),
            Map.of("https://blog.example.com/coast/mediaresource/m1", "IMG_2034-sunset.jpg"));

    private static final String CLEAN = """
            Opening paragraph.

            ## Route

            See [the route](#route), [the ferry](/coast/entry/ferry) and
            [the ferry again](https://blog.example.com/coast/entry/ferry?utm_source=x#times).
            Elsewhere: [another blog](https://elsewhere.example/entry/nope), [top](#top), [here](#).

            ![A ferry leaving at dawn](https://images.example/ferry.jpg)

            <img src="https://images.example/rule.png" alt="">

            ### Times

            ## Fares
            """;

    private static String render(String markdown) {
        return HTMLSanitizer.sanitize(MarkdownRenderer.render(markdown));
    }

    private static List<Finding> findings(String markdown) {
        return SeoBody.of(render(markdown)).findings(SITE);
    }

    private static List<String> codes(List<Finding> findings) {
        return findings.stream().map(Finding::code).toList();
    }

    private static Finding only(List<Finding> findings, String code) {
        List<Finding> matching = findings.stream().filter(f -> f.code().equals(code)).toList();
        assertEquals(1, matching.size(), code + " in " + findings);
        return matching.get(0);
    }

    @Test
    void aCleanBodyHasNoFindings() {
        assertEquals(List.of(), findings(CLEAN));
    }

    @Test
    void anEmptyOrMissingBodyHasNoFindings() {
        assertEquals(List.of(), SeoBody.of("").findings(SITE));
        assertEquals(List.of(), SeoBody.of(null).findings(SITE));
    }

    @Test
    void anH1InTheBodyIsAWarningNamingIt() {
        Finding found = only(findings("# Welcome aboard\n\nText.\n"), "body_h1");

        assertEquals(EntrySeoChecks.WARNING, found.severity());
        assertTrue(found.message().contains("Welcome aboard"), found.message());
        assertNull(found.value());
        assertNull(found.limit());
    }

    @Test
    void aHeadingTwoLevelsBelowThePreviousIsASkipAtInfo() {
        Finding found = only(findings("## Route\n\n#### Detail\n"), "heading_skip");

        assertEquals(EntrySeoChecks.INFO, found.severity());
        assertTrue(found.message().contains("Route") && found.message().contains("Detail"), found.message());
    }

    /** The theme prints the entry title as the h1, so a body opening at h3 skips h2. */
    @Test
    void aBodyWhoseFirstHeadingIsAnH3SkipsALevelBelowTheTitle() {
        assertEquals(List.of("heading_skip"), codes(findings("### Detail\n")));
    }

    @Test
    void goingBackUpAnyNumberOfLevelsIsNoSkip() {
        assertEquals(List.of(), codes(findings("## A\n\n### B\n\n#### C\n\n## D\n")));
    }

    @Test
    void anImageWithNoAltAttributeIsMissingAlt() {
        Finding found = only(findings("<img src=\"https://images.example/a.png\">\n"), "image_alt_missing");

        assertEquals(EntrySeoChecks.WARNING, found.severity());
        assertTrue(found.message().contains("https://images.example/a.png"), found.message());
    }

    @Test
    void anImageWhoseAltIsItsFileNameIsMissingAlt() {
        assertEquals(List.of("image_alt_missing"),
                codes(findings("![IMG_2034.jpg](https://images.example/photos/IMG_2034.jpg)\n")));
    }

    /**
     * The [image] shortcode, with no alt text and no caption, falls back to
     * the media file's name read as words; its src carries only the id, so
     * the name comes from the site.
     */
    @Test
    void aMediaFileWhoseAltIsItsFileNameAsWordsIsMissingAlt() {
        String html = "<figure><img src=\"https://blog.example.com/coast/mediaresource/m1?w=640\""
                + " alt=\"IMG 2034 sunset\"></figure>";
        SeoBody.Site site = new SeoBody.Site(List.of(), Set.of(),
                Map.of("https://blog.example.com/coast/mediaresource/m1?w=640", "IMG_2034-sunset.jpg"));

        assertEquals(List.of("image_alt_missing"), codes(SeoBody.of(html).findings(site)));
    }

    @Test
    void severalImagesMissingAltAreOneFindingNamingEach() {
        Finding found = only(findings("<img src=\"/a.png\"> <img src=\"/b.png\">\n"), "image_alt_missing");

        assertTrue(found.message().startsWith("2 images"), found.message());
        assertTrue(found.message().contains("/a.png") && found.message().contains("/b.png"), found.message());
    }

    @Test
    void aFragmentLinkToNoIdInTheBodyIsBroken() {
        Finding found = only(findings("## Route\n\nSee [fares](#fares).\n"), "fragment_link_broken");

        assertEquals(EntrySeoChecks.WARNING, found.severity());
        assertTrue(found.message().contains("#fares"), found.message());
    }

    @Test
    void aFragmentLinkMatchesAnIdOnAnyElementEvenPercentEncoded() {
        assertEquals(List.of(), codes(findings("<span id=\"café\">x</span> [here](#caf%C3%A9)\n")));
    }

    @Test
    void aLinkToAnUnpublishedEntryOfThisWeblogIsBrokenRelativeOrAbsolute() {
        Finding relative = only(findings("[draft](/coast/entry/draft-post)\n"), "entry_link_broken");
        assertEquals(EntrySeoChecks.WARNING, relative.severity());
        assertTrue(relative.message().contains("/coast/entry/draft-post"), relative.message());

        assertEquals(List.of("entry_link_broken"),
                codes(findings("[gone](https://blog.example.com/coast/entry/gone/)\n")));
    }

    @Test
    void entryAnchorsAreThoseOfLinksToThisWeblogsEntries() {
        SeoBody body = SeoBody.of(render(CLEAN + "\n[d](/coast/entry/draft%20post)\n"));

        assertEquals(Set.of("ferry", "draft post"), body.entryAnchors(List.of(RELATIVE, ABSOLUTE)));
    }

    @Test
    void imageSourcesAreEveryImgSrcInOrder() {
        SeoBody body = SeoBody.of("<img src=\"/a.png\" alt=\"a\"><p><img src=\"/b.png\"></p>");

        assertEquals(List.of("/a.png", "/b.png"), body.imageSources());
    }
}

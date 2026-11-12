package com.wikantik.pressroom.business.seo;

import java.util.List;
import com.wikantik.pressroom.business.seo.EntrySeoChecks.Finding;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The field checks of spec 2026-10-24 section 6 (acceptance criterion 7):
 * each code fires on an entry built to trip it, and none on a clean entry.
 * Lengths come from {@link SeoLimits}, never from literals, so a limit
 * moved there moves these tests with it.
 */
class EntrySeoChecksTest {

    /** Between DESCRIPTION_MIN and DESCRIPTION_MAX characters. */
    private static final String GOOD_DESCRIPTION = "x".repeat(SeoLimits.DESCRIPTION_MIN + 10);

    private static Weblog weblog() {
        Weblog weblog = new Weblog();
        weblog.setId("w1");
        weblog.setHandle("coast");
        weblog.setName("Coast Notes");
        return weblog;
    }

    /** Every field set, every length inside its limits. */
    private static WeblogEntry clean(String id, String title) {
        WeblogEntry entry = new WeblogEntry();
        entry.setId(id);
        entry.setAnchor(id);
        entry.setTitle(title);
        entry.setMetaTitle(title + " at dawn");
        entry.setSearchDescription(GOOD_DESCRIPTION + " " + id);
        entry.setFeaturedImageId("m-" + id);
        entry.setNoindex(Boolean.FALSE);
        return entry;
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
    void aCleanEntryHasNoFieldFindings() {
        assertEquals(List.of(), EntrySeoChecks.fields(clean("e1", "Harbour walk"), weblog()));
    }

    @Test
    void anEntryWithNothingSetHasTodaysFourGapsUnderTheirOldNames() {
        List<Finding> gaps = EntrySeoChecks.gaps(new WeblogEntry());

        assertEquals(List.of("missing_search_description", "missing_meta_title", "missing_featured_image"),
                codes(gaps));
        gaps.forEach(f -> assertEquals(f.code().equals("missing_featured_image")
                ? EntrySeoChecks.INFO : EntrySeoChecks.WARNING, f.severity(), f.code()));
        gaps.forEach(f -> assertFalse(f.message().isBlank(), f.code()));
    }

    /**
     * A featured image is optional (Jake, 2026-10-31): a writing-first
     * weblog's post is complete without one, and the warning nudged the first
     * agent post into setting one it then also put in the text.
     */
    @Test
    void aMissingFeaturedImageIsOnlyANote() {
        Finding found = EntrySeoChecks.gaps(new WeblogEntry()).stream()
                .filter(f -> f.code().equals("missing_featured_image")).findFirst().orElseThrow();

        assertEquals(EntrySeoChecks.INFO, found.severity());
    }

    /**
     * But a post whose text shows a picture and that has no featured image is
     * a warning (Jake, 2026-11-07): the front page and every list page show
     * a post's featured image or a lettered tile, so eight agent posts that
     * hotlinked their pictures went out as tiles. The message names the fix,
     * and on a hero theme it also says to take the picture out of the text.
     */
    @Test
    void aMissingFeaturedImageIsAWarningWhenTheTextShowsAPicture() {
        for (String text : List.of(
                "<p>Intro.</p><img src=\"https://raw.githubusercontent.com/a/b/x.jpg\" alt=\"Ruin\">",
                "Intro.\n\n![Ruin](https://raw.githubusercontent.com/a/b/x.jpg)\n",
                "Intro.\n\n[image id=\"0c0f9e40-0000-4000-8000-000000000000\" alt=\"Ruin\"]\n")) {
            WeblogEntry entry = new WeblogEntry();
            entry.setText(text);

            Finding found = only(EntrySeoChecks.gaps(entry), "missing_featured_image");

            assertEquals(EntrySeoChecks.WARNING, found.severity(), text);
            assertTrue(found.message().contains("featuredImageUrl"), found.message());
            // Spec 2026-11-07: the picture stays in the text (no hero repeats
            // it now), and the usual cause is an import the write opted out of.
            assertFalse(found.message().contains("take it out of the text"), found.message());
            assertTrue(found.message().contains("importExternalImages: false"), found.message());
        }
    }

    @Test
    void aPictureOnlyInACodeSpanIsNotAPicture() {
        WeblogEntry entry = new WeblogEntry();
        entry.setText("Write `![alt](url)` for an image, or `<img src=\"x\">`.");

        assertEquals(EntrySeoChecks.INFO, only(EntrySeoChecks.gaps(entry), "missing_featured_image").severity());
    }

    @Test
    void noindexIsAWarningOfItsOwn() {
        WeblogEntry entry = clean("e1", "Harbour walk");
        entry.setNoindex(Boolean.TRUE);

        Finding noindex = only(EntrySeoChecks.fields(entry, weblog()), "noindex");
        assertEquals(EntrySeoChecks.WARNING, noindex.severity());
        assertEquals(List.of("noindex"), codes(EntrySeoChecks.fields(entry, weblog())));
    }

    /** A blank value is missing, and missing is reported once: not also as short. */
    @Test
    void aWhitespaceOnlyDescriptionIsMissingAndNotAlsoShort() {
        WeblogEntry entry = clean("e1", "Harbour walk");
        entry.setSearchDescription("   ");

        assertEquals(List.of("missing_search_description"), codes(EntrySeoChecks.fields(entry, weblog())));
    }

    @Test
    void aDescriptionOverTheMaximumIsLongWithItsLengthAndTheLimit() {
        WeblogEntry entry = clean("e1", "Harbour walk");
        entry.setSearchDescription("y".repeat(SeoLimits.DESCRIPTION_MAX + 1));

        Finding found = only(EntrySeoChecks.fields(entry, weblog()), "search_description_long");
        assertEquals(EntrySeoChecks.WARNING, found.severity());
        assertEquals(SeoLimits.DESCRIPTION_MAX + 1, found.value());
        assertEquals(SeoLimits.DESCRIPTION_MAX, found.limit());
        assertTrue(found.message().contains(String.valueOf(SeoLimits.DESCRIPTION_MAX)), found.message());
    }

    @Test
    void aDescriptionAtTheMaximumIsNotLong() {
        WeblogEntry entry = clean("e1", "Harbour walk");
        entry.setSearchDescription("y".repeat(SeoLimits.DESCRIPTION_MAX));

        assertEquals(List.of(), EntrySeoChecks.fields(entry, weblog()));
    }

    @Test
    void aDescriptionUnderTheMinimumIsShortAtInfoSeverity() {
        WeblogEntry entry = clean("e1", "Harbour walk");
        entry.setSearchDescription("y".repeat(SeoLimits.DESCRIPTION_MIN - 1));

        Finding found = only(EntrySeoChecks.fields(entry, weblog()), "search_description_short");
        assertEquals(EntrySeoChecks.INFO, found.severity());
        assertEquals(SeoLimits.DESCRIPTION_MIN - 1, found.value());
        assertEquals(SeoLimits.DESCRIPTION_MIN, found.limit());
    }

    /**
     * A description that is the post's own opening cut off mid-sentence reads
     * as broken in a search result; the audit says so, so an agent or the
     * author can write one that stands on its own.
     */
    @Test
    void aDescriptionCutFromTheOpeningMidSentenceIsAWarning() {
        WeblogEntry entry = clean("e1", "Harbour walk");
        entry.setText("The harbour wall was built in 1850 to shelter the fishing fleet from winter storms. "
                + "It still does.");
        entry.setSearchDescription("The harbour wall was built in 1850 to shelter the fishing fleet from.");

        Finding found = only(EntrySeoChecks.fields(entry, weblog()), "search_description_cut");
        assertEquals(EntrySeoChecks.WARNING, found.severity());

        entry.setSearchDescription("The harbour wall was built in 1850 to shelter the fishing fleet from winter "
                + "storms.");
        assertEquals(List.of(), EntrySeoChecks.fields(entry, weblog()), "a whole sentence is fine");
    }

    /**
     * A meta title that is the entry title cut off mid-word (seen on
     * blog.wikantik.com: "...Pasture-Raised Meats and Placer Citr") shows in
     * the browser tab and the search result as a broken word.
     */
    @Test
    void aMetaTitleCutFromTheTitleMidWordIsAWarning() {
        WeblogEntry entry = clean("e1", "Newcastle Produce and Placer Citrus");
        entry.setMetaTitle("Newcastle Produce and Placer Citr");

        Finding found = only(EntrySeoChecks.fields(entry, weblog()), "meta_title_cut");
        assertEquals(EntrySeoChecks.WARNING, found.severity());

        entry.setMetaTitle("Newcastle Produce and Placer");
        assertEquals(List.of(), codes(EntrySeoChecks.fields(entry, weblog())).stream()
                .filter("meta_title_cut"::equals).toList(), "cut at a word is a shorter title, not a broken one");
        entry.setTitle("Newcastle Produce &amp; Placer Citrus");
        entry.setMetaTitle("Newcastle Produce & Placer Cit");
        assertEquals(1, codes(EntrySeoChecks.fields(entry, weblog())).stream()
                .filter("meta_title_cut"::equals).count(), "compared with the title as readers see it");
    }

    @Test
    void aDescriptionAtTheMinimumIsNotShort() {
        WeblogEntry entry = clean("e1", "Harbour walk");
        entry.setSearchDescription("y".repeat(SeoLimits.DESCRIPTION_MIN));

        assertEquals(List.of(), EntrySeoChecks.fields(entry, weblog()));
    }

    /** Characters are what a reader counts: an emoji is one, not two UTF-16 units. */
    @Test
    void lengthsCountCharactersNotUtf16Units() {
        WeblogEntry entry = clean("e1", "Harbour walk");
        entry.setSearchDescription("🌊".repeat(SeoLimits.DESCRIPTION_MIN));

        assertEquals(List.of(), EntrySeoChecks.fields(entry, weblog()));
    }

    @Test
    void aLongMetaTitleIsALongPageTitle() {
        WeblogEntry entry = clean("e1", "Harbour walk");
        entry.setMetaTitle("t".repeat(SeoLimits.TITLE_MAX + 1));

        Finding found = only(EntrySeoChecks.fields(entry, weblog()), "title_long");
        assertEquals(EntrySeoChecks.WARNING, found.severity());
        assertEquals(SeoLimits.TITLE_MAX + 1, found.value());
        assertEquals(SeoLimits.TITLE_MAX, found.limit());
    }

    @Test
    void aMetaTitleAtTheMaximumIsNotLong() {
        WeblogEntry entry = clean("e1", "Harbour walk");
        entry.setMetaTitle("t".repeat(SeoLimits.TITLE_MAX));

        assertEquals(List.of(), EntrySeoChecks.fields(entry, weblog()));
    }

    /**
     * Without a meta title the themes print "title — weblog name", and the
     * stored title is HTML-escaped: "Fish &amp; Chips" is nine characters of
     * title, not thirteen.
     */
    @Test
    void withoutAMetaTitleThePageTitleIsTheUnescapedTitleAndTheWeblogName() {
        WeblogEntry entry = clean("e1", "Fish &amp; Chips");
        entry.setMetaTitle(null);

        assertEquals("Fish & Chips — Coast Notes", EntrySeoChecks.pageTitle(entry, weblog()));
    }

    /**
     * The themes add " — weblog name" only when the whole fits in what a
     * search result shows (UtilitiesModel#titleWithName, 0.8.10), so the
     * checks count the title the reader is shown: a title that fits alone
     * is never "too long" because of the name.
     */
    @Test
    void theWeblogNameIsDroppedRatherThanMakingTheTitleTooLong() {
        Weblog weblog = weblog();
        WeblogEntry entry = clean("e1", "t".repeat(SeoLimits.TITLE_MAX - " — ".length() - weblog.getName().length()));
        entry.setMetaTitle(null);
        assertEquals(entry.getTitle() + " — Coast Notes", EntrySeoChecks.pageTitle(entry, weblog));
        assertEquals(List.of(), codes(EntrySeoChecks.fields(entry, weblog)));

        entry.setTitle(entry.getTitle() + "t");
        assertEquals(entry.getTitle(), EntrySeoChecks.pageTitle(entry, weblog), "the name no longer fits");
        assertEquals(List.of(), codes(EntrySeoChecks.fields(entry, weblog)));
    }

    /**
     * A title that fits needs no meta title. The warning used to fire on
     * every entry without one, and agents told to publish with no warnings
     * silenced it by copying the title in, or the title cut at 100
     * characters (Bing site scan, 2026-11-11: 94 titles too long).
     */
    @Test
    void onlyATitleTooLongForASearchResultNeedsAMetaTitle() {
        Weblog weblog = weblog();
        WeblogEntry entry = clean("e1", "t".repeat(SeoLimits.TITLE_MAX));
        entry.setMetaTitle(null);
        assertEquals(List.of(), codes(EntrySeoChecks.fields(entry, weblog)), "60 characters fits");

        entry.setTitle(entry.getTitle() + "t");
        assertEquals(List.of("missing_meta_title", "title_long"), codes(EntrySeoChecks.fields(entry, weblog)));
        assertEquals(SeoLimits.TITLE_MAX + 1, only(EntrySeoChecks.fields(entry, weblog), "title_long").value());
        assertTrue(only(EntrySeoChecks.gaps(entry), "missing_meta_title").message().contains("61 characters"),
                "says how long the title is");
    }

    /** "Fish &amp; Chips" is stored escaped; a reader sees one character per entity. */
    @Test
    void aTitlesLengthForTheMetaTitleCheckIsWhatAReaderSees() {
        WeblogEntry entry = clean("e1", "&amp;".repeat(SeoLimits.TITLE_MAX));
        entry.setMetaTitle(null);
        assertEquals(List.of(), codes(EntrySeoChecks.gaps(entry)));
    }

    @Test
    void anEscapedEntityCountsAsOneCharacter() {
        Weblog weblog = weblog();
        int room = SeoLimits.TITLE_MAX - " — ".length() - weblog.getName().length();
        WeblogEntry entry = clean("e1", "&amp;".repeat(room));
        entry.setMetaTitle(null);

        assertFalse(codes(EntrySeoChecks.fields(entry, weblog)).contains("title_long"));
    }

    @Test
    void twoPublishedEntriesWithOneTitleAreEachADuplicateNamingTheOther() {
        WeblogEntry first = clean("first", "Harbour walk");
        WeblogEntry second = clean("second", "Harbour walk");
        EntrySeoChecks.Duplicates duplicates = EntrySeoChecks.duplicates(List.of(first, second), weblog());

        Finding onFirst = only(duplicates.of(first), "title_duplicate");
        Finding onSecond = only(duplicates.of(second), "title_duplicate");
        assertEquals(EntrySeoChecks.WARNING, onFirst.severity());
        assertTrue(onFirst.message().contains("second"), onFirst.message());
        assertTrue(onSecond.message().contains("first"), onSecond.message());
    }

    /** The page title is what a search result shows, so that is what must differ. */
    @Test
    void titlesAreComparedAsThePrintedPageTitleIgnoringCaseAndSpacing() {
        WeblogEntry first = clean("first", "Harbour walk");
        first.setMetaTitle(null);
        WeblogEntry second = clean("second", "Harbour   WALK");
        second.setMetaTitle(null);
        WeblogEntry third = clean("third", "Harbour walk");
        third.setMetaTitle("Something else entirely");

        EntrySeoChecks.Duplicates duplicates = EntrySeoChecks.duplicates(List.of(first, second, third), weblog());
        assertEquals(List.of("title_duplicate"), codes(duplicates.of(first)));
        assertEquals(List.of("title_duplicate"), codes(duplicates.of(second)));
        assertEquals(List.of(), codes(duplicates.of(third)));
    }

    @Test
    void twoPublishedEntriesWithOneDescriptionAreEachADuplicate() {
        WeblogEntry first = clean("first", "Harbour walk");
        WeblogEntry second = clean("second", "Ferry times");
        second.setSearchDescription(first.getSearchDescription());

        EntrySeoChecks.Duplicates duplicates = EntrySeoChecks.duplicates(List.of(first, second), weblog());
        assertEquals(List.of("search_description_duplicate"), codes(duplicates.of(first)));
        assertEquals(List.of("search_description_duplicate"), codes(duplicates.of(second)));
    }

    /** A draft is compared against the published entries; it never duplicates itself. */
    @Test
    void anEntryIsNeverItsOwnDuplicateAndBlanksNeverMatch() {
        WeblogEntry only = clean("only", "Harbour walk");
        WeblogEntry blankA = new WeblogEntry();
        blankA.setId("a");
        WeblogEntry blankB = new WeblogEntry();
        blankB.setId("b");
        EntrySeoChecks.Duplicates duplicates = EntrySeoChecks.duplicates(List.of(only, blankA, blankB), weblog());

        assertEquals(List.of(), duplicates.of(only));
        assertEquals(List.of(), duplicates.of(blankA));

        WeblogEntry draft = clean("draft", "Harbour walk");
        assertEquals(List.of("title_duplicate"), codes(duplicates.of(draft)));
    }

    @Test
    void manyDuplicatesAreNamedUpToAFewAndCounted() {
        List<WeblogEntry> many = List.of(clean("a", "Same"), clean("b", "Same"), clean("c", "Same"),
                clean("d", "Same"), clean("e", "Same"), clean("f", "Same"));
        Finding found = only(EntrySeoChecks.duplicates(many, weblog()).of(many.get(0)), "title_duplicate");

        assertTrue(found.message().contains("and 2 more"), found.message());
    }
}

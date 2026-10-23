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
package org.apache.roller.weblogger.pojos;

import org.apache.roller.util.UUIDGenerator;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.pojos.WeblogEntry.PubStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.sql.Timestamp;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;

/**
 * Covers the computed properties of {@link WeblogEntry} -- the parts that are
 * logic rather than storage.
 *
 * <p>{@code createAnchorBase} decides the permalink of every post ever
 * published, {@code getDisplayTitle} is what appears when a post has no title,
 * and the tag methods maintain three sets at once (current, added, removed)
 * that the persistence layer then replays against the tag aggregate table. All
 * three are pure functions of the entry, so none of this needs a database.
 */
class WeblogEntryLogicTest {

    private Weblog weblog;
    private WeblogEntry entry;

    @BeforeEach
    void setUp() {
        weblog = new Weblog();
        weblog.setHandle("testblog");
        weblog.setLocale("en_US");

        entry = new WeblogEntry();
        entry.setWebsite(weblog);
        entry.setCreatorUserName("alice");
    }

    // ---------------------------------------------------------------- anchors

    @Test
    void anchorIsBuiltFromTheFirstFiveWordsOfTheTitle() {
        entry.setTitle("The Quick Brown Fox Jumps Over The Lazy Dog");

        assertEquals("the-quick-brown-fox-jumps", entry.createAnchorBase('-'),
                "The anchor takes the leading words of the title, lowercased and "
                        + "hyphen-joined. Changing the word count or the separator changes "
                        + "the permalink of every post created from then on.");
    }

    /**
     * The separator is a site setting, and it is runtime-settable. The entity
     * no longer reads it: the manager that builds anchors
     * ({@code JPAWeblogEntryManagerImpl.createAnchor}) reads it per call and
     * passes it in, which is what keeps a change visible without a restart.
     * Here the pojo simply honours whatever it is given.
     *
     * <p>Only new anchors are affected. Anchors already stored on entries keep
     * whichever separator was in force when they were created, which is what
     * stops a change of setting from breaking every existing permalink.
     */
    @Test
    void anchorUsesTheSeparatorItIsGiven() {
        entry.setTitle("The Quick Brown Fox Jumps Over The Lazy Dog");

        assertEquals("the_quick_brown_fox_jumps", entry.createAnchorBase('_'),
                "With the pre-5.1 separator selected the anchor must join on underscores");
    }

    @Test
    void anchorStripsPunctuationFromTheTitle() {
        entry.setTitle("Hello, World! (again)");

        assertEquals("hello-world-again", entry.createAnchorBase('-'),
                "Punctuation must not reach the URL: non-alphanumerics become word "
                        + "boundaries, not literal characters in the anchor");
    }

    @Test
    void anchorFallsBackToTheEntryTextWhenThereIsNoTitle() {
        entry.setTitle("   ");
        entry.setText("Some words about nothing much at all");

        assertEquals("some-words-about-nothing-much", entry.createAnchorBase('-'),
                "An untitled post still needs a readable permalink, so the body supplies "
                        + "the words -- and the same leading-word limit applies");
    }

    @Test
    void anchorFallsBackToThePublicationDateWhenThereIsNoTextEither() {
        entry.setTitle(null);
        entry.setText(null);
        entry.setPubTime(Timestamp.valueOf("2024-03-09 12:00:00"));

        assertEquals("20240309", entry.createAnchorBase('-'),
                "With nothing to name it by, the anchor is the YYYYMMDD publication date. "
                        + "Returning empty here would produce a permalink of '/entry/'.");
    }

    // ----------------------------------------------------------- display copy

    @Test
    void displayTitleStripsMarkupFromTheTitle() {
        entry.setTitle("A <em>bold</em> claim");

        assertEquals("A  bold  claim", entry.getDisplayTitle(),
                "Titles are rendered into contexts that do not accept markup (page "
                        + "<title>, feed entries), so tags are removed rather than escaped");
    }

    @Test
    void displayTitleFallsBackToTheBodyWhenTheTitleIsBlank() {
        entry.setTitle("  ");
        entry.setText("<p>Body text</p>");

        assertEquals("Body text", entry.getDisplayTitle(),
                "A blank title must be treated like a missing one -- a whitespace title "
                        + "would otherwise render as an empty link in the editor list");
    }

    @Test
    void displayTitleFromBodyIsCappedAtTheColumnWidth() {
        entry.setTitle(null);
        entry.setText("x".repeat(500));

        assertEquals(255, entry.getDisplayTitle().length(),
                "The generated title is truncated to 255 characters; without the cap a "
                        + "long post would push its entire body into the title");
    }

    @Test
    void rss09xDescriptionEscapesMarkupInsteadOfRemovingIt() {
        entry.setText("<p>tea & biscuits</p>");

        assertEquals("&lt;p&gt;tea &amp; biscuits&lt;/p&gt;", entry.getRss09xDescription(),
                "RSS 0.9x carries the body as escaped HTML inside the description "
                        + "element, so the markup must survive as entities");
    }

    @Test
    void rss09xDescriptionTruncatesWithAnEllipsisWhenAskedFor() {
        entry.setText("abcdefghij");

        assertEquals("abcde...", entry.getRss09xDescription(8),
                "A truncated description must fit the requested length exactly, ellipsis "
                        + "included -- three characters of the text are given up for it");
        assertEquals("abcdefghij", entry.getRss09xDescription(10),
                "Text that already fits must be returned untouched");
        assertEquals("abcdefghij", entry.getRss09xDescription(),
                "The no-argument form means 'no limit'");
    }

    // -------------------------------------------------------------- status

    @Test
    void statusPredicatesReportExactlyOneStatusAtATime() {
        entry.setStatus(PubStatus.DRAFT);
        assertTrue(entry.isDraft());
        assertFalse(entry.isPending());
        assertFalse(entry.isPublished());

        entry.setStatus(PubStatus.PENDING);
        assertFalse(entry.isDraft());
        assertTrue(entry.isPending(),
                "PENDING is the state a limited member's post sits in awaiting moderation");
        assertFalse(entry.isPublished());

        entry.setStatus(PubStatus.PUBLISHED);
        assertFalse(entry.isDraft());
        assertFalse(entry.isPending());
        assertTrue(entry.isPublished());

        entry.setStatus(PubStatus.SCHEDULED);
        assertFalse(entry.isDraft(), "A scheduled post is not a draft");
        assertFalse(entry.isPublished(),
                "A scheduled post must not read as published until its time arrives, or "
                        + "ScheduledEntriesTask would have nothing left to promote");
    }

    @Test
    void newEntriesStartAsDrafts() {
        assertTrue(new WeblogEntry().isDraft(),
                "An entry with no status set must default to DRAFT -- defaulting to "
                        + "PUBLISHED would put half-written posts on the front page");
    }

    // ------------------------------------------------------------------ ids

    @Test
    void blankIdsFromFormBeansAreIgnored() {
        // An unfilled hidden form field arrives as "", which must not wipe the
        // generated id and leave the entity unsaveable.
        String generated = entry.getId();
        assertNotNull(generated, "A new entry generates its own id");

        entry.setId("");
        assertEquals(generated, entry.getId(),
                "An empty string is never a valid id and must leave the existing one alone");

        entry.setId("  ");
        assertEquals(generated, entry.getId(), "Whitespace is not an id either");

        entry.setId("real-id");
        assertEquals("real-id", entry.getId(), "A real id must still be accepted");
    }

    // ---------------------------------------------------------- attributes

    @Test
    void entryAttributesAreLookedUpAndUpdatedByName() throws Exception {
        entry.putEntryAttribute("mood", "cheerful");

        assertEquals("cheerful", entry.findEntryAttribute("mood"));
        assertNull(entry.findEntryAttribute("absent"),
                "An attribute that was never set must read as null, not blank");

        WeblogEntryAttribute stored = entry.getEntryAttributes().iterator().next();
        assertSame(entry, stored.getEntry(),
                "The attribute must point back at its entry, or the persistence layer has "
                        + "no foreign key to write and the row is orphaned");

        entry.putEntryAttribute("mood", "grumpy");
        assertEquals("grumpy", entry.findEntryAttribute("mood"));
        assertEquals(1, entry.getEntryAttributes().size(),
                "Setting an attribute twice must update it in place rather than add a "
                        + "second row with the same name");
    }

    @Test
    void entryAttributesSortByName() {
        // They live in a TreeSet, so compareTo is what orders them; a compareTo
        // that always returned 0 would silently collapse them to one.
        WeblogEntryAttribute mood = new WeblogEntryAttribute();
        mood.setName("mood");
        WeblogEntryAttribute author = new WeblogEntryAttribute();
        author.setName("author");

        assertTrue(author.compareTo(mood) < 0, "author sorts before mood");
        assertTrue(mood.compareTo(author) > 0, "and the ordering is antisymmetric");
        assertEquals(0, mood.compareTo(mood));
    }

    // ---------------------------------------------------------------- tags

    @Test
    void addingATagNormalisesItAndRecordsItAsAdded() throws Exception {
        entry.setUpdateTime(Timestamp.valueOf("2024-01-01 00:00:00"));
        entry.addTag("JavaScript");

        WeblogEntryTag tag = entry.getTags().iterator().next();
        assertEquals("javascript", tag.getName(),
                "Tags are lowercased on the way in because there is no reliable 1:1 "
                        + "uppercase/lowercase mapping across languages; storing both cases "
                        + "would split the tag cloud");
        assertEquals(weblog, tag.getWeblog(), "The tag must be attached to the entry's weblog");
        assertSame(entry, tag.getWeblogEntry(),
                "and to the entry itself -- without the back-reference the tag row has no "
                        + "entry to hang off and is dropped on save");
        assertEquals("alice", tag.getCreatorUserName());
        assertEquals(entry.getUpdateTime(), tag.getTime());
        assertTrue(entry.getAddedTags().contains(tag),
                "New tags must be recorded in addedTags so the aggregate counts can be "
                        + "incremented when the entry is saved");
    }

    @Test
    void addingATagTwiceIsANoOp() throws Exception {
        entry.addTag("java");
        entry.addTag("JAVA");

        assertEquals(1, entry.getTags().size(),
                "The same tag in different case is the same tag; a duplicate would "
                        + "double-count in the tag aggregate table");
        assertEquals(1, entry.getAddedTags().size(),
                "A tag that was already present must not be reported as newly added");
    }

    @Test
    void tagsThatNormaliseToNothingAreDiscarded() throws Exception {
        entry.addTag("   ");

        assertTrue(entry.getTags().isEmpty(),
                "Whitespace is stripped by normalisation, leaving nothing to tag with. "
                        + "Storing an empty tag would produce a tag-index URL of '/tags/'.");
    }

    @Test
    void tagsAreLowercasedInTheWeblogsOwnLocaleNotTheServers() throws Exception {
        // The reason addTag takes the weblog's locale rather than calling
        // toLowerCase() with the default: Turkish maps 'I' to the dotless 'ı',
        // so a Turkish blog and an English one disagree about what a tag is
        // called. Using the server default would silently rewrite the tags of
        // every blog whose language is not the server's.
        weblog.setLocale("tr");
        entry.addTag("TITLE");
        assertEquals("tıtle", entry.getTags().iterator().next().getName(),
                "A Turkish weblog must lowercase its tags with Turkish rules");

        Weblog english = new Weblog();
        english.setHandle("english");
        english.setLocale("en");
        WeblogEntry englishEntry = new WeblogEntry();
        englishEntry.setWebsite(english);
        englishEntry.addTag("TITLE");
        assertEquals("title", englishEntry.getTags().iterator().next().getName(),
                "while an English one uses English rules on the same input");
    }

    @Test
    void tagsAsStringIsSortedByNameAndSpaceSeparated() throws Exception {
        entry.addTag("zebra");
        entry.addTag("apple");
        entry.addTag("mango");

        assertEquals("apple mango zebra", entry.getTagsAsString(),
                "The editor shows tags in a stable, sorted order; insertion order would "
                        + "make the field appear to shuffle itself between saves");
    }

    @Test
    void anUntaggedEntryRendersAsAnEmptyTagField() {
        // The separator-trimming step has to notice there is nothing to trim.
        assertEquals("", entry.getTagsAsString(),
                "An entry with no tags must produce an empty field, not a stray separator "
                        + "and not an exception from trimming one that is not there");
    }

    @Test
    void settingTagsAsStringAlsoUsesTheWeblogsLocale() throws Exception {
        weblog.setLocale("tr");
        entry.setTagsAsString("TITLE");

        assertEquals("tıtle", entry.getTagsAsString(),
                "The tag field and addTag() must normalise identically, or a tag typed "
                        + "into the field would not match the same tag added another way");
    }

    @Test
    void settingTagsAsStringAddsAndRemovesToMatch() throws Exception {
        entry.addTag("keep");
        entry.addTag("drop");
        WeblogEntryTag dropped = entry.getTags().stream()
                .filter(t -> "drop".equals(t.getName())).findFirst().orElseThrow();

        entry.setTagsAsString("keep add");

        assertEquals("add keep", entry.getTagsAsString(),
                "The entry must end up with exactly the tags the field listed");
        assertTrue(entry.getRemovedTags().contains(dropped),
                "A tag the user deleted from the field must be recorded in removedTags so "
                        + "its aggregate count is decremented");
    }

    @Test
    void clearingTheTagFieldRemovesEveryTag() throws Exception {
        entry.addTag("one");
        entry.addTag("two");

        entry.setTagsAsString("");

        assertTrue(entry.getTags().isEmpty(), "An empty tag field means no tags");
        assertEquals(2, entry.getRemovedTags().size(),
                "Every tag dropped this way must be recorded for the aggregate update; "
                        + "clearing the set without recording leaks phantom counts");
    }

    @Test
    void tagFieldAcceptsCommasAndWhitespaceAsSeparators() throws Exception {
        entry.setTagsAsString("one, two\tthree");

        assertEquals("one three two", entry.getTagsAsString(),
                "Users type tag lists inconsistently; commas, spaces and tabs must all "
                        + "separate rather than end up inside a tag name");
    }

    // --------------------------------------------------------- date helpers

    @Test
    void publicationTimeIsFormattedInTheWeblogsLocale() {
        entry.setPubTime(Timestamp.valueOf("2024-03-09 15:30:00"));

        assertEquals("2024-03-09", entry.formatPubTime("yyyy-MM-dd"));
    }

    @Test
    void updateTimeIsFormattedInTheWeblogsLocaleLikePublicationTime() {
        weblog.setLocale("fr");
        entry.setUpdateTime(Timestamp.valueOf("2024-03-09 15:30:00"));

        assertEquals("mars", entry.formatUpdateTime("MMMM"),
                "formatUpdateTime used the server's locale while formatPubTime used the weblog's, "
                + "so one page could print an entry's two dates in two languages");
    }

    @Test
    void formattingReportsAnErrorStringRatherThanThrowing() {
        // These are called straight from Velocity templates, where an exception
        // takes out the whole page render.
        entry.setPubTime(null);
        assertEquals("ERROR: formatting date", entry.formatPubTime("yyyy-MM-dd"),
                "A missing publication date must not blow up the template that formats it");

        entry.setPubTime(Timestamp.valueOf("2024-03-09 15:30:00"));
        assertEquals("ERROR: formatting date", entry.formatPubTime("not a pattern"),
                "A malformed pattern in a customised theme must not blow up the page");

        entry.setUpdateTime(null);
        assertEquals("ERROR: formatting date", entry.formatUpdateTime("yyyy-MM-dd"));
    }

    // ------------------------------------------------------------- misc

    @Test
    void categoriesExposesTheSingleCategoryAsAList() {
        WeblogCategory category = new WeblogCategory();
        category.setName("General");
        entry.setCategory(category);

        assertEquals(List.of(category), entry.getCategories(),
                "Feed templates iterate getCategories(); it must contain the entry's one "
                        + "category rather than being empty");
    }

    @Test
    void copyConstructorCarriesEveryEditableField() {
        // setData() is used to refresh a detached entry from a form bean. A
        // field it forgets is a field the user's edit silently loses, so every
        // one is given a value nothing else in this object has.
        WeblogCategory category = new WeblogCategory();
        category.setName("Travel");
        entry.setId("entry-1");
        entry.setCategory(category);
        entry.setCreatorUserName("bob");
        entry.setTitle("Original");
        entry.setLink("http://example.com/link");
        entry.setText("Body");
        entry.setSummary("Summary");
        entry.setSearchDescription("Search description");
        entry.setAnchor("original");
        entry.setPubTime(Timestamp.valueOf("2024-03-09 15:30:00"));
        entry.setUpdateTime(Timestamp.valueOf("2024-03-10 09:00:00"));
        entry.setStatus(PubStatus.PUBLISHED);
        entry.setRightToLeft(Boolean.TRUE);
        entry.setPinnedToMain(Boolean.TRUE);
        entry.setLocale("fr");

        WeblogEntry copy = new WeblogEntry(entry);

        assertEquals("entry-1", copy.getId());
        assertSame(category, copy.getCategory());
        assertEquals(weblog, copy.getWebsite());
        assertEquals("bob", copy.getCreatorUserName());
        assertEquals("Original", copy.getTitle());
        assertEquals("http://example.com/link", copy.getLink());
        assertEquals("Body", copy.getText());
        assertEquals("Summary", copy.getSummary());
        assertEquals("Search description", copy.getSearchDescription());
        assertEquals("original", copy.getAnchor());
        assertEquals(Timestamp.valueOf("2024-03-09 15:30:00"), copy.getPubTime());
        assertEquals(Timestamp.valueOf("2024-03-10 09:00:00"), copy.getUpdateTime());
        assertEquals(PubStatus.PUBLISHED, copy.getStatus(),
                "Status must be copied; silently resetting it to DRAFT would unpublish "
                        + "the post the copy is standing in for");
        assertEquals(Boolean.TRUE, copy.getRightToLeft());
        assertEquals(Boolean.TRUE, copy.getPinnedToMain());
        assertEquals("fr", copy.getLocale());
    }

    // ------------------------------------------------------------------ tags

    /**
     * A tag added before the entry has an update time must still carry one.
     *
     * <p>{@code roller_weblogentrytag.time} is NOT NULL, and {@code addTag}
     * copies whatever {@code getUpdateTime()} happens to return at the moment
     * it is called. On a brand-new entry that is null, so the insert fails --
     * which made {@code POST /api/v1/weblogs/{handle}/entries} answer 500 for
     * any request carrying tags, every time.
     *
     * <p>The JSP editor escaped it only by accident of ordering:
     * {@code EntryEditController} calls {@code setUpdateTime} on the line
     * before {@code EntryBean.copyTo}, and copyTo is what applies the tags.
     * Nothing said so, and nothing enforced it -- the API simply applied tags
     * first and saved second, which is the natural order to write. Fixing it
     * here rather than by adding the same magic line to the API removes the
     * ordering trap for every caller, present and future.
     */
    @Test
    void aTagAddedBeforeTheEntryHasAnUpdateTimeStillCarriesOne() throws WebloggerException {
        assertNull(entry.getUpdateTime(), "precondition: a new entry has no update time");

        entry.addTag("travel");

        WeblogEntryTag tag = entry.getTags().iterator().next();
        assertNotNull(tag.getTime(),
                "roller_weblogentrytag.time is NOT NULL, so a null here is an insert "
                        + "that fails at the database rather than a field left empty");
    }

    /**
     * When the entry DOES have an update time, the tag keeps taking it, so a
     * tag and the entry it belongs to still agree on when they were written.
     * This is the behaviour that already existed and must not drift.
     */
    @Test
    void aTagTakesTheEntrysUpdateTimeWhenThereIsOne() throws WebloggerException {
        Timestamp updated = new Timestamp(1_500_000_000_000L);
        entry.setUpdateTime(updated);

        entry.addTag("travel");

        assertEquals(updated, entry.getTags().iterator().next().getTime());
    }

    // ------------------------------------------------------------- copies and revisions

    /**
     * A copy needs one id, not two: {@code new WeblogEntry()} drew one that
     * {@code setData} then overwrote with the original's, before the copy
     * drew its real one (each a {@code SecureRandom} draw under a JVM-wide lock).
     */
    @Test
    void copyAsDraftDrawsExactlyOneId() throws Exception {
        entry.setTitle("Original");
        entry.setId("original-id");
        try (MockedStatic<UUIDGenerator> generator = mockStatic(UUIDGenerator.class, CALLS_REAL_METHODS)) {
            WeblogEntry copy = entry.copyAsDraft("Copy");

            assertNotNull(copy.getId());
            assertFalse("original-id".equals(copy.getId()), "the copy has its own identity");
            generator.verify(UUIDGenerator::generateUUID, times(1));
        }
    }
}

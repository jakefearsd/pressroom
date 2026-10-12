/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  The ASF licenses this file to You
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
package org.apache.roller.weblogger.ui.controllers.editor;

import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.TimeZone;

import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.pojos.JsonLdType;
import org.apache.roller.weblogger.pojos.WeblogCategory;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.apache.roller.weblogger.pojos.WeblogEntry.PubStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link EntryBean}, the form model behind the entry editor.
 *
 * <p>Two things here are worth pinning down. First {@code getPubTime}, which
 * turns the {@code datetime-local} value the editor submits into the instant an
 * entry goes live: get the timezone handling wrong and posts publish hours off,
 * or a scheduled post fires immediately. Second {@code copyTo}, which is the
 * only place that checks a submitted category actually belongs to the weblog
 * being edited — without it, a crafted form could file an entry under another
 * blog's category.
 */
class EntryBeanTest extends EditorControllerTestSupport {

    private static final TimeZone NEW_YORK = TimeZone.getTimeZone("America/New_York");

    private EntryBean bean;

    @BeforeEach
    void setUpBean() {
        bean = new EntryBean();
    }

    // --- getPubTime ---

    @Test
    void pubTimeParsesTheDatetimeLocalValueAsWallClockTimeInTheGivenZone() {
        // What an <input type="datetime-local"> submits.
        bean.setPubTimeLocal("2024-03-15T14:30");

        Timestamp pubTime = bean.getPubTime(NEW_YORK);

        assertEquals("2024-03-15 14:30:00", formatIn(pubTime, NEW_YORK),
                "The datetime-local value must be applied as wall-clock time in the given zone");
    }

    @Test
    void pubTimeIsInterpretedInTheGivenTimezoneNotTheServers() {
        bean.setPubTimeLocal("2024-03-15T12:00");

        Timestamp inNewYork = bean.getPubTime(NEW_YORK);
        Timestamp inTokyo = bean.getPubTime(TimeZone.getTimeZone("Asia/Tokyo"));

        assertNotEquals(inNewYork, inTokyo,
                "Noon in New York and noon in Tokyo are different instants; if these come out "
                        + "equal the timezone is being ignored and scheduled posts will fire at "
                        + "the wrong time for every blog not in the server's zone");
    }

    @Test
    void pubTimeAcceptsSecondsIfABrowserSubmitsThem() {
        // Mirrors eventDatesWithSecondsAreAcceptedAndShownToTheMinute below:
        // some browsers submit seconds on a datetime-local field even though
        // the editor's own field never carries them, and that must not be
        // rejected.
        bean.setPubTimeLocal("2024-03-15T14:30:45");

        assertEquals("2024-03-15 14:30:45", formatIn(bean.getPubTime(NEW_YORK), NEW_YORK));
    }

    @Test
    void pubTimeIsNullWhenNoDatetimeWasChosen() {
        // A null pubTime is meaningful: EntryEditController reads it as
        // "publish now" rather than "schedule".
        assertNull(bean.getPubTime(NEW_YORK), "No pubTimeLocal means no pub time");

        bean.setPubTimeLocal("");
        assertNull(bean.getPubTime(NEW_YORK), "An empty pubTimeLocal means no pub time");

        bean.setPubTimeLocal("   ");
        assertNull(bean.getPubTime(NEW_YORK), "A whitespace-only pubTimeLocal means no pub time");
    }

    @Test
    void pubTimeThrowsOnAMalformedValueRatherThanSilentlyPublishingNow() {
        // The old dateString/hours/minutes/seconds combination swallowed a
        // parse failure (log + null), which EntryEditController then read as
        // "publish now" -- a mistyped date silently published immediately
        // instead of telling the author anything was wrong. The input type
        // keeps ordinary browsers honest, but a hand-crafted POST must not
        // get that silent behaviour back: it must surface as a validation
        // error instead.
        bean.setPubTimeLocal("not-a-date");

        assertThrows(DateTimeParseException.class, () -> bean.getPubTime(NEW_YORK));
    }

    // --- status predicates ---

    @Test
    void statusPredicatesMatchOnlyTheirOwnStatus() {
        bean.setStatus(PubStatus.DRAFT.name());
        assertTrue(bean.isDraft());
        assertFalse(bean.isPending() || bean.isPublished() || bean.isScheduled());

        bean.setStatus(PubStatus.PENDING.name());
        assertTrue(bean.isPending());
        assertFalse(bean.isDraft() || bean.isPublished() || bean.isScheduled());

        bean.setStatus(PubStatus.PUBLISHED.name());
        assertTrue(bean.isPublished());
        assertFalse(bean.isDraft() || bean.isPending() || bean.isScheduled());

        bean.setStatus(PubStatus.SCHEDULED.name());
        assertTrue(bean.isScheduled());
        assertFalse(bean.isDraft() || bean.isPending() || bean.isPublished());
    }

    @Test
    void statusPredicatesAreAllFalseWhenNoStatusIsSet() {
        assertFalse(bean.isDraft() || bean.isPending() || bean.isPublished() || bean.isScheduled(),
                "An unset status must not accidentally read as one of the real statuses");
    }

    // --- copyTo ---

    @Test
    void copyToEscapesTheTitleButNotTheBody() throws Exception {
        // Titles are rendered as text in a great many places, so they are
        // escaped on the way in. Entry bodies are deliberately raw HTML.
        WeblogEntry entry = entryInCategory("cat-1");
        bean.setStatus(PubStatus.DRAFT.name());
        bean.setCategoryId("cat-1");
        bean.setTitle("Tom & Jerry <b>rule</b>");
        bean.setText("<p>Real markup stays</p>");

        bean.copyTo(entry, entry.getCategory());

        assertEquals("Tom &amp; Jerry &lt;b&gt;rule&lt;/b&gt;", entry.getTitle());
        assertEquals("<p>Real markup stays</p>", entry.getText());
    }

    @Test
    void copyToRejectsACategoryBelongingToAnotherWeblog() throws Exception {
        // This is the authorization check on the category dropdown. The form
        // posts a bare category id, so without it any blog could file entries
        // into any other blog's category.
        WeblogEntry entry = entryInCategory("cat-1");
        WeblogCategory foreign = new WeblogCategory();
        foreign.setId("cat-9");
        foreign.setName("Someone else's");
        org.apache.roller.weblogger.pojos.Weblog otherWeblog =
                new org.apache.roller.weblogger.pojos.Weblog();
        otherWeblog.setId("weblog-2");
        otherWeblog.setHandle("otherblog");
        foreign.setWeblog(otherWeblog);

        bean.setStatus(PubStatus.DRAFT.name());
        bean.setCategoryId("cat-9");

        WebloggerException ex = assertThrows(WebloggerException.class,
                () -> bean.copyTo(entry, foreign));
        assertTrue(ex.getMessage().contains("Illegal category"),
                "Expected the cross-weblog category to be refused, got: " + ex.getMessage());
    }

    @Test
    void copyToRejectsACategoryIdThatDoesNotResolve() throws Exception {
        // The controller looked the id up and found nothing; the bean must
        // refuse rather than file the entry with no category.
        WeblogEntry entry = entryInCategory("cat-1");

        bean.setStatus(PubStatus.DRAFT.name());
        bean.setCategoryId("nope");

        WebloggerException ex = assertThrows(WebloggerException.class,
                () -> bean.copyTo(entry, null));
        assertTrue(ex.getMessage().contains("could not be found"),
                "Expected a not-found complaint, got: " + ex.getMessage());
    }

    @Test
    void copyToRefusesToSaveAnEntryWithNoCategory() throws Exception {
        WeblogEntry entry = entryInCategory("cat-1");
        bean.setStatus(PubStatus.DRAFT.name());
        bean.setCategoryId(null);

        WebloggerException ex = assertThrows(WebloggerException.class,
                () -> bean.copyTo(entry, null));
        assertTrue(ex.getMessage().contains("No category specified"),
                "Expected a missing-category complaint, got: " + ex.getMessage());
    }

    /**
     * The form bean is pure: the category it files the entry under is handed
     * to it by the controller, which owns the lookup. Before the DI wave
     * {@code copyTo} reached the static locator for a
     * {@code WeblogEntryManager} to resolve the id itself.
     */
    @Test
    void copyToNeverConsultsTheEntryManager() throws Exception {
        WeblogEntry entry = entryInCategory("cat-1");
        bean.setStatus(PubStatus.DRAFT.name());
        bean.setCategoryId("cat-1");

        bean.copyTo(entry, entry.getCategory());

        verify(weblogger.getWeblogEntryManager(), never()).getWeblogCategory(any());
    }

    @Test
    void copyToStripsPunctuationFromTags() throws Exception {
        // Tags become URL path segments, so anything non-alphanumeric is
        // flattened to a space (and thereby splits the tag) before storage.
        WeblogEntry entry = entryInCategory("cat-1");
        bean.setStatus(PubStatus.DRAFT.name());
        bean.setCategoryId("cat-1");
        bean.setTagsAsString("hello,world foo/bar");

        bean.copyTo(entry, entry.getCategory());

        assertEquals("bar foo hello world", entry.getTagsAsString(),
                "Punctuation must split tags rather than survive into the tag name");
    }

    @Test
    void copyToTurnsNullTagsIntoNoTagsRatherThanThrowing() throws Exception {
        WeblogEntry entry = entryInCategory("cat-1");
        bean.setStatus(PubStatus.DRAFT.name());
        bean.setCategoryId("cat-1");
        bean.setTagsAsString(null);

        bean.copyTo(entry, entry.getCategory());

        assertEquals("", entry.getTagsAsString());
    }

    @Test
    void copyToCarriesTheSummaryAndSeoFieldsAcross() throws Exception {
        WeblogEntry entry = entryInCategory("cat-1");
        bean.setStatus(PubStatus.DRAFT.name());
        bean.setCategoryId("cat-1");
        bean.setSummary("A summary");
        bean.setSearchDescription("For search engines");
        bean.setLocale("fr_FR");
        bean.setFeaturedImageId("mf-featured-1");
        bean.setMetaTitle("Custom SEO Title");
        bean.setOgImageId("mf-og-1");
        bean.setCanonicalUrl("http://example.com/canonical");
        bean.setNoindex(true);
        bean.setJsonLdType(JsonLdType.EVENT.name());
        bean.setGeoLatitude(48.8584);
        bean.setGeoLongitude(2.2945);
        bean.setEventStart(new Timestamp(1_800_000_000_000L));
        bean.setEventEnd(new Timestamp(1_800_003_600_000L));
        bean.setEventLocation("Champ de Mars");

        bean.copyTo(entry, entry.getCategory());

        assertEquals("A summary", entry.getSummary());
        assertEquals("For search engines", entry.getSearchDescription());
        assertEquals("fr_FR", entry.getLocale());
        assertEquals("mf-featured-1", entry.getFeaturedImageId());
        assertEquals("Custom SEO Title", entry.getMetaTitle());
        assertEquals("mf-og-1", entry.getOgImageId());
        assertEquals("http://example.com/canonical", entry.getCanonicalUrl());
        assertEquals(Boolean.TRUE, entry.getNoindex());
        assertEquals(JsonLdType.EVENT, entry.getJsonLdType());
        assertEquals(48.8584, entry.getGeoLatitude());
        assertEquals(2.2945, entry.getGeoLongitude());
        assertEquals(new Timestamp(1_800_000_000_000L), entry.getEventStart());
        assertEquals(new Timestamp(1_800_003_600_000L), entry.getEventEnd());
        assertEquals("Champ de Mars", entry.getEventLocation());
    }

    @Test
    void copyToNormalizesAMissingJsonLdTypeToTheBlogPostingDefault() throws Exception {
        // The bean carries the type as a string (like status), but unlike
        // status it is parsed leniently: an unset field and an unrecognized
        // value both mean "just the BlogPosting default", never an exception
        // out of the save path.
        WeblogEntry entry = entryInCategory("cat-1");
        bean.setStatus(PubStatus.DRAFT.name());
        bean.setCategoryId("cat-1");
        bean.setJsonLdType(null);

        bean.copyTo(entry, entry.getCategory());
        assertEquals(JsonLdType.BLOG_POSTING, entry.getJsonLdType());

        bean.setJsonLdType("SomethingFromTheFuture");
        bean.copyTo(entry, entry.getCategory());
        assertEquals(JsonLdType.BLOG_POSTING, entry.getJsonLdType(),
                "An unknown submitted type must degrade to the default, not throw");
    }

    @Test
    void copyToClearsTravelFieldsTheAuthorBlankedOut() throws Exception {
        // Mirrors the negative-case tests for the flags: a copyTo that only
        // ever wrote non-null values would silently keep stale coordinates and
        // event details on an entry whose author removed them.
        WeblogEntry entry = entryInCategory("cat-1");
        entry.setGeoLatitude(48.8584);
        entry.setGeoLongitude(2.2945);
        entry.setEventStart(new Timestamp(1_800_000_000_000L));
        entry.setEventEnd(new Timestamp(1_800_003_600_000L));
        entry.setEventLocation("Champ de Mars");

        bean.setStatus(PubStatus.DRAFT.name());
        bean.setCategoryId("cat-1");

        bean.copyTo(entry, entry.getCategory());

        assertNull(entry.getGeoLatitude());
        assertNull(entry.getGeoLongitude());
        assertNull(entry.getEventStart());
        assertNull(entry.getEventEnd());
        assertNull(entry.getEventLocation());
    }

    // --- the SEO card's datetime-local binding ---

    @Test
    void eventTimesShowInTheWeblogsZone() throws Exception {
        weblog.setTimeZone("Europe/Berlin");
        WeblogEntry entry = storedEntry();
        entry.setEventStart(Timestamp.from(java.time.Instant.parse("2026-08-02T17:30:00Z")));
        entry.setEventEnd(Timestamp.from(java.time.Instant.parse("2026-08-02T20:00:00Z")));

        bean.copyFrom(entry, Locale.ROOT);

        assertEquals("2026-08-02T19:30", bean.getEventStartLocal());
        assertEquals("2026-08-02T22:00", bean.getEventEndLocal());
    }

    @Test
    void anEntryWithNoEventTimesShowsEmptyFields() throws Exception {
        bean.copyFrom(storedEntry(), Locale.ROOT);

        assertEquals("", bean.getEventStartLocal());
        assertEquals("", bean.getEventEndLocal());
    }

    @Test
    void anEntryWithoutAWebsiteAndWithoutEventTimesStillCopies() {
        WeblogEntry entry = new WeblogEntry();
        entry.setId("e");
        entry.setStatus(PubStatus.DRAFT);
        WeblogCategory category = new WeblogCategory();
        category.setId("cat-1");
        entry.setCategory(category);

        bean.copyFrom(entry, Locale.ROOT);

        assertEquals("", bean.getEventStartLocal());
    }

    @Test
    void theSubmittedTextIsKeptUntilTheControllerResolvesIt() {
        bean.setEventStartLocal("not a time");

        assertEquals("not a time", bean.getEventStartLocal());
        assertNull(bean.getEventStart(), "the setter only stores the text");
    }

    @Test
    void copyToCarriesTheNegativeCaseOfNoindexToo() throws Exception {
        // Mirrors copyToCarriesTheNegativeCaseOfEveryFlagToo below, but for the
        // SEO noindex flag: a copyTo that only ever flipped it on would still
        // look correct in a test that never turns it back off.
        WeblogEntry entry = entryInCategory("cat-1");
        entry.setNoindex(Boolean.TRUE);
        bean.setStatus(PubStatus.DRAFT.name());
        bean.setCategoryId("cat-1");
        bean.setNoindex(false);

        bean.copyTo(entry, entry.getCategory());

        assertEquals(Boolean.FALSE, entry.getNoindex());
    }

    @Test
    void copyToDoesNotTouchPubTimeOrPinnedToMain() throws Exception {
        // Both are set by EntryEditController, which applies rules copyTo has
        // no way to know about (scheduling, and the global-admin gate on
        // pinning). If copyTo started writing them it would silently undo those
        // decisions.
        WeblogEntry entry = entryInCategory("cat-1");
        Timestamp existingPubTime = new Timestamp(1_700_000_000_000L);
        entry.setPubTime(existingPubTime);
        entry.setPinnedToMain(Boolean.TRUE);

        bean.setStatus(PubStatus.DRAFT.name());
        bean.setCategoryId("cat-1");
        bean.setPinnedToMain(false);
        bean.setPubTimeLocal("2020-01-01T00:00");

        bean.copyTo(entry, entry.getCategory());

        assertEquals(existingPubTime, entry.getPubTime(), "copyTo must leave pubTime alone");
        assertTrue(entry.getPinnedToMain(), "copyTo must leave pinnedToMain alone");
    }

    // --- copyFrom ---

    @Test
    void copyFromUnescapesTheTitleForRedisplayInTheEditor() throws Exception {
        // The stored title is escaped; the editor's text input must show the
        // characters the author actually typed, or every save would re-escape
        // and "&" would creep towards "&amp;amp;".
        WeblogEntry entry = storedEntry();
        entry.setTitle("Tom &amp; Jerry");

        bean.copyFrom(entry, Locale.US);

        assertEquals("Tom & Jerry", bean.getTitle());
    }

    @Test
    void copyFromFormatsTheStoredPubTimeAsADatetimeLocalValueInTheWeblogsTimezone() throws Exception {
        WeblogEntry entry = storedEntry();
        // The weblog's timezone is what the editor displays in.
        weblog.setTimeZone("America/New_York");
        SimpleDateFormat inZone = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        inZone.setTimeZone(NEW_YORK);
        entry.setPubTime(new Timestamp(inZone.parse("2024-03-15 14:30:45").getTime()));

        bean.copyFrom(entry, Locale.US);

        // Truncated to the minute, the same trade eventStartLocal already
        // makes -- a datetime-local input has no seconds control.
        assertEquals("2024-03-15T14:30", bean.getPubTimeLocal());
    }

    @Test
    void copyFromLeavesPubTimeLocalUnsetForAnEntryThatWasNeverPublished() throws Exception {
        WeblogEntry entry = storedEntry();
        entry.setPubTime(null);

        bean.copyFrom(entry, Locale.US);

        assertNull(bean.getPubTimeLocal(),
                "A draft with no pub time must not be given a fabricated date");
    }

    @Test
    void copyFromRoundTripsThroughGetPubTime() throws Exception {
        // The editor's whole date story is copyFrom -> user edits -> getPubTime.
        // If the two disagree on format or timezone, merely opening and saving
        // an entry would move its publication time.
        WeblogEntry entry = storedEntry();
        SimpleDateFormat inZone = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        inZone.setTimeZone(NEW_YORK);
        Timestamp original = new Timestamp(inZone.parse("2024-07-04 09:08:00").getTime());
        entry.setPubTime(original);

        bean.copyFrom(entry, Locale.US);
        Timestamp reparsed = bean.getPubTime(NEW_YORK);

        assertEquals(original, reparsed,
                "Opening an entry and saving it unchanged must not move its publication time");
    }

    @Test
    void copyFromCarriesEveryFieldTheEditorRedisplays() throws Exception {
        // Exhaustive on purpose. copyFrom is the only thing that populates the
        // edit form, and a field it silently fails to copy comes back blank in
        // the textarea -- which the very next save then persists, destroying
        // the stored value. A round-trip test that checks only a few fields
        // would not notice.
        WeblogEntry entry = storedEntry();
        entry.setTitle("Stored title");
        entry.setLocale("de_DE");
        entry.setStatus(PubStatus.SCHEDULED);
        entry.setSummary("The summary");
        entry.setText("The body");
        entry.setSearchDescription("The search description");
        entry.setTagsAsString("alpha beta");
        entry.setPinnedToMain(Boolean.TRUE);
        entry.setFeaturedImageId("mf-featured-1");
        entry.setMetaTitle("Stored SEO Title");
        entry.setOgImageId("mf-og-1");
        entry.setCanonicalUrl("http://example.com/canonical");
        entry.setNoindex(Boolean.TRUE);
        entry.setJsonLdType(JsonLdType.TOURIST_ATTRACTION);
        entry.setGeoLatitude(64.1466);
        entry.setGeoLongitude(-21.9426);
        entry.setEventStart(new Timestamp(1_800_000_000_000L));
        entry.setEventEnd(new Timestamp(1_800_003_600_000L));
        entry.setEventLocation("Harpa");

        bean.copyFrom(entry, Locale.US);

        assertEquals("entry-1", bean.getId());
        assertEquals("Stored title", bean.getTitle());
        assertEquals("de_DE", bean.getLocale());
        assertEquals(PubStatus.SCHEDULED.name(), bean.getStatus());
        assertEquals("The summary", bean.getSummary());
        assertEquals("The body", bean.getText());
        assertEquals("The search description", bean.getSearchDescription());
        assertEquals("cat-1", bean.getCategoryId());
        assertEquals("alpha beta", bean.getTagsAsString());
        assertTrue(bean.getPinnedToMain());
        assertEquals("mf-featured-1", bean.getFeaturedImageId());
        assertEquals("Stored SEO Title", bean.getMetaTitle());
        assertEquals("mf-og-1", bean.getOgImageId());
        assertEquals("http://example.com/canonical", bean.getCanonicalUrl());
        assertTrue(bean.getNoindex());
        assertEquals(JsonLdType.TOURIST_ATTRACTION.name(), bean.getJsonLdType());
        assertEquals(64.1466, bean.getGeoLatitude());
        assertEquals(-21.9426, bean.getGeoLongitude());
        assertEquals(new Timestamp(1_800_000_000_000L), bean.getEventStart());
        assertEquals(new Timestamp(1_800_003_600_000L), bean.getEventEnd());
        assertEquals("Harpa", bean.getEventLocation());
    }

    @Test
    void copyFromLeavesNeverSetTravelFieldsNull() throws Exception {
        // Entries persisted before V008 have all six travel columns null; the
        // editor must show them as blank, not throw and not fabricate values.
        WeblogEntry entry = storedEntry();

        bean.copyFrom(entry, Locale.US);

        assertNull(bean.getJsonLdType(),
                "A never-chosen type stays null; the editor treats null as the "
                        + "BLOG_POSTING default");
        assertNull(bean.getGeoLatitude());
        assertNull(bean.getGeoLongitude());
        assertNull(bean.getEventStart());
        assertNull(bean.getEventEnd());
        assertNull(bean.getEventLocation());
    }



    @Test
    void copyFromTreatsANullNoindexAsFalseRatherThanThrowing() throws Exception {
        // WeblogEntry.noindex is a boxed Boolean (nullable column, entries
        // persisted before this field existed have no value); EntryBean.noindex
        // is a primitive boolean, so copyFrom must unbox defensively instead of
        // NPEing on every pre-existing entry the moment this field shipped.
        WeblogEntry entry = storedEntry();
        entry.setNoindex(null);

        bean.copyFrom(entry, Locale.US);

        assertFalse(bean.getNoindex());
    }

    @Test
    void copyToFilesTheEntryUnderTheSelectedCategory() throws Exception {
        // The category drives the entry's URL and its feed membership.
        WeblogEntry entry = entryInCategory("cat-1");
        WeblogCategory replacement = new WeblogCategory();
        replacement.setId("cat-2");
        replacement.setName("Food");
        replacement.setWeblog(weblog);

        bean.setStatus(PubStatus.DRAFT.name());
        bean.setCategoryId("cat-2");
        bean.copyTo(entry, replacement);

        assertEquals(replacement, entry.getCategory());
    }

    @Test
    void flagsDefaultToFalseOnAFreshForm() {
        // The "add entry" form starts from a new bean; a flag defaulting to
        // true would silently pin every new post to the site front page.
        EntryBean fresh = new EntryBean();
        assertFalse(fresh.getPinnedToMain());
    }

    private WeblogEntry entryInCategory(String categoryId) throws WebloggerException {
        WeblogCategory category = new WeblogCategory();
        category.setId(categoryId);
        category.setName("Travel");
        category.setWeblog(weblog);
        when(weblogger.getWeblogEntryManager().getWeblogCategory(categoryId)).thenReturn(category);

        WeblogEntry entry = new WeblogEntry();
        entry.setWebsite(weblog);
        entry.setCategory(category);
        return entry;
    }

    private WeblogEntry storedEntry() throws WebloggerException {
        WeblogEntry entry = entryInCategory("cat-1");
        entry.setId("entry-1");
        entry.setTitle("A title");
        entry.setStatus(PubStatus.PUBLISHED);
        entry.setLocale("en_US");
        return entry;
    }

    private static String formatIn(Timestamp timestamp, TimeZone zone) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        format.setTimeZone(zone);
        return format.format(timestamp);
    }

    /** Characterisation: a cleared entry already copies to empty fields; written to pin it, passes at once. */
    @Test
    void aClearedEntryShowsEmptyFieldsInTheEditor() throws Exception {
        WeblogEntry entry = storedEntry();
        entry.setFeaturedImageId(null);
        entry.setGeoLatitude(null);
        entry.setGeoLongitude(null);
        entry.setEventStart(null);
        entry.setEventEnd(null);
        entry.setEventLocation(null);

        bean.copyFrom(entry, Locale.ROOT);

        assertTrue(bean.getFeaturedImageId() == null || bean.getFeaturedImageId().isEmpty());
        assertNull(bean.getGeoLatitude());
        assertNull(bean.getGeoLongitude());
        assertNull(bean.getEventStart());
        assertEquals("", bean.getEventStartLocal());
        assertEquals("", bean.getEventEndLocal());
        assertTrue(bean.getEventLocation() == null || bean.getEventLocation().isEmpty());
    }
}

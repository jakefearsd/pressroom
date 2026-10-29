package com.wikantik.pressroom.ui.restapi.v1;

import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.ui.restapi.dto.EntryDtos;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;
import static org.junit.jupiter.api.Assertions.*;

class EntriesApiWriteTest {

    private static Weblog weblogInZone(String zoneId) {
        Weblog weblog = new Weblog();
        weblog.setTimeZone(zoneId);
        return weblog;
    }

    /**
     * The title must arrive escaped exactly once. Themes emit $entry.title
     * bare, so an unescaped store is stored XSS and a double-escaped store
     * renders "&amp;amp;".
     */
    @Test
    void applyingAWriteEscapesTheTitleExactlyOnce() {
        WeblogEntry entry = new WeblogEntry();
        EntryDtos.EntryWrite write = EntryWrites.write().title("Cats & Dogs").build();

        EntryDtos.applyWrite(entry, write, weblogInZone("UTC"), Set.of());

        assertEquals("Cats &amp; Dogs", entry.getTitle());
    }

    /** A field absent from a PATCH body must not clear the stored value. */
    @Test
    void anAbsentFieldIsLeftAlone() {
        WeblogEntry entry = new WeblogEntry();
        entry.setTitle("kept");
        entry.setText("also kept");
        EntryDtos.EntryWrite write = EntryWrites.write().text("new body").build();

        EntryDtos.applyWrite(entry, write, weblogInZone("UTC"), Set.of());

        assertEquals("kept", entry.getTitle());
        assertEquals("new body", entry.getText());
    }

    /** pubTime is read in the weblog's zone, not the server's. */
    @Test
    void pubTimeIsParsedInTheWeblogsZone() {
        WeblogEntry utcEntry = new WeblogEntry();
        WeblogEntry tokyoEntry = new WeblogEntry();
        EntryDtos.EntryWrite write = EntryWrites.write().pubTime("2026-03-01T09:30").build();

        EntryDtos.applyWrite(utcEntry, write, weblogInZone("UTC"), Set.of());
        EntryDtos.applyWrite(tokyoEntry, write, weblogInZone("Asia/Tokyo"), Set.of());

        assertNotEquals(utcEntry.getPubTime().getTime(), tokyoEntry.getPubTime().getTime());
    }

    @Test
    void aMistypedPubTimeIsRejectedRatherThanPublishingNow() {
        EntryDtos.EntryWrite write = EntryWrites.write().pubTime("yesterday-ish").build();

        assertThrows(com.wikantik.pressroom.ui.restapi.ApiException.class,
                () -> EntryDtos.applyWrite(new WeblogEntry(), write, weblogInZone("UTC"), Set.of()));
    }

    /**
     * Whole-branch review, Must Fix 1: weblogentry.title is varchar(255) NOT
     * NULL and the guard must run AFTER escapeTitle, not before -- a 200
     * -character title made entirely of '&' escapes to 1000 stored
     * characters (each '&' becomes "&amp;"), so a raw-input length check
     * would pass this exact case and still 500 on save. 200 raw characters
     * clears any pre-escape check but overflows the column once escaped.
     */
    @Test
    void aTitleThatOverflowsTheColumnOnlyAfterEscapingIsRejected() {
        String title = "&".repeat(200);
        EntryDtos.EntryWrite write = EntryWrites.write().title(title).build();

        com.wikantik.pressroom.ui.restapi.ApiException ex = assertThrows(
                com.wikantik.pressroom.ui.restapi.ApiException.class,
                () -> EntryDtos.applyWrite(new WeblogEntry(), write, weblogInZone("UTC"), Set.of()));
        assertEquals(400, ex.getStatus());
    }

    /** A 255-character raw title that stays 255 characters once escaped must be accepted. */
    @Test
    void aTitleAtExactlyTheColumnLimitAfterEscapingIsAccepted() {
        String title = "a".repeat(255);
        EntryDtos.EntryWrite write = EntryWrites.write().title(title).build();

        WeblogEntry entry = new WeblogEntry();
        EntryDtos.applyWrite(entry, write, weblogInZone("UTC"), Set.of());
        assertEquals(255, entry.getTitle().length());
    }

    /**
     * metaTitle/searchDescription/canonicalUrl are all weblogentry
     * varchar(255) columns (V006__media_metadata_and_entry_seo.sql) with no
     * escaping step -- unlike title, the raw input length IS the stored
     * length, so a simple over-limit value is enough to prove the guard.
     */
    @Test
    void aMetaTitleLongerThanTheColumnIsRejected() {
        EntryDtos.EntryWrite write = EntryWrites.write().metaTitle("x".repeat(256)).build();

        assertThrows(com.wikantik.pressroom.ui.restapi.ApiException.class,
                () -> EntryDtos.applyWrite(new WeblogEntry(), write, weblogInZone("UTC"), Set.of()));
    }

    @Test
    void aSearchDescriptionLongerThanTheColumnIsRejected() {
        EntryDtos.EntryWrite write = EntryWrites.write().searchDescription("x".repeat(256)).build();

        assertThrows(com.wikantik.pressroom.ui.restapi.ApiException.class,
                () -> EntryDtos.applyWrite(new WeblogEntry(), write, weblogInZone("UTC"), Set.of()));
    }

    @Test
    void aCanonicalUrlLongerThanTheColumnIsRejected() {
        EntryDtos.EntryWrite write = EntryWrites.write().canonicalUrl("x".repeat(256)).build();

        assertThrows(com.wikantik.pressroom.ui.restapi.ApiException.class,
                () -> EntryDtos.applyWrite(new WeblogEntry(), write, weblogInZone("UTC"), Set.of()));
    }

    @Test
    void aWhitespaceOnlySearchDescriptionIsStoredAsNull() {
        WeblogEntry entry = new WeblogEntry();
        entry.setSearchDescription("old");
        EntryDtos.applyWrite(entry, EntryWrites.write().searchDescription("  \t ").build(),
                weblogInZone("UTC"), Set.of());
        assertNull(entry.getSearchDescription());
    }

    @Test
    void paddedSeoFieldsAreStoredTrimmedAndBlankOnesAsNull() {
        WeblogEntry entry = new WeblogEntry();
        EntryDtos.applyWrite(entry, EntryWrites.write().metaTitle("  Title ")
                .searchDescription(" A description.  ").canonicalUrl(" https://example.com/x ").build(),
                weblogInZone("UTC"), Set.of());
        assertEquals("Title", entry.getMetaTitle());
        assertEquals("A description.", entry.getSearchDescription());
        assertEquals("https://example.com/x", entry.getCanonicalUrl());

        EntryDtos.applyWrite(entry, EntryWrites.write().metaTitle(" ").canonicalUrl("").build(),
                weblogInZone("UTC"), Set.of());
        assertNull(entry.getMetaTitle());
        assertNull(entry.getCanonicalUrl());
    }

    @Test
    void techArticleIsAnAcceptedJsonLdType() {
        WeblogEntry entry = new WeblogEntry();
        EntryDtos.applyWrite(entry, EntryWrites.write().jsonLdType("tech_article").build(),
                weblogInZone("UTC"), Set.of());
        assertEquals(com.wikantik.pressroom.pojos.JsonLdType.TECH_ARTICLE, entry.getJsonLdType());
    }

    /** featuredImageId/ogImageId store a pressroom_mediafile id -- varchar(48), the shortest column here. */
    @Test
    void aFeaturedImageIdLongerThanTheColumnIsRejected() {
        EntryDtos.EntryWrite write = EntryWrites.write().featuredImageId("x".repeat(49)).build();

        assertThrows(com.wikantik.pressroom.ui.restapi.ApiException.class,
                () -> EntryDtos.applyWrite(new WeblogEntry(), write, weblogInZone("UTC"), Set.of()));
    }

    @Test
    void anOgImageIdLongerThanTheColumnIsRejected() {
        EntryDtos.EntryWrite write = EntryWrites.write().ogImageId("x".repeat(49)).build();

        assertThrows(com.wikantik.pressroom.ui.restapi.ApiException.class,
                () -> EntryDtos.applyWrite(new WeblogEntry(), write, weblogInZone("UTC"), Set.of()));
    }

    @Test
    void scratchOfCarriesWhatTheCrossFieldChecksReadAndNothingElse() {
        WeblogEntry entry = new WeblogEntry();
        entry.setTitle("not copied");
        entry.setStatus(WeblogEntry.PubStatus.SCHEDULED);
        entry.setPubTime(java.sql.Timestamp.valueOf("2026-03-01 09:30:00"));
        entry.setEventStart(java.sql.Timestamp.valueOf("2026-08-02 19:30:00"));
        entry.setEventEnd(java.sql.Timestamp.valueOf("2026-08-02 22:00:00"));

        WeblogEntry scratch = EntryDtos.scratchOf(entry);

        assertNotSame(entry, scratch);
        assertEquals(entry.getStatus(), scratch.getStatus());
        assertEquals(entry.getPubTime(), scratch.getPubTime());
        assertEquals(entry.getEventStart(), scratch.getEventStart());
        assertEquals(entry.getEventEnd(), scratch.getEventEnd());
        assertNull(scratch.getTitle());
    }

    @Test
    void applyWriteReportsEveryBadFieldAtOnce() {
        EntryDtos.EntryWrite write = EntryWrites.write().status("NOPE").jsonLdType("NOPE")
                .geoLatitude(91.0).build();

        com.wikantik.pressroom.ui.restapi.ApiException ex = assertThrows(
                com.wikantik.pressroom.ui.restapi.ApiException.class,
                () -> EntryDtos.applyWrite(new WeblogEntry(), write, weblogInZone("UTC"), Set.of()));

        assertEquals(List.of("status", "jsonLdType", "geoLatitude"),
                ex.getFieldErrors().stream().map(e -> e.field()).toList());
    }

    // ------------------------------------------------------------ location

    private static EntryDtos.EntryWrite locationWrite(String jsonLdType, Double lat, Double lng) {
        return EntryWrites.write().jsonLdType(jsonLdType).geoLatitude(lat).geoLongitude(lng).build();
    }

    private static void assertRejected(EntryDtos.EntryWrite write) {
        com.wikantik.pressroom.ui.restapi.ApiException ex = assertThrows(
                com.wikantik.pressroom.ui.restapi.ApiException.class,
                () -> EntryDtos.applyWrite(new WeblogEntry(), write, weblogInZone("UTC"), Set.of()));
        assertEquals(400, ex.getStatus());
    }

    /**
     * What a publishing agent sends for a post about one place: the
     * structured-data type, which makes the permalink emit a
     * TouristAttraction block, and the coordinates, which become its geo and
     * centre the entry's [map]. The type is read like status, case-insensitively.
     */
    @Test
    void aLocationMakesTheEntryATouristAttractionAtThoseCoordinates() {
        WeblogEntry entry = new WeblogEntry();

        EntryDtos.applyWrite(entry, locationWrite("tourist_attraction", 32.9228, -117.2534),
                weblogInZone("UTC"), Set.of());

        assertEquals(com.wikantik.pressroom.pojos.JsonLdType.TOURIST_ATTRACTION, entry.getJsonLdType());
        assertEquals(32.9228, entry.getGeoLatitude());
        assertEquals(-117.2534, entry.getGeoLongitude());
    }

    /**
     * JsonLdType.fromString falls back to BLOG_POSTING for a value it does
     * not know, which is right for a stored row and wrong for a request: a
     * typo would quietly drop the place's structured data.
     */
    @Test
    void anUnknownStructuredDataTypeIsRejectedRatherThanFallingBackToBlogPosting() {
        assertRejected(locationWrite("TouristAttractionn", null, null));
        assertRejected(locationWrite(" ", null, null));
    }

    /**
     * The renderer silently ignores coordinates off the globe, so storing
     * them would accept a location that never appears anywhere.
     */
    @Test
    void coordinatesOffTheGlobeAreRejected() {
        assertRejected(locationWrite(null, 90.0001, null));
        assertRejected(locationWrite(null, -90.0001, null));
        assertRejected(locationWrite(null, null, 180.0001));
        assertRejected(locationWrite(null, null, -180.0001));
        assertRejected(locationWrite(null, Double.POSITIVE_INFINITY, null));
        assertRejected(locationWrite(null, null, Double.NaN));
    }

    @Test
    void thePolesAndTheAntimeridianAreOnTheGlobe() {
        WeblogEntry entry = new WeblogEntry();

        EntryDtos.applyWrite(entry, locationWrite(null, -90.0, 180.0), weblogInZone("UTC"), Set.of());

        assertEquals(-90.0, entry.getGeoLatitude());
        assertEquals(180.0, entry.getGeoLongitude());
    }

    /** As with every other field, absent means leave it, not clear it. */
    @Test
    void aWriteWithoutALocationLeavesTheStoredOneAlone() {
        WeblogEntry entry = new WeblogEntry();
        entry.setJsonLdType(com.wikantik.pressroom.pojos.JsonLdType.TOURIST_ATTRACTION);
        entry.setGeoLatitude(52.5);
        entry.setGeoLongitude(13.4);

        EntryDtos.applyWrite(entry, locationWrite(null, null, null), weblogInZone("UTC"), Set.of());

        assertEquals(com.wikantik.pressroom.pojos.JsonLdType.TOURIST_ATTRACTION, entry.getJsonLdType());
        assertEquals(52.5, entry.getGeoLatitude());
        assertEquals(13.4, entry.getGeoLongitude());
    }
}

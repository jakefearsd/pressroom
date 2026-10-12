package org.apache.roller.weblogger.ui.restapi.v1;

import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.apache.roller.weblogger.ui.restapi.dto.EntryDtos;
import org.junit.jupiter.api.Test;
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
        EntryDtos.EntryWrite write = new EntryDtos.EntryWrite(
                "Cats & Dogs", null, null, null, null, null, null,
                null, null, null, null, null, null,
                null, null, null, null, null, null, null, null);

        EntryDtos.applyWrite(entry, write, weblogInZone("UTC"), Set.of());

        assertEquals("Cats &amp; Dogs", entry.getTitle());
    }

    /** A field absent from a PATCH body must not clear the stored value. */
    @Test
    void anAbsentFieldIsLeftAlone() {
        WeblogEntry entry = new WeblogEntry();
        entry.setTitle("kept");
        entry.setText("also kept");
        EntryDtos.EntryWrite write = new EntryDtos.EntryWrite(
                null, null, "new body", null, null, null, null,
                null, null, null, null, null, null,
                null, null, null, null, null, null, null, null);

        EntryDtos.applyWrite(entry, write, weblogInZone("UTC"), Set.of());

        assertEquals("kept", entry.getTitle());
        assertEquals("new body", entry.getText());
    }

    /** pubTime is read in the weblog's zone, not the server's. */
    @Test
    void pubTimeIsParsedInTheWeblogsZone() {
        WeblogEntry utcEntry = new WeblogEntry();
        WeblogEntry tokyoEntry = new WeblogEntry();
        EntryDtos.EntryWrite write = new EntryDtos.EntryWrite(
                null, null, null, null, null, "2026-03-01T09:30", null,
                null, null, null, null, null, null,
                null, null, null, null, null, null, null, null);

        EntryDtos.applyWrite(utcEntry, write, weblogInZone("UTC"), Set.of());
        EntryDtos.applyWrite(tokyoEntry, write, weblogInZone("Asia/Tokyo"), Set.of());

        assertNotEquals(utcEntry.getPubTime().getTime(), tokyoEntry.getPubTime().getTime());
    }

    @Test
    void aMistypedPubTimeIsRejectedRatherThanPublishingNow() {
        EntryDtos.EntryWrite write = new EntryDtos.EntryWrite(
                null, null, null, null, null, "yesterday-ish", null,
                null, null, null, null, null, null,
                null, null, null, null, null, null, null, null);

        assertThrows(org.apache.roller.weblogger.ui.restapi.ApiException.class,
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
        EntryDtos.EntryWrite write = new EntryDtos.EntryWrite(
                title, null, null, null, null, null, null,
                null, null, null, null, null, null,
                null, null, null, null, null, null, null, null);

        org.apache.roller.weblogger.ui.restapi.ApiException ex = assertThrows(
                org.apache.roller.weblogger.ui.restapi.ApiException.class,
                () -> EntryDtos.applyWrite(new WeblogEntry(), write, weblogInZone("UTC"), Set.of()));
        assertEquals(400, ex.getStatus());
    }

    /** A 255-character raw title that stays 255 characters once escaped must be accepted. */
    @Test
    void aTitleAtExactlyTheColumnLimitAfterEscapingIsAccepted() {
        String title = "a".repeat(255);
        EntryDtos.EntryWrite write = new EntryDtos.EntryWrite(
                title, null, null, null, null, null, null,
                null, null, null, null, null, null,
                null, null, null, null, null, null, null, null);

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
        EntryDtos.EntryWrite write = new EntryDtos.EntryWrite(
                null, null, null, null, null, null, null,
                "x".repeat(256), null, null, null, null, null,
                null, null, null, null, null, null, null, null);

        assertThrows(org.apache.roller.weblogger.ui.restapi.ApiException.class,
                () -> EntryDtos.applyWrite(new WeblogEntry(), write, weblogInZone("UTC"), Set.of()));
    }

    @Test
    void aSearchDescriptionLongerThanTheColumnIsRejected() {
        EntryDtos.EntryWrite write = new EntryDtos.EntryWrite(
                null, null, null, null, null, null, null,
                null, "x".repeat(256), null, null, null, null,
                null, null, null, null, null, null, null, null);

        assertThrows(org.apache.roller.weblogger.ui.restapi.ApiException.class,
                () -> EntryDtos.applyWrite(new WeblogEntry(), write, weblogInZone("UTC"), Set.of()));
    }

    @Test
    void aCanonicalUrlLongerThanTheColumnIsRejected() {
        EntryDtos.EntryWrite write = new EntryDtos.EntryWrite(
                null, null, null, null, null, null, null,
                null, null, "x".repeat(256), null, null, null,
                null, null, null, null, null, null, null, null);

        assertThrows(org.apache.roller.weblogger.ui.restapi.ApiException.class,
                () -> EntryDtos.applyWrite(new WeblogEntry(), write, weblogInZone("UTC"), Set.of()));
    }

    /** featuredImageId/ogImageId store a roller_mediafile id -- varchar(48), the shortest column here. */
    @Test
    void aFeaturedImageIdLongerThanTheColumnIsRejected() {
        EntryDtos.EntryWrite write = new EntryDtos.EntryWrite(
                null, null, null, null, null, null, null,
                null, null, null, null, "x".repeat(49), null,
                null, null, null, null, null, null, null, null);

        assertThrows(org.apache.roller.weblogger.ui.restapi.ApiException.class,
                () -> EntryDtos.applyWrite(new WeblogEntry(), write, weblogInZone("UTC"), Set.of()));
    }

    @Test
    void anOgImageIdLongerThanTheColumnIsRejected() {
        EntryDtos.EntryWrite write = new EntryDtos.EntryWrite(
                null, null, null, null, null, null, null,
                null, null, null, null, null, "x".repeat(49),
                null, null, null, null, null, null, null, null);

        assertThrows(org.apache.roller.weblogger.ui.restapi.ApiException.class,
                () -> EntryDtos.applyWrite(new WeblogEntry(), write, weblogInZone("UTC"), Set.of()));
    }

    // ------------------------------------------------------------ location

    private static EntryDtos.EntryWrite locationWrite(String jsonLdType, Double lat, Double lng) {
        return new EntryDtos.EntryWrite(
                null, null, null, null, null, null, null,
                null, null, null, null, null, null,
                jsonLdType, lat, lng, null, null, null, null, null);
    }

    private static void assertRejected(EntryDtos.EntryWrite write) {
        org.apache.roller.weblogger.ui.restapi.ApiException ex = assertThrows(
                org.apache.roller.weblogger.ui.restapi.ApiException.class,
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

        assertEquals(org.apache.roller.weblogger.pojos.JsonLdType.TOURIST_ATTRACTION, entry.getJsonLdType());
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
        entry.setJsonLdType(org.apache.roller.weblogger.pojos.JsonLdType.TOURIST_ATTRACTION);
        entry.setGeoLatitude(52.5);
        entry.setGeoLongitude(13.4);

        EntryDtos.applyWrite(entry, locationWrite(null, null, null), weblogInZone("UTC"), Set.of());

        assertEquals(org.apache.roller.weblogger.pojos.JsonLdType.TOURIST_ATTRACTION, entry.getJsonLdType());
        assertEquals(52.5, entry.getGeoLatitude());
        assertEquals(13.4, entry.getGeoLongitude());
    }
}

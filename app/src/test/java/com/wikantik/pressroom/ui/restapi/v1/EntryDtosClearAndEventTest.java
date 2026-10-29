package com.wikantik.pressroom.ui.restapi.v1;

import java.util.Set;
import com.wikantik.pressroom.pojos.JsonLdType;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.ui.restapi.ApiException;
import com.wikantik.pressroom.ui.restapi.dto.EntryDtos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a PATCH can remove, how events are timed, and which request values are
 * refused outright, at the level of {@link EntryDtos#applyWrite}. Complements
 * {@link EntriesApiWriteTest}, which covers titles, publication time and the
 * column limits.
 */
class EntryDtosClearAndEventTest {

    private static Weblog weblog() {
        Weblog weblog = new Weblog();
        weblog.setTimeZone("Europe/Berlin");
        return weblog;
    }

    /** An EntryWrite carrying only the named fields; everything else null. */
    private static EntryDtos.EntryWrite write(String status, String summary, String metaTitle,
            String searchDescription, String canonicalUrl, Boolean noindex, String featured, String og,
            String eventStart, String eventEnd, String eventLocation, Boolean commentsClosed) {
        return EntryWrites.write()
                .summary(summary).status(status).metaTitle(metaTitle)
                .searchDescription(searchDescription).canonicalUrl(canonicalUrl).noindex(noindex)
                .featuredImageId(featured).ogImageId(og).eventStart(eventStart).eventEnd(eventEnd)
                .eventLocation(eventLocation).commentsClosed(commentsClosed).build();
    }

    @Test
    void everyPlainFieldAWriteCarriesIsStored() {
        WeblogEntry entry = new WeblogEntry();

        EntryDtos.applyWrite(entry, write("draft", "sum", "mt", "sd", "https://example.com/c", true, "feat", "og",
                "2026-08-02T19:30", "2026-08-02T21:00", "The Hall", true), weblog(), Set.of());

        assertEquals(WeblogEntry.PubStatus.DRAFT, entry.getStatus());
        assertEquals("sum", entry.getSummary());
        assertEquals("mt", entry.getMetaTitle());
        assertEquals("sd", entry.getSearchDescription());
        assertEquals("https://example.com/c", entry.getCanonicalUrl());
        assertTrue(entry.getNoindex());
        assertEquals("feat", entry.getFeaturedImageId());
        assertEquals("og", entry.getOgImageId());
        assertEquals("The Hall", entry.getEventLocation());
        assertTrue(entry.getCommentsClosed());
        assertTrue(entry.getEventEnd().after(entry.getEventStart()));
    }

    @Test
    void clearRemovesEachNamedFieldAndOnlyThose() {
        WeblogEntry entry = new WeblogEntry();
        EntryDtos.applyWrite(entry, write(null, "sum", "mt", "sd", "https://example.com/c", null, "feat", "og",
                "2026-08-02T19:30", "2026-08-02T21:00", "The Hall", null), weblog(), Set.of());
        entry.setJsonLdType(JsonLdType.EVENT);
        entry.setGeoLatitude(1.0);
        entry.setGeoLongitude(2.0);
        EntryDtos.EntryWrite nothing = write(null, null, null, null, null, null, null, null, null, null, null, null);

        EntryDtos.applyWrite(entry, nothing, weblog(), Set.of("summary", "metaTitle", "searchDescription",
                "canonicalUrl", "featuredImageId", "ogImageId", "jsonLdType", "geoLatitude", "geoLongitude",
                "eventStart", "eventEnd"));

        assertNull(entry.getSummary());
        assertNull(entry.getMetaTitle());
        assertNull(entry.getSearchDescription());
        assertNull(entry.getCanonicalUrl());
        assertNull(entry.getFeaturedImageId());
        assertNull(entry.getOgImageId());
        assertEquals(JsonLdType.BLOG_POSTING, entry.getJsonLdType(), "clearing the type means the default");
        assertNull(entry.getGeoLatitude());
        assertNull(entry.getGeoLongitude());
        assertNull(entry.getEventStart());
        assertNull(entry.getEventEnd());
        assertEquals("The Hall", entry.getEventLocation(), "not named, so not cleared");
    }

    @Test
    void eventLocationCanBeClearedToo() {
        WeblogEntry entry = new WeblogEntry();
        entry.setEventLocation("The Hall");

        EntryDtos.applyWrite(entry, write(null, null, null, null, null, null, null, null, null, null, null, null),
                weblog(), Set.of("eventLocation"));

        assertNull(entry.getEventLocation());
    }

    @Test
    void aFieldNobodyCanClearIsAProgrammingErrorNotASilentNoOp() {
        WeblogEntry entry = new WeblogEntry();

        assertThrows(IllegalArgumentException.class, () -> EntryDtos.applyWrite(entry,
                write(null, null, null, null, null, null, null, null, null, null, null, null), weblog(),
                Set.of("title")));
    }

    @Test
    void anEventThatEndsBeforeItStartsIsRefused() {
        WeblogEntry entry = new WeblogEntry();

        ApiException ex = assertThrows(ApiException.class, () -> EntryDtos.applyWrite(entry,
                write(null, null, null, null, null, null, null, null, "2026-08-02T21:00", "2026-08-02T19:30", null, null),
                weblog(), Set.of()));

        assertEquals(400, ex.getStatus());
    }

    @Test
    void aBlankOrMalformedEventTimeIsRefusedNotTreatedAsRemoval() {
        for (String bad : new String[] {"  ", "tomorrow evening"}) {
            ApiException ex = assertThrows(ApiException.class, () -> EntryDtos.applyWrite(new WeblogEntry(),
                    write(null, null, null, null, null, null, null, null, bad, null, null, null), weblog(), Set.of()),
                    bad);
            assertEquals(400, ex.getStatus(), bad);
        }
    }

    @Test
    void aStatusMustBeKnownAndWritableAndTrashedIsNotWritable() {
        for (String bad : new String[] {"", "archived", "TRASHED"}) {
            ApiException ex = assertThrows(ApiException.class, () -> EntryDtos.applyWrite(new WeblogEntry(),
                    write(bad, null, null, null, null, null, null, null, null, null, null, null), weblog(), Set.of()),
                    bad);
            assertEquals(400, ex.getStatus(), "'" + bad + "'");
        }
    }

    @Test
    void aFilterStatusIsParsedIgnoringCaseAndSpacesAndRefusesTheUnknown() {
        assertEquals(WeblogEntry.PubStatus.PUBLISHED, EntryDtos.parseFilterStatus(" published "));
        assertEquals(WeblogEntry.PubStatus.TRASHED, EntryDtos.parseFilterStatus("trashed"));
        assertEquals(400, assertThrows(ApiException.class, () -> EntryDtos.parseFilterStatus("nope")).getStatus());
        assertEquals(400, assertThrows(ApiException.class, () -> EntryDtos.parseFilterStatus(null)).getStatus());
    }
}

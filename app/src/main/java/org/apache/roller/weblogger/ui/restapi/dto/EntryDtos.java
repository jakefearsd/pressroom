package org.apache.roller.weblogger.ui.restapi.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.roller.weblogger.pojos.JsonLdType;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.apache.roller.weblogger.pojos.WeblogEntryTag;
import org.apache.roller.weblogger.ui.controllers.EntryFieldRules;
import org.apache.roller.weblogger.ui.restapi.ApiException;
import org.apache.roller.weblogger.ui.restapi.ColumnLimits;

/**
 * Views of a weblog entry for the automation API, plus the two status
 * parsers every write/filter call site uses.
 */
public final class EntryDtos {

    private static final double MAX_LATITUDE = 90;
    private static final double MAX_LONGITUDE = 180;

    /** The fields {@code clear} may name on an entry. */
    public static final List<String> ENTRY_CLEARABLE = List.of("summary", "metaTitle", "searchDescription",
            "canonicalUrl", "featuredImageId", "ogImageId", "jsonLdType", "geoLatitude", "geoLongitude",
            "eventStart", "eventEnd", "eventLocation");

    /** Fields that are cleared together or not at all. */
    public static final List<List<String>> ENTRY_PAIRS = List.of(List.of("geoLatitude", "geoLongitude"));

    private EntryDtos() {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EntryView(
            String id, String anchor, String title, String summary, String text,
            String status, String category, List<String> tags,
            Instant pubTime, Instant updateTime, String permalink,
            String metaTitle, String searchDescription, String canonicalUrl,
            Boolean noindex, String featuredImageId, String ogImageId,
            String jsonLdType, Double geoLatitude, Double geoLongitude,
            Instant eventStart, Instant eventEnd, String eventLocation) {
    }

    public record EntryPage(List<EntryView> items, int offset, int limit, boolean hasMore) {
    }

    /**
     * A preview request. {@code text} is nullable and null (or the whole
     * body being absent -- {@code ApiExceptionHandler} turns that into a 400
     * before a controller method ever runs) is treated as an empty draft
     * rather than rejected: an author previewing a blank editor is a normal
     * moment, not an error.
     */
    public record PreviewRequest(String text) {
    }

    /** The rendered HTML fragment a preview call returns. */
    public record PreviewView(String html) {
    }

    /**
     * Titles are stored HTML-escaped (see CLAUDE.md's entry/page title
     * asymmetry), so the view carries the stored value through unchanged --
     * escaping it again here would send "&amp;amp;" to every client.
     * {@code permalink} is passed in rather than computed here because the
     * entity does not know its own url (that is the {@code URLStrategy}'s
     * job), and this pure mapper has no business depending on the tier.
     */
    public static EntryView toView(WeblogEntry entry, String permalink) {
        return new EntryView(
                entry.getId(),
                entry.getAnchor(),
                entry.getTitle(),
                entry.getSummary(),
                entry.getText(),
                entry.getStatus() == null ? null : entry.getStatus().name(),
                entry.getCategory() == null ? null : entry.getCategory().getName(),
                entry.getTags().stream().map(WeblogEntryTag::getName).sorted().toList(),
                instant(entry.getPubTime()),
                instant(entry.getUpdateTime()),
                permalink,
                entry.getMetaTitle(),
                entry.getSearchDescription(),
                entry.getCanonicalUrl(),
                entry.getNoindex(),
                entry.getFeaturedImageId(),
                entry.getOgImageId(),
                entry.getJsonLdType() == null ? null : entry.getJsonLdType().name(),
                entry.getGeoLatitude(),
                entry.getGeoLongitude(),
                instant(entry.getEventStart()),
                instant(entry.getEventEnd()),
                entry.getEventLocation());
    }

    private static Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    /**
     * Parses a client-supplied status for a WRITE.
     *
     * <p>TRASHED is deliberately not writable: trashing and restoring go
     * through their own endpoints, which are also the paths that keep the
     * Lucene index consistent and bump weblog.lastModified. Letting a PATCH
     * set TRASHED would skip both and leave a trashed entry findable by site
     * search, linking to a permalink that 404s.
     */
    public static WeblogEntry.PubStatus parseWritableStatus(String raw) {
        WeblogEntry.PubStatus status = parseStatus(raw);
        if (status == WeblogEntry.PubStatus.TRASHED) {
            throw ApiException.badRequest(
                    "Use DELETE to trash an entry and POST .../restore to bring it back.");
        }
        return status;
    }

    /**
     * Parses a client-supplied status for a FILTER -- the same four statuses
     * as {@link #parseWritableStatus} plus TRASHED, because reading the
     * trash is exactly what a trash listing is for.
     *
     * <p>Kept as a separate method rather than a boolean parameter on a
     * shared parser: a write check must never be relaxed into a filter check
     * by flipping an argument, so there is no argument that could flip it.
     * {@link #parseWritableStatus}'s TRASHED rejection is unconditional and
     * lives only in that method.
     */
    public static WeblogEntry.PubStatus parseFilterStatus(String raw) {
        return parseStatus(raw);
    }

    private static WeblogEntry.PubStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            throw ApiException.badRequest("status is required.");
        }
        try {
            return WeblogEntry.PubStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Unknown status '" + raw + "'.", e);
        }
    }

    /**
     * A create or partial update. Every component is nullable and null means
     * ABSENT, not "clear this" -- a PATCH that omits a field must leave the
     * stored value alone. {@code category} and {@code tags} are read by the
     * controller instead of {@link #applyWrite}, because both need a manager
     * lookup this pure mapper has no business making.
     */
    public record EntryWrite(
            String title, String summary, String text,
            @Schema(description = "DRAFT, PUBLISHED, PENDING or SCHEDULED; a new entry without one is a "
                    + "DRAFT. TRASHED is not writable: trash, restore and delete-forever are their own calls.")
            String status,
            @Schema(description = "The category's name, not its id. Omitted on create, the weblog's "
                    + "default category.")
            String category,
            @Schema(description = "Wall-clock publication time in the weblog's own timezone, for example "
                    + "2026-03-01T09:30. Publishing without one means now; SCHEDULED needs one.")
            String pubTime,
            @Schema(description = "The entry's tags. On update, replaces the whole set.")
            List<String> tags,
            String metaTitle, String searchDescription, String canonicalUrl,
            Boolean noindex,
            @Schema(description = "Id of a media file in this weblog, used as the featured image.")
            String featuredImageId,
            String ogImageId,
            @Schema(description = "Structured data the permalink emits beside BlogPosting: BLOG_POSTING "
                    + "(the default), TOURIST_ATTRACTION, TOURIST_TRIP, EVENT or FAQ_PAGE.")
            String jsonLdType,
            @Schema(description = "Latitude in decimal degrees, -90 to 90. With TOURIST_ATTRACTION it is "
                    + "the place's geo; it also centres the entry's [map].")
            Double geoLatitude,
            @Schema(description = "Longitude in decimal degrees, -180 to 180.")
            Double geoLongitude,
            @Schema(description = "With EVENT: the start, wall-clock time in the weblog's zone, "
                    + "for example 2026-08-02T19:30.")
            String eventStart,
            @Schema(description = "The end, in the same form; not before eventStart.")
            String eventEnd,
            @Schema(description = "The venue's name.")
            String eventLocation,
            @Schema(description = "On update, fields to remove: summary, metaTitle, searchDescription, "
                    + "canonicalUrl, featuredImageId, ogImageId, jsonLdType (back to BLOG_POSTING), "
                    + "geoLatitude with geoLongitude, eventStart, eventEnd, eventLocation. "
                    + "Null never removes anything.")
            List<String> clear) {
    }

    /**
     * Applies every field {@code write} actually carries onto {@code entry}.
     * {@code category} and {@code tags} are deliberately left untouched here
     * -- see {@link EntryWrite}'s javadoc -- the controller applies both
     * itself after this call returns. {@code cleared} (already validated by
     * {@code ClearFields}) is applied after the sets, before the event-order check.
     */
    public static void applyWrite(WeblogEntry entry, EntryWrite write, Weblog weblog, Set<String> cleared) {
        if (write.title() != null) {
            // The one place raw author input becomes escaped markup, shared
            // with the JSP editor so the two cannot drift. Checked AFTER
            // escaping, not before: escapeHtml4 can grow a title up to 5x
            // (every '&' becomes "&amp;"), so a raw value under the column
            // limit can still overflow it once stored -- a guard on the raw
            // input would pass and still 500 on save.
            String escaped = EntryFieldRules.escapeTitle(write.title());
            ColumnLimits.requireMaxLength("title", escaped, ColumnLimits.ENTRY_TITLE);
            entry.setTitle(escaped);
        }
        if (write.summary() != null) {
            entry.setSummary(write.summary());
        }
        if (write.text() != null) {
            entry.setText(write.text());
        }
        if (write.status() != null) {
            entry.setStatus(parseWritableStatus(write.status()));
        }
        if (write.pubTime() != null) {
            try {
                entry.setPubTime(EntryFieldRules.parsePubTime(
                        write.pubTime(), weblog.getTimeZoneInstance()));
            } catch (DateTimeParseException e) {
                // EntryFieldRules.parsePubTime throws DateTimeParseException,
                // NOT IllegalArgumentException -- DateTimeParseException does
                // not extend it, so catching the wrong type here would let a
                // mistyped pubtime escape as an opaque 500 instead of the
                // 400 this exists to produce.
                throw ApiException.badRequest(
                        "pubTime must be a wall-clock time in the weblog's zone, "
                        + "for example 2026-03-01T09:30.", e);
            }
        }
        if (write.metaTitle() != null) {
            ColumnLimits.requireMaxLength("metaTitle", write.metaTitle(), ColumnLimits.META_TITLE);
            entry.setMetaTitle(write.metaTitle());
        }
        if (write.searchDescription() != null) {
            ColumnLimits.requireMaxLength(
                    "searchDescription", write.searchDescription(), ColumnLimits.SEARCH_DESCRIPTION);
            entry.setSearchDescription(write.searchDescription());
        }
        if (write.canonicalUrl() != null) {
            ColumnLimits.requireMaxLength("canonicalUrl", write.canonicalUrl(), ColumnLimits.CANONICAL_URL);
            entry.setCanonicalUrl(write.canonicalUrl());
        }
        if (write.noindex() != null) {
            entry.setNoindex(write.noindex());
        }
        if (write.featuredImageId() != null) {
            ColumnLimits.requireMaxLength(
                    "featuredImageId", write.featuredImageId(), ColumnLimits.FEATURED_IMAGE_ID);
            entry.setFeaturedImageId(write.featuredImageId());
        }
        if (write.ogImageId() != null) {
            ColumnLimits.requireMaxLength("ogImageId", write.ogImageId(), ColumnLimits.OG_IMAGE_ID);
            entry.setOgImageId(write.ogImageId());
        }
        // The location: the type decides which structured-data block the
        // permalink emits (TOURIST_ATTRACTION carries the coordinates as its
        // geo), and the coordinates also centre the entry's [map].
        if (write.jsonLdType() != null) {
            entry.setJsonLdType(parseJsonLdType(write.jsonLdType()));
        }
        if (write.geoLatitude() != null) {
            entry.setGeoLatitude(requireOnTheGlobe("geoLatitude", write.geoLatitude(), MAX_LATITUDE));
        }
        if (write.geoLongitude() != null) {
            entry.setGeoLongitude(requireOnTheGlobe("geoLongitude", write.geoLongitude(), MAX_LONGITUDE));
        }
        if (write.eventStart() != null) {
            entry.setEventStart(eventTime("eventStart", write.eventStart(), weblog));
        }
        if (write.eventEnd() != null) {
            entry.setEventEnd(eventTime("eventEnd", write.eventEnd(), weblog));
        }
        if (write.eventLocation() != null) {
            ColumnLimits.requireMaxLength("eventLocation", write.eventLocation(), ColumnLimits.EVENT_LOCATION);
            entry.setEventLocation(write.eventLocation());
        }
        for (String name : cleared) {
            clearField(entry, name);
        }
        if (EntryFieldRules.endsBeforeStart(entry.getEventStart(), entry.getEventEnd())) {
            throw ApiException.badRequest("eventEnd must not be before eventStart.");
        }
    }

    private static void clearField(WeblogEntry entry, String name) {
        switch (name) {
            case "summary" -> entry.setSummary(null);
            case "metaTitle" -> entry.setMetaTitle(null);
            case "searchDescription" -> entry.setSearchDescription(null);
            case "canonicalUrl" -> entry.setCanonicalUrl(null);
            case "featuredImageId" -> entry.setFeaturedImageId(null);
            case "ogImageId" -> entry.setOgImageId(null);
            case "jsonLdType" -> entry.setJsonLdType(JsonLdType.BLOG_POSTING);
            case "geoLatitude" -> entry.setGeoLatitude(null);
            case "geoLongitude" -> entry.setGeoLongitude(null);
            case "eventStart" -> entry.setEventStart(null);
            case "eventEnd" -> entry.setEventEnd(null);
            case "eventLocation" -> entry.setEventLocation(null);
            default -> throw new IllegalArgumentException("Not clearable: " + name);
        }
    }

    /** A blank value is refused, not read as "remove": removing is clear's job. */
    private static Timestamp eventTime(String field, String raw, Weblog weblog) {
        String detail = field + " must be a wall-clock time in the weblog's zone, for example 2026-08-02T19:30.";
        if (raw.isBlank()) {
            throw ApiException.badRequest(detail);
        }
        try {
            return EntryFieldRules.parseEventTime(raw, weblog.getTimeZoneInstance());
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest(detail, e);
        }
    }

    /**
     * Strict, unlike {@link JsonLdType#fromString}, which falls back to
     * BLOG_POSTING so a stored row it does not know still renders. From a
     * request that fallback would turn a typo into an entry that quietly
     * loses its structured data.
     */
    private static JsonLdType parseJsonLdType(String raw) {
        try {
            return JsonLdType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Unknown jsonLdType '" + raw + "'; it is one of "
                    + Arrays.stream(JsonLdType.values()).map(Enum::name)
                            .collect(Collectors.joining(", ")) + ".", e);
        }
    }

    /**
     * The renderer drops coordinates that are not finite or lie off the
     * globe (EntryJsonLd.geoOf, MapShortcode.centerOf), so storing them
     * would accept a location no reader or search engine ever sees.
     */
    private static Double requireOnTheGlobe(String field, Double value, double max) {
        if (!Double.isFinite(value) || Math.abs(value) > max) {
            throw ApiException.badRequest(field + " must be between -" + (int) max
                    + " and " + (int) max + ".");
        }
        return value;
    }
}

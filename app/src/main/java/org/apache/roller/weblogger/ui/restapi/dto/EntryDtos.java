package org.apache.roller.weblogger.ui.restapi.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.roller.weblogger.business.ContentHashes;
import org.apache.roller.weblogger.pojos.JsonLdType;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.apache.roller.weblogger.pojos.WeblogEntryTag;
import org.apache.roller.weblogger.ui.controllers.EntryFieldRules;
import org.apache.roller.weblogger.ui.restapi.ApiException;
import org.apache.roller.weblogger.ui.restapi.FieldErrors;
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

    /** {@code edits}' text, one for update and preview, since one MCP property serves both. */
    static final String EDITS_DESCRIPTION = "Find/replace pairs applied to the entry's stored text, in order, "
            + "each to the text as the previous ones left it: on update, and on preview with id. Each find must "
            + "occur exactly once, or the request is refused naming every failing edit, and nothing is saved. At "
            + "most 50; not with text. The result is saved as though sent as text (importExternalImages included).";

    /**
     * {@code expectedContentHash}'s text, one for update and a revision
     * restore, since one MCP property serves both.
     */
    static final String EXPECTED_CONTENT_HASH_DESCRIPTION = "On update and on a revision restore, the contentHash "
            + "the entry was read with. When the stored text's hash differs, someone changed the entry since: the "
            + "request is a 409 entry-changed whose currentContentHash is the stored text's hash, and nothing is "
            + "saved. Get the entry again and redo the change. Without it, the write overwrites whatever is stored.";

    private EntryDtos() {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EntryView(
            String id, String anchor, String title, String summary, String text,
            @Schema(description = "SHA-256 of the stored text (UTF-8), lower-case hex. Send it back as "
                    + "expectedContentHash on update so the update is refused if the entry changed meanwhile.")
            String contentHash,
            String status, String category, List<String> tags,
            Instant pubTime, Instant updateTime, String permalink,
            String metaTitle, String searchDescription, String canonicalUrl,
            Boolean noindex, String featuredImageId, String ogImageId,
            String jsonLdType, Double geoLatitude, Double geoLongitude,
            Instant eventStart, Instant eventEnd, String eventLocation, Boolean commentsClosed) {
    }

    public record EntryPage(List<EntryView> items, int offset, int limit, boolean hasMore) {
    }

    /**
     * A preview request. {@code text} is nullable and null (or the whole
     * body being absent -- {@code ApiExceptionHandler} turns that into a 400
     * before a controller method ever runs) is treated as an empty draft
     * rather than rejected: an author previewing a blank editor is a normal
     * moment, not an error. {@code edits}, previewing an existing entry,
     * apply to its stored text in place of {@code text}.
     */
    public record PreviewRequest(
            String text,
            @Schema(description = EDITS_DESCRIPTION)
            List<TextEdit> edits) {
    }

    /**
     * The rendered HTML fragment a preview call returns, and what in it will
     * render badly on some theme or for some reader (never null; empty when
     * nothing does). Advice only: the same text saves without complaint.
     */
    public record PreviewView(String html, List<String> warnings) {
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
                contentHash(entry.getText()),
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
                entry.getEventLocation(),
                entry.getCommentsClosed());
    }

    /**
     * SHA-256 of {@code text} as UTF-8, bare lower-case hex: the scheme of
     * Wikantik's read_page/update_page, so an agent working across the stack
     * learns one. Null for null text.
     */
    public static String contentHash(String text) {
        return text == null ? null : ContentHashes.sha256Hex(text.getBytes(StandardCharsets.UTF_8));
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
            throw ApiException.invalidField("status", "status is required.");
        }
        try {
            return WeblogEntry.PubStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.invalidField("status", "Unknown status '" + raw + "'.", e);
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
            String title,
            @Schema(description = "A sentence or two shown under the title in lists of entries, and as the "
                    + "feed's summary. Markdown is accepted; lists show it as plain text. Without one, lists show "
                    + "the first couple of sentences of the text.")
            String summary,
            String text,
            @Schema(description = "DRAFT, PUBLISHED, PENDING or SCHEDULED; a new entry without one is a "
                    + "DRAFT. TRASHED is not writable: trash, restore and delete-forever are their own calls.")
            String status,
            @Schema(description = "The category's name, not its id. Omitted on create, the weblog's "
                    + "default category.")
            String category,
            @Schema(description = "Wall-clock publication time in the weblog's own timezone, for example "
                    + "2026-03-01T09:30, or an instant with Z or an offset (2026-03-01T08:30:00Z, the form "
                    + "an entry is read back with). Publishing without one means now; SCHEDULED needs one.")
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
                    + "for example 2026-08-02T19:30, or an instant such as 2026-08-02T17:30:00Z.")
            String eventStart,
            @Schema(description = "The end, in the same form; not before eventStart.")
            String eventEnd,
            @Schema(description = "The venue's name.")
            String eventLocation,
            @Schema(description = "True to stop new comments on this entry; existing ones stay. Not clearable.")
            Boolean commentsClosed,
            @Schema(description = "The entry's URL slug: lower-case letters and digits in words joined by - or _, "
                    + "for example torrey-pines. On create it sets the slug, made from the title when omitted, and "
                    + "one another entry of the weblog holds, a trashed one included, answers 409; it cannot change "
                    + "after. entries get (GET .../entries/by-anchor/{anchor}) finds the entry by it.")
            String anchor,
            @Schema(description = "A public http(s) URL of a JPEG, PNG, GIF or WebP image to import as the "
                    + "featured image, in one call. An image already in the weblog's default directory with the "
                    + "same bytes is reused. Not with featuredImageId.")
            String featuredImageUrl,
            @Schema(description = "Alt text for the image featuredImageUrl imports; it fills the file's alt "
                    + "text only when that has none.")
            String featuredImageAlt,
            @Schema(description = "True to import every image the text loads from another site (HTML img src "
                    + "and Markdown images, at most 10) and point the text at the copies. Without text on an "
                    + "update, the stored text. All or nothing: if any image fails, the entry is not written; "
                    + "images already imported stay in the media library and a retry reuses them.")
            Boolean importExternalImages,
            @Schema(description = "On update, fields to remove: summary, metaTitle, searchDescription, "
                    + "canonicalUrl, featuredImageId, ogImageId, jsonLdType (back to BLOG_POSTING), "
                    + "geoLatitude with geoLongitude, eventStart, eventEnd, eventLocation. "
                    + "Null never removes anything.")
            List<String> clear,
            @Schema(description = EDITS_DESCRIPTION)
            List<TextEdit> edits,
            @Schema(description = EXPECTED_CONTENT_HASH_DESCRIPTION)
            String expectedContentHash,
            @Schema(description = "On update, why this change is made, at most 255 characters, for example "
                    + "\"Shorten the intro\". It labels, in the entry's revision list, the revision the update "
                    + "leaves: the content it replaced. An update that changes no title, text or summary leaves no "
                    + "revision, and its note is dropped. Not on create.")
            String revisionNote) {

        /**
         * This write with what the import step produced: the featured image it
         * set and the text it rewrote. A null argument keeps the write's own value.
         */
        public EntryWrite withImports(String importedFeaturedImageId, String rewrittenText) {
            return with(importedFeaturedImageId != null ? importedFeaturedImageId : featuredImageId,
                    rewrittenText != null ? rewrittenText : text);
        }

        /**
         * This write with {@code resolvedText} as its text: what its {@code
         * edits} made of the stored text. From here on the write is handled
         * exactly as though the caller had sent that text.
         */
        public EntryWrite withText(String resolvedText) {
            return with(featuredImageId, resolvedText);
        }

        private EntryWrite with(String newFeaturedImageId, String newText) {
            return new EntryWrite(title, summary, newText, status, category, pubTime, tags, metaTitle,
                    searchDescription, canonicalUrl, noindex, newFeaturedImageId, ogImageId, jsonLdType,
                    geoLatitude, geoLongitude, eventStart, eventEnd, eventLocation, commentsClosed, anchor,
                    featuredImageUrl, featuredImageAlt, importExternalImages, clear, edits, expectedContentHash,
                    revisionNote);
        }
    }

    /** One exact-text replacement of an update's {@code edits}. */
    public record TextEdit(
            @Schema(description = "Text of the entry, exactly as stored (get returns it), that must occur exactly "
                    + "once; include surrounding text until it does.")
            String find,
            @Schema(description = "What replaces it; an empty string deletes it.")
            String replace) {
    }

    /**
     * Applies every field {@code write} actually carries onto {@code entry}.
     * {@code category} and {@code tags} are deliberately left untouched here
     * -- see {@link EntryWrite}'s javadoc -- the controller applies both
     * itself after this call returns. {@code cleared} (already validated by
     * {@code ClearFields}) is applied after the sets, before the event-order check.
     * Every field's problem is collected; the one 400 thrown at the end names them all.
     */
    public static void applyWrite(WeblogEntry entry, EntryWrite write, Weblog weblog, Set<String> cleared) {
        FieldErrors errors = new FieldErrors();
        if (write.title() != null) {
            errors.check("title", () -> {
                // The one place raw author input becomes escaped markup, shared
                // with the JSP editor so the two cannot drift. Checked AFTER
                // escaping, not before: escapeHtml4 can grow a title up to 5x
                // (every '&' becomes "&amp;"), so a raw value under the column
                // limit can still overflow it once stored -- a guard on the raw
                // input would pass and still 500 on save.
                String escaped = EntryFieldRules.escapeTitle(write.title());
                ColumnLimits.requireMaxLength("title", escaped, ColumnLimits.ENTRY_TITLE);
                entry.setTitle(escaped);
            });
        }
        if (write.summary() != null) {
            entry.setSummary(write.summary());
        }
        if (write.text() != null) {
            entry.setText(write.text());
        }
        if (write.status() != null) {
            errors.check("status", () -> entry.setStatus(parseWritableStatus(write.status())));
        }
        if (write.pubTime() != null) {
            errors.check("pubTime", () -> entry.setPubTime(pubTime(write.pubTime(), weblog)));
        }
        if (write.metaTitle() != null) {
            errors.check("metaTitle", () -> {
                ColumnLimits.requireMaxLength("metaTitle", write.metaTitle(), ColumnLimits.META_TITLE);
                entry.setMetaTitle(write.metaTitle());
            });
        }
        if (write.searchDescription() != null) {
            errors.check("searchDescription", () -> {
                ColumnLimits.requireMaxLength(
                        "searchDescription", write.searchDescription(), ColumnLimits.SEARCH_DESCRIPTION);
                entry.setSearchDescription(write.searchDescription());
            });
        }
        if (write.canonicalUrl() != null) {
            errors.check("canonicalUrl", () -> {
                ColumnLimits.requireMaxLength("canonicalUrl", write.canonicalUrl(), ColumnLimits.CANONICAL_URL);
                entry.setCanonicalUrl(write.canonicalUrl());
            });
        }
        if (write.commentsClosed() != null) {
            entry.setCommentsClosed(write.commentsClosed());
        }
        if (write.noindex() != null) {
            entry.setNoindex(write.noindex());
        }
        if (write.featuredImageId() != null) {
            errors.check("featuredImageId", () -> {
                ColumnLimits.requireMaxLength(
                        "featuredImageId", write.featuredImageId(), ColumnLimits.FEATURED_IMAGE_ID);
                entry.setFeaturedImageId(write.featuredImageId());
            });
        }
        if (write.ogImageId() != null) {
            errors.check("ogImageId", () -> {
                ColumnLimits.requireMaxLength("ogImageId", write.ogImageId(), ColumnLimits.OG_IMAGE_ID);
                entry.setOgImageId(write.ogImageId());
            });
        }
        // The location: the type decides which structured-data block the
        // permalink emits (TOURIST_ATTRACTION carries the coordinates as its
        // geo), and the coordinates also centre the entry's [map].
        if (write.jsonLdType() != null) {
            errors.check("jsonLdType", () -> entry.setJsonLdType(parseJsonLdType(write.jsonLdType())));
        }
        if (write.geoLatitude() != null) {
            errors.check("geoLatitude", () -> entry.setGeoLatitude(
                    requireOnTheGlobe("geoLatitude", write.geoLatitude(), MAX_LATITUDE)));
        }
        if (write.geoLongitude() != null) {
            errors.check("geoLongitude", () -> entry.setGeoLongitude(
                    requireOnTheGlobe("geoLongitude", write.geoLongitude(), MAX_LONGITUDE)));
        }
        // [start, end]: false while a sent time has not parsed. The order check
        // below is skipped then, or it would judge the other time against a stale one.
        boolean[] timeOk = {write.eventStart() == null, write.eventEnd() == null};
        if (write.eventStart() != null) {
            errors.check("eventStart", () -> {
                entry.setEventStart(eventTime("eventStart", write.eventStart(), weblog));
                timeOk[0] = true;
            });
        }
        if (write.eventEnd() != null) {
            errors.check("eventEnd", () -> {
                entry.setEventEnd(eventTime("eventEnd", write.eventEnd(), weblog));
                timeOk[1] = true;
            });
        }
        if (write.eventLocation() != null) {
            errors.check("eventLocation", () -> {
                ColumnLimits.requireMaxLength("eventLocation", write.eventLocation(), ColumnLimits.EVENT_LOCATION);
                entry.setEventLocation(write.eventLocation());
            });
        }
        for (String name : cleared) {
            clearField(entry, name);
        }
        if (timeOk[0] && timeOk[1] && EntryFieldRules.endsBeforeStart(entry.getEventStart(), entry.getEventEnd())) {
            errors.add("eventEnd", "eventEnd must not be before eventStart.");
        }
        errors.throwIfAny();
    }

    /**
     * {@code raw} read as wall-clock time in the weblog's zone.
     * EntryFieldRules.parsePubTime throws DateTimeParseException, NOT
     * IllegalArgumentException -- DateTimeParseException does not extend it,
     * so catching the wrong type here would let a mistyped pubtime escape as
     * an opaque 500 instead of the 400 this exists to produce.
     */
    private static Timestamp pubTime(String raw, Weblog weblog) {
        try {
            return EntryFieldRules.parsePubTime(raw, weblog.getTimeZoneInstance());
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest(
                    "pubTime must be a wall-clock time in the weblog's zone, for example 2026-03-01T09:30, "
                            + "or an instant such as 2026-03-01T08:30:00Z.", e);
        }
    }

    /**
     * A detached copy of {@code entry} carrying exactly what a write's
     * cross-field checks read -- status, pubTime and the event times -- so a
     * write can be checked in full before anything touches the real entry.
     * Checking against the managed entity itself would leave it half-written
     * on a refusal, and the import step that runs between checking and
     * applying commits the persistence context.
     */
    public static WeblogEntry scratchOf(WeblogEntry entry) {
        WeblogEntry scratch = new WeblogEntry();
        scratch.setWebsite(entry.getWebsite());
        scratch.setStatus(entry.getStatus());
        scratch.setPubTime(entry.getPubTime());
        scratch.setEventStart(entry.getEventStart());
        scratch.setEventEnd(entry.getEventEnd());
        return scratch;
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
        String detail = field + " must be a wall-clock time in the weblog's zone, for example 2026-08-02T19:30, "
                + "or an instant such as 2026-08-02T17:30:00Z.";
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

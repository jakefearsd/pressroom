package com.wikantik.pressroom.ui.restapi;

import java.util.Set;

/**
 * Every {@code code} a problem body or a field error can carry: a stable,
 * case-sensitive string of dotted lowerCamel segments that clients branch on
 * (spec 2026-11-20-api-contract-design, Task 1). The code is the contract;
 * its message key ({@code api.problem.<code>} in {@code
 * ApplicationResources.properties}) is an implementation detail, and its
 * English is what {@code detail} reads for a keyed problem.
 *
 * <p>{@code arity} is the number of arguments the code's text takes, the
 * number of distinct {@code {n}} placeholders in its English. Arguments are
 * strings, formatted by the call site, so {@code MessageFormat} never groups
 * a number. A code with arity 0 is rendered without {@code MessageFormat},
 * as the JSPs render theirs, so its apostrophes are written single; one with
 * placeholders writes a literal apostrophe {@code ''}.
 *
 * <p>Adding a code: add the constant, its English under its key, its row in
 * the README's code table, and its string to {@code ProblemCodeCatalogTest}'s
 * pin. Never rename or remove one: a client branching on it breaks.
 * {@code docs/dev/api.md}, "Problem codes", has the rest.
 */
public enum ProblemCode {

    /** The generic 400: a literal-detail refusal not yet given a code of its own. */
    REQUEST_INVALID("request.invalid", ProblemType.INVALID_REQUEST, 0),
    /** A 400 naming more than one field; each {@code errors[]} entry carries its own code. */
    REQUEST_INVALID_FIELDS("request.invalidFields", ProblemType.INVALID_REQUEST, 0),
    REQUEST_MALFORMED_BODY("request.malformedBody", ProblemType.INVALID_REQUEST, 0),
    /** {0}: the parameter's name. */
    PARAMETER_NOT_WHOLE_NUMBER("parameter.notWholeNumber", ProblemType.INVALID_REQUEST, 1),
    /** {0}: the parameter's name. */
    PARAMETER_NOT_NUMBER("parameter.notNumber", ProblemType.INVALID_REQUEST, 1),
    /** {0}: the parameter's name. */
    PARAMETER_NOT_BOOLEAN("parameter.notBoolean", ProblemType.INVALID_REQUEST, 1),
    /** {0}: the parameter's name. */
    PARAMETER_INVALID_VALUE("parameter.invalidValue", ProblemType.INVALID_REQUEST, 1),
    PAGING_INVALID_RANGE("paging.invalidRange", ProblemType.INVALID_REQUEST, 0),
    AUTH_UNAUTHORIZED("auth.unauthorized", ProblemType.UNAUTHORIZED, 0),
    AUTH_FORBIDDEN("auth.forbidden", ProblemType.FORBIDDEN, 0),
    RESOURCE_NOT_FOUND("resource.notFound", ProblemType.NOT_FOUND, 0),
    REQUEST_NOT_ACCEPTABLE("request.notAcceptable", ProblemType.NOT_ACCEPTABLE, 0),
    RESOURCE_CONFLICT("resource.conflict", ProblemType.CONFLICT, 0),
    ENTRY_CHANGED("entry.changed", ProblemType.ENTRY_CHANGED, 0),
    PAGE_CHANGED("page.changed", ProblemType.PAGE_CHANGED, 0),
    /** {0}: the byte limit, as digits. */
    REQUEST_TOO_LARGE("request.tooLarge", ProblemType.TOO_LARGE, 1),
    REQUEST_UNSUPPORTED_MEDIA_TYPE("request.unsupportedMediaType", ProblemType.UNSUPPORTED_MEDIA_TYPE, 0),
    REQUEST_THROTTLED("request.throttled", ProblemType.THROTTLED, 0),
    SERVER_INTERNAL_ERROR("server.internalError", ProblemType.INTERNAL_ERROR, 0),
    UPSTREAM_FAILURE("upstream.failure", ProblemType.UPSTREAM_FAILURE, 0),
    SERVER_BUSY("server.busy", ProblemType.BUSY, 0),
    /** No entry has this id in the weblog (or the caller may not see it). */
    ENTRY_NOT_FOUND("entry.notFound", ProblemType.NOT_FOUND, 0),
    /** No live page has this id in the weblog. */
    PAGE_NOT_FOUND("page.notFound", ProblemType.NOT_FOUND, 0),
    /** The entry or page has no revision with this id. */
    REVISION_NOT_FOUND("revision.notFound", ProblemType.NOT_FOUND, 0),
    /** No weblog is addressed by this request. */
    WEBLOG_NOT_FOUND("weblog.notFound", ProblemType.NOT_FOUND, 0),
    /** A write reached the controller with no signed-in user. */
    AUTH_NOT_AUTHENTICATED("auth.notAuthenticated", ProblemType.UNAUTHORIZED, 0),
    /** The entry is in the trash; restore it before editing or previewing it. */
    ENTRY_TRASHED("entry.trashed", ProblemType.CONFLICT, 0),
    /** Trash was asked for an entry already in the trash. */
    ENTRY_ALREADY_TRASHED("entry.alreadyTrashed", ProblemType.CONFLICT, 0),
    /** Restore was asked for an entry that is not in the trash. */
    ENTRY_NOT_IN_TRASH("entry.notInTrash", ProblemType.CONFLICT, 0),
    /** Delete-forever was asked for an entry that is not in the trash. */
    ENTRY_DELETE_NEEDS_TRASH("entry.deleteForeverNeedsTrash", ProblemType.CONFLICT, 0),
    /** The page is in the trash; restore it before changing it. */
    PAGE_IN_TRASH("page.trashed", ProblemType.CONFLICT, 0),
    /** Trash was asked for a page already in the trash. */
    PAGE_ALREADY_TRASHED("page.alreadyTrashed", ProblemType.CONFLICT, 0),
    /** Restore was asked for a page that is not in the trash. */
    PAGE_NOT_IN_TRASH("page.notInTrash", ProblemType.CONFLICT, 0),
    /** Delete-forever was asked for a page that is not in the trash. */
    PAGE_DELETE_NEEDS_TRASH("page.deleteForeverNeedsTrash", ProblemType.CONFLICT, 0),
    /** The entry or page already has its 10 kept revisions; release one first. */
    REVISION_KEEP_LIMIT("revision.keepLimit", ProblemType.CONFLICT, 0),
    /** A required field is missing or blank; `{0}` is its name. */
    FIELD_REQUIRED("field.required", ProblemType.INVALID_REQUEST, 1),
    /** A field that may be omitted was sent blank. */
    FIELD_BLANK("field.blank", ProblemType.INVALID_REQUEST, 1),
    /** A value is longer than its column allows. */
    FIELD_TOO_LONG("field.tooLong", ProblemType.INVALID_REQUEST, 2),
    /** A field that acts on stored content (`clear`, `expectedContentHash`) was sent on a create. */
    FIELD_UPDATE_ONLY("field.updateOnly", ProblemType.INVALID_REQUEST, 1),
    /** `revisionNote` was sent on a create. */
    REVISION_NOTE_CREATE("revisionNote.onCreate", ProblemType.INVALID_REQUEST, 0),
    /** `status` is not one of the known statuses. */
    STATUS_UNKNOWN("status.unknown", ProblemType.INVALID_REQUEST, 1),
    /** An entry write set `status` to TRASHED. */
    ENTRY_STATUS_TRASH("entry.status.trashNotWritable", ProblemType.INVALID_REQUEST, 0),
    /** A page write set `status` to TRASHED. */
    PAGE_STATUS_TRASH("page.status.trashNotWritable", ProblemType.INVALID_REQUEST, 0),
    /** The pages list `status` filter is not TRASHED. */
    PAGE_STATUS_FILTER("page.statusFilterInvalid", ProblemType.INVALID_REQUEST, 0),
    /** An entry's `expectedContentHash` is not a 64-digit hex hash. */
    ENTRY_HASH_INVALID("entry.expectedContentHashInvalid", ProblemType.INVALID_REQUEST, 0),
    /** A page's `expectedContentHash` is not a 64-digit hex hash. */
    PAGE_HASH_INVALID("page.expectedContentHashInvalid", ProblemType.INVALID_REQUEST, 0),
    /** `fields` names one field the entry view lacks. */
    FIELDS_UNKNOWN("fields.unknown", ProblemType.INVALID_REQUEST, 2),
    /** `fields` names several fields the entry view lacks. */
    FIELDS_UNKNOWN_MANY("fields.unknownMany", ProblemType.INVALID_REQUEST, 2),
    /** A write sent both `text` and `edits`. */
    EDITS_WITH_TEXT("edits.withText", ProblemType.INVALID_REQUEST, 0),
    /** More edits than one request applies. */
    EDITS_TOO_MANY("edits.tooMany", ProblemType.INVALID_REQUEST, 2),
    /** An `edits` item is null. {1}: the shape of an edit, as written in the request. */
    EDITS_EMPTY("edits.empty", ProblemType.INVALID_REQUEST, 2),
    /** An `edits` item has no `find`. */
    EDITS_NO_FIND("edits.noFind", ProblemType.INVALID_REQUEST, 1),
    /** An `edits` item has no `replace`. */
    EDITS_NO_REPLACE("edits.noReplace", ProblemType.INVALID_REQUEST, 1),
    /** An edit's `find` is not in the stored text. */
    EDITS_NOT_FOUND("edits.notFound", ProblemType.INVALID_REQUEST, 1),
    /** An edit's `find` matches more than once. */
    EDITS_AMBIGUOUS("edits.ambiguous", ProblemType.INVALID_REQUEST, 2),
    /** `edits` was sent on a create. */
    EDITS_ON_CREATE("edits.onCreate", ProblemType.INVALID_REQUEST, 0),
    /** `edits` was sent to preview without an entry id. */
    EDITS_PREVIEW_NEEDS_ID("edits.previewNeedsEntry", ProblemType.INVALID_REQUEST, 0),
    /** An update sent a different `anchor`. */
    ANCHOR_IMMUTABLE("anchor.immutable", ProblemType.INVALID_REQUEST, 0),
    /** `anchor` is not in the allowed form; `{0}` is the length limit. */
    ANCHOR_INVALID("anchor.invalidForm", ProblemType.INVALID_REQUEST, 1),
    /** A trashed entry of the weblog holds the `anchor`. */
    ANCHOR_HELD_BY_TRASH("anchor.heldByTrashedEntry", ProblemType.CONFLICT, 1),
    /** Another entry of the weblog holds the `anchor`. */
    ANCHOR_IN_USE("anchor.inUse", ProblemType.CONFLICT, 1),
    /** `status` SCHEDULED with no `pubTime`. */
    PUBTIME_REQUIRED("pubTime.requiredForScheduled", ProblemType.INVALID_REQUEST, 0),
    /** `pubTime` does not parse. */
    PUBTIME_INVALID("pubTime.invalid", ProblemType.INVALID_REQUEST, 0),
    /** `eventStart` or `eventEnd` (`{0}`) is blank or does not parse. */
    EVENT_TIME_INVALID("eventTime.invalid", ProblemType.INVALID_REQUEST, 1),
    /** `eventEnd` precedes `eventStart`. */
    EVENT_END_BEFORE_START("eventEnd.beforeStart", ProblemType.INVALID_REQUEST, 0),
    /** `jsonLdType` is not a known type. */
    JSONLD_TYPE_UNKNOWN("jsonLdType.unknown", ProblemType.INVALID_REQUEST, 2),
    /** `geoLatitude` or `geoLongitude` is off the globe. */
    GEO_OUT_OF_RANGE("geo.outOfRange", ProblemType.INVALID_REQUEST, 2),
    /** An entry's `strand` is not one of the blog's strand pages. */
    STRAND_NOT_A_STRAND("strand.notAStrandPage", ProblemType.INVALID_REQUEST, 0),
    /** The weblog already has its most pinned posts. */
    PINNED_LIMIT("pinned.limit", ProblemType.INVALID_REQUEST, 1),
    /** `category` names no category of the weblog. */
    CATEGORY_UNKNOWN("category.unknown", ProblemType.INVALID_REQUEST, 2),
    /** An image id field does not name a public image of the weblog. */
    IMAGE_NOT_PUBLIC("image.notPublic", ProblemType.INVALID_REQUEST, 1),
    /** `canonicalUrl` is not an absolute http(s) URL. */
    CANONICAL_URL_INVALID("canonicalUrl.notAbsolute", ProblemType.INVALID_REQUEST, 0),
    /** `clear` names something that cannot be cleared. */
    CLEAR_NOT_CLEARABLE("clear.notClearable", ProblemType.INVALID_REQUEST, 2),
    /** `clear` names only one of a pair that clears together. */
    CLEAR_TOGETHER("clear.togetherOnly", ProblemType.INVALID_REQUEST, 2),
    /** A field is both sent and named in `clear`. */
    CLEAR_AND_SET("clear.setAndCleared", ProblemType.INVALID_REQUEST, 1),
    /** An image import was asked for on a site that takes no uploads. */
    UPLOAD_DISABLED("upload.disabled", ProblemType.INVALID_REQUEST, 0),
    /** `featuredImageAlt` was sent without `featuredImageUrl`. */
    IMAGE_ALT_WITHOUT_URL("image.altWithoutUrl", ProblemType.INVALID_REQUEST, 0),
    /** Both `featuredImageUrl` and `featuredImageId` were sent. */
    IMAGE_URL_AND_ID("image.urlAndId", ProblemType.INVALID_REQUEST, 0),
    /** `featuredImageUrl` was sent with `clear: [featuredImageId]`. */
    IMAGE_URL_AND_CLEAR("image.urlAndClear", ProblemType.INVALID_REQUEST, 0),
    /** The text holds an image the import cannot take (an HTML `srcset`); `detail` says which. Text from the business tier. */
    IMAGE_SCAN_REFUSED("image.scanRefused", ProblemType.INVALID_REQUEST, 1),
    /** More external images in the text than one write imports. */
    IMAGE_TOO_MANY("image.tooManyBodyImages", ProblemType.INVALID_REQUEST, 2),
    /** An import was asked for while the default media directory is private. */
    IMAGE_DIRECTORY_PRIVATE("image.directoryPrivate", ProblemType.INVALID_REQUEST, 0),
    /** The import step ran out of time before this image. */
    IMAGE_IMPORT_BUDGET("image.importBudgetExhausted", ProblemType.INVALID_REQUEST, 2),
    /** One image could not be imported; `detail` names it and says why. Text from the import step. */
    IMAGE_IMPORT_FAILED("image.importFailed", ProblemType.INVALID_REQUEST, 1),
    /** `entries` was omitted from a parts replacement. */
    STRAND_ENTRIES_REQUIRED("strand.entriesRequired", ProblemType.INVALID_REQUEST, 0),
    /** Parts were set on a page that is not a strand. */
    STRAND_NOT_STRAND("strand.pageNotAStrand", ProblemType.INVALID_REQUEST, 0),
    /** More parts than a strand holds. */
    STRAND_TOO_MANY("strand.tooManyParts", ProblemType.INVALID_REQUEST, 1),
    /** A listed post belongs to another strand. */
    STRAND_IN_ANOTHER("strand.postInAnotherStrand", ProblemType.INVALID_REQUEST, 1),
    /** A post id is repeated in `entries`. */
    STRAND_DUPLICATE("strand.postListedTwice", ProblemType.INVALID_REQUEST, 1),
    /** A listed post id is not a post of the weblog. */
    STRAND_POST_UNKNOWN("strand.postUnknown", ProblemType.INVALID_REQUEST, 1),
    /** `strand` was set on a site page. */
    STRAND_SITE_PAGE("strand.sitePage", ProblemType.INVALID_REQUEST, 0),
    /** `strand` false on a strand that still has parts. {1}: the route's `{id}` placeholder, as written. */
    STRAND_HAS_PARTS("strand.hasParts", ProblemType.INVALID_REQUEST, 2),
    /** `navOrder` is below 0. */
    NAV_ORDER_NEGATIVE("navOrder.negative", ProblemType.INVALID_REQUEST, 1),
    /** `slug` is blank or a reserved name. */
    SLUG_RESERVED("slug.reserved", ProblemType.INVALID_REQUEST, 1),
    /** `slug` contains a `/`. */
    SLUG_HAS_SLASH("slug.hasSlash", ProblemType.INVALID_REQUEST, 1),
    /** A trashed page holds the `slug`. */
    SLUG_HELD_BY_TRASH("slug.heldByTrashedPage", ProblemType.CONFLICT, 1),
    /** Another page holds the `slug`. */
    SLUG_IN_USE("slug.inUse", ProblemType.CONFLICT, 1),
    /** A deprecated parameter alias (`{0}`) was sent with a different value than its current name (`{1}`). */
    PARAMETER_ALIAS_CONFLICT("parameter.aliasConflict", ProblemType.INVALID_REQUEST, 2),
    /**
     * RFC 9457's {@code about:blank}, which MCP answers for an error reply
     * that brought no problem body of its own: the status is the reply's and
     * the title its reason phrase, so it has no {@link ProblemType}, and no
     * detail is sent.
     */
    HTTP_ERROR("http.error", null, 0);

    /**
     * The codes whose text is not ours: its English is {@code {0}}, and the
     * argument is a business exception's own message, passed through whole
     * because the business tier's messages are not a finite set this API can
     * key. This set may only shrink: a code leaves it when its source is
     * keyed. {@code ProblemCodeCatalogTest} pins it and holds it to the codes
     * whose English is {@code {0}}.
     */
    public static final Set<ProblemCode> PASSTHROUGH_CODES = Set.of(IMAGE_SCAN_REFUSED, IMAGE_IMPORT_FAILED);

    private static final String KEY_PREFIX = "api.problem.";

    private final String code;
    private final ProblemType type;
    private final int arity;

    ProblemCode(String code, ProblemType type, int arity) {
        this.code = code;
        this.type = type;
        this.arity = arity;
    }

    /** The wire string. */
    public String code() {
        return code;
    }

    /** The message key of the code's text. */
    public String key() {
        return KEY_PREFIX + code;
    }

    /** The number of arguments the code's text takes. */
    public int arity() {
        return arity;
    }

    /** The category a problem with this code has; null for {@link #HTTP_ERROR}, whose status varies. */
    public ProblemType type() {
        return type;
    }
}

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
    /**
     * A member without publish rights (a contributor) asked to change an entry that is not their own draft
     * or pending entry, as the entry editor refuses.
     */
    ENTRY_NOT_CHANGEABLE("entry.notChangeable", ProblemType.FORBIDDEN, 0),
    /** A member without publish rights asked for field {@code {0}} (pinned, strand, an image import), a publisher's. */
    ENTRY_NEEDS_PUBLISH_RIGHTS("entry.needsPublishRights", ProblemType.FORBIDDEN, 1),
    /** The page is in the trash; restore it before changing it. */
    PAGE_IN_TRASH("page.trashed", ProblemType.CONFLICT, 0),
    /** Trash was asked for a page already in the trash. */
    PAGE_ALREADY_TRASHED("page.alreadyTrashed", ProblemType.CONFLICT, 0),
    /** Restore was asked for a page that is not in the trash. */
    PAGE_NOT_IN_TRASH("page.notInTrash", ProblemType.CONFLICT, 0),
    /** Delete-forever was asked for a page that is not in the trash. */
    PAGE_DELETE_NEEDS_TRASH("page.deleteForeverNeedsTrash", ProblemType.CONFLICT, 0),
    /** The entry or page already has its kept revisions, `{0}` of them (the limit); release one first. */
    REVISION_TOO_MANY("revision.tooMany", ProblemType.CONFLICT, 1),
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
    ENTRY_TRASH_STATUS_NOT_WRITABLE("entry.trashStatusNotWritable", ProblemType.INVALID_REQUEST, 0),
    /** A page write set `status` to TRASHED. */
    PAGE_TRASH_STATUS_NOT_WRITABLE("page.trashStatusNotWritable", ProblemType.INVALID_REQUEST, 0),
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
    EDITS_FIND_NOT_MATCHED("edits.findNotMatched", ProblemType.INVALID_REQUEST, 1),
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
    /** An entry's `strand` is not the id of one of the blog's strand pages. */
    STRAND_ID_NOT_STRAND_PAGE("strand.idNotStrandPage", ProblemType.INVALID_REQUEST, 0),
    /** The weblog already has its most pinned posts. */
    PINNED_TOO_MANY("pinned.tooMany", ProblemType.INVALID_REQUEST, 1),
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
    /** The text holds an image the import cannot take (an HTML `srcset`); `detail` says which. The argument is the tag. */
    IMAGE_SCAN_REFUSED("image.scanRefused", ProblemType.INVALID_REQUEST, 1),
    /** More external images in the text than one write imports. */
    IMAGE_TOO_MANY("image.tooManyBodyImages", ProblemType.INVALID_REQUEST, 2),
    /** An import was asked for while the default media directory is private. */
    IMAGE_DIRECTORY_PRIVATE("image.directoryPrivate", ProblemType.INVALID_REQUEST, 0),
    /** The import step ran out of time before this image. */
    IMAGE_IMPORT_BUDGET("image.importBudgetExhausted", ProblemType.INVALID_REQUEST, 2),
    /** One image could not be imported; `detail` names it and says why. Text from the import step. */
    IMAGE_IMPORT_FAILED("image.importFailed", ProblemType.INVALID_REQUEST, 2),
    /** `entries` was omitted from a parts replacement. */
    STRAND_ENTRIES_REQUIRED("strand.entriesRequired", ProblemType.INVALID_REQUEST, 0),
    /** Parts were set on a page whose {@code strand} flag is not set. */
    STRAND_FLAG_NOT_SET("strand.flagNotSet", ProblemType.INVALID_REQUEST, 0),
    /** More parts than a strand holds. */
    STRAND_TOO_MANY("strand.tooMany", ProblemType.INVALID_REQUEST, 1),
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
    /** A password (HTTP Basic) was sent anywhere but the token resource, which is the only place one is accepted. */
    AUTH_PASSWORD_NOT_ACCEPTED_HERE("auth.passwordNotAcceptedHere", ProblemType.UNAUTHORIZED, 0),
    /** The token resource turned the password away: wrong, a disabled account, an agent or an unknown user, told alike. */
    AUTH_PASSWORD_REJECTED("auth.passwordRejected", ProblemType.UNAUTHORIZED, 0),
    /** A write authenticated by the admin UI's session carried no CSRF token, or the wrong one. */
    CSRF_INVALID("csrf.invalid", ProblemType.FORBIDDEN, 0),
    /** The caller lacks the role this route needs (on the site, or on a weblog it is a member of). */
    AUTH_NO_PERMISSION("auth.noPermission", ProblemType.FORBIDDEN, 0),
    /** The account (a reader signed in with SSO) holds no login permission, so it may not use the API. */
    AUTH_API_NOT_ALLOWED("auth.apiNotAllowed", ProblemType.FORBIDDEN, 0),
    /** An administration route was called with a token whose role is not ADMIN. */
    TOKEN_NOT_ADMIN_SCOPED("token.notAdminScoped", ProblemType.FORBIDDEN, 0),
    /** A write was sent with a READ token. */
    TOKEN_READ_ONLY("token.readOnly", ProblemType.FORBIDDEN, 0),
    /** A token limited to one weblog called a route that belongs to no weblog. */
    TOKEN_WEBLOG_SCOPED_ROUTE("token.weblogScopedRoute", ProblemType.NOT_FOUND, 1),
    /** A token was used to mint a token; minting takes a password. */
    TOKEN_MINT_REFUSED("token.mintRefused", ProblemType.FORBIDDEN, 0),
    /** A token was used to list or revoke tokens; that takes a password. */
    TOKEN_MANAGEMENT_NEEDS_PASSWORD("token.managementNeedsPassword", ProblemType.FORBIDDEN, 0),
    /** The caller has no token with this id. */
    TOKEN_NOT_FOUND("token.notFound", ProblemType.NOT_FOUND, 0),
    /** A token's {@code weblog} names a weblog the caller is not a member of. */
    TOKEN_WEBLOG_NOT_MEMBER("token.weblogNotMember", ProblemType.INVALID_REQUEST, 1),
    /** A token's {@code expiresAt} is not in the future. */
    TOKEN_EXPIRY_NOT_IN_FUTURE("token.expiryNotInFuture", ProblemType.INVALID_REQUEST, 0),
    /** A token's {@code role} is not READ, POST or ADMIN. */
    TOKEN_ROLE_UNKNOWN("token.roleUnknown", ProblemType.INVALID_REQUEST, 1),
    /** The MCP endpoint was called with anything but an API token. */
    MCP_TOKEN_REQUIRED("mcp.tokenRequired", ProblemType.UNAUTHORIZED, 0),
    /** MCP only: a tool with several actions was called without {@code action}. */
    MCP_ACTION_MISSING("mcp.actionMissing", ProblemType.INVALID_REQUEST, 2),
    /** MCP only: {@code action} names no action of the tool. */
    MCP_ACTION_UNKNOWN("mcp.actionUnknown", ProblemType.INVALID_REQUEST, 3),
    /** MCP only: an argument the tool or action does not take. */
    MCP_ARGUMENT_NOT_APPLICABLE("mcp.argumentNotApplicable", ProblemType.INVALID_REQUEST, 2),
    /** MCP only: a required argument is missing. */
    MCP_ARGUMENT_MISSING("mcp.argumentMissing", ProblemType.INVALID_REQUEST, 2),
    /** MCP only: an argument that must be a JSON object is not one. */
    MCP_ARGUMENT_NOT_OBJECT("mcp.argumentNotObject", ProblemType.INVALID_REQUEST, 1),
    /** MCP only: a value that becomes one path segment is empty, a dot segment, or holds {@code /}, a backslash or {@code ;}. */
    MCP_ARGUMENT_NOT_PATH_SEGMENT("mcp.argumentNotPathSegment", ProblemType.INVALID_REQUEST, 1),
    /** The API may not change a site administrator's account. */
    ACCOUNT_ADMIN_UI_ONLY("account.adminManagedInAdminUi", ProblemType.FORBIDDEN, 0),
    /** The API may not change an agent account. */
    ACCOUNT_AGENT_UI_ONLY("account.agentManagedInAdminUi", ProblemType.FORBIDDEN, 0),
    /** A runtime setting the API reads but never writes ({@code site.absoluteurl}). */
    SETTING_UI_ONLY("setting.managedInAdminUi", ProblemType.FORBIDDEN, 1),
    /** No user has this user name. */
    USER_NOT_FOUND("user.notFound", ProblemType.NOT_FOUND, 0),
    /** {@code userName} is taken (by an account of any status). */
    USER_NAME_IN_USE("user.nameInUse", ProblemType.CONFLICT, 1),
    /** {@code userName} holds a character the site does not allow in user names. */
    USER_NAME_CHARACTERS("user.nameHasDisallowedCharacters", ProblemType.INVALID_REQUEST, 0),
    /** A user create has no {@code emailAddress}, or one that is not an address. */
    USER_EMAIL_REQUIRED("user.emailRequired", ProblemType.INVALID_REQUEST, 0),
    /** A user update's {@code emailAddress} is not an address. */
    USER_EMAIL_INVALID("user.emailInvalid", ProblemType.INVALID_REQUEST, 0),
    /** Another user's email address is changed in the admin UI only (owner decision 2026-11-21). */
    USER_EMAIL_UI_ONLY("user.emailUiOnly", ProblemType.FORBIDDEN, 0),
    /** A user create on a site with no mail set up: the set-password link could not be sent. */
    USER_MAIL_NOT_CONFIGURED("user.mailNotConfigured", ProblemType.INVALID_REQUEST, 0),
    /** The account was created, disabled, but its set-password mail failed. */
    USER_PASSWORD_LINK_NOT_SENT("user.passwordLinkNotSent", ProblemType.UPSTREAM_FAILURE, 0),
    /** A set-password link was asked for an agent account, which has no password. */
    USER_NOT_A_PERSON("user.notAPerson", ProblemType.INVALID_REQUEST, 0),
    /** A set-password link was asked for a single-sign-on account, which signs in through its provider. */
    USER_SSO_BOUND("user.ssoBound", ProblemType.INVALID_REQUEST, 0),
    /** {@code writer} was sent for an account that is not single-sign-on: only those have an editable author role. */
    USER_WRITER_SSO_ONLY("user.writerSsoOnly", ProblemType.INVALID_REQUEST, 0),
    /** The set-password link could not be mailed (an existing account; nothing changed). */
    USER_PASSWORD_LINK_FAILED("user.passwordLinkFailed", ProblemType.UPSTREAM_FAILURE, 0),
    /** A settings PATCH named no setting. */
    CONFIG_PROPERTIES_REQUIRED("config.propertiesRequired", ProblemType.INVALID_REQUEST, 0),
    /** A settings PATCH named a setting with a blank name. */
    CONFIG_PROPERTY_NAME_REQUIRED("config.propertyNameRequired", ProblemType.INVALID_REQUEST, 0),
    /** A setting that is not runtime-settable. */
    CONFIG_PROPERTY_UNKNOWN("config.propertyUnknown", ProblemType.INVALID_REQUEST, 1),
    /** A setting was sent as null. */
    CONFIG_VALUE_NULL("config.valueNull", ProblemType.INVALID_REQUEST, 1),
    /** An integer setting outside the range it allows. */
    CONFIG_OUT_OF_RANGE("config.outOfRange", ProblemType.INVALID_REQUEST, 3),
    /** A boolean setting that is not {@code true} or {@code false}. */
    CONFIG_NOT_BOOLEAN("config.notBoolean", ProblemType.INVALID_REQUEST, 1),
    /** An integer setting that is not an integer. */
    CONFIG_NOT_INTEGER("config.notInteger", ProblemType.INVALID_REQUEST, 1),
    /** A number setting that is not a number. */
    CONFIG_NOT_NUMBER("config.notNumber", ProblemType.INVALID_REQUEST, 1),
    /** {@code site.frontpage.weblog.handle} names the site space, which holds the site's pages and is no blog. */
    CONFIG_FRONT_DOOR_SITE_SPACE("config.frontDoorSiteSpace", ProblemType.INVALID_REQUEST, 0),
    /** {@code site.frontpage.page} changed to a slug that is not a published site page. */
    CONFIG_FRONT_DOOR_PAGE_UNPUBLISHED("config.frontDoorPageUnpublished", ProblemType.INVALID_REQUEST, 0),
    /** Both front-door settings would be non-empty after the patch. */
    CONFIG_FRONT_DOOR_BOTH_SET("config.frontDoorBothSet", ProblemType.INVALID_REQUEST, 0),
    /** {@code site.privacy.page} changed to a reference that is not a published page. */
    CONFIG_PRIVACY_PAGE_UNPUBLISHED("config.privacyPageUnpublished", ProblemType.INVALID_REQUEST, 0),
    /** A number field is not finite. */
    FIELD_NOT_FINITE("field.notFinite", ProblemType.INVALID_REQUEST, 1),
    /** A field that holds a UUID does not. */
    FIELD_NOT_UUID("field.notUuid", ProblemType.INVALID_REQUEST, 1),
    /** A media upload carried no file. */
    MEDIA_FILE_REQUIRED("media.fileRequired", ProblemType.INVALID_REQUEST, 0),
    /** A media import could not fetch or store the image; {@code detail} says why. Text from the import step. */
    MEDIA_IMPORT_REFUSED("media.importRefused", ProblemType.INVALID_REQUEST, 1),
    /** No media file of the weblog has this id. */
    MEDIA_NOT_FOUND("media.notFound", ProblemType.NOT_FOUND, 0),
    /** A media file's new {@code name} is held by another file in the directory it will be in. */
    MEDIA_NAME_IN_USE("media.nameInUse", ProblemType.CONFLICT, 1),
    /** A crop was asked of a file that is not a JPEG or PNG image; `{0}` is the file's content type. */
    MEDIA_NOT_CROPPABLE("media.notCroppable", ProblemType.CONFLICT, 1),
    /** The crop rectangle does not overlap the image; the arguments are x, y, width, height, then the image's width and height. */
    MEDIA_CROP_OUTSIDE("media.cropOutside", ProblemType.INVALID_REQUEST, 6),
    /** A crop's `width` or `height` is below 1; `{0}` is its name. */
    MEDIA_CROP_SIZE_INVALID("media.cropSizeInvalid", ProblemType.INVALID_REQUEST, 1),
    /** The `expectedWidth`/`expectedHeight` sent is not the image's size now; the arguments are its width and height. */
    MEDIA_CROP_STALE("media.cropStale", ProblemType.CONFLICT, 2),
    /** A directory {@code name} may not contain a slash (one leading slash is dropped). */
    DIRECTORY_NAME_INVALID("directory.nameInvalid", ProblemType.INVALID_REQUEST, 0),
    /** No media directory of the weblog has this id. */
    DIRECTORY_NOT_FOUND("directory.notFound", ProblemType.NOT_FOUND, 0),
    /** A directory may not be named {@code default}. */
    DIRECTORY_NAME_RESERVED("directory.nameReserved", ProblemType.INVALID_REQUEST, 0),
    /** The weblog already has a directory with this {@code name}. */
    DIRECTORY_NAME_IN_USE("directory.nameInUse", ProblemType.CONFLICT, 1),
    /** {@code focalX} or {@code focalY} is outside 0 to 1. */
    FOCAL_OUT_OF_RANGE("focal.outOfRange", ProblemType.INVALID_REQUEST, 1),
    /** One focal coordinate was sent for a file with no focal point. */
    FOCAL_PAIR_ONLY("focal.pairOnly", ProblemType.INVALID_REQUEST, 0),
    /** No category of the weblog has this id. */
    CATEGORY_NOT_FOUND("category.notFound", ProblemType.NOT_FOUND, 0),
    /** The weblog already has a category with this {@code name}. */
    CATEGORY_NAME_IN_USE("category.nameInUse", ProblemType.CONFLICT, 1),
    /** A category {@code name} may not contain a character HTML escaping would change. */
    CATEGORY_NAME_INVALID("category.nameInvalid", ProblemType.INVALID_REQUEST, 0),
    /** The weblog's last category cannot be deleted. */
    CATEGORY_LAST_ONE("category.lastOne", ProblemType.CONFLICT, 0),
    /** A category delete's {@code moveTo} names the category itself. */
    CATEGORY_MOVE_TO_SELF("category.moveToSelf", ProblemType.INVALID_REQUEST, 0),
    /** A category delete's {@code moveTo} names no category of the weblog. */
    CATEGORY_MOVE_TARGET_NOT_FOUND("category.moveTargetNotFound", ProblemType.NOT_FOUND, 0),
    /** The category has entries and no {@code moveTo} was given. */
    CATEGORY_HAS_ENTRIES("category.hasEntries", ProblemType.CONFLICT, 0),
    /** No comment on the weblog's entries has this id. */
    COMMENT_NOT_FOUND("comment.notFound", ProblemType.NOT_FOUND, 0),
    /** A comment's {@code status} may be set only to PUBLISHED or HIDDEN. */
    COMMENT_STATUS_NOT_SETTABLE("comment.statusNotSettable", ProblemType.INVALID_REQUEST, 0),
    /** A comment {@code status} that is not a status. */
    COMMENT_STATUS_UNKNOWN("comment.statusUnknown", ProblemType.INVALID_REQUEST, 0),
    /** A comment its author deleted cannot be published. */
    COMMENT_DELETED_BY_AUTHOR("comment.deletedByAuthor", ProblemType.CONFLICT, 0),
    /** A reply refused: no account ({@code CommentRefusal.NOT_SIGNED_IN}). Each reply refusal has its own code. */
    COMMENT_REPLY_NOT_SIGNED_IN("comment.replyNotSignedIn", ProblemType.INVALID_REQUEST, 0),
    /** A reply refused: the account lacks the comment permission ({@code NO_PERMISSION}). */
    COMMENT_REPLY_NO_PERMISSION("comment.replyNoPermission", ProblemType.INVALID_REQUEST, 0),
    /** A reply refused: the account is disabled or gone ({@code DISABLED}). */
    COMMENT_REPLY_ACCOUNT_DISABLED("comment.replyAccountDisabled", ProblemType.INVALID_REQUEST, 0),
    /** A reply refused: the comment replied to is unknown ({@code NOT_FOUND}). */
    COMMENT_REPLY_PARENT_UNKNOWN("comment.replyParentUnknown", ProblemType.INVALID_REQUEST, 0),
    /** A reply refused: the entry is closed to comments ({@code CLOSED}). */
    COMMENT_REPLY_ENTRY_CLOSED("comment.replyEntryClosed", ProblemType.INVALID_REQUEST, 0),
    /** A reply refused: the weblog has comments off ({@code COMMENTS_OFF}). */
    COMMENT_REPLY_COMMENTS_OFF("comment.replyCommentsOff", ProblemType.INVALID_REQUEST, 0),
    /** A reply refused: the weblog has a custom domain ({@code CUSTOM_DOMAIN}). */
    COMMENT_REPLY_CUSTOM_DOMAIN("comment.replyCustomDomain", ProblemType.INVALID_REQUEST, 0),
    /** A reply refused: the body is empty ({@code EMPTY_BODY}). */
    COMMENT_REPLY_EMPTY_BODY("comment.replyEmptyBody", ProblemType.INVALID_REQUEST, 0),
    /** A reply refused: the body is too long ({@code BODY_TOO_LONG}). */
    COMMENT_REPLY_BODY_TOO_LONG("comment.replyBodyTooLong", ProblemType.INVALID_REQUEST, 0),
    /**
     * A reply refused ({@code BAD_REPLY}): not a member's reply to a published,
     * unanswered top-level comment, or the store refused it.
     */
    COMMENT_REPLY_NOT_ACCEPTED("comment.replyNotAccepted", ProblemType.INVALID_REQUEST, 0),
    /** A reply refused: the account has posted its share for now ({@code RATE_LIMITED}). */
    COMMENT_REPLY_RATE_LIMITED("comment.replyRateLimited", ProblemType.THROTTLED, 0),
    /** No redirect of the weblog has this id. */
    REDIRECT_NOT_FOUND("redirect.notFound", ProblemType.NOT_FOUND, 0),
    /** A redirect's {@code source} or {@code target} ({0}) is not a path starting with one {@code /}. */
    REDIRECT_PATH_NOT_RELATIVE("redirect.pathNotRelative", ProblemType.INVALID_REQUEST, 2),
    /** A redirect path carries a query string. */
    REDIRECT_PATH_HAS_QUERY("redirect.pathHasQuery", ProblemType.INVALID_REQUEST, 2),
    /** A redirect path contains a backslash. */
    REDIRECT_PATH_HAS_BACKSLASH("redirect.pathHasBackslash", ProblemType.INVALID_REQUEST, 2),
    /** A redirect path contains a control character. */
    REDIRECT_PATH_HAS_CONTROL("redirect.pathHasControlCharacters", ProblemType.INVALID_REQUEST, 1),
    /** A redirect's {@code target} is its {@code source}. */
    REDIRECT_TARGET_IS_SOURCE("redirect.targetIsSource", ProblemType.INVALID_REQUEST, 1),
    /** The weblog already redirects this {@code source}. */
    REDIRECT_SOURCE_IN_USE("redirect.sourceInUse", ProblemType.CONFLICT, 1),
    /** The {@code source} is another redirect's target: redirects are one hop. */
    REDIRECT_SOURCE_IS_TARGET("redirect.sourceIsTarget", ProblemType.INVALID_REQUEST, 2),
    /** The {@code target} is itself redirected: redirects are one hop. */
    REDIRECT_TARGET_REDIRECTED("redirect.targetRedirected", ProblemType.INVALID_REQUEST, 2),
    /** The rule names no weblog (the manager's own check; the API always sets one). */
    REDIRECT_WEBLOG_REQUIRED("redirect.weblogRequired", ProblemType.INVALID_REQUEST, 0),
    /** A redirect path ({0}: source or target) is blank after normalising. */
    REDIRECT_PATH_REQUIRED("redirect.pathRequired", ProblemType.INVALID_REQUEST, 1),
    /** The SEO audit was asked for trashed entries. */
    AUDIT_TRASHED_NOT_COVERED("audit.trashedNotCovered", ProblemType.INVALID_REQUEST, 0),
    /** {@code period} is not one of the traffic periods. */
    TRAFFIC_PERIOD_UNKNOWN("traffic.periodUnknown", ProblemType.INVALID_REQUEST, 1),
    /** {@code theme} is {@code custom}, which only the admin UI sets. */
    THEME_CUSTOM_NOT_ALLOWED("theme.customNotAllowed", ProblemType.INVALID_REQUEST, 0),
    /** {@code theme} is not one of the themes offered to this weblog. */
    THEME_NOT_OFFERED("theme.notOffered", ProblemType.INVALID_REQUEST, 1),
    /** The site does not let users create weblogs. */
    WEBLOG_CREATION_DISABLED("weblog.creationDisabled", ProblemType.FORBIDDEN, 0),
    /** The caller already has a weblog, and the site allows one. */
    WEBLOG_ONE_BLOG_LIMIT("weblog.oneBlogLimit", ProblemType.FORBIDDEN, 0),
    /** An agent created a weblog with no {@code emailAddress} on a site with no address to stand in. */
    WEBLOG_EMAIL_REQUIRED("weblog.emailRequired", ProblemType.INVALID_REQUEST, 0),
    /** {@code locale} is not one the settings form offers. */
    LOCALE_INVALID("locale.invalid", ProblemType.INVALID_REQUEST, 0),
    /** {@code timeZone} is not a time zone id. */
    TIME_ZONE_INVALID("timeZone.invalid", ProblemType.INVALID_REQUEST, 0),
    /** {@code handle} uses a character the site does not allow, or is too long. */
    HANDLE_INVALID("handle.invalid", ProblemType.INVALID_REQUEST, 2),
    /** {@code handle} is a path the site reserves for its own pages. */
    HANDLE_RESERVED("handle.reserved", ProblemType.INVALID_REQUEST, 1),
    /** Another weblog (of any status) has this {@code handle}. */
    HANDLE_IN_USE("handle.inUse", ProblemType.CONFLICT, 0),
    /** A site page (live or in the trash) is served at this {@code handle}. */
    HANDLE_HELD_BY_SITE_PAGE("handle.heldBySitePage", ProblemType.CONFLICT, 0),
    /** {@code entryDisplayCount} is below 1 or above the site's limit. */
    ENTRY_DISPLAY_COUNT_OUT_OF_RANGE("entryDisplayCount.outOfRange", ProblemType.INVALID_REQUEST, 1),
    /** {@code entryOrder} is not NEWEST or OLDEST. */
    ENTRY_ORDER_INVALID("entryOrder.invalid", ProblemType.INVALID_REQUEST, 0),
    /** {@code pageWidth} is not STANDARD or WIDE. */
    PAGE_WIDTH_INVALID("pageWidth.invalid", ProblemType.INVALID_REQUEST, 0),
    /** {@code businessId} names no business profile. */
    BUSINESS_UNKNOWN("business.unknown", ProblemType.INVALID_REQUEST, 0),
    /** No business profile has this id. */
    BUSINESS_NOT_FOUND("business.notFound", ProblemType.NOT_FOUND, 0),
    /** The business profile is in use by weblogs and cannot be deleted; the arg is how many. */
    BUSINESS_IN_USE("business.inUse", ProblemType.CONFLICT, 1),
    /** {@code businessType} is not one of the known types, which the arg lists. */
    BUSINESS_TYPE_UNKNOWN("businessType.unknown", ProblemType.INVALID_REQUEST, 1),
    /** The named URL field is not a public, absolute http or https URL. */
    FIELD_NOT_PUBLIC_URL("field.notPublicUrl", ProblemType.INVALID_REQUEST, 1),
    /** {@code telephone} is off the telephone pattern. */
    TELEPHONE_INVALID("telephone.invalid", ProblemType.INVALID_REQUEST, 0),
    /** {@code email} is not a valid address. */
    EMAIL_INVALID("email.invalid", ProblemType.INVALID_REQUEST, 0),
    /** {@code sameAs} holds more links than the arg allows. */
    SAME_AS_TOO_MANY("sameAs.tooMany", ProblemType.INVALID_REQUEST, 1),
    /** A {@code sameAs} link, the arg, is not a public http(s) URL of at most 255 characters. */
    SAME_AS_ENTRY_INVALID("sameAs.entryInvalid", ProblemType.INVALID_REQUEST, 1),
    /** {@code frontPage} is not a published page of this weblog. */
    FRONT_PAGE_INVALID("frontPage.invalid", ProblemType.INVALID_REQUEST, 0),
    /** {@code placeType} is not LodgingBusiness. */
    PLACE_TYPE_INVALID("placeType.invalid", ProblemType.INVALID_REQUEST, 0),
    /** A {@code placeType} was set with no {@code placeLocality}. */
    PLACE_LOCALITY_REQUIRED("placeLocality.required", ProblemType.INVALID_REQUEST, 0),
    /** {@code placeCountry} is not a two-letter country code. */
    PLACE_COUNTRY_INVALID("placeCountry.invalid", ProblemType.INVALID_REQUEST, 0),
    /** {@code placeLat} is off the globe, or was given without {@code placeLng}. */
    PLACE_LAT_INVALID("placeLat.invalid", ProblemType.INVALID_REQUEST, 0),
    /** {@code placeLng} is off the globe, or was given without {@code placeLat}. */
    PLACE_LNG_INVALID("placeLng.invalid", ProblemType.INVALID_REQUEST, 0),
    /** {@code bookingUrl} is not an http(s) URL. */
    BOOKING_URL_INVALID("bookingUrl.invalid", ProblemType.INVALID_REQUEST, 0),
    /** {@code customDomain} is not a hostname. */
    CUSTOM_DOMAIN_MALFORMED("customDomain.malformed", ProblemType.INVALID_REQUEST, 0),
    /** {@code customDomain} is the site's own host. */
    CUSTOM_DOMAIN_SITE_HOST("customDomain.siteHost", ProblemType.INVALID_REQUEST, 0),
    /** Another weblog has this {@code customDomain}. */
    CUSTOM_DOMAIN_IN_USE("customDomain.inUse", ProblemType.CONFLICT, 0),
    /** Whether {@code customDomain} is taken could not be checked; retry. */
    CUSTOM_DOMAIN_UNCHECKABLE("customDomain.uncheckable", ProblemType.INVALID_REQUEST, 0),
    /**
     * RFC 9457's {@code about:blank}, which MCP answers for an error reply
     * that brought no problem body of its own: the status is the reply's and
     * the title its reason phrase, so it has no {@link ProblemType}, and no
     * detail is sent.
     */
    HTTP_ERROR("http.error", null, 0);

    /**
     * The codes whose text is not wholly ours: the last placeholder of its
     * English is a business exception's own message, passed through whole
     * because the business tier's messages are not a finite set this API can
     * key. Task 3 fixed its final members (spec 2026-11-20): an image import's
     * failure ({@code image.importFailed} and the media route's
     * {@code media.importRefused}). The final review dropped
     * {@code redirect.refused}: every redirect refusal has a reason, and any
     * other save failure is the opaque 500. 0.16.3 dropped
     * {@code image.scanRefused}: its sentence is keyed and the tag it names
     * is the argument. This set may only shrink: a code leaves it when its source is
     * keyed. {@code ProblemCodeCatalogTest} pins it, holds each member's English
     * to ending in its last placeholder, and every code whose English is just
     * {@code {0}} to being a member.
     */
    public static final Set<ProblemCode> PASSTHROUGH_CODES =
            Set.of(IMAGE_IMPORT_FAILED, MEDIA_IMPORT_REFUSED);

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

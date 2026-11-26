package com.wikantik.pressroom.ui.restapi;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The problem codes are a wire contract (spec 2026-11-20-api-contract-design,
 * Task 1, criteria 2, 3 and 5): the full set is pinned here, and every code's
 * English lives in the message bundle with as many placeholders as the code
 * declares arguments.
 */
class ProblemCodeCatalogTest {

    private static final String BASE_BUNDLE = "/ApplicationResources.properties";

    /** The seven shipped translations, as {@code MessageFormatRegressionTest} names them. */
    private static final List<String> TRANSLATIONS = List.of("de", "es", "fr", "ja", "ko", "ru", "zh_CN");

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\d+)(?:,[^}]*)?}");

    /**
     * Every code a client may see. Removing or renaming one breaks clients
     * that branch on it, so it fails here; adding one means editing this pin,
     * which is deliberate (and the README's code table with it).
     */
    @Test
    void theCodesArePinned() {
        assertEquals(Set.of(
                        "request.invalid",
                        "request.invalidFields",
                        "request.malformedBody",
                        "parameter.notWholeNumber",
                        "parameter.notNumber",
                        "parameter.notBoolean",
                        "parameter.invalidValue",
                        "paging.invalidRange",
                        "resource.notFound",
                        "resource.conflict",
                        "auth.unauthorized",
                        "auth.forbidden",
                        "entry.changed",
                        "page.changed",
                        "server.internalError",
                        "server.busy",
                        "request.tooLarge",
                        "request.unsupportedMediaType",
                        "request.notAcceptable",
                        "request.throttled",
                        "upstream.failure",
                        "entry.notFound",
                        "page.notFound",
                        "revision.notFound",
                        "weblog.notFound",
                        "auth.notAuthenticated",
                        "entry.trashed",
                        "entry.alreadyTrashed",
                        "entry.notInTrash",
                        "entry.deleteForeverNeedsTrash",
                        "entry.notChangeable",
                        "entry.needsPublishRights",
                        "page.trashed",
                        "page.alreadyTrashed",
                        "page.notInTrash",
                        "page.deleteForeverNeedsTrash",
                        "revision.tooMany",
                        "field.required",
                        "field.blank",
                        "field.tooLong",
                        "field.updateOnly",
                        "revisionNote.onCreate",
                        "status.unknown",
                        "entry.trashStatusNotWritable",
                        "page.trashStatusNotWritable",
                        "page.statusFilterInvalid",
                        "entry.expectedContentHashInvalid",
                        "page.expectedContentHashInvalid",
                        "fields.unknown",
                        "fields.unknownMany",
                        "edits.withText",
                        "edits.tooMany",
                        "edits.empty",
                        "edits.noFind",
                        "edits.noReplace",
                        "edits.findNotMatched",
                        "edits.ambiguous",
                        "edits.onCreate",
                        "edits.previewNeedsEntry",
                        "anchor.immutable",
                        "anchor.invalidForm",
                        "anchor.heldByTrashedEntry",
                        "anchor.inUse",
                        "pubTime.requiredForScheduled",
                        "pubTime.invalid",
                        "eventTime.invalid",
                        "eventEnd.beforeStart",
                        "jsonLdType.unknown",
                        "geo.outOfRange",
                        "strand.idNotStrandPage",
                        "pinned.tooMany",
                        "category.unknown",
                        "image.notPublic",
                        "canonicalUrl.notAbsolute",
                        "clear.notClearable",
                        "clear.togetherOnly",
                        "clear.setAndCleared",
                        "upload.disabled",
                        "image.altWithoutUrl",
                        "image.urlAndId",
                        "image.urlAndClear",
                        "image.scanRefused",
                        "image.tooManyBodyImages",
                        "image.directoryPrivate",
                        "image.importBudgetExhausted",
                        "image.importFailed",
                        "strand.entriesRequired",
                        "strand.flagNotSet",
                        "strand.tooMany",
                        "strand.postInAnotherStrand",
                        "strand.postListedTwice",
                        "strand.postUnknown",
                        "strand.sitePage",
                        "strand.hasParts",
                        "navOrder.negative",
                        "slug.reserved",
                        "slug.hasSlash",
                        "slug.heldByTrashedPage",
                        "slug.inUse",
                        "parameter.aliasConflict",
                        "auth.passwordNotAcceptedHere",
                        "auth.passwordRejected",
                        "csrf.invalid",
                        "auth.noPermission",
                        "auth.apiNotAllowed",
                        "token.notAdminScoped",
                        "token.readOnly",
                        "token.weblogScopedRoute",
                        "token.mintRefused",
                        "token.managementNeedsPassword",
                        "token.notFound",
                        "token.weblogNotMember",
                        "token.expiryNotInFuture",
                        "token.roleUnknown",
                        "mcp.tokenRequired",
                        "mcp.actionMissing",
                        "mcp.actionUnknown",
                        "mcp.argumentNotApplicable",
                        "mcp.argumentMissing",
                        "mcp.argumentNotObject",
                        "mcp.argumentNotPathSegment",
                        "account.adminManagedInAdminUi",
                        "account.agentManagedInAdminUi",
                        "setting.managedInAdminUi",
                        "user.notFound",
                        "user.nameInUse",
                        "user.nameHasDisallowedCharacters",
                        "user.emailRequired",
                        "user.emailInvalid",
                        "user.emailUiOnly",
                        "user.mailNotConfigured",
                        "user.passwordLinkNotSent",
                        "config.propertiesRequired",
                        "config.propertyNameRequired",
                        "config.propertyUnknown",
                        "config.valueNull",
                        "config.outOfRange",
                        "config.notBoolean",
                        "config.notInteger",
                        "config.notNumber",
                        "config.frontDoorSiteSpace",
                        "config.frontDoorPageUnpublished",
                        "config.frontDoorBothSet",
                        "config.privacyPageUnpublished",
                        "field.notFinite",
                        "field.notUuid",
                        "media.fileRequired",
                        "media.importRefused",
                        "media.notFound",
                        "media.nameInUse",
                        "media.notCroppable",
                        "media.cropOutside",
                        "media.cropSizeInvalid",
                        "media.cropStale",
                        "directory.nameInvalid",
                        "directory.notFound",
                        "directory.nameReserved",
                        "directory.nameInUse",
                        "focal.outOfRange",
                        "focal.pairOnly",
                        "category.notFound",
                        "category.nameInUse",
                        "category.nameInvalid",
                        "category.lastOne",
                        "category.moveToSelf",
                        "category.moveTargetNotFound",
                        "category.hasEntries",
                        "comment.notFound",
                        "comment.statusNotSettable",
                        "comment.statusUnknown",
                        "comment.deletedByAuthor",
                        "comment.replyNotSignedIn",
                        "comment.replyNoPermission",
                        "comment.replyAccountDisabled",
                        "comment.replyParentUnknown",
                        "comment.replyEntryClosed",
                        "comment.replyCommentsOff",
                        "comment.replyCustomDomain",
                        "comment.replyEmptyBody",
                        "comment.replyBodyTooLong",
                        "comment.replyNotAccepted",
                        "comment.replyRateLimited",
                        "redirect.notFound",
                        "redirect.pathNotRelative",
                        "redirect.pathHasQuery",
                        "redirect.pathHasBackslash",
                        "redirect.pathHasControlCharacters",
                        "redirect.targetIsSource",
                        "redirect.sourceInUse",
                        "redirect.sourceIsTarget",
                        "redirect.targetRedirected",
                        "redirect.weblogRequired",
                        "redirect.pathRequired",
                        "audit.trashedNotCovered",
                        "traffic.periodUnknown",
                        "theme.customNotAllowed",
                        "theme.notOffered",
                        "weblog.creationDisabled",
                        "weblog.oneBlogLimit",
                        "weblog.emailRequired",
                        "locale.invalid",
                        "timeZone.invalid",
                        "handle.invalid",
                        "handle.reserved",
                        "handle.inUse",
                        "handle.heldBySitePage",
                        "entryDisplayCount.outOfRange",
                        "entryOrder.invalid",
                        "pageWidth.invalid",
                        "business.unknown",
                        "business.notFound",
                        "business.inUse",
                        "businessType.unknown",
                        "field.notPublicUrl",
                        "telephone.invalid",
                        "email.invalid",
                        "sameAs.tooMany",
                        "sameAs.entryInvalid",
                        "frontPage.invalid",
                        "placeType.invalid",
                        "placeLocality.required",
                        "placeCountry.invalid",
                        "placeLat.invalid",
                        "placeLng.invalid",
                        "bookingUrl.invalid",
                        "customDomain.malformed",
                        "customDomain.siteHost",
                        "customDomain.inUse",
                        "customDomain.uncheckable",
                        "auth.sessionOnly",
                        "template.notFound",
                        "template.nameInUse",
                        "template.required",
                        "template.actionUnavailable",
                        "stylesheet.notFound",
                        "stylesheet.customTheme",
                        "theme.customisationDisabled",
                        "member.notFound",
                        "member.userUnknown",
                        "member.readerAccount",
                        "member.levelInvalid",
                        "member.groupBloggingOff",
                        "member.selfChange",
                        "member.lastAdmin",
                        "submission.notFound",
                        "http.error"),
                Arrays.stream(ProblemCode.values()).map(ProblemCode::code).collect(Collectors.toSet()));
    }

    /**
     * Task 2: a code whose text is a business exception's own message has
     * the base value {0}, and is listed here. The set may only shrink, so it
     * is pinned; a new passthrough code, or a keyed one left in the set,
     * fails.
     */
    @Test
    void thePassthroughCodesArePinnedAndEndInTheForeignText() throws IOException {
        // Task 3 pinned the final set; it may only shrink from here.
        // The final-review fix wave dropped redirect.refused (a store failure is the opaque 500);
        // 0.16.3 dropped image.scanRefused (its sentence is keyed, the tag its argument).
        assertEquals(Set.of(ProblemCode.IMAGE_IMPORT_FAILED, ProblemCode.MEDIA_IMPORT_REFUSED),
                ProblemCode.PASSTHROUGH_CODES);
        Properties base = load(BASE_BUNDLE);
        Set<ProblemCode> argumentOnly = new java.util.HashSet<>();
        for (ProblemCode code : ProblemCode.values()) {
            String english = base.getProperty(code.key());
            if ("{0}".equals(english)) {
                argumentOnly.add(code);
            }
            if (ProblemCode.PASSTHROUGH_CODES.contains(code)) {
                assertTrue(english.endsWith("{" + (code.arity() - 1) + "}"),
                        code.code() + ": a passthrough's foreign text is its last placeholder: " + english);
            }
        }
        assertTrue(ProblemCode.PASSTHROUGH_CODES.containsAll(argumentOnly), "a code whose English is {0} is passthrough");
    }

    /**
     * The naming convention (docs/dev/api.md, "Problem codes"): exactly two
     * segments, {@code <resource or field>.<condition>}, each lowerCamel.
     */
    @Test
    void everyCodeHasExactlyTwoSegments() {
        Set<String> offenders = new TreeSet<>();
        for (ProblemCode code : ProblemCode.values()) {
            if (!code.code().matches("[a-z][a-zA-Z0-9]*\\.[a-z][a-zA-Z0-9]*")) {
                offenders.add(code.code());
            }
        }
        assertEquals(Set.of(), offenders, "codes that are not <resource>.<condition>");
    }

    /** A condition that says "in use" is a conflict with what exists, and only a 409 says it. */
    @Test
    void everyInUseCodeIsA409() {
        Set<String> offenders = new TreeSet<>();
        for (ProblemCode code : ProblemCode.values()) {
            String condition = code.code().substring(code.code().indexOf('.') + 1);
            boolean saysInUse = "inUse".equals(condition) || condition.endsWith("InUse");
            if (saysInUse && (code.type() == null || code.type().status() != 409)) {
                offenders.add(code.code());
            }
        }
        assertEquals(Set.of(), offenders, "a *.inUse code that is not a 409");
    }

    /** A reply refused for rate is a 429 like the throttle, not a 400 about the request. */
    @Test
    void theReplyRateLimitIsA429() {
        assertEquals(429, ProblemCode.COMMENT_REPLY_RATE_LIMITED.type().status());
    }

    /** A condition that says "not found" is a 404, and only a 404 says it. */
    @Test
    void everyNotFoundCodeIsA404() {
        Set<String> offenders = new TreeSet<>();
        for (ProblemCode code : ProblemCode.values()) {
            String condition = code.code().substring(code.code().indexOf('.') + 1);
            boolean saysNotFound = "notFound".equals(condition) || condition.endsWith("NotFound");
            if (saysNotFound && (code.type() == null || code.type().status() != 404)) {
                offenders.add(code.code());
            }
        }
        assertEquals(Set.of(), offenders, "a *.notFound code that is not a 404");
    }

    /**
     * A value with placeholders is rendered by {@code MessageFormat}, which
     * throws on a malformed pattern ({@code {name}}, a lone brace). The
     * renderer falls back to the English, but a broken translation should
     * not ship: every bundle that defines a code taking arguments parses.
     */
    @Test
    void everyBundlesValueForACodeWithArgumentsIsAValidPattern() throws IOException {
        Properties broken = new Properties();
        broken.setProperty(ProblemCode.REQUEST_TOO_LARGE.key(), "Bonjour {name} {0}");
        assertEquals(Set.of("request.tooLarge in test"), malformed(broken, "test"), "the check sees a {word}");

        Set<String> offenders = new TreeSet<>(malformed(load(BASE_BUNDLE), "base"));
        for (String language : TRANSLATIONS) {
            offenders.addAll(malformed(load("/ApplicationResources_" + language + ".properties"), language));
        }
        assertEquals(Set.of(), offenders, "values MessageFormat will not parse");
    }

    private static Set<String> malformed(Properties bundle, String name) {
        Set<String> offenders = new TreeSet<>();
        for (ProblemCode code : ProblemCode.values()) {
            String value = bundle.getProperty(code.key());
            if (value == null || code.arity() == 0) {
                continue;
            }
            try {
                new java.text.MessageFormat(value, java.util.Locale.ROOT);
            } catch (IllegalArgumentException e) {
                offenders.add(code.code() + " in " + name);
            }
        }
        return offenders;
    }

    @Test
    void everyCodeIsDottedLowerCamelAndUnique() {
        Set<String> seen = new TreeSet<>();
        for (ProblemCode code : ProblemCode.values()) {
            assertTrue(code.code().matches("[a-z][a-zA-Z0-9]*(\\.[a-z][a-zA-Z0-9]*)+"), code.code());
            assertTrue(seen.add(code.code()), "duplicate code " + code.code());
        }
    }

    @Test
    void everyCodesKeyIsInTheBaseBundleAndIsNeverMarkup() throws IOException {
        Properties base = load(BASE_BUNDLE);
        for (ProblemCode code : ProblemCode.values()) {
            String value = base.getProperty(code.key());
            assertNotNull(value, code.code() + ": no " + code.key() + " in the base bundle");
            assertFalse(value.contains("<"), code.code() + ": markup in a JSON detail: " + value);
        }
    }

    /**
     * Criterion 5: the declared argument count is the number of distinct
     * placeholders in the English, so a call site passing what the code
     * declares fills every one. Translations are held to the same count.
     */
    @Test
    void everyCodesArityIsItsPlaceholderCountInEveryBundleThatDefinesIt() throws IOException {
        assertEquals(1, placeholders("Use '{0}' for {1}."), "a quoted '{0}' is MessageFormat literal text");
        assertEquals(1, placeholders("Don''t send {0} twice: '{0}' stays as written."),
                "a doubled '' is one literal apostrophe, not the start of a quote");
        assertEquals(2, placeholders("{0} and {1,number,integer}"), "a formatted placeholder counts");
        Properties base = load(BASE_BUNDLE);
        for (ProblemCode code : ProblemCode.values()) {
            assertEquals(code.arity(), placeholders(base.getProperty(code.key())),
                    code.code() + ": arity against the English " + base.getProperty(code.key()));
        }
        for (String language : TRANSLATIONS) {
            Properties translation = load("/ApplicationResources_" + language + ".properties");
            for (ProblemCode code : ProblemCode.values()) {
                String value = translation.getProperty(code.key());
                if (value != null) {
                    assertEquals(code.arity(), placeholders(value), code.code() + " in " + language + ": " + value);
                }
            }
        }
    }

    @Test
    void everyApiProblemKeyBelongsToACode() throws IOException {
        Set<String> keys = Arrays.stream(ProblemCode.values()).map(ProblemCode::key).collect(Collectors.toSet());
        Set<String> orphans = new TreeSet<>();
        for (String key : load(BASE_BUNDLE).stringPropertyNames()) {
            if (key.startsWith("api.problem.") && !keys.contains(key)) {
                orphans.add(key);
            }
        }
        assertEquals(Set.of(), orphans, "api.problem.* keys no ProblemCode names");
    }

    /** Every code but http.error has the one type its problems carry. */
    @Test
    void everyCodeButHttpErrorHasAType() {
        for (ProblemCode code : ProblemCode.values()) {
            assertEquals(code != ProblemCode.HTTP_ERROR, code.type() != null, code.code());
        }
    }

    /**
     * The distinct {@code {n}} in {@code value} as {@code MessageFormat} reads it:
     * a doubled {@code ''} is one literal apostrophe, and text between single
     * apostrophes (a quoted {@code '{0}'}) is literal, not a placeholder.
     */
    private static int placeholders(String value) {
        Set<String> indexes = new TreeSet<>();
        Matcher matcher = PLACEHOLDER.matcher(value.replace("''", "").replaceAll("'[^']*'?", ""));
        while (matcher.find()) {
            indexes.add(matcher.group(1));
        }
        return indexes.size();
    }

    private static Properties load(String resource) throws IOException {
        Properties properties = new Properties();
        try (InputStream in = ProblemCodeCatalogTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, resource);
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return properties;
    }
}

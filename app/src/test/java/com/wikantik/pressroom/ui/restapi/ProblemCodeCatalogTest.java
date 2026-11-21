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
                        "page.trashed",
                        "page.alreadyTrashed",
                        "page.notInTrash",
                        "page.deleteForeverNeedsTrash",
                        "revision.keepLimit",
                        "field.required",
                        "field.blank",
                        "field.tooLong",
                        "field.updateOnly",
                        "revisionNote.onCreate",
                        "status.unknown",
                        "entry.status.trashNotWritable",
                        "page.status.trashNotWritable",
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
                        "edits.notFound",
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
                        "strand.notAStrandPage",
                        "pinned.limit",
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
                        "strand.pageNotAStrand",
                        "strand.tooManyParts",
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
        assertEquals(Set.of(ProblemCode.IMAGE_SCAN_REFUSED, ProblemCode.IMAGE_IMPORT_FAILED),
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

    private static int placeholders(String value) {
        Set<String> indexes = new TreeSet<>();
        Matcher matcher = PLACEHOLDER.matcher(value);
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

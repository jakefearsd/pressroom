# Publishing-agent API refinements Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `/api/v1` and `/api/mcp` a better fit for the publishing agent: every bad field in one response, chosen slugs, a category error that lists the names, checked media ids, duplicate-free image import, and one-call image import for the featured image and the body.

**Architecture:** Entry writes become *check every field (on a scratch copy) → import images (each import commits) → apply to the real entry and save*. A small `FieldErrors` collector turns today's first-failure 400s into one 400 listing every field. Image import moves out of `MediaApi` into a shared `MediaImports` helper that dedupes by a SHA-256 content hash stored by migration V032. A pure `ExternalImages` scanner finds and rewrites external images using the same commonmark parser the renderer uses.

**Tech Stack:** Java 25, Spring Boot 4.1 MVC, EclipseLink/PostgreSQL 18, commonmark 0.30.0 (source spans), commons-text 1.15 (`StringEscapeUtils`), JUnit 5 + Mockito + MockMvc, Testcontainers.

**Spec:** `docs/superpowers/specs/2026-10-13-publishing-agent-api-refinements-design.md`

## Global Constraints

- Work directly on `master`. Commit your own task's work locally; **never push**. End each commit message with a blank line and then exactly:
  `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` and
  `Claude-Session: https://claude.ai/code/session_017ft4V8YAq5me5Kq3CcDYVa`
- TDD: write the failing test, run it and see it fail for the reason stated, then write the code.
- Never run two Maven builds at once in this checkout. Before any `mvn`, run `pgrep -f "[s]urefirebooter.*source/roller" >/dev/null && echo BUSY || echo CLEAR` and wait for CLEAR.
- Targeted test runs: `mvn -o -q -pl app test -Dtest=<Class>` (add `-Dsurefire.failIfNoSpecifiedTests=false` when naming several). Each task ends with `mvn -o -pl app verify` green: javac `-Xlint:all` with warnings as errors, PMD 7.27.0, CPD, SpotBugs + find-sec-bugs at zero, JaCoCo LINE 0.9590 / BRANCH 0.9100, and the security-boundary package rule (which covers `ui.restapi.auth`, `ui.restapi.v1`, `ui.restapi.mcp`) LINE 0.93 / BRANCH 0.89.
- Name every `@RequestParam`/`@PathVariable`. An SLF4J `Throwable` stays the last argument with no `{}` for it.
- Nothing in `ui.controllers` may import from `ui.restapi` (the one exception, `RollerHandlerInterceptor`'s `ApiException` import, stays the only one).
- Field names in `errors` are the JSON property (or query parameter) names exactly as a caller sends them.
- `detail` of a multi-problem 400 is the messages joined by one space; with one problem it is that message, unchanged from today.
- Anchor form: `^[a-z0-9]+(?:[-_][a-z0-9]+)*$`, at most 255 characters.
- Body-image import: at most 10 distinct external URLs per write; a 60-second budget for the whole import step; all or nothing for the entry.
- `content_hash` is SHA-256, lower-case hex, 64 characters.
- `McpInstructions.TEXT` stays under 3000 characters.
- Match the surrounding code: javadoc on every new public type and on non-obvious methods, comments that say *why*, no comment that restates the code.

## Review Focus

1. A PATCH that sends an entry back exactly as it was read (its stored `anchor`, and a stored `featuredImageId` whose file has since been deleted) must succeed. Pinned in Task 3 (same anchor) and Task 4 (deleted file).
2. Entry text with CRLF line endings must be rewritten at the right offsets. Pinned in Task 7.
3. An `<img src>` carrying HTML entities (`&amp;` in its query) must be fetched by its decoded URL while only the raw attribute value is replaced. Pinned in Task 7.
4. Shortcodes that carry URLs (`[video url="https://youtu.be/…"]`, `[cta href="…"]`) must never be imported. Pinned in Task 7.
5. One URL used as `featuredImageUrl` and in the body, or twice in the body (once as HTML, once as Markdown), is fetched once. Pinned in Task 8.

## File Structure

Created:
- `app/src/main/java/org/apache/roller/weblogger/ui/restapi/FieldErrors.java` — collects 400s from independent checks into one 400.
- `app/src/main/java/org/apache/roller/weblogger/ui/restapi/MediaImports.java` — one image import from a URL: fetch, hash, reuse a match or store, log one line.
- `app/src/main/java/org/apache/roller/weblogger/business/ContentHashes.java` — SHA-256 helpers.
- `app/src/main/java/org/apache/roller/weblogger/business/ExternalImages.java` — finds and rewrites external images in entry text (pure).
- `app/src/main/java/org/apache/roller/weblogger/ui/restapi/v1/EntryImports.java` — the entry write's import step: plan (checks) and run.
- `bin/db/migrations/V032__media_content_hash.sql`.
- Tests: `FieldErrorsTest`, `EntryWrites` (test builder), `EntriesWriteApiFieldErrorsTest`, `EntriesWriteApiAnchorTest`, `EntriesWriteApiMediaIdTest`, `MediaContentHashMigrationTest`, `MediaFileContentHashTest`, `MediaImportsTest`, `ExternalImagesTest`, `EntriesWriteApiImportsTest`.

Modified: `ApiProblem`, `ApiException`, `ColumnLimits`, `EntryDtos`, `EntriesWriteApi`, `EntryFieldRules`, `WeblogOwnership`, `MediaApi`, `MediaFile` + `MediaFile.orm.xml`, `MediaFileManager` + `JPAMediaFileManagerImpl`, `MarkdownRenderer` (extensions visible to the package), the other `/api/v1` controllers and DTOs (Task 9), `McpInstructions`, `docs/api/README.md`, `docs/dev/api.md`, `docs/dev/routing.md`, `docs/dev/media.md`, `it-selenium/.../McpIT.java`.

---

### Task 1: Field-error plumbing

**Files:**
- Modify: `app/src/main/java/org/apache/roller/weblogger/ui/restapi/ApiProblem.java`
- Modify: `app/src/main/java/org/apache/roller/weblogger/ui/restapi/ApiException.java`
- Create: `app/src/main/java/org/apache/roller/weblogger/ui/restapi/FieldErrors.java`
- Modify: `app/src/main/java/org/apache/roller/weblogger/ui/restapi/ColumnLimits.java` (`requireMaxLength`)
- Test: `app/src/test/java/org/apache/roller/weblogger/ui/restapi/FieldErrorsTest.java` (create), `app/src/test/java/org/apache/roller/weblogger/ui/restapi/ColumnLimitsTest.java` (add one test)

**Interfaces:**
- Produces:
  - `ApiException.invalidField(String field, String detail)` and `invalidField(String field, String detail, Throwable cause)` → 400 with one `FieldError(field, detail)`.
  - `ApiException.conflictOnField(String field, String detail)` → 409 with one `FieldError`.
  - `List<ApiProblem.FieldError> ApiException.getFieldErrors()` → a copy; empty when none.
  - `FieldErrors`: `<E extends Exception> void check(String field, FieldErrors.Check<E> check) throws E`, `<E extends Exception> void check(FieldErrors.Check<E> check) throws E`, `void add(String field, String message)`, `boolean isEmpty()`, `void throwIfAny()`.
  - `ColumnLimits.requireMaxLength` refusals carry `errors: [{field}]`.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/org/apache/roller/weblogger/ui/restapi/FieldErrorsTest.java`:

```java
package org.apache.roller.weblogger.ui.restapi;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Several checks' 400s reported as one (spec 2026-10-13, section 1). */
class FieldErrorsTest {

    @Test
    void everyFailingCheckIsReportedInOneException() {
        FieldErrors errors = new FieldErrors();
        errors.check("category", () -> {
            throw ApiException.badRequest("Unknown category 'X'.");
        });
        errors.check("geoLatitude", () -> {
            throw ApiException.badRequest("geoLatitude must be between -90 and 90.");
        });
        errors.check("title", () -> { });

        ApiException ex = assertThrows(ApiException.class, errors::throwIfAny);

        assertEquals(400, ex.getStatus());
        assertEquals(List.of("category", "geoLatitude"),
                ex.getFieldErrors().stream().map(ApiProblem.FieldError::field).toList());
        assertEquals("Unknown category 'X'. geoLatitude must be between -90 and 90.", ex.getMessage());
    }

    @Test
    void aSingleProblemKeepsItsOwnMessageAsTheDetail() {
        FieldErrors errors = new FieldErrors();
        errors.check("status", () -> {
            throw ApiException.badRequest("Unknown status 'X'.");
        });

        ApiException ex = assertThrows(ApiException.class, errors::throwIfAny);

        assertEquals("Unknown status 'X'.", ex.getMessage());
        assertEquals("status", ex.getFieldErrors().get(0).field());
    }

    @Test
    void aFieldTheCheckAlreadyNamedIsKept() {
        FieldErrors errors = new FieldErrors();
        errors.check("tags", () -> {
            throw ApiException.invalidField("tag", "too long");
        });

        ApiException ex = assertThrows(ApiException.class, errors::throwIfAny);

        assertEquals("tag", ex.getFieldErrors().get(0).field());
    }

    @Test
    void aCheckWithoutAFieldContributesEveryErrorItNames() {
        FieldErrors errors = new FieldErrors();
        errors.check(() -> {
            throw ApiException.validation("a b", List.of(
                    new ApiProblem.FieldError("a", "a"), new ApiProblem.FieldError("b", "b")));
        });

        ApiException ex = assertThrows(ApiException.class, errors::throwIfAny);

        assertEquals(2, ex.getFieldErrors().size());
    }

    @Test
    void anythingButA400Propagates() {
        FieldErrors errors = new FieldErrors();

        ApiException conflict = assertThrows(ApiException.class,
                () -> errors.check("anchor", () -> {
                    throw ApiException.conflict("taken");
                }));

        assertEquals(409, conflict.getStatus());
        assertTrue(errors.isEmpty());
    }

    @Test
    void noProblemsMeansNoException() {
        FieldErrors errors = new FieldErrors();
        errors.check("title", () -> { });

        assertDoesNotThrow(errors::throwIfAny);
    }

    @Test
    void addRecordsAProblemDirectly() {
        FieldErrors errors = new FieldErrors();
        errors.add("anchor", "anchor cannot change.");

        ApiException ex = assertThrows(ApiException.class, errors::throwIfAny);

        assertEquals("anchor cannot change.", ex.getMessage());
    }

    @Test
    void theProblemBodyCarriesTheFieldsAndOmitsAMissingOne() {
        ApiException ex = ApiException.validation("x y", List.of(
                new ApiProblem.FieldError("category", "x"), new ApiProblem.FieldError(null, "y")));
        ObjectMapper json = new ObjectMapper();

        JsonNode problem = json.readTree(json.writeValueAsString(ex.toProblem("/api/v1/x")));

        assertEquals("category", problem.get("errors").get(0).get("field").asString());
        assertFalse(problem.get("errors").get(1).has("field"));
    }

    @Test
    void invalidFieldAndConflictOnFieldNameTheirField() {
        assertEquals("url", ApiException.invalidField("url", "bad", new IllegalStateException())
                .getFieldErrors().get(0).field());
        ApiException conflict = ApiException.conflictOnField("anchor", "taken");
        assertEquals(409, conflict.getStatus());
        assertEquals("anchor", conflict.getFieldErrors().get(0).field());
        assertTrue(ApiException.badRequest("plain").getFieldErrors().isEmpty());
    }
}
```

Add to `ColumnLimitsTest`:

```java
    @Test
    void aRefusalNamesItsField() {
        ApiException ex = assertThrows(ApiException.class,
                () -> ColumnLimits.requireMaxLength("metaTitle", "x".repeat(256), 255));

        assertEquals("'metaTitle' must be 255 characters or fewer.", ex.getMessage());
        assertEquals("metaTitle", ex.getFieldErrors().get(0).field());
    }
```

- [ ] **Step 2: Run them and watch them fail**

Run: `mvn -o -q -pl app test -Dtest='FieldErrorsTest,ColumnLimitsTest'`
Expected: compilation failure (`FieldErrors`, `invalidField`, `getFieldErrors` do not exist).

- [ ] **Step 3: Implement**

In `ApiProblem.java`, annotate the nested record so a field-less error omits `field`:

```java
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FieldError(String field, String message) { }
```

In `ApiException.java`, after `validation(...)`:

```java
    /** A 400 about one request field: its detail, and an {@code errors} entry naming the field. */
    public static ApiException invalidField(String field, String detail) {
        return validation(detail, List.of(new ApiProblem.FieldError(field, detail)));
    }

    /** As above, chaining {@code cause} -- see the cause-carrying constructor. */
    public static ApiException invalidField(String field, String detail, Throwable cause) {
        return new ApiException(400, TYPE_BASE + "invalid-request", "Invalid request", detail,
                List.of(new ApiProblem.FieldError(field, detail)), cause);
    }

    /** A 409 about one request field, such as an entry anchor another entry already holds. */
    public static ApiException conflictOnField(String field, String detail) {
        return new ApiException(409, TYPE_BASE + "conflict", "Conflict", detail,
                List.of(new ApiProblem.FieldError(field, detail)));
    }
```

and beside `getStatus()`:

```java
    /** The field errors this exception carries; empty when it names none. */
    public List<ApiProblem.FieldError> getFieldErrors() {
        return errors == null ? List.of() : List.copyOf(errors);
    }
```

Create `FieldErrors.java`:

```java
package org.apache.roller.weblogger.ui.restapi;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The problems of several independent checks, reported together: a write
 * with three bad fields answers one 400 naming all three, not three round
 * trips. Only a 400 {@link ApiException} is collected. A 404, a 409 or an
 * unexpected failure is not a field problem, and propagates at once.
 */
public final class FieldErrors {

    /** One check. It reports a problem by throwing a 400 {@link ApiException}. */
    @FunctionalInterface
    public interface Check<E extends Exception> {
        void run() throws E;
    }

    private final List<ApiProblem.FieldError> errors = new ArrayList<>();

    /**
     * Runs {@code check} and records a 400 it throws. Errors the exception
     * names are kept as they are; one naming none is recorded against
     * {@code field}, so a check deep in a parser needs no field of its own.
     */
    public <E extends Exception> void check(String field, Check<E> check) throws E {
        try {
            check.run();
        } catch (ApiException e) {
            if (e.getStatus() != 400) {
                throw e;
            }
            List<ApiProblem.FieldError> named = e.getFieldErrors();
            errors.addAll(named.isEmpty() ? List.of(new ApiProblem.FieldError(field, e.getMessage())) : named);
        }
    }

    /** As above, for a check whose 400s name their own fields. */
    public <E extends Exception> void check(Check<E> check) throws E {
        check(null, check);
    }

    public void add(String field, String message) {
        errors.add(new ApiProblem.FieldError(field, message));
    }

    public boolean isEmpty() {
        return errors.isEmpty();
    }

    /** One 400 carrying every recorded problem, its detail their messages joined; nothing when there are none. */
    public void throwIfAny() {
        if (!errors.isEmpty()) {
            throw ApiException.validation(
                    errors.stream().map(ApiProblem.FieldError::message).collect(Collectors.joining(" ")),
                    List.copyOf(errors));
        }
    }
}
```

In `ColumnLimits.requireMaxLength`, replace `ApiException.badRequest(` with `ApiException.invalidField(field, ` (same message text):

```java
        if (value != null && value.length() > max) {
            throw ApiException.invalidField(field,
                    "'" + field + "' must be " + max + " characters or fewer.");
        }
```

- [ ] **Step 4: Run them and watch them pass**

Run: `mvn -o -q -pl app test -Dtest='FieldErrorsTest,ColumnLimitsTest,ApiExceptionHandlerTest'`
Expected: PASS.

- [ ] **Step 5: Full verify and commit**

Run: `mvn -o -pl app verify` — expected BUILD SUCCESS.

```bash
git add app/src/main/java/org/apache/roller/weblogger/ui/restapi/{ApiProblem,ApiException,FieldErrors,ColumnLimits}.java \
        app/src/test/java/org/apache/roller/weblogger/ui/restapi/{FieldErrorsTest,ColumnLimitsTest}.java
git commit -m "feat(api): field errors -- FieldErrors collects 400s, ColumnLimits names its field"
```

---

### Task 2: Entry writes check every field before applying any

**Files:**
- Create: `app/src/test/java/org/apache/roller/weblogger/ui/restapi/v1/EntryWrites.java` (test-only builder)
- Modify: `app/src/test/java/org/apache/roller/weblogger/ui/restapi/v1/EntriesApiWriteTest.java` (12 positional `new EntryDtos.EntryWrite(` calls → builder)
- Modify: `app/src/test/java/org/apache/roller/weblogger/ui/restapi/v1/EntryDtosClearAndEventTest.java` (1 call → builder)
- Modify: `app/src/main/java/org/apache/roller/weblogger/ui/restapi/dto/EntryDtos.java` (`applyWrite` collects; `scratchOf`; `pubTime` helper)
- Modify: `app/src/main/java/org/apache/roller/weblogger/ui/restapi/v1/EntriesWriteApi.java` (`create`, `update`, new `checkWrite`, `apply`, `categoryFor`, `checkTags`; `applyCategory` and `requireTitleAndText` removed)
- Test: `app/src/test/java/org/apache/roller/weblogger/ui/restapi/v1/EntriesWriteApiFieldErrorsTest.java` (create)

**Interfaces:**
- Consumes: Task 1's `FieldErrors`, `ApiException.invalidField`.
- Produces:
  - `EntryDtos.scratchOf(WeblogEntry) → WeblogEntry`.
  - `EntryDtos.applyWrite(...)` unchanged signature, now throwing one 400 that lists every bad field.
  - In `EntriesWriteApi` (private): `void checkWrite(FieldErrors, Weblog, WeblogEntry entry, EntryDtos.EntryWrite, Set<String> cleared) throws WebloggerException` and `void apply(WeblogEntry, Weblog, EntryDtos.EntryWrite, Set<String>) throws WebloggerException`. Tasks 3, 4 and 8 add to these.
  - Test builder `EntryWrites.write()…build()`. Tasks 3 and 8 add a setter to it for each component they add.

- [ ] **Step 1: Add the test builder and move the positional calls onto it (no behaviour change)**

Create `EntryWrites.java`:

```java
package org.apache.roller.weblogger.ui.restapi.v1;

import java.util.List;
import org.apache.roller.weblogger.ui.restapi.dto.EntryDtos;

/**
 * Builds an {@link EntryDtos.EntryWrite} by name. The record has twenty-odd
 * positional components, and a test that passes nulls by position breaks,
 * or silently shifts, whenever one is added.
 */
final class EntryWrites {

    private String title;
    private String summary;
    private String text;
    private String status;
    private String category;
    private String pubTime;
    private List<String> tags;
    private String metaTitle;
    private String searchDescription;
    private String canonicalUrl;
    private Boolean noindex;
    private String featuredImageId;
    private String ogImageId;
    private String jsonLdType;
    private Double geoLatitude;
    private Double geoLongitude;
    private String eventStart;
    private String eventEnd;
    private String eventLocation;
    private Boolean commentsClosed;
    private List<String> clear;

    private EntryWrites() {
    }

    static EntryWrites write() {
        return new EntryWrites();
    }

    EntryWrites title(String value) { title = value; return this; }
    EntryWrites summary(String value) { summary = value; return this; }
    EntryWrites text(String value) { text = value; return this; }
    EntryWrites status(String value) { status = value; return this; }
    EntryWrites category(String value) { category = value; return this; }
    EntryWrites pubTime(String value) { pubTime = value; return this; }
    EntryWrites tags(List<String> value) { tags = value; return this; }
    EntryWrites metaTitle(String value) { metaTitle = value; return this; }
    EntryWrites searchDescription(String value) { searchDescription = value; return this; }
    EntryWrites canonicalUrl(String value) { canonicalUrl = value; return this; }
    EntryWrites noindex(Boolean value) { noindex = value; return this; }
    EntryWrites featuredImageId(String value) { featuredImageId = value; return this; }
    EntryWrites ogImageId(String value) { ogImageId = value; return this; }
    EntryWrites jsonLdType(String value) { jsonLdType = value; return this; }
    EntryWrites geoLatitude(Double value) { geoLatitude = value; return this; }
    EntryWrites geoLongitude(Double value) { geoLongitude = value; return this; }
    EntryWrites eventStart(String value) { eventStart = value; return this; }
    EntryWrites eventEnd(String value) { eventEnd = value; return this; }
    EntryWrites eventLocation(String value) { eventLocation = value; return this; }
    EntryWrites commentsClosed(Boolean value) { commentsClosed = value; return this; }
    EntryWrites clear(List<String> value) { clear = value; return this; }

    EntryDtos.EntryWrite build() {
        return new EntryDtos.EntryWrite(title, summary, text, status, category, pubTime, tags,
                metaTitle, searchDescription, canonicalUrl, noindex, featuredImageId, ogImageId,
                jsonLdType, geoLatitude, geoLongitude, eventStart, eventEnd, eventLocation,
                commentsClosed, clear);
    }
}
```

Rewrite each `new EntryDtos.EntryWrite(...)` in `EntriesApiWriteTest` and `EntryDtosClearAndEventTest` with the builder, naming only the non-null positions. Positions, for reading the old calls: 1 title, 2 summary, 3 text, 4 status, 5 category, 6 pubTime, 7 tags, 8 metaTitle, 9 searchDescription, 10 canonicalUrl, 11 noindex, 12 featuredImageId, 13 ogImageId, 14 jsonLdType, 15 geoLatitude, 16 geoLongitude, 17 eventStart, 18 eventEnd, 19 eventLocation, 20 commentsClosed, 21 clear. For example the first call in `EntriesApiWriteTest` becomes `EntryWrites.write().title("Cats & Dogs").build()`, and `EntryDtosClearAndEventTest`'s helper becomes:

```java
        return EntryWrites.write().summary(summary).status(status).metaTitle(metaTitle)
                .searchDescription(searchDescription).canonicalUrl(canonicalUrl).noindex(noindex)
                .featuredImageId(featured).ogImageId(og).eventStart(eventStart).eventEnd(eventEnd)
                .eventLocation(eventLocation).commentsClosed(commentsClosed).build();
```

Run: `mvn -o -q -pl app test -Dtest='EntriesApiWriteTest,EntryDtosClearAndEventTest'` — expected PASS (a pure refactor of test code). Afterwards `grep -rn "new EntryDtos.EntryWrite(" app/src/test` must print only `EntryWrites.java`.

- [ ] **Step 2: Write the failing behaviour tests**

Create `EntriesWriteApiFieldErrorsTest.java`:

```java
package org.apache.roller.weblogger.ui.restapi.v1;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.apache.roller.weblogger.business.URLStrategy;
import org.apache.roller.weblogger.business.WeblogEntryManager;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogCategory;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.apache.roller.weblogger.ui.restapi.ApiExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * An entry write reports every bad field in one 400, and a refused write
 * changes nothing (spec 2026-10-13, section 1; acceptance criteria 1, 2, 3, 5).
 */
class EntriesWriteApiFieldErrorsTest {

    private final Weblogger weblogger = mock(Weblogger.class);
    private final WeblogEntryManager entries = mock(WeblogEntryManager.class);
    private final Weblog weblog = new Weblog();

    @BeforeEach
    void setUp() throws Exception {
        weblog.setId("myblog-id");
        weblog.setHandle("myblog");
        weblog.setTimeZone("UTC");
        when(weblogger.getWeblogEntryManager()).thenReturn(entries);
        when(weblogger.getUrlStrategy()).thenReturn(mock(URLStrategy.class));
        when(entries.getWeblogCategories(weblog)).thenReturn(List.of(
                new WeblogCategory(weblog, "Food", null, null),
                new WeblogCategory(weblog, "Travel", null, null)));
    }

    private MockMvc mvc() {
        EntriesWriteApi controller = new EntriesWriteApi();
        controller.weblogger = weblogger;
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    private JsonNode refused(MockHttpServletRequestBuilder request) throws Exception {
        User user = new User();
        user.setUserName("maiia");
        String body = mvc().perform(request
                        .requestAttr("actionWeblog", weblog)
                        .requestAttr("authenticatedUser", user)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();
        return new ObjectMapper().readTree(body);
    }

    private static List<String> fields(JsonNode problem) {
        List<String> fields = new ArrayList<>();
        for (JsonNode error : problem.get("errors")) {
            fields.add(error.get("field").asString());
        }
        return fields;
    }

    @Test
    void aCreateWithTwoBadFieldsIsOne400NamingBothAndSavesNothing() throws Exception {
        JsonNode problem = refused(post("/v1/weblogs/myblog/entries").content(
                "{\"title\":\"t\",\"text\":\"x\",\"category\":\"Travle\",\"geoLatitude\":91}"));

        assertEquals(Set.of("category", "geoLatitude"), Set.copyOf(fields(problem)));
        verify(entries, never()).saveWeblogEntry(any());
    }

    @Test
    void anUnknownCategoryNamesTheWeblogsCategories() throws Exception {
        JsonNode problem = refused(post("/v1/weblogs/myblog/entries").content(
                "{\"title\":\"t\",\"text\":\"x\",\"category\":\"Travle\"}"));

        assertEquals("Unknown category 'Travle'; it is one of: Food, Travel.", problem.get("detail").asString());
        assertEquals(List.of("category"), fields(problem));
    }

    @Test
    void aSingleProblemKeepsTodaysDetailAndNamesItsField() throws Exception {
        JsonNode problem = refused(post("/v1/weblogs/myblog/entries").content(
                "{\"title\":\"t\",\"text\":\"x\",\"status\":\"NOPE\"}"));

        assertEquals("Unknown status 'NOPE'.", problem.get("detail").asString());
        assertEquals(List.of("status"), fields(problem));
    }

    @Test
    void aCreateMissingTitleAndTextNamesBoth() throws Exception {
        JsonNode problem = refused(post("/v1/weblogs/myblog/entries").content("{}"));

        assertEquals(List.of("title", "text"), fields(problem));
    }

    /**
     * Criterion 3: the valid fields of a refused PATCH are not applied
     * either. Before this change applyWrite set the title, then threw on
     * jsonLdType, leaving the managed entity half-written.
     */
    @Test
    void aPatchRefusedForOneFieldChangesNoFieldAtAll() throws Exception {
        WeblogEntry stored = new WeblogEntry();
        stored.setId("e1");
        stored.setWebsite(weblog);
        stored.setTitle("kept");
        stored.setSummary("kept too");
        stored.setStatus(WeblogEntry.PubStatus.DRAFT);
        when(entries.getWeblogEntry("e1")).thenReturn(stored);

        refused(patch("/v1/weblogs/myblog/entries/e1").content(
                "{\"title\":\"new\",\"summary\":\"new\",\"jsonLdType\":\"NOPE\"}"));

        assertEquals("kept", stored.getTitle());
        assertEquals("kept too", stored.getSummary());
        verify(entries, never()).saveWeblogEntry(any());
    }

    /** The event-order check still sees a start stored on the entry, through the scratch copy. */
    @Test
    void aPatchEndingBeforeTheStoredStartIsRefused() throws Exception {
        WeblogEntry stored = new WeblogEntry();
        stored.setId("e2");
        stored.setWebsite(weblog);
        stored.setTitle("Festival");
        stored.setEventStart(java.sql.Timestamp.valueOf("2026-08-02 19:30:00"));
        when(entries.getWeblogEntry("e2")).thenReturn(stored);

        JsonNode problem = refused(patch("/v1/weblogs/myblog/entries/e2").content(
                "{\"eventEnd\":\"2026-08-01T10:00\"}"));

        assertEquals(List.of("eventEnd"), fields(problem));
        assertEquals(null, stored.getEventEnd());
    }
}
```

Add to `EntriesApiWriteTest`:

```java
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

        org.apache.roller.weblogger.ui.restapi.ApiException ex = assertThrows(
                org.apache.roller.weblogger.ui.restapi.ApiException.class,
                () -> EntryDtos.applyWrite(new WeblogEntry(), write, weblogInZone("UTC"), Set.of()));

        assertEquals(List.of("status", "jsonLdType", "geoLatitude"),
                ex.getFieldErrors().stream().map(e -> e.field()).toList());
    }
```

(add `import java.util.List;` to that test if missing.)

- [ ] **Step 3: Run them and watch them fail**

Run: `mvn -o -q -pl app test -Dtest='EntriesWriteApiFieldErrorsTest,EntriesApiWriteTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation fails on `EntryDtos.scratchOf`. After stubbing it out to make it compile, the field tests fail: no `errors` array, the PATCH test sees `title` = "new", the category message has no list.

- [ ] **Step 4: Rewrite `applyWrite` to collect every field's problem**

In `EntryDtos.java` add `import org.apache.roller.weblogger.ui.restapi.FieldErrors;` and replace `applyWrite` with:

```java
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
        if (write.eventStart() != null) {
            errors.check("eventStart", () -> entry.setEventStart(eventTime("eventStart", write.eventStart(), weblog)));
        }
        if (write.eventEnd() != null) {
            errors.check("eventEnd", () -> entry.setEventEnd(eventTime("eventEnd", write.eventEnd(), weblog)));
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
        if (EntryFieldRules.endsBeforeStart(entry.getEventStart(), entry.getEventEnd())) {
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
                    "pubTime must be a wall-clock time in the weblog's zone, for example 2026-03-01T09:30.", e);
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
```

Update the `applyWrite` javadoc's last sentence to: "{@code cleared} (already validated by {@code ClearFields}) is applied after the sets, before the event-order check. Every field's problem is collected; the one 400 thrown at the end names them all."

- [ ] **Step 5: Restructure `create` and `update` around `checkWrite` and `apply`**

In `EntriesWriteApi.java` add imports `java.util.HashSet`, `java.util.stream.Collectors`, `org.apache.roller.weblogger.ui.restapi.FieldErrors`. Replace `create`:

```java
    @PostMapping("")
    public ResponseEntity<EntryDtos.EntryView> create(
            HttpServletRequest request, @RequestBody EntryDtos.EntryWrite body) throws WebloggerException {
        Weblog weblog = requireActionWeblog(request);
        User user = requireAuthenticatedUser(request);

        if (body.clear() != null && !body.clear().isEmpty()) {
            throw ApiException.invalidField("clear", "clear applies only to updates.");
        }
        WeblogEntry entry = new WeblogEntry();
        entry.setWebsite(weblog);
        entry.setCreatorUserName(user.getUserName());

        FieldErrors errors = new FieldErrors();
        errors.check("title", () -> requireNotBlank("title", body.title()));
        errors.check("text", () -> requireNotBlank("text", body.text()));
        checkWrite(errors, weblog, entry, body, Set.of());
        errors.throwIfAny();

        apply(entry, weblog, body, Set.of());
        // No explicit category fallback needed here:
        // JPAWeblogEntryManagerImpl.saveWeblogEntry already defaults a
        // categoryless entry to the weblog's first category -- the same
        // fallback CategoriesApi.delete relies on to guarantee at least one
        // category always exists.
        weblogger.getWeblogEntryManager().saveWeblogEntry(entry);
        weblogger.flush();

        URI location = ServletUriComponentsBuilder.fromRequestUri(request)
                .path("/{id}")
                .buildAndExpand(entry.getId())
                .toUri();
        return ResponseEntity.created(location).body(EntryDtos.toView(entry, permalink(entry)));
    }
```

Replace `update`:

```java
    @PatchMapping("/{id}")
    public EntryDtos.EntryView update(
            HttpServletRequest request, @PathVariable("id") String id,
            @RequestBody EntryDtos.EntryWrite body) throws WebloggerException {
        Weblog weblog = requireActionWeblog(request);
        // requireLiveEntry, not requireEntry: a trashed entry's id here
        // would otherwise reach saveWeblogEntry directly, resurrecting it
        // (to DRAFT or straight to PUBLISHED) by a side door that bypasses
        // restore's DRAFT-only rule entirely. See requireLiveEntry's javadoc.
        WeblogEntry entry = requireLiveEntry(request, id);

        FieldErrors errors = new FieldErrors();
        Set<String> cleared = new HashSet<>();
        errors.check("clear", () -> cleared.addAll(ClearFields.validate(body.clear(), body,
                EntryDtos.ENTRY_CLEARABLE, EntryDtos.ENTRY_PAIRS)));
        checkWrite(errors, weblog, entry, body, cleared);
        errors.throwIfAny();

        apply(entry, weblog, body, cleared);
        weblogger.getWeblogEntryManager().saveWeblogEntry(entry);
        weblogger.flush();

        return EntryDtos.toView(entry, permalink(entry));
    }
```

Add these private methods (replacing `requireTitleAndText` and `applyCategory`; keep `applyPublishNowDefault` and `requireScheduledHasPubTime` with their javadocs as they are):

```java
    /**
     * Every field check of a write, before anything is applied. The checks
     * run against a scratch copy of {@code entry} ({@link EntryDtos#scratchOf}),
     * so a refused write leaves the real entry exactly as it was, and the
     * import step that runs between checking and applying (it commits) can
     * never commit half a write. Problems land in {@code errors}.
     */
    private void checkWrite(FieldErrors errors, Weblog weblog, WeblogEntry entry,
                            EntryDtos.EntryWrite body, Set<String> cleared) throws WebloggerException {
        WeblogEntry scratch = EntryDtos.scratchOf(entry);
        errors.check(() -> EntryDtos.applyWrite(scratch, body, weblog, cleared));
        errors.check("category", () -> categoryFor(weblog, body.category()));
        errors.check("tags", () -> checkTags(body.tags()));
        applyPublishNowDefault(scratch);
        errors.check("pubTime", () -> requireScheduledHasPubTime(scratch));
    }

    /**
     * Applies a write {@link #checkWrite} passed. Each step repeats a check
     * that already passed on identical input, so none of them refuses here.
     */
    private void apply(WeblogEntry entry, Weblog weblog, EntryDtos.EntryWrite body, Set<String> cleared)
            throws WebloggerException {
        EntryDtos.applyWrite(entry, body, weblog, cleared);
        WeblogCategory category = categoryFor(weblog, body.category());
        if (category != null) {
            entry.setCategory(category);
        }
        applyTags(entry, body.tags());
        applyPublishNowDefault(entry);
        requireScheduledHasPubTime(entry);
    }

    /**
     * {@code title} and {@code text} are NOT NULL columns
     * (V002__baseline_schema.sql) and a brand-new WeblogEntry defaults both
     * to null, so an omitted one used to reach weblogger.flush() and die on
     * the constraint -- an opaque 500 from the generic handler. Checked only
     * on create: an existing entry already satisfies the constraint, and a
     * PATCH omitting either must leave the stored value alone per
     * applyWrite's "null means absent" contract, not suddenly demand one.
     */
    private static void requireNotBlank(String field, String value) {
        if (value == null || value.isBlank()) {
            throw ApiException.invalidField(field, field + " is required.");
        }
    }

    /**
     * The category named by {@code categoryName}, or null when the write
     * names none (absent leaves the entry's category untouched, per
     * {@code applyWrite}'s "null means absent" contract). {@code category}
     * names a category by NAME, matching {@code EntryDtos.EntryView.category}.
     * {@code getWeblogCategoryByName} is scoped to {@code weblog} in the
     * query itself, so an unknown name is simply absent -- there is no
     * separate ownership check to bypass. An unknown name is answered with
     * the weblog's names, so a caller can correct itself in one round trip
     * instead of listing categories before every write.
     */
    private WeblogCategory categoryFor(Weblog weblog, String categoryName) throws WebloggerException {
        if (categoryName == null) {
            return null;
        }
        String trimmed = categoryName.trim();
        if (trimmed.isBlank()) {
            throw ApiException.invalidField("category", "category cannot be blank.");
        }
        WeblogCategory category = weblogger.getWeblogEntryManager().getWeblogCategoryByName(weblog, trimmed);
        if (category == null) {
            String names = weblogger.getWeblogEntryManager().getWeblogCategories(weblog).stream()
                    .map(WeblogCategory::getName)
                    .collect(Collectors.joining(", "));
            throw ApiException.invalidField("category",
                    "Unknown category '" + categoryName + "'; it is one of: " + names + ".");
        }
        return category;
    }

    /**
     * The length guard is checked AFTER splitting the joined string on
     * {@link Utilities#TAG_SPLIT_CHARS} -- the same split {@code
     * WeblogEntry.setTagsAsString} performs internally -- rather than per
     * list element. A single list element can itself carry several
     * space/comma-separated tags whose COMBINED length exceeds the column
     * even though every tag actually stored (one row per split token) is
     * short; checking before the split measured the wrong thing and
     * over-rejected that case.
     */
    private static void checkTags(List<String> tags) {
        if (tags == null) {
            return;
        }
        for (String tag : Utilities.splitStringAsTags(String.join(" ", tags))) {
            ColumnLimits.requireMaxLength("tags", tag, ColumnLimits.TAG_NAME);
        }
    }
```

and reduce `applyTags` to the write (its javadoc keeps the null/empty-list paragraph; the length paragraph moved to `checkTags`):

```java
    private void applyTags(WeblogEntry entry, List<String> tags) throws WebloggerException {
        if (tags != null) {
            entry.setTagsAsString(String.join(" ", tags));
        }
    }
```

- [ ] **Step 6: Run the tests and watch them pass**

Run: `mvn -o -q -pl app test -Dtest='EntriesWriteApi*Test,EntriesApi*Test,EntryDtos*Test' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS. If an existing test asserted the old tag-length message `'tag' must be …`, update its expected text to `'tags' must be …` (the field is now `tags`, the name the caller sent).

- [ ] **Step 7: Full verify and commit**

Run: `mvn -o -pl app verify` — expected BUILD SUCCESS.

```bash
git add app/src/main/java/org/apache/roller/weblogger/ui/restapi/dto/EntryDtos.java \
        app/src/main/java/org/apache/roller/weblogger/ui/restapi/v1/EntriesWriteApi.java \
        app/src/test/java/org/apache/roller/weblogger/ui/restapi/v1/
git commit -m "feat(api): an entry write names every bad field, and a refusal changes nothing"
```

---

### Task 3: Anchors chosen at create

**Files:**
- Modify: `app/src/main/java/org/apache/roller/weblogger/ui/controllers/EntryFieldRules.java`
- Modify: `app/src/main/java/org/apache/roller/weblogger/ui/restapi/dto/EntryDtos.java` (`EntryWrite.anchor`)
- Modify: `app/src/main/java/org/apache/roller/weblogger/ui/restapi/v1/EntriesWriteApi.java`
- Modify: `app/src/test/java/org/apache/roller/weblogger/ui/restapi/v1/EntryWrites.java` (+`anchor`)
- Test: `app/src/test/java/org/apache/roller/weblogger/ui/controllers/EntryFieldRulesTest.java` (add), `app/src/test/java/org/apache/roller/weblogger/ui/restapi/v1/EntriesWriteApiAnchorTest.java` (create)

**Interfaces:**
- Consumes: Task 1 `ApiException.conflictOnField`, Task 2 `checkWrite`/`apply`.
- Produces:
  - `EntryFieldRules.ANCHOR_MAX_LENGTH` (255) and `EntryFieldRules.isValidAnchor(String) → boolean`.
  - `EntryDtos.EntryWrite` gains component `String anchor`, placed after `commentsClosed` and before `clear`.
  - `EntriesWriteApi.requireAnchorFree(Weblog, String) throws WebloggerException` (private). Task 8 calls it a second time.

- [ ] **Step 1: Write the failing tests**

Add to `EntryFieldRulesTest`:

```java
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "torrey-pines", "torrey_pines", "a", "2026-berlin-wall", "x1-y2_z3"})
    void lowerCaseWordsJoinedByOneSeparatorAreAValidAnchor(String anchor) {
        assertTrue(EntryFieldRules.isValidAnchor(anchor));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "Torrey", "torrey pines", "torrey--pines", "-x", "x-", "", "straße", "a/b", "a.b", "a-_b"})
    void anythingElseIsNot(String anchor) {
        assertFalse(EntryFieldRules.isValidAnchor(anchor));
    }

    @Test
    void anAnchorMayBeAsLongAsTheColumnAndNoLonger() {
        assertTrue(EntryFieldRules.isValidAnchor("a".repeat(255)));
        assertFalse(EntryFieldRules.isValidAnchor("a".repeat(256)));
        assertFalse(EntryFieldRules.isValidAnchor(null));
    }
```

Create `EntriesWriteApiAnchorTest.java` (same fixture shape as `EntriesWriteApiFieldErrorsTest`; copy its `setUp`, `mvc()` and `fields(...)` and add the ones below):

```java
package org.apache.roller.weblogger.ui.restapi.v1;

// imports: as EntriesWriteApiFieldErrorsTest, plus
// org.mockito.ArgumentCaptor, org.springframework.test.web.servlet.ResultActions,
// static org.mockito.ArgumentMatchers.eq, static org.mockito.Mockito.doAnswer

/** Anchors chosen at create (spec 2026-10-13, section 2; criteria 6-9). */
class EntriesWriteApiAnchorTest {

    // weblogger, entries, weblog, setUp(), mvc() and fields(...) exactly as in EntriesWriteApiFieldErrorsTest

    private ResultActions send(MockHttpServletRequestBuilder request) throws Exception {
        User user = new User();
        user.setUserName("maiia");
        return mvc().perform(request.requestAttr("actionWeblog", weblog)
                .requestAttr("authenticatedUser", user).contentType(MediaType.APPLICATION_JSON));
    }

    private static JsonNode json(ResultActions result) throws Exception {
        return new ObjectMapper().readTree(result.andReturn().getResponse().getContentAsString());
    }

    private WeblogEntry holder(WeblogEntry.PubStatus status) {
        WeblogEntry holder = new WeblogEntry();
        holder.setId("holder");
        holder.setWebsite(weblog);
        holder.setAnchor("torrey-pines");
        holder.setStatus(status);
        return holder;
    }

    @Test
    void aChosenAnchorIsStoredAndThePermalinkEndsWithIt() throws Exception {
        URLStrategy urls = weblogger.getUrlStrategy();
        when(urls.getWeblogEntryURL(weblog, null, "torrey-pines", true))
                .thenReturn("https://blog.example.com/myblog/entry/torrey-pines");
        ArgumentCaptor<WeblogEntry> saved = ArgumentCaptor.forClass(WeblogEntry.class);

        JsonNode view = json(send(post("/v1/weblogs/myblog/entries").content(
                "{\"title\":\"Torrey Pines State Natural Reserve\",\"text\":\"x\",\"anchor\":\"torrey-pines\"}"))
                .andExpect(status().isCreated()));

        verify(entries).saveWeblogEntry(saved.capture());
        assertEquals("torrey-pines", saved.getValue().getAnchor());
        assertTrue(view.get("permalink").asString().endsWith("/torrey-pines"));
    }

    @Test
    void withoutAnAnchorTheManagerGeneratesOneAsBefore() throws Exception {
        ArgumentCaptor<WeblogEntry> saved = ArgumentCaptor.forClass(WeblogEntry.class);

        send(post("/v1/weblogs/myblog/entries").content("{\"title\":\"t\",\"text\":\"x\"}"))
                .andExpect(status().isCreated());

        verify(entries).saveWeblogEntry(saved.capture());
        assertNull(saved.getValue().getAnchor());
        verify(entries, never()).getWeblogEntryByAnchor(any(), any());
    }

    @Test
    void aMalformedAnchorIsA400BeforeAnyLookup() throws Exception {
        JsonNode problem = json(send(post("/v1/weblogs/myblog/entries").content(
                "{\"title\":\"t\",\"text\":\"x\",\"anchor\":\"Torrey Pines\"}")).andExpect(status().isBadRequest()));

        assertEquals(List.of("anchor"), fields(problem));
        verify(entries, never()).getWeblogEntryByAnchor(any(), any());
        verify(entries, never()).saveWeblogEntry(any());
    }

    @Test
    void anAnchorALiveEntryHoldsIsA409() throws Exception {
        when(entries.getWeblogEntryByAnchor(weblog, "torrey-pines")).thenReturn(holder(WeblogEntry.PubStatus.PUBLISHED));

        JsonNode problem = json(send(post("/v1/weblogs/myblog/entries").content(
                "{\"title\":\"t\",\"text\":\"x\",\"anchor\":\"torrey-pines\"}")).andExpect(status().isConflict()));

        assertEquals(List.of("anchor"), fields(problem));
        assertEquals("anchor 'torrey-pines' is already used by another entry.", problem.get("detail").asString());
        verify(entries, never()).saveWeblogEntry(any());
    }

    @Test
    void anAnchorATrashedEntryHoldsIsA409ThatSaysHowToFreeIt() throws Exception {
        when(entries.getWeblogEntryByAnchor(weblog, "torrey-pines")).thenReturn(holder(WeblogEntry.PubStatus.TRASHED));

        JsonNode problem = json(send(post("/v1/weblogs/myblog/entries").content(
                "{\"title\":\"t\",\"text\":\"x\",\"anchor\":\"torrey-pines\"}")).andExpect(status().isConflict()));

        assertTrue(problem.get("detail").asString().contains("in the trash"));
        assertTrue(problem.get("detail").asString().contains("delete it forever"));
    }

    @Test
    void theConflictIsReportedOnlyOnceEveryOtherFieldIsValid() throws Exception {
        when(entries.getWeblogEntryByAnchor(weblog, "torrey-pines")).thenReturn(holder(WeblogEntry.PubStatus.PUBLISHED));

        JsonNode problem = json(send(post("/v1/weblogs/myblog/entries").content(
                "{\"title\":\"t\",\"text\":\"x\",\"anchor\":\"torrey-pines\",\"category\":\"Travle\"}"))
                .andExpect(status().isBadRequest()));

        assertEquals(List.of("category"), fields(problem));
        verify(entries, never()).getWeblogEntryByAnchor(any(), any());
    }

    private WeblogEntry stored() throws Exception {
        WeblogEntry stored = new WeblogEntry();
        stored.setId("e1");
        stored.setWebsite(weblog);
        stored.setTitle("Torrey Pines");
        stored.setAnchor("torrey-pines");
        stored.setStatus(WeblogEntry.PubStatus.DRAFT);
        when(entries.getWeblogEntry("e1")).thenReturn(stored);
        return stored;
    }

    /** Review Focus 1: an entry sent back as it was read carries its own anchor. */
    @Test
    void aPatchCarryingTheStoredAnchorSucceeds() throws Exception {
        WeblogEntry stored = stored();

        send(patch("/v1/weblogs/myblog/entries/e1").content(
                "{\"anchor\":\"torrey-pines\",\"summary\":\"new\"}")).andExpect(status().isOk());

        assertEquals("new", stored.getSummary());
        assertEquals("torrey-pines", stored.getAnchor());
    }

    @Test
    void aPatchCarryingAnotherAnchorIsA400AndChangesNothing() throws Exception {
        WeblogEntry stored = stored();

        JsonNode problem = json(send(patch("/v1/weblogs/myblog/entries/e1").content(
                "{\"anchor\":\"torrey\",\"summary\":\"new\"}")).andExpect(status().isBadRequest()));

        assertEquals(List.of("anchor"), fields(problem));
        assertEquals("torrey-pines", stored.getAnchor());
        assertNull(stored.getSummary());
        verify(entries, never()).saveWeblogEntry(any());
    }
}
```

- [ ] **Step 2: Run them and watch them fail**

Run: `mvn -o -q -pl app test -Dtest='EntryFieldRulesTest,EntriesWriteApiAnchorTest'`
Expected: compilation fails (`isValidAnchor`, `EntryWrites.anchor` do not exist).

- [ ] **Step 3: Implement**

In `EntryFieldRules.java` (add `import java.util.regex.Pattern;` if missing):

```java
    /** {@code weblogentry.anchor} is varchar(255) (V002__baseline_schema.sql). */
    public static final int ANCHOR_MAX_LENGTH = 255;

    /** A chosen anchor: lower-case letters and digits, in words joined by one '-' or '_'. */
    private static final Pattern CHOSEN_ANCHOR = Pattern.compile("[a-z0-9]+(?:[-_][a-z0-9]+)*");

    /**
     * Whether a caller may choose {@code anchor} as an entry's slug. Stricter
     * than the anchors Roller generates or has stored, which may carry other
     * characters: a chosen anchor is refused, never normalised, so the URL is
     * exactly the one the caller asked for.
     */
    public static boolean isValidAnchor(String anchor) {
        return anchor != null && anchor.length() <= ANCHOR_MAX_LENGTH && CHOSEN_ANCHOR.matcher(anchor).matches();
    }
```

In `EntryDtos.EntryWrite`, add after `commentsClosed` (before `clear`):

```java
            @Schema(description = "The entry's URL slug: lower-case letters and digits in words joined by - or _, "
                    + "for example torrey-pines. Set only when the entry is created; omitted, it is made from the "
                    + "title. Another entry of the weblog holding it, a trashed one included, answers 409.")
            String anchor,
```

Add `anchor` to `EntryWrites` (field, setter `EntryWrites anchor(String value)`, and in `build()` between `commentsClosed` and `clear`).

In `EntriesWriteApi.create`, after the two `requireNotBlank` checks:

```java
        if (body.anchor() != null) {
            errors.check("anchor", () -> requireAnchorForm(body.anchor()));
        }
```

and after `errors.throwIfAny();`:

```java
        if (body.anchor() != null) {
            requireAnchorFree(weblog, body.anchor());
            // saveWeblogEntry generates an anchor only when none is set
            entry.setAnchor(body.anchor());
        }
```

In `update`, after the `clear` check:

```java
        if (body.anchor() != null && !body.anchor().equals(entry.getAnchor())) {
            errors.add("anchor", "anchor is set when an entry is created and cannot change.");
        }
```

Add the helpers:

```java
    private static void requireAnchorForm(String anchor) {
        if (!EntryFieldRules.isValidAnchor(anchor)) {
            throw ApiException.invalidField("anchor", "anchor must be lower-case letters and digits in words "
                    + "joined by one '-' or '_', at most " + EntryFieldRules.ANCHOR_MAX_LENGTH
                    + " characters, for example torrey-pines.");
        }
    }

    /**
     * A chosen anchor no entry of this weblog holds. A trashed entry still
     * holds its anchor -- it can be restored to the same permalink -- so the
     * message says how to free it. Not a field-format problem but a clash
     * with stored state, hence 409, reported only once every other field
     * passed: there is no point telling a caller about a clash in a write
     * that would be refused anyway.
     */
    private void requireAnchorFree(Weblog weblog, String anchor) throws WebloggerException {
        WeblogEntry holder = weblogger.getWeblogEntryManager().getWeblogEntryByAnchor(weblog, anchor);
        if (holder == null) {
            return;
        }
        throw ApiException.conflictOnField("anchor", holder.getStatus() == WeblogEntry.PubStatus.TRASHED
                ? "anchor '" + anchor + "' is held by an entry in the trash; delete it forever to free it."
                : "anchor '" + anchor + "' is already used by another entry.");
    }
```

- [ ] **Step 4: Run them and watch them pass**

Run: `mvn -o -q -pl app test -Dtest='EntryFieldRulesTest,EntriesWriteApi*Test,EntriesApiWriteTest,McpToolCatalogTest,OpenApiDocumentTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS. (`anchor` becomes an MCP `entries` argument automatically; `ArgumentDeriver` derives it from the record.)

- [ ] **Step 5: Full verify and commit**

Run: `mvn -o -pl app verify` — expected BUILD SUCCESS.

```bash
git add app/src/main/java/org/apache/roller/weblogger/ui/controllers/EntryFieldRules.java \
        app/src/main/java/org/apache/roller/weblogger/ui/restapi/dto/EntryDtos.java \
        app/src/main/java/org/apache/roller/weblogger/ui/restapi/v1/EntriesWriteApi.java \
        app/src/test/java/org/apache/roller/weblogger/ui/controllers/EntryFieldRulesTest.java \
        app/src/test/java/org/apache/roller/weblogger/ui/restapi/v1/{EntryWrites,EntriesWriteApiAnchorTest}.java
git commit -m "feat(api): choose an entry's anchor at create; 409 when another entry holds it"
```

---

### Task 4: Featured and Open Graph image ids must be this weblog's public images

**Files:**
- Modify: `app/src/main/java/org/apache/roller/weblogger/ui/controllers/WeblogOwnership.java` (+`mediaFile`)
- Modify: `app/src/main/java/org/apache/roller/weblogger/ui/restapi/v1/MediaApi.java` (`requireMedia` delegates; `belongsTo` deleted)
- Modify: `app/src/main/java/org/apache/roller/weblogger/ui/restapi/v1/EntriesWriteApi.java` (`checkWrite` + `checkImageId`)
- Modify: `app/src/test/java/org/apache/roller/weblogger/ui/restapi/v1/EntriesWriteApiControllerTest.java` (`patchAcceptsAnImportedMediaIdAsTheFeaturedImage` stubs a real image)
- Test: `app/src/test/java/org/apache/roller/weblogger/ui/controllers/WeblogOwnershipTest.java` (add), `app/src/test/java/org/apache/roller/weblogger/ui/restapi/v1/EntriesWriteApiMediaIdTest.java` (create)

**Interfaces:**
- Produces: `WeblogOwnership.mediaFile(Weblogger, String id, Weblog) → MediaFile` (null for blank, unknown, foreign or unreadable). Task 6 (`MediaImports`) does not need it; `MediaApi` and `EntriesWriteApi` do.

- [ ] **Step 1: Write the failing tests**

Add to `WeblogOwnershipTest` (it is DB-backed; `mine`, `theirs`, `weblogger` come from its `setUp`):

```java
    @Test
    void aMediaFileIsFoundOnlyThroughItsOwnWeblog() throws Exception {
        weblogger.getPropertiesManager().getProperties().get("uploads.enabled").setValue("true");
        MediaFileManager mfm = weblogger.getMediaFileManager();
        Weblog managed = TestUtils.getManagedWebsite(mine);
        MediaFile file = new MediaFile();
        file.setName("own.jpg");
        file.setDirectory(mfm.getDefaultMediaFileDirectory(managed));
        file.setWeblog(managed);
        file.setContentType("image/jpeg");
        file.setInputStream(getClass().getResourceAsStream("/hawk.jpg"));
        mfm.createMediaFile(managed, file, new RollerMessages());
        TestUtils.endSession(true);

        assertNotNull(WeblogOwnership.mediaFile(weblogger, file.getId(), TestUtils.getManagedWebsite(mine)));
        assertNull(WeblogOwnership.mediaFile(weblogger, file.getId(), TestUtils.getManagedWebsite(theirs)));
        assertNull(WeblogOwnership.mediaFile(weblogger, "no-such-id", TestUtils.getManagedWebsite(mine)));
        assertNull(WeblogOwnership.mediaFile(weblogger, " ", TestUtils.getManagedWebsite(mine)));
        assertNull(WeblogOwnership.mediaFile(weblogger, null, TestUtils.getManagedWebsite(mine)));
    }
```

(imports: `MediaFile`, `MediaFileManager`, `RollerMessages`, `assertNotNull`, `assertNull`.)

Create `EntriesWriteApiMediaIdTest.java` with the `EntriesWriteApiFieldErrorsTest` fixture (copy `setUp`, `mvc()`, `fields(...)`, and `EntriesWriteApiAnchorTest`'s `send`/`json`) plus a mocked `MediaFileManager`:

```java
/** featuredImageId/ogImageId name this weblog's public images (spec 2026-10-13, section 4; criterion 10). */
class EntriesWriteApiMediaIdTest {

    // weblogger, entries, weblog, mvc(), fields(...), send(...), json(...) as in EntriesWriteApiAnchorTest
    private final MediaFileManager media = mock(MediaFileManager.class);

    // The copied setUp(), with these lines appended to it. One @BeforeEach,
    // not two: JUnit does not promise the order of two in one class.
    @BeforeEach
    void setUp() throws Exception {
        // ... the copied setUp() body ...
        when(weblogger.getMediaFileManager()).thenReturn(media);
        Weblog other = new Weblog();
        other.setId("other-id");
        MediaFileDirectory mine = new MediaFileDirectory(weblog, "default", null);
        MediaFileDirectory hidden = new MediaFileDirectory(weblog, "hidden", null);
        hidden.setPrivate(true);
        MediaFileDirectory theirs = new MediaFileDirectory(other, "default", null);
        stub("img", mine, "image/jpeg");
        stub("pdf", mine, "application/pdf");
        stub("private", hidden, "image/jpeg");
        stub("foreign", theirs, "image/jpeg");
    }

    private void stub(String id, MediaFileDirectory directory, String contentType) throws Exception {
        MediaFile file = new MediaFile();
        file.setId(id);
        file.setDirectory(directory);
        file.setWeblog(directory.getWeblog());
        file.setContentType(contentType);
        when(media.getMediaFile(id)).thenReturn(file);
    }

    private int create(String field, String id) throws Exception {
        return send(post("/v1/weblogs/myblog/entries").content(
                "{\"title\":\"t\",\"text\":\"x\",\"" + field + "\":\"" + id + "\"}"))
                .andReturn().getResponse().getStatus();
    }

    @Test
    void aPublicImageOfThisWeblogIsAccepted() throws Exception {
        assertEquals(201, create("featuredImageId", "img"));
        assertEquals(201, create("ogImageId", "img"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"foreign", "pdf", "private", "missing"})
    void anythingElseIsA400OnTheField(String id) throws Exception {
        JsonNode problem = json(send(post("/v1/weblogs/myblog/entries").content(
                "{\"title\":\"t\",\"text\":\"x\",\"featuredImageId\":\"" + id + "\",\"ogImageId\":\"" + id + "\"}"))
                .andExpect(status().isBadRequest()));

        assertEquals(List.of("featuredImageId", "ogImageId"), fields(problem));
        verify(entries, never()).saveWeblogEntry(any());
    }

    /** Review Focus 1: the stored id of a file deleted since is not re-checked. */
    @Test
    void aPatchCarryingTheStoredIdOfADeletedFileSucceeds() throws Exception {
        WeblogEntry stored = new WeblogEntry();
        stored.setId("e1");
        stored.setWebsite(weblog);
        stored.setTitle("Harbour");
        stored.setFeaturedImageId("deleted-since");
        when(entries.getWeblogEntry("e1")).thenReturn(stored);

        send(patch("/v1/weblogs/myblog/entries/e1").content(
                "{\"featuredImageId\":\"deleted-since\",\"summary\":\"new\"}")).andExpect(status().isOk());

        assertEquals("new", stored.getSummary());
        verify(media, never()).getMediaFile("deleted-since");
    }
}
```

- [ ] **Step 2: Run them and watch them fail**

Run: `mvn -o -q -pl app test -Dtest='WeblogOwnershipTest,EntriesWriteApiMediaIdTest'`
Expected: compilation fails on `WeblogOwnership.mediaFile`; once it exists, `anythingElseIsA400OnTheField` fails with 201.

- [ ] **Step 3: Implement**

In `WeblogOwnership.java` (import `org.apache.roller.weblogger.pojos.MediaFile`):

```java
    /**
     * The media file with this id, but only when it belongs to {@code
     * weblog}. A file's weblog is its directory's; {@code MediaFile.weblog}
     * is the fallback for a file read without its directory.
     */
    public static MediaFile mediaFile(Weblogger weblogger, String id, Weblog weblog) {
        if (id == null || id.isBlank()) {
            return null;
        }
        try {
            MediaFile file = weblogger.getMediaFileManager().getMediaFile(id);
            if (file == null) {
                return null;
            }
            Weblog owner = file.getDirectory() != null && file.getDirectory().getWeblog() != null
                    ? file.getDirectory().getWeblog()
                    : file.getWeblog();
            if (owner == null || owner.getId() == null || !owner.getId().equals(weblog.getId())) {
                return null;
            }
            return file;
        } catch (WebloggerException ex) {
            log.error("Error looking up a media file for weblog {}", handleOf(weblog), ex);
        }
        return null;
    }
```

In `MediaApi.requireMedia`, replace the lookup and the `belongsTo` call:

```java
        MediaFile file = WeblogOwnership.mediaFile(weblogger, id, weblog);
        if (file == null) {
            throw ApiException.notFound("No such media file.");
        }
        return file;
```

delete `belongsTo`, and change `requireMedia`'s javadoc to say it delegates to `WeblogOwnership.mediaFile`, the by-id ownership check it shares with the entry write's image-id check.

In `EntriesWriteApi.checkWrite`, append:

```java
        checkImageId(errors, weblog, "featuredImageId", body.featuredImageId(), entry.getFeaturedImageId());
        checkImageId(errors, weblog, "ogImageId", body.ogImageId(), entry.getOgImageId());
```

and add (imports `MediaFile`, `WeblogOwnership`):

```java
    /**
     * featuredImageId and ogImageId must name an image readers can load: a
     * media file of this weblog, an image, outside every private directory.
     * They were only length-checked, so a typo or a foreign id was stored and
     * the post silently had no featured image. A value equal to the stored
     * one is not re-checked, so an entry sent back exactly as it was read
     * never fails on a file deleted since.
     */
    private void checkImageId(FieldErrors errors, Weblog weblog, String field, String id, String stored) {
        if (id == null || id.equals(stored)) {
            return;
        }
        MediaFile file = WeblogOwnership.mediaFile(weblogger, id, weblog);
        boolean hidden = file != null && file.getDirectory() != null && file.getDirectory().isPrivate();
        if (file == null || !file.isImageFile() || hidden) {
            errors.add(field, field + " must be the id of an image in this weblog's media, "
                    + "outside any private directory.");
        }
    }
```

In `EntriesWriteApiControllerTest.patchAcceptsAnImportedMediaIdAsTheFeaturedImage`, after `String mediaId = …`, stub the image (imports `MediaFileManager`, `MediaFileDirectory`, `MediaFile`):

```java
        MediaFileManager media = mock(MediaFileManager.class);
        when(weblogger.getMediaFileManager()).thenReturn(media);
        org.apache.roller.weblogger.pojos.MediaFile image = new org.apache.roller.weblogger.pojos.MediaFile();
        image.setId(mediaId);
        image.setDirectory(new org.apache.roller.weblogger.pojos.MediaFileDirectory(weblog, "default", null));
        image.setContentType("image/jpeg");
        when(media.getMediaFile(mediaId)).thenReturn(image);
```

and add to its javadoc: "The id must now name an image of this weblog (spec 2026-10-13, section 4), so the test stubs one."

- [ ] **Step 4: Run them and watch them pass**

Run: `mvn -o -q -pl app test -Dtest='WeblogOwnership*Test,EntriesWriteApi*Test,MediaApi*Test' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 5: Full verify and commit**

Run: `mvn -o -pl app verify` — expected BUILD SUCCESS.

```bash
git add app/src/main/java/org/apache/roller/weblogger/ui/controllers/WeblogOwnership.java \
        app/src/main/java/org/apache/roller/weblogger/ui/restapi/v1/{MediaApi,EntriesWriteApi}.java \
        app/src/test/java/org/apache/roller/weblogger/ui/controllers/WeblogOwnershipTest.java \
        app/src/test/java/org/apache/roller/weblogger/ui/restapi/v1/{EntriesWriteApiMediaIdTest,EntriesWriteApiControllerTest}.java
git commit -m "fix(api): featuredImageId and ogImageId must name a public image of the weblog"
```

---

### Task 5: Media files record a content hash (V032)

**Files:**
- Create: `bin/db/migrations/V032__media_content_hash.sql`
- Create: `app/src/main/java/org/apache/roller/weblogger/business/ContentHashes.java`
- Modify: `app/src/main/java/org/apache/roller/weblogger/pojos/MediaFile.java` (+`contentHash`)
- Modify: `app/src/main/resources/org/apache/roller/weblogger/pojos/MediaFile.orm.xml` (+basic, +named query)
- Modify: `app/src/main/java/org/apache/roller/weblogger/business/MediaFileManager.java` (+`getMediaFileByContentHash`)
- Modify: `app/src/main/java/org/apache/roller/weblogger/business/jpa/JPAMediaFileManagerImpl.java` (`persistNewMediaFile`, `updateMediaFile(…, InputStream)`, `cropMediaFile`, new query method)
- Test: `app/src/test/java/org/apache/roller/weblogger/business/startup/MediaContentHashMigrationTest.java` (create), `app/src/test/java/org/apache/roller/weblogger/business/MediaFileContentHashTest.java` (create)

**Interfaces:**
- Produces:
  - `ContentHashes.sha256Hex(byte[]) → String` (64 lower-case hex) and `ContentHashes.sha256() → MessageDigest`.
  - `MediaFile.getContentHash()/setContentHash(String)`.
  - `MediaFileManager.getMediaFileByContentHash(MediaFileDirectory directory, String contentHash) throws WebloggerException → MediaFile` (oldest match, or null).

- [ ] **Step 1: Write the failing tests**

Create `MediaContentHashMigrationTest.java` in `business/startup`. Copy the private helpers `scalar`, `exec`, `freshDatabase`, `dropDatabase`, `adminConnection` and `jdbcUrlFor` verbatim from `CommentsMigrationTest` (CPD does not scan tests), then:

```java
/** V032 adds roller_mediafile.content_hash (spec 2026-10-13, section 5; criterion 11). */
public class MediaContentHashMigrationTest {

    @Test
    void theColumnIsANullableSixtyFourCharacterString() throws Exception {
        String db = "migrationtest_content_hash";
        try (Connection con = freshDatabase(db)) {
            assertEquals(1, scalar(con, "SELECT count(*) FROM information_schema.columns "
                    + "WHERE table_name = 'roller_mediafile' AND column_name = 'content_hash' "
                    + "AND is_nullable = 'YES' AND character_maximum_length = 64"));
            assertEquals(1, scalar(con, "SELECT count(*) FROM pg_indexes "
                    + "WHERE indexname = 'mf_dir_content_hash_idx'"));
        } finally {
            dropDatabase(db);
        }
    }

    @Test
    void applyingItAgainChangesNothing() throws Exception {
        String db = "migrationtest_content_hash_twice";
        try (Connection con = freshDatabase(db)) {
            for (Path migration : MigrationFiles.all()) {
                if (migration.getFileName().toString().startsWith("V032")) {
                    exec(con, Files.readString(migration, StandardCharsets.UTF_8));
                }
            }
            assertEquals(1, scalar(con, "SELECT count(*) FROM information_schema.columns "
                    + "WHERE table_name = 'roller_mediafile' AND column_name = 'content_hash'"));
        } finally {
            dropDatabase(db);
        }
    }
}
```

Create `MediaFileContentHashTest.java` in `business`, using `MediaFileCropTest`'s fixture (copy its `setUp`, `tearDown`, `upload(...)`, `generatedJpeg(...)` and fields; user `contentHashTestUser`, weblog `contentHashTestWeblog`):

```java
/** A new media file records the SHA-256 of its bytes; a content change clears it (criterion 12). */
public class MediaFileContentHashTest {

    // fields, setUp(), tearDown(), upload(...), generatedJpeg(...) as in MediaFileCropTest

    @Test
    void aNewFileRecordsTheHashOfItsBytes() throws Exception {
        byte[] bytes = generatedJpeg(40, 30);
        MediaFile file = upload("hash.jpg", "image/jpeg", new ByteArrayInputStream(bytes));

        MediaFile reread = mfMgr.getMediaFile(file.getId());
        assertEquals(ContentHashes.sha256Hex(bytes), reread.getContentHash());
        assertEquals(64, reread.getContentHash().length());
    }

    @Test
    void replacingTheContentClearsTheHash() throws Exception {
        MediaFile file = upload("replace.jpg", "image/jpeg", new ByteArrayInputStream(generatedJpeg(40, 30)));
        MediaFile managed = mfMgr.getMediaFile(file.getId());

        mfMgr.updateMediaFile(TestUtils.getManagedWebsite(weblog), managed,
                new ByteArrayInputStream(generatedJpeg(50, 50)));
        TestUtils.endSession(true);

        assertNull(mfMgr.getMediaFile(file.getId()).getContentHash());
    }

    @Test
    void croppingClearsTheHash() throws Exception {
        MediaFile file = upload("crop.jpg", "image/jpeg", new ByteArrayInputStream(generatedJpeg(80, 60)));
        MediaFile managed = mfMgr.getMediaFile(file.getId());

        mfMgr.cropMediaFile(TestUtils.getManagedWebsite(weblog), managed, 0, 0, 40, 30);
        TestUtils.endSession(true);

        assertNull(mfMgr.getMediaFile(file.getId()).getContentHash());
    }

    @Test
    void theLookupFindsAMatchOnlyInTheSameDirectory() throws Exception {
        byte[] bytes = generatedJpeg(40, 30);
        MediaFile file = upload("match.jpg", "image/jpeg", new ByteArrayInputStream(bytes));
        String hash = ContentHashes.sha256Hex(bytes);
        MediaFileDirectory other = mfMgr.createMediaFileDirectory(TestUtils.getManagedWebsite(weblog), "elsewhere");
        TestUtils.endSession(true);

        MediaFileDirectory root = mfMgr.getMediaFileDirectory(rootDirectory.getId());
        assertEquals(file.getId(), mfMgr.getMediaFileByContentHash(root, hash).getId());
        assertNull(mfMgr.getMediaFileByContentHash(mfMgr.getMediaFileDirectory(other.getId()), hash));
        assertNull(mfMgr.getMediaFileByContentHash(root, ContentHashes.sha256Hex(new byte[] {1})));
        assertNull(mfMgr.getMediaFileByContentHash(root, null));
    }
}
```

(`createMediaFileDirectory(Weblog, String)` gives the directory its id in the constructor, which also adds it to the weblog's directories; `saveWeblog` cascades it, and `endSession(true)` flushes it — the same sequence `MediaApi.createDirectory` relies on.)

- [ ] **Step 2: Run them and watch them fail**

Run: `mvn -o -q -pl app test -Dtest='MediaContentHashMigrationTest,MediaFileContentHashTest'`
Expected: compilation fails (`ContentHashes`, `getContentHash`, `getMediaFileByContentHash`).

- [ ] **Step 3: Write the migration**

`bin/db/migrations/V032__media_content_hash.sql` (licence header copied verbatim from `V030__user_sso_identity.sql`), then:

```sql
--
-- Migration: a content hash on media files, so an image import whose bytes
-- are already in the target directory returns that file instead of a copy.
--
-- content_hash is the SHA-256 (lower-case hex) of the bytes the file was
-- created with. It is NULL for files from before this migration, and is
-- cleared when a file's content is replaced or cropped: such files never
-- match. Matching by URL was rejected -- a stored URL would keep a signed
-- URL's credentials, and what a URL serves can change. See
-- docs/superpowers/specs/2026-10-13-publishing-agent-api-refinements-design.md.
--
-- Idempotent: safe to run on a database that already has it.

ALTER TABLE roller_mediafile ADD COLUMN IF NOT EXISTS content_hash varchar(64);

CREATE INDEX IF NOT EXISTS mf_dir_content_hash_idx
    ON roller_mediafile (directoryid, content_hash)
    WHERE content_hash IS NOT NULL;
```

Run `grep -rn "V031" app/src/test docs/dev bin/db/migrations/README.md` and update any list or count of migrations that stops at V031 (`SchemaMigrationTest` discovers files itself).

- [ ] **Step 4: Implement the hash**

`ContentHashes.java` in `org.apache.roller.weblogger.business`:

```java
package org.apache.roller.weblogger.business;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 as media files record it: lower-case hex, 64 characters. */
public final class ContentHashes {

    private ContentHashes() {
    }

    /** A fresh SHA-256 digest. Every JDK ships SHA-256, so its absence is a broken runtime. */
    public static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    public static String sha256Hex(byte[] bytes) {
        return hex(sha256().digest(bytes));
    }

    public static String hex(byte[] digest) {
        return HexFormat.of().formatHex(digest);
    }
}
```

`MediaFile.java`: add `private String contentHash;` beside `blurhash`, with

```java
    /**
     * SHA-256 (lower-case hex) of the bytes this file was created with, or
     * null: for files from before V032, and once the content is replaced or
     * cropped. An import of identical bytes into the same directory returns
     * this file instead of storing a copy.
     */
    public String getContentHash() {
        return contentHash;
    }

    public void setContentHash(String contentHash) {
        this.contentHash = contentHash;
    }
```

`MediaFile.orm.xml`: add after the `MediaFile.getByWeblogAndOrigpath` named query

```xml
        <named-query name="MediaFile.getByDirectoryAndContentHash">
            <query>SELECT f FROM MediaFile f WHERE f.directory = ?1 AND f.contentHash = ?2 ORDER BY f.dateUploaded</query>
        </named-query>
```

and after the `blurhash` basic

```xml
            <basic name="contentHash">
                <column name="content_hash" insertable="true" updatable="true" unique="false" nullable="true"/>
            </basic>
```

(Check the file's mapping of the directory relationship is named `directory`; the `getByWeblogAndOrigpath` query shows `f.weblog` is. Use whatever name the `many-to-one` for `directoryid` carries.)

`MediaFileManager.java`:

```java
    /**
     * The oldest file in {@code directory} whose content hash is {@code
     * contentHash}, or null. A file without a hash -- from before V032, or
     * replaced or cropped since -- never matches.
     */
    MediaFile getMediaFileByContentHash(MediaFileDirectory directory, String contentHash)
            throws WebloggerException;
```

`JPAMediaFileManagerImpl.java`: in `persistNewMediaFile`, replace `cmgr.saveFileContent(weblog, mediaFile.getId(), is);` with

```java
        // The hash is taken from the bytes as they stream to disk, so an
        // upload of any size is hashed without being held in memory.
        MessageDigest sha256 = ContentHashes.sha256();
        cmgr.saveFileContent(weblog, mediaFile.getId(), new DigestInputStream(is, sha256));
        mediaFile.setContentHash(ContentHashes.hex(sha256.digest()));
        strategy.store(mediaFile);
```

In `updateMediaFile(Weblog, MediaFile, InputStream)`, make its first line

```java
        // New content is no longer the image an earlier import matched by hash.
        mediaFile.setContentHash(null);
```

In `cropMediaFile`, set `mediaFile.setContentHash(null);` beside the statement that stores the re-derived width and height, with the comment `// A cropped file is no longer the bytes it was imported as.`

Add:

```java
    @Override
    public MediaFile getMediaFileByContentHash(MediaFileDirectory directory, String contentHash)
            throws WebloggerException {
        if (directory == null || contentHash == null) {
            return null;
        }
        TypedQuery<MediaFile> q = strategy.getNamedQuery(
                "MediaFile.getByDirectoryAndContentHash", MediaFile.class);
        q.setParameter(1, directory);
        q.setParameter(2, contentHash);
        q.setMaxResults(1);
        List<MediaFile> found = q.getResultList();
        return found.isEmpty() ? null : found.get(0);
    }
```

(imports `java.security.DigestInputStream`, `java.security.MessageDigest`, `org.apache.roller.weblogger.business.ContentHashes`.) If any other class implements `MediaFileManager`, the compiler names it; add the method there too.

- [ ] **Step 5: Run them and watch them pass**

Run: `mvn -o -q -pl app test -Dtest='MediaContentHashMigrationTest,MediaFileContentHashTest,MediaFileTest,MediaFileCropTest,SchemaMigrationTest,EqualsContractTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 6: Full verify and commit**

Run: `mvn -o -pl app verify` — expected BUILD SUCCESS.

```bash
git add bin/db/migrations/V032__media_content_hash.sql \
        app/src/main/java/org/apache/roller/weblogger/business/{ContentHashes,MediaFileManager}.java \
        app/src/main/java/org/apache/roller/weblogger/business/jpa/JPAMediaFileManagerImpl.java \
        app/src/main/java/org/apache/roller/weblogger/pojos/MediaFile.java \
        app/src/main/resources/org/apache/roller/weblogger/pojos/MediaFile.orm.xml \
        app/src/test/java/org/apache/roller/weblogger/business/{MediaFileContentHashTest,startup/MediaContentHashMigrationTest}.java
git commit -m "feat(media): V032 content hash -- new files record SHA-256, replaced or cropped ones clear it"
```

---

### Task 6: Duplicate-free import, shared by the media and entry APIs

**Files:**
- Create: `app/src/main/java/org/apache/roller/weblogger/ui/restapi/MediaImports.java`
- Modify: `app/src/main/java/org/apache/roller/weblogger/ui/restapi/v1/MediaApi.java` (`importImage` uses it; `hostOf` and `IMPORT_LOG` move)
- Test: `app/src/test/java/org/apache/roller/weblogger/ui/restapi/v1/MediaApiTest.java` (add), `app/src/test/java/org/apache/roller/weblogger/ui/restapi/MediaImportsTest.java` (create, DB-backed)

**Interfaces:**
- Consumes: Task 5 `ContentHashes.sha256Hex`, `MediaFileManager.getMediaFileByContentHash`.
- Produces:
  - `MediaImports.Imported(MediaFile file, boolean created)`.
  - `MediaImports.ImportRefused extends Exception` with `String outcome()`; its message is safe to show the caller.
  - `static MediaImports.Imported importImage(Weblogger, RemoteImageFetcher, Weblog, MediaFileDirectory, String url, String name, String altText) throws ImportRefused, WebloggerException`. It flushes on success.
  - `static void log(Weblog, String url, String outcome, long bytes, long startedNanos)`.

- [ ] **Step 1: Write the failing tests**

Add to `MediaApiTest` in the imports section:

```java
    @Test
    void importingBytesAlreadyInTheDirectoryAnswers200WithThatFile() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        RemoteImageFetcher fetcher = mock(RemoteImageFetcher.class);
        when(fetcher.fetch(SIGNED_URL, 2097152L, null))
                .thenReturn(new FetchedImage("sunset.jpg", ImageKind.JPEG, JPEG_BYTES));
        MediaApi controller = importController(weblogger, weblog, fetcher);
        MediaFileManager mfm = weblogger.getMediaFileManager();
        MediaFile existing = new MediaFile();
        existing.setName("sunset.jpg");
        existing.setAltText("");
        existing.setDirectory(mfm.getDefaultMediaFileDirectory(weblog));
        existing.setContentType("image/jpeg");
        when(mfm.getMediaFileByContentHash(mfm.getDefaultMediaFileDirectory(weblog),
                ContentHashes.sha256Hex(JPEG_BYTES))).thenReturn(existing);

        try (MockedStatic<WebloggerRuntimeConfig> _ = runtimeConfig(true)) {
            String body = mockMvc(controller)
                    .perform(importRequest(weblog, "{\"url\":\"" + SIGNED_URL + "\",\"altText\":\"Dusk\"}"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            tools.jackson.databind.JsonNode json = new tools.jackson.databind.ObjectMapper().readTree(body);
            assertEquals(existing.getId(), json.get("id").asString());
            assertEquals("", json.get("altText").asString(), "a deliberate \"\" is never overwritten");
        }
        verify(mfm, never()).createMediaFile(any(), any(), any());
    }

    @Test
    void aMatchedFileWithoutAltTextTakesTheImportsAltText() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        RemoteImageFetcher fetcher = mock(RemoteImageFetcher.class);
        when(fetcher.fetch(SIGNED_URL, 2097152L, null))
                .thenReturn(new FetchedImage("sunset.jpg", ImageKind.JPEG, JPEG_BYTES));
        MediaApi controller = importController(weblogger, weblog, fetcher);
        MediaFileManager mfm = weblogger.getMediaFileManager();
        MediaFile existing = new MediaFile();
        existing.setName("sunset.jpg");
        existing.setDirectory(mfm.getDefaultMediaFileDirectory(weblog));
        existing.setContentType("image/jpeg");
        when(mfm.getMediaFileByContentHash(any(), any())).thenReturn(existing);

        try (MockedStatic<WebloggerRuntimeConfig> _ = runtimeConfig(true)) {
            mockMvc(controller)
                    .perform(importRequest(weblog, "{\"url\":\"" + SIGNED_URL + "\",\"altText\":\"Dusk\"}"))
                    .andExpect(status().isOk());
        }
        assertEquals("Dusk", existing.getAltText());
        verify(mfm).updateMediaFile(weblog, existing);
    }

    @Test
    void aRefusedFetchNamesTheUrlField() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        RemoteImageFetcher fetcher = mock(RemoteImageFetcher.class);
        when(fetcher.fetch(any(), org.mockito.ArgumentMatchers.anyLong(), any()))
                .thenThrow(new FetchRefusal("That address is not on the public internet."));
        MediaApi controller = importController(weblogger, weblog, fetcher);

        try (MockedStatic<WebloggerRuntimeConfig> _ = runtimeConfig(true)) {
            String body = mockMvc(controller)
                    .perform(importRequest(weblog, "{\"url\":\"http://10.0.0.1/x.png\"}"))
                    .andExpect(status().isBadRequest())
                    .andReturn().getResponse().getContentAsString();
            assertEquals("url", new tools.jackson.databind.ObjectMapper().readTree(body)
                    .get("errors").get(0).get("field").asString());
        }
    }
```

Create `MediaImportsTest.java` (DB-backed, fixture as `MediaFileCropTest`: user `mediaImportsTestUser`, weblog `mediaImportsTestWeblog`, uploads enabled; `generatedJpeg` copied):

```java
/** Importing identical bytes twice keeps one file (criterion 13), against the real database. */
public class MediaImportsTest {

    // fields, setUp(), tearDown(), generatedJpeg(...) as in MediaFileCropTest

    @Test
    void theSameBytesImportedTwiceAre201Then200AndOneFile() throws Exception {
        byte[] bytes = generatedJpeg(40, 30);
        RemoteImageFetcher fetcher = mock(RemoteImageFetcher.class);
        when(fetcher.fetch(eq("https://img.example.com/a.jpg"), anyLong(), any()))
                .thenReturn(new FetchedImage("a.jpg", ImageKind.JPEG, bytes));
        Weblogger weblogger = TestUtils.weblogger();

        MediaImports.Imported first = MediaImports.importImage(weblogger, fetcher,
                TestUtils.getManagedWebsite(weblog), mfMgr.getMediaFileDirectory(rootDirectory.getId()),
                "https://img.example.com/a.jpg", null, null);
        TestUtils.endSession(true);
        MediaImports.Imported second = MediaImports.importImage(weblogger, fetcher,
                TestUtils.getManagedWebsite(weblog), mfMgr.getMediaFileDirectory(rootDirectory.getId()),
                "https://img.example.com/a.jpg", null, "Later alt");
        TestUtils.endSession(true);

        assertTrue(first.created());
        assertFalse(second.created());
        assertEquals(first.file().getId(), second.file().getId());
        assertEquals(1, mfMgr.getMediaFileDirectory(rootDirectory.getId()).getMediaFiles().size());
        assertEquals("Later alt", mfMgr.getMediaFile(first.file().getId()).getAltText());
    }
}
```

- [ ] **Step 2: Run them and watch them fail**

Run: `mvn -o -q -pl app test -Dtest='MediaApiTest,MediaImportsTest'`
Expected: compilation fails on `MediaImports`; with a stub, the 200 test sees 201 and a second file.

- [ ] **Step 3: Implement `MediaImports`**

```java
package org.apache.roller.weblogger.ui.restapi;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import org.apache.commons.lang3.StringUtils;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.ContentHashes;
import org.apache.roller.weblogger.business.MediaFileManager;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.business.fetch.FetchRefusal;
import org.apache.roller.weblogger.business.fetch.FetchedImage;
import org.apache.roller.weblogger.business.fetch.RemoteImageFetcher;
import org.apache.roller.weblogger.pojos.MediaFile;
import org.apache.roller.weblogger.pojos.MediaFileDirectory;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.ui.controllers.MediaUploads;
import org.apache.roller.weblogger.util.RollerMessages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One image import from a public URL, shared by {@code POST .../media/imports}
 * and the entry writes that import ({@code featuredImageUrl},
 * {@code importExternalImages}), so the three cannot drift on what an import
 * is. The fetched bytes are stored through {@link MediaUploads#store}, the path
 * a browser upload takes, so every upload rule applies. Bytes already in the
 * target directory (matched by content hash) return that file instead of a
 * copy, which makes a retried or re-run publish cost a download, not a
 * duplicate.
 *
 * <p>Each import commits ({@code weblogger.flush()}) before it returns:
 * creating a media file writes its bytes to disk at once, so an import that
 * did not commit would leave a file on disk with no row. Callers must not
 * have uncommitted changes they might still refuse.
 */
public final class MediaImports {

    private static final Logger IMPORT_LOG = LoggerFactory.getLogger("roller.media.import");

    /** The file an import produced or matched; {@code created} is false for a match. */
    public record Imported(MediaFile file, boolean created) { }

    /** Why an import produced no file. The message is safe to show the caller. */
    public static final class ImportRefused extends Exception {
        private static final long serialVersionUID = 1L;
        private final String outcome;

        ImportRefused(String outcome, String detail, Throwable cause) {
            super(detail, cause);
            this.outcome = outcome;
        }

        /** The {@code outcome=} word the import log line carried. */
        public String outcome() {
            return outcome;
        }
    }

    private MediaImports() {
    }

    /**
     * Fetches {@code url} into {@code directory}. A file already there with
     * the same bytes is returned unchanged, except that a null alt text takes
     * {@code altText} ({@code ""} is a deliberate "decorative" and is kept).
     * One INFO line on {@code roller.media.import} either way.
     */
    public static Imported importImage(Weblogger weblogger, RemoteImageFetcher fetcher, Weblog weblog,
                                       MediaFileDirectory directory, String url, String name, String altText)
            throws ImportRefused, WebloggerException {
        long started = System.nanoTime();
        String outcome = "error";
        long bytes = 0;
        try {
            FetchedImage image = fetcher.fetch(url, MediaUploads.maxFileBytes(), name);
            bytes = image.size();
            MediaFileManager mfm = weblogger.getMediaFileManager();
            MediaFile existing = mfm.getMediaFileByContentHash(directory, ContentHashes.sha256Hex(image.bytes()));
            if (existing != null) {
                if (altText != null && existing.getAltText() == null) {
                    existing.setAltText(altText);
                    mfm.updateMediaFile(weblog, existing);
                }
                weblogger.flush();
                outcome = "matched";
                return new Imported(existing, false);
            }
            RollerMessages messages = new RollerMessages();
            MediaUploads.Result stored = MediaUploads.store(
                    MediaUploads.Source.of(image.fileName(), image.kind().contentType(), image.bytes()),
                    weblog, directory, mfm, messages);
            if (stored.file() == null) {
                outcome = stored.status();
                throw new ImportRefused(stored.status(), stored.detail(), null);
            }
            MediaFile file = stored.file();
            if (altText != null) {
                file.setAltText(altText);
                mfm.updateMediaFile(weblog, file);
            }
            weblogger.flush();
            outcome = "created";
            return new Imported(file, true);
        } catch (FetchRefusal e) {
            outcome = "refused";
            throw new ImportRefused(outcome, e.getMessage(), e);
        } finally {
            log(weblog, url, outcome, bytes, started);
        }
    }

    /**
     * The import log line: weblog, host, outcome, bytes, ms. Never the path
     * or query, where a signed URL keeps its credentials.
     */
    public static void log(Weblog weblog, String url, String outcome, long bytes, long startedNanos) {
        IMPORT_LOG.info("weblog={} host={} outcome={} bytes={} ms={}", weblog.getHandle(), hostOf(url),
                outcome, bytes, (System.nanoTime() - startedNanos) / 1_000_000);
    }

    /** The URL's host only, lower-cased; "?" when it has none. URI parsing keeps it to hostname characters. */
    static String hostOf(String url) {
        if (StringUtils.isBlank(url)) {
            return "?";
        }
        try {
            String host = new URI(url.trim()).getHost();
            return host == null ? "?" : host.toLowerCase(Locale.ROOT);
        } catch (URISyntaxException e) {
            return "?";
        }
    }
}
```

(Verify `MediaUploads.Source` and `MediaUploads.Result` are visible from `ui.restapi`; `MediaApi` in `ui.restapi.v1` already uses both, so they are public.)

- [ ] **Step 4: Point `MediaApi.importImage` at it**

Replace `importImage` with:

```java
    /**
     * Import an image from a public URL through {@link MediaImports}: 201
     * with a Location for a new file, 200 with the file already in the
     * directory when the bytes match one. The checks before any network
     * request are this endpoint's own and log their refusal on the same
     * import logger.
     */
    @PostMapping("/imports")
    public ResponseEntity<MediaDtos.MediaView> importImage(HttpServletRequest request,
            @RequestBody MediaDtos.MediaImport body) throws WebloggerException {
        Weblog weblog = requireActionWeblog(request);
        long started = System.nanoTime();
        MediaFileDirectory directory;
        try {
            directory = importDirectory(request, weblog, body, weblogger.getMediaFileManager());
        } catch (ApiException e) {
            MediaImports.log(weblog, body.url(), "refused", 0, started);
            throw e;
        }
        MediaImports.Imported imported;
        try {
            imported = MediaImports.importImage(weblogger, imageFetcher, weblog, directory,
                    body.url(), body.name(), body.altText());
        } catch (MediaImports.ImportRefused e) {
            throw ApiException.invalidField("url", e.getMessage(), e);
        }
        MediaFile file = imported.file();
        MediaDtos.MediaView view = MediaDtos.toView(file, url(weblog, file));
        if (!imported.created()) {
            return ResponseEntity.ok(view);
        }
        URI location = ServletUriComponentsBuilder.fromContextPath(request)
                .path("/api/v1/weblogs/{handle}/media/{id}")
                .buildAndExpand(weblog.getHandle(), file.getId()).toUri();
        return ResponseEntity.created(location).body(view);
    }
```

In `importDirectory`, make the blank-url refusal `ApiException.invalidField("url", "url is required.")`. Delete `MediaApi.hostOf` and `IMPORT_LOG` (now in `MediaImports`), and the imports they alone used.

- [ ] **Step 5: Run them and watch them pass**

Run: `mvn -o -q -pl app test -Dtest='MediaApi*Test,MediaImportsTest,MediaUploadTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS, including the existing import-log tests (the logger name is unchanged).

- [ ] **Step 6: Full verify and commit**

Run: `mvn -o -pl app verify` — expected BUILD SUCCESS.

```bash
git add app/src/main/java/org/apache/roller/weblogger/ui/restapi/MediaImports.java \
        app/src/main/java/org/apache/roller/weblogger/ui/restapi/v1/MediaApi.java \
        app/src/test/java/org/apache/roller/weblogger/ui/restapi/{MediaImportsTest,v1/MediaApiTest}.java
git commit -m "feat(api): media import returns the existing file for bytes it already holds"
```

---

### Task 7: Find and rewrite external images in entry text

**Files:**
- Create: `app/src/main/java/org/apache/roller/weblogger/business/ExternalImages.java`
- Modify: `app/src/main/java/org/apache/roller/weblogger/business/MarkdownRenderer.java` (`EXTENSIONS` package-private)
- Test: `app/src/test/java/org/apache/roller/weblogger/business/ExternalImagesTest.java` (create)

**Interfaces:**
- Produces:
  - `ExternalImages.Kind { HTML_SRC, MARKDOWN }`.
  - `ExternalImages.Ref(Kind kind, int start, int end, String url, String alt, String title)`.
  - `ExternalImages.Scan(List<Ref> refs, List<String> refusals)` with `List<String> distinctUrls()`.
  - `static Scan scan(String text, Set<String> ownHosts)` — `ownHosts` are lower-case host names.
  - `static String rewrite(String text, List<Ref> refs, Map<String, String> localUrls)`.

- [ ] **Step 1: Write the failing tests**

```java
package org.apache.roller.weblogger.business;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Criteria 15 and 16, and Review Focus 2-4. */
class ExternalImagesTest {

    private static final Set<String> OWN = Set.of("blog.example.com");

    private static List<String> urls(String text) {
        return ExternalImages.scan(text, OWN).refs().stream().map(ExternalImages.Ref::url).toList();
    }

    @Test
    void anHtmlImageIsFoundWhicheverQuotesItsSrcUses() {
        assertEquals(List.of("https://a.example/1.jpg", "https://a.example/2.jpg", "https://a.example/3.jpg"),
                urls("<p><img src=\"https://a.example/1.jpg\"> <img src='https://a.example/2.jpg'> "
                        + "<img alt=x src=https://a.example/3.jpg></p>"));
    }

    @Test
    void inlineAndReferenceMarkdownImagesAreFound() {
        assertEquals(List.of("https://a.example/inline.jpg", "https://a.example/ref.jpg"),
                urls("![one](https://a.example/inline.jpg)\n\n![two][r]\n\n[r]: https://a.example/ref.jpg\n"));
    }

    @Test
    void codeIsLeftAlone() {
        assertEquals(List.of(), urls("```\n![x](https://a.example/fenced.jpg)\n```\n\n"
                + "    <img src=\"https://a.example/indented.jpg\">\n\n"
                + "`<img src=\"https://a.example/inline-code.jpg\">`\n"));
    }

    @Test
    void relativeProtocolRelativeDataAndOwnHostUrlsAreLeftAlone() {
        assertEquals(List.of(), urls("<img src=\"/myblog/mediaresource/abc\"> <img src=\"//cdn.example/x.jpg\"> "
                + "<img src=\"data:image/png;base64,AAAA\"> <img src=\"https://BLOG.example.com/myblog/x.jpg\"> "
                + "![a](images/local.png)"));
    }

    /** Review Focus 4. */
    @Test
    void shortcodesCarryingUrlsAreNotImages() {
        assertEquals(List.of(), urls("[video url=\"https://youtu.be/dQw4w9WgXcQ\" caption=\"x\"]\n\n"
                + "[cta href=\"https://example.com/book.jpg\" label=\"Book\"]\n\n[image id=\"abc\"]"));
    }

    @Test
    void aSrcIsDecodedForTheFetchButItsRawRangeIsWhatIsReplaced() {
        String text = "<img src=\"https://a.example/x.jpg?a=1&amp;b=2\" alt=\"A &amp; B\">";
        ExternalImages.Ref ref = ExternalImages.scan(text, OWN).refs().get(0);

        assertEquals("https://a.example/x.jpg?a=1&b=2", ref.url());
        assertEquals("A & B", ref.alt());
        assertEquals("https://a.example/x.jpg?a=1&amp;b=2", text.substring(ref.start(), ref.end()));
    }

    @Test
    void anExternalSrcsetIsRefusedAndAnOwnOneIsNot() {
        ExternalImages.Scan scan = ExternalImages.scan(
                "<img src=\"https://a.example/x.jpg\" srcset=\"https://a.example/x2.jpg 2x\">"
                + "<picture><source srcset=\"https://b.example/y.webp\"><img src=\"/y.jpg\"></picture>"
                + "<img src=\"/z.jpg\" srcset=\"/z2.jpg 2x, https://blog.example.com/z3.jpg 3x\">", OWN);

        assertEquals(2, scan.refusals().size());
    }

    @Test
    void theRewriteChangesOnlyTheSrcValue() {
        String text = "Before <img class=\"wide\" src=\"https://a.example/x.jpg\" alt=\"X\"> after.";
        ExternalImages.Scan scan = ExternalImages.scan(text, OWN);

        String rewritten = ExternalImages.rewrite(text, scan.refs(),
                Map.of("https://a.example/x.jpg", "https://blog.example.com/myblog/mediaresource/m1"));

        assertEquals("Before <img class=\"wide\" src=\"https://blog.example.com/myblog/mediaresource/m1\" "
                + "alt=\"X\"> after.", rewritten);
    }

    @Test
    void aMarkdownImageBecomesInlineKeepingItsAltAndTitle() {
        String text = "Look: ![The [old] pier](https://a.example/p.jpg \"At \\\"dusk\\\"\") and ![r][ref]\n\n"
                + "[ref]: https://a.example/r.jpg\n";
        ExternalImages.Scan scan = ExternalImages.scan(text, OWN);

        String rewritten = ExternalImages.rewrite(text, scan.refs(), Map.of(
                "https://a.example/p.jpg", "https://blog.example.com/m/p",
                "https://a.example/r.jpg", "https://blog.example.com/m/r"));

        assertEquals("Look: ![The \\[old\\] pier](https://blog.example.com/m/p \"At \\\"dusk\\\"\") and "
                + "![r](https://blog.example.com/m/r)\n\n[ref]: https://a.example/r.jpg\n", rewritten);
    }

    /** Review Focus 2. */
    @Test
    void crlfTextIsRewrittenAtTheRightOffsets() {
        String text = "Intro\r\n\r\n![a](https://a.example/a.jpg)\r\nmore <img src=\"https://a.example/b.png\"> end\r\n";
        ExternalImages.Scan scan = ExternalImages.scan(text, OWN);

        String rewritten = ExternalImages.rewrite(text, scan.refs(), Map.of(
                "https://a.example/a.jpg", "L1", "https://a.example/b.png", "L2"));

        assertEquals("Intro\r\n\r\n![a](L1)\r\nmore <img src=\"L2\"> end\r\n", rewritten);
    }

    @Test
    void anHtmlBlockSpanningLinesIsScanned() {
        assertEquals(List.of("https://a.example/c.gif"),
                urls("<div>\r\n<img\r\n src='https://a.example/c.gif'>\r\n</div>\r\n"));
    }

    @Test
    void distinctUrlsKeepTheOrderOfFirstAppearance() {
        ExternalImages.Scan scan = ExternalImages.scan("![a](https://a.example/2.jpg) "
                + "<img src=\"https://a.example/1.jpg\"> ![b](https://a.example/2.jpg)", OWN);

        assertEquals(List.of("https://a.example/2.jpg", "https://a.example/1.jpg"), scan.distinctUrls());
        assertEquals(3, scan.refs().size());
    }

    @Test
    void aMalformedHttpUrlCountsAsExternalSoTheFetchRefusesItOutLoud() {
        assertEquals(List.of("https://a.example/bad path.jpg"),
                urls("<img src=\"https://a.example/bad path.jpg\">"));
    }

    @Test
    void aRefWithNoLocalCopyIsLeftAsItWas() {
        String text = "<img src=\"https://a.example/x.jpg\">";
        assertEquals(text, ExternalImages.rewrite(text, ExternalImages.scan(text, OWN).refs(), Map.of()));
    }

    @Test
    void emptyTextHasNoImages() {
        assertTrue(ExternalImages.scan("", OWN).refs().isEmpty());
        assertTrue(ExternalImages.scan(null, OWN).refs().isEmpty());
    }
}
```

- [ ] **Step 2: Run them and watch them fail**

Run: `mvn -o -q -pl app test -Dtest=ExternalImagesTest`
Expected: compilation fails (`ExternalImages` does not exist).

- [ ] **Step 3: Implement**

In `MarkdownRenderer.java`, make the extension list visible to the package so the scanner parses exactly as the renderer does:

```java
    /** Package-visible so {@link ExternalImages} parses entry text exactly as it renders. */
    static final List<Extension> EXTENSIONS = List.of(
```

Create `ExternalImages.java`:

```java
package org.apache.roller.weblogger.business;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.text.StringEscapeUtils;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Code;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.Image;
import org.commonmark.node.Node;
import org.commonmark.node.SourceSpan;
import org.commonmark.node.Text;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;

/**
 * Finds the images an entry's text loads from other sites, and rewrites the
 * text to load local copies instead. The text is parsed with the commonmark
 * parser and extensions {@link MarkdownRenderer} uses, with source spans on,
 * so an image is found exactly where the renderer would draw one: code
 * blocks and inline code are never touched, and Markdown reference images
 * resolve through their definitions. HTML {@code <img>} tags are found inside
 * the parser's HTML nodes; a {@code srcset} naming another site is refused
 * rather than half-imported.
 *
 * <p>Shortcodes are not images: {@code [video url=...]} and the like are
 * plain text to the parser, and expand only at render time.
 */
public final class ExternalImages {

    /** How a reference is written, which decides how it is rewritten. */
    public enum Kind { HTML_SRC, MARKDOWN }

    /**
     * One external image. {@code start}/{@code end} delimit what a rewrite
     * replaces: the raw {@code src} value of an HTML image, or the whole
     * Markdown image. {@code url} is decoded (HTML entities, Markdown
     * escapes), ready to fetch.
     */
    public record Ref(Kind kind, int start, int end, String url, String alt, String title) { }

    /** Every external image in text order, and the tags refused outright. */
    public record Scan(List<Ref> refs, List<String> refusals) {
        /** Each URL once, in order of first appearance. */
        public List<String> distinctUrls() {
            return refs.stream().map(Ref::url).distinct().toList();
        }
    }

    private static final Parser PARSER = Parser.builder()
            .extensions(MarkdownRenderer.EXTENSIONS)
            .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES)
            .build();

    // An attribute value containing '>' ends the tag early; such a tag's
    // later attributes are not seen. Accepted: alt text is the only
    // attribute that plausibly carries one, and src/srcset precede it in
    // every generator this blog sees.
    private static final Pattern TAG = Pattern.compile("<(img|source)\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    // The leading \s keeps data-src and similar from matching as src.
    private static final Pattern ATTRIBUTE = Pattern.compile(
            "\\s(src|srcset|alt)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'=<>`]+))",
            Pattern.CASE_INSENSITIVE);
    private static final int ABBREVIATE_AT = 120;

    private ExternalImages() {
    }

    /** The external images of {@code text}; {@code ownHosts} (lower case) are this site's. */
    public static Scan scan(String text, Set<String> ownHosts) {
        if (text == null || text.isEmpty()) {
            return new Scan(List.of(), List.of());
        }
        List<Ref> refs = new ArrayList<>();
        List<String> refusals = new ArrayList<>();
        PARSER.parse(text).accept(new AbstractVisitor() {
            @Override
            public void visit(Image image) {
                int[] range = range(image.getSourceSpans());
                if (range.length == 2 && isExternal(image.getDestination(), ownHosts)) {
                    refs.add(new Ref(Kind.MARKDOWN, range[0], range[1], image.getDestination(),
                            altOf(image), image.getTitle()));
                }
                // no visitChildren: an image's children are its alt text
            }

            @Override
            public void visit(HtmlInline html) {
                scanHtml(text, range(html.getSourceSpans()), ownHosts, refs, refusals);
            }

            @Override
            public void visit(HtmlBlock html) {
                scanHtml(text, range(html.getSourceSpans()), ownHosts, refs, refusals);
            }
        });
        refs.sort(Comparator.comparingInt(Ref::start));
        return new Scan(List.copyOf(refs), List.copyOf(refusals));
    }

    /**
     * {@code text} with each ref pointing at {@code localUrls.get(ref.url())}.
     * An HTML ref has only its {@code src} value replaced, so the rest of the
     * tag and the text around it are byte-for-byte unchanged; a Markdown ref
     * becomes an inline image, which also takes a reference image off its
     * definition without touching the definition (a link may still use it).
     * A ref with no local copy is left as it was.
     */
    public static String rewrite(String text, List<Ref> refs, Map<String, String> localUrls) {
        StringBuilder out = new StringBuilder(text);
        List<Ref> backwards = new ArrayList<>(refs);
        backwards.sort(Comparator.comparingInt(Ref::start).reversed());
        for (Ref ref : backwards) {
            String local = localUrls.get(ref.url());
            if (local != null) {
                out.replace(ref.start(), ref.end(), ref.kind() == Kind.HTML_SRC
                        ? StringEscapeUtils.escapeHtml4(local)
                        : markdownImage(ref.alt(), local, ref.title()));
            }
        }
        return out.toString();
    }

    /**
     * An absolute http(s) URL on another host. One that starts like one but
     * does not parse counts as external too, so the fetch refuses it out
     * loud instead of it being silently left behind as a hotlink.
     */
    static boolean isExternal(String url, Set<String> ownHosts) {
        if (url == null) {
            return false;
        }
        String trimmed = url.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return false;
        }
        try {
            String host = new URI(trimmed).getHost();
            return host == null || !ownHosts.contains(host.toLowerCase(Locale.ROOT));
        } catch (URISyntaxException e) {
            return true;
        }
    }

    /** {start, end} of a node in the input, or an empty array when the parser gave it no span. */
    private static int[] range(List<SourceSpan> spans) {
        if (spans == null || spans.isEmpty()) {
            return new int[0];
        }
        SourceSpan last = spans.get(spans.size() - 1);
        return new int[] {spans.get(0).getInputIndex(), last.getInputIndex() + last.getLength()};
    }

    private static void scanHtml(String text, int[] range, Set<String> ownHosts,
                                 List<Ref> refs, List<String> refusals) {
        if (range.length != 2) {
            return;
        }
        Matcher tag = TAG.matcher(text).region(range[0], range[1]);
        while (tag.find()) {
            String src = null;
            int srcStart = -1;
            int srcEnd = -1;
            String alt = null;
            Matcher attribute = ATTRIBUTE.matcher(text).region(tag.start(), tag.end());
            while (attribute.find()) {
                int group = attribute.group(2) != null ? 2 : attribute.group(3) != null ? 3 : 4;
                String value = StringEscapeUtils.unescapeHtml4(attribute.group(group));
                switch (attribute.group(1).toLowerCase(Locale.ROOT)) {
                    case "src" -> {
                        src = value;
                        srcStart = attribute.start(group);
                        srcEnd = attribute.end(group);
                    }
                    case "srcset" -> {
                        if (srcsetIsExternal(value, ownHosts)) {
                            refusals.add("srcset is not imported; use one src, or [image id=...] for a "
                                    + "responsive image: " + abbreviate(tag.group()));
                        }
                    }
                    default -> alt = value;
                }
            }
            if ("img".equalsIgnoreCase(tag.group(1)) && isExternal(src, ownHosts)) {
                refs.add(new Ref(Kind.HTML_SRC, srcStart, srcEnd, src, alt, null));
            }
        }
    }

    private static boolean srcsetIsExternal(String srcset, Set<String> ownHosts) {
        for (String candidate : srcset.split(",")) {
            String trimmed = candidate.trim();
            if (!trimmed.isEmpty() && isExternal(trimmed.split("\\s+")[0], ownHosts)) {
                return true;
            }
        }
        return false;
    }

    private static String altOf(Node image) {
        StringBuilder alt = new StringBuilder();
        image.accept(new AbstractVisitor() {
            @Override
            public void visit(Text text) {
                alt.append(text.getLiteral());
            }

            @Override
            public void visit(Code code) {
                alt.append(code.getLiteral());
            }
        });
        return alt.isEmpty() ? null : alt.toString();
    }

    private static String markdownImage(String alt, String url, String title) {
        StringBuilder md = new StringBuilder("![")
                .append(alt == null ? "" : alt.replace("\\", "\\\\").replace("[", "\\[").replace("]", "\\]"))
                .append("](").append(url);
        if (title != null && !title.isEmpty()) {
            md.append(" \"").append(title.replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return md.append(')').toString();
    }

    /** {@code value} cut to a readable length for a message. */
    public static String abbreviate(String value) {
        return value.length() <= ABBREVIATE_AT ? value : value.substring(0, ABBREVIATE_AT) + "...";
    }
}
```

- [ ] **Step 4: Run them and watch them pass**

Run: `mvn -o -q -pl app test -Dtest='ExternalImagesTest,MarkdownRendererTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS. If `aMarkdownImageBecomesInlineKeepingItsAltAndTitle` fails only on how commonmark reports the title's escaped quotes, keep the test's intent — the title survives the round trip and renders the same — and adjust the expected string to what commonmark 0.30 returns from `getTitle()`, re-escaped.

- [ ] **Step 5: Full verify and commit**

Run: `mvn -o -pl app verify` — expected BUILD SUCCESS.

```bash
git add app/src/main/java/org/apache/roller/weblogger/business/{ExternalImages,MarkdownRenderer}.java \
        app/src/test/java/org/apache/roller/weblogger/business/ExternalImagesTest.java
git commit -m "feat(content): find and rewrite external images in entry text"
```

---

### Task 8: `featuredImageUrl`, `featuredImageAlt` and `importExternalImages` on entry writes

**Files:**
- Create: `app/src/main/java/org/apache/roller/weblogger/ui/restapi/v1/EntryImports.java`
- Modify: `app/src/main/java/org/apache/roller/weblogger/ui/restapi/dto/EntryDtos.java` (three components, `withImports`)
- Modify: `app/src/main/java/org/apache/roller/weblogger/ui/restapi/v1/EntriesWriteApi.java` (`imageFetcher`, `clock`, the import step in `create`/`update`)
- Modify: `app/src/test/java/org/apache/roller/weblogger/ui/restapi/v1/EntryWrites.java` (+3 setters)
- Test: `app/src/test/java/org/apache/roller/weblogger/ui/restapi/v1/EntriesWriteApiImportsTest.java` (create)

**Interfaces:**
- Consumes: Task 1 `FieldErrors`; Task 3 `requireAnchorFree`; Task 6 `MediaImports.importImage`/`ImportRefused`; Task 7 `ExternalImages.scan`/`rewrite`/`abbreviate`.
- Produces:
  - `EntryWrite` components (after `anchor`, before `clear`): `String featuredImageUrl`, `String featuredImageAlt`, `Boolean importExternalImages`.
  - `EntryWrite.withImports(String importedFeaturedImageId, String rewrittenText) → EntryWrite`.
  - `EntryImports(Weblogger, RemoteImageFetcher, Clock)`; `Plan plan(FieldErrors, Weblog, EntryWrite, Set<String> cleared, String storedText)`; `EntryWrite run(Plan, Weblog, EntryWrite) throws WebloggerException`; constants `MAX_BODY_IMAGES = 10`, `BUDGET = Duration.ofSeconds(60)`.

- [ ] **Step 1: Write the failing tests**

Create `EntriesWriteApiImportsTest.java`. Use the `EntriesWriteApiAnchorTest` fixture (copy `setUp`, `mvc()`, `fields`, `send`, `json`), and extend it:

```java
/** The import step of an entry write (spec 2026-10-13, sections 6 and 7; criteria 14, 17-20). */
class EntriesWriteApiImportsTest {

    // weblogger, entries, weblog, fields(...), send(...), json(...) as in EntriesWriteApiAnchorTest;
    // setUp() as there, with imports()'s body below appended to it (one @BeforeEach, not two:
    // JUnit does not promise the order of two in one class)

    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 1, 2, 3};
    private final MediaFileManager media = mock(MediaFileManager.class);
    private final RemoteImageFetcher fetcher = mock(RemoteImageFetcher.class);
    private final MutableClock clock = new MutableClock();
    private MockedStatic<WebloggerRuntimeConfig> runtime;
    private MediaFileDirectory directory;

    /** A clock a test moves by hand. */
    static final class MutableClock extends java.time.Clock {
        private java.time.Instant now = java.time.Instant.parse("2026-10-13T09:00:00Z");
        void advance(java.time.Duration by) { now = now.plus(by); }
        @Override public java.time.ZoneId getZone() { return java.time.ZoneOffset.UTC; }
        @Override public java.time.Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public java.time.Instant instant() { return now; }
    }

    /** Appended to the copied setUp() -- not a @BeforeEach of its own. */
    private void imports() throws Exception {
        when(weblogger.getMediaFileManager()).thenReturn(media);
        directory = new MediaFileDirectory(weblog, "default", null);
        when(media.getDefaultMediaFileDirectory(weblog)).thenReturn(directory);
        URLStrategy urls = weblogger.getUrlStrategy();
        when(urls.getWeblogURL(weblog, null, true)).thenReturn("https://blog.example.com/myblog/");
        when(urls.getMediaFileURL(eq(weblog), anyString(), eq(true)))
                .thenAnswer(inv -> "https://blog.example.com/myblog/mediaresource/" + inv.getArgument(1));
        // createMediaFile stays a no-op mock: the imported id becomes featuredImageId after the
        // checks ran, so nothing looks it up again
        runtime = mockStatic(WebloggerRuntimeConfig.class);
        runtime.when(() -> WebloggerRuntimeConfig.getBooleanProperty("uploads.enabled")).thenReturn(true);
        runtime.when(() -> WebloggerRuntimeConfig.getProperty("uploads.file.maxsize")).thenReturn("2.00");
        runtime.when(WebloggerRuntimeConfig::getAbsoluteContextURL).thenReturn("https://blog.example.com");
    }

    @AfterEach
    void closeRuntime() {
        runtime.close();
    }

    @Override
    MockMvc mvc() {   // replaces the copied mvc(): the controller also needs a fetcher and the clock
        EntriesWriteApi controller = new EntriesWriteApi();
        controller.weblogger = weblogger;
        controller.imageFetcher = fetcher;
        controller.clock = clock;
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    private void serves(String url) throws Exception {
        String name = url.substring(url.lastIndexOf('/') + 1);
        // distinct bytes per URL, so no two URLs match each other by hash
        byte[] bytes = java.util.Arrays.copyOf(JPEG, JPEG.length + 1);
        bytes[bytes.length - 1] = (byte) url.hashCode();
        when(fetcher.fetch(eq(url), anyLong(), any())).thenReturn(new FetchedImage(name, ImageKind.JPEG, bytes));
    }

    @Test
    void aFeaturedImageUrlIsImportedAndBecomesTheFeaturedImage() throws Exception {
        serves("https://img.example.com/pier.jpg");

        JsonNode view = json(send(post("/v1/weblogs/myblog/entries").content("{\"title\":\"t\",\"text\":\"x\","
                + "\"featuredImageUrl\":\"https://img.example.com/pier.jpg\",\"featuredImageAlt\":\"The pier\"}"))
                .andExpect(status().isCreated()));

        ArgumentCaptor<MediaFile> created = ArgumentCaptor.forClass(MediaFile.class);
        verify(media).createMediaFile(eq(weblog), created.capture(), any());
        assertEquals(created.getValue().getId(), view.get("featuredImageId").asString());
        assertEquals("The pier", created.getValue().getAltText());
    }

    @Test
    void featuredImageUrlWithFeaturedImageIdOrAltAloneIsA400WithNoFetch() throws Exception {
        json(send(post("/v1/weblogs/myblog/entries").content("{\"title\":\"t\",\"text\":\"x\","
                + "\"featuredImageUrl\":\"https://img.example.com/a.jpg\",\"featuredImageId\":\"m1\"}"))
                .andExpect(status().isBadRequest()));
        JsonNode altAlone = json(send(post("/v1/weblogs/myblog/entries").content(
                "{\"title\":\"t\",\"text\":\"x\",\"featuredImageAlt\":\"x\"}")).andExpect(status().isBadRequest()));

        assertEquals(List.of("featuredImageAlt"), fields(altAlone));
        verify(fetcher, never()).fetch(any(), anyLong(), any());
    }

    @Test
    void bodyImagesAreImportedAndTheTextPointsAtTheCopies() throws Exception {
        serves("https://img.example.com/a.jpg");
        serves("https://img.example.com/b.jpg");

        JsonNode view = json(send(post("/v1/weblogs/myblog/entries").content("{\"title\":\"t\","
                + "\"text\":\"<img src=\\\"https://img.example.com/a.jpg\\\" alt=\\\"A\\\"> ![B](https://img.example.com/b.jpg)\","
                + "\"importExternalImages\":true}")).andExpect(status().isCreated()));

        String text = view.get("text").asString();
        assertFalse(text.contains("img.example.com"), text);
        assertTrue(text.contains("<img src=\"https://blog.example.com/myblog/mediaresource/"), text);
        assertTrue(text.contains("![B](https://blog.example.com/myblog/mediaresource/"), text);
    }

    /** Review Focus 5. */
    @Test
    void aUrlUsedTwiceAndAsTheFeaturedImageIsFetchedOnce() throws Exception {
        serves("https://img.example.com/a.jpg");

        send(post("/v1/weblogs/myblog/entries").content("{\"title\":\"t\","
                + "\"text\":\"<img src=\\\"https://img.example.com/a.jpg\\\"> ![x](https://img.example.com/a.jpg)\","
                + "\"featuredImageUrl\":\"https://img.example.com/a.jpg\",\"importExternalImages\":true}"))
                .andExpect(status().isCreated());

        verify(fetcher, times(1)).fetch(eq("https://img.example.com/a.jpg"), anyLong(), any());
    }

    @Test
    void elevenImagesOrAnExternalSrcsetAreRefusedBeforeAnyFetch() throws Exception {
        StringBuilder eleven = new StringBuilder();
        for (int i = 0; i < 11; i++) {
            eleven.append("![x](https://img.example.com/").append(i).append(".jpg) ");
        }
        JsonNode tooMany = json(send(post("/v1/weblogs/myblog/entries").content("{\"title\":\"t\",\"text\":\""
                + eleven + "\",\"importExternalImages\":true}")).andExpect(status().isBadRequest()));
        JsonNode srcset = json(send(post("/v1/weblogs/myblog/entries").content("{\"title\":\"t\",\"text\":"
                + "\"<img src=\\\"https://a.example/x.jpg\\\" srcset=\\\"https://a.example/x2.jpg 2x\\\">\","
                + "\"importExternalImages\":true}")).andExpect(status().isBadRequest()));

        assertEquals(List.of("text"), fields(tooMany));
        assertEquals(List.of("text"), fields(srcset));
        verify(fetcher, never()).fetch(any(), anyLong(), any());
    }

    @Test
    void withUploadsOffTheImportIsRefusedOnItsField() throws Exception {
        runtime.when(() -> WebloggerRuntimeConfig.getBooleanProperty("uploads.enabled")).thenReturn(false);

        JsonNode problem = json(send(post("/v1/weblogs/myblog/entries").content(
                "{\"title\":\"t\",\"text\":\"x\",\"importExternalImages\":true}")).andExpect(status().isBadRequest()));

        assertEquals(List.of("importExternalImages"), fields(problem));
    }

    private WeblogEntry stored(String text) throws Exception {
        WeblogEntry stored = new WeblogEntry();
        stored.setId("e1");
        stored.setWebsite(weblog);
        stored.setTitle("Harbour");
        stored.setText(text);
        when(entries.getWeblogEntry("e1")).thenReturn(stored);
        return stored;
    }

    @Test
    void oneFailingImageRefusesTheWriteNamingItAndKeepsTheOthersImported() throws Exception {
        String text = "![a](https://img.example.com/a.jpg) ![b](https://img.example.com/b.jpg) "
                + "![c](https://img.example.com/c.jpg)";
        WeblogEntry entry = stored(text);
        serves("https://img.example.com/a.jpg");
        when(fetcher.fetch(eq("https://img.example.com/b.jpg"), anyLong(), any()))
                .thenThrow(new FetchRefusal("Not a JPEG, PNG, GIF or WebP image."));
        serves("https://img.example.com/c.jpg");

        JsonNode problem = json(send(patch("/v1/weblogs/myblog/entries/e1").content(
                "{\"summary\":\"new\",\"importExternalImages\":true}")).andExpect(status().isBadRequest()));

        assertEquals(List.of("text"), fields(problem));
        assertTrue(problem.get("errors").get(0).get("message").asString().contains("b.jpg"));
        assertEquals(text, entry.getText());
        assertNull(entry.getSummary());
        verify(media, times(2)).createMediaFile(any(), any(), any());
        verify(entries, never()).saveWeblogEntry(any());
    }

    @Test
    void whenTheBudgetRunsOutTheRestAreReportedNotFetched() throws Exception {
        stored("![a](https://img.example.com/a.jpg) ![b](https://img.example.com/b.jpg)");
        byte[] bytes = JPEG.clone();
        when(fetcher.fetch(eq("https://img.example.com/a.jpg"), anyLong(), any())).thenAnswer(inv -> {
            clock.advance(java.time.Duration.ofSeconds(61));
            return new FetchedImage("a.jpg", ImageKind.JPEG, bytes);
        });

        JsonNode problem = json(send(patch("/v1/weblogs/myblog/entries/e1").content(
                "{\"importExternalImages\":true}")).andExpect(status().isBadRequest()));

        assertTrue(problem.get("errors").get(0).get("message").asString().contains("budget"));
        verify(fetcher, never()).fetch(eq("https://img.example.com/b.jpg"), anyLong(), any());
    }

    @Test
    void aPatchWithoutTextImportsFromTheStoredText() throws Exception {
        WeblogEntry entry = stored("Intro ![a](https://img.example.com/a.jpg)");
        serves("https://img.example.com/a.jpg");

        send(patch("/v1/weblogs/myblog/entries/e1").content("{\"importExternalImages\":true}"))
                .andExpect(status().isOk());

        assertTrue(entry.getText().startsWith("Intro ![a](https://blog.example.com/myblog/mediaresource/"),
                entry.getText());
        verify(entries).saveWeblogEntry(entry);
    }

    @Test
    void aPrivateDefaultDirectoryRefusesTheImport() throws Exception {
        directory.setPrivate(true);
        serves("https://img.example.com/a.jpg");

        JsonNode problem = json(send(post("/v1/weblogs/myblog/entries").content("{\"title\":\"t\",\"text\":\"x\","
                + "\"featuredImageUrl\":\"https://img.example.com/a.jpg\"}")).andExpect(status().isBadRequest()));

        assertEquals(List.of("featuredImageUrl"), fields(problem));
        verify(fetcher, never()).fetch(any(), anyLong(), any());
    }
}
```

The `@Override` on `mvc()` is a reading aid only — drop it (there is no superclass); write `mvc()` as shown instead of the copied one. Imports: `MediaFileManager`, `MediaFileDirectory`, `MediaFile`, `RemoteImageFetcher`, `FetchedImage`, `FetchRefusal`, `ImageKind`, `WebloggerRuntimeConfig`, `MockedStatic`, `ArgumentCaptor`, `AfterEach`, and the static Mockito/assert members used.

- [ ] **Step 2: Run them and watch them fail**

Run: `mvn -o -q -pl app test -Dtest=EntriesWriteApiImportsTest`
Expected: compilation fails (`imageFetcher`, `clock`, the new `EntryWrite` components).

- [ ] **Step 3: Add the components and `withImports`**

In `EntryDtos.EntryWrite`, after `anchor`:

```java
            @Schema(description = "A public http(s) URL of a JPEG, PNG, GIF or WebP image to import as the "
                    + "featured image, in one call. An image already in the weblog's default directory with the "
                    + "same bytes is reused. Not with featuredImageId.")
            String featuredImageUrl,
            @Schema(description = "Alt text for the image featuredImageUrl imports; it fills the file's alt "
                    + "text only when that has none.")
            String featuredImageAlt,
            @Schema(description = "True to import every image the text loads from another site (HTML img src "
                    + "and Markdown images, at most 10) and point the text at the copies. Without text on an "
                    + "update, the stored text. All or nothing: if any image fails, nothing is written.")
            Boolean importExternalImages,
```

and add to the record body:

```java
        /**
         * This write with what the import step produced: the featured image it
         * set and the text it rewrote. A null argument keeps the write's own value.
         */
        public EntryWrite withImports(String importedFeaturedImageId, String rewrittenText) {
            return new EntryWrite(title, summary, rewrittenText != null ? rewrittenText : text, status, category,
                    pubTime, tags, metaTitle, searchDescription, canonicalUrl, noindex,
                    importedFeaturedImageId != null ? importedFeaturedImageId : featuredImageId, ogImageId,
                    jsonLdType, geoLatitude, geoLongitude, eventStart, eventEnd, eventLocation, commentsClosed,
                    anchor, featuredImageUrl, featuredImageAlt, importExternalImages, clear);
        }
```

Add the three fields and setters to `EntryWrites` (`featuredImageUrl(String)`, `featuredImageAlt(String)`, `importExternalImages(Boolean)`), passed in `build()` after `anchor`.

- [ ] **Step 4: Implement `EntryImports`**

```java
package org.apache.roller.weblogger.ui.restapi.v1;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.ExternalImages;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.business.fetch.RemoteImageFetcher;
import org.apache.roller.weblogger.config.WebloggerRuntimeConfig;
import org.apache.roller.weblogger.pojos.MediaFile;
import org.apache.roller.weblogger.pojos.MediaFileDirectory;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.ui.controllers.MediaUploads;
import org.apache.roller.weblogger.ui.restapi.ApiException;
import org.apache.roller.weblogger.ui.restapi.ColumnLimits;
import org.apache.roller.weblogger.ui.restapi.FieldErrors;
import org.apache.roller.weblogger.ui.restapi.MediaImports;
import org.apache.roller.weblogger.ui.restapi.dto.EntryDtos;

/**
 * The import step of an entry write: {@code featuredImageUrl} and {@code
 * importExternalImages}. {@link #plan} checks everything that can be checked
 * without the network, alongside the write's other field checks, so a write
 * refused for a typo fetches nothing. {@link #run} imports in order -- the
 * featured image, then the body's images by first appearance -- each URL once,
 * and is all or nothing for the entry: any image refused, or not fetched
 * within the budget, refuses the write naming every such image. Images that
 * did import stay in the library (each import commits); a retry matches them
 * by content hash, so it costs downloads, not copies.
 */
final class EntryImports {

    /** Distinct external images one write may import. */
    static final int MAX_BODY_IMAGES = 10;
    /**
     * The import step stops starting fetches this long after it began. Each
     * fetch already has its own 30-second deadline; Cloudflare ends a request
     * at 100 seconds, and the write still has to save after the last fetch.
     */
    static final Duration BUDGET = Duration.ofSeconds(60);

    /** What the import step will do, worked out before anything is fetched. */
    record Plan(String featuredUrl, String featuredAlt, String text, ExternalImages.Scan scan) {
        static final Plan NONE = new Plan(null, null, null, null);

        boolean isEmpty() {
            return featuredUrl == null && (scan == null || scan.refs().isEmpty());
        }
    }

    private record Wanted(String url, String alt, String field) { }

    private final Weblogger weblogger;
    private final RemoteImageFetcher fetcher;
    private final Clock clock;

    EntryImports(Weblogger weblogger, RemoteImageFetcher fetcher, Clock clock) {
        this.weblogger = weblogger;
        this.fetcher = fetcher;
        this.clock = clock;
    }

    /**
     * Checks a write's import fields into {@code errors} and returns what to
     * import. The uploads switch and the site's own hosts are read only when
     * the write asks for an import. {@code storedText} is the entry's current
     * text, scanned when an update asks for an import without sending text.
     */
    Plan plan(FieldErrors errors, Weblog weblog, EntryDtos.EntryWrite body, Set<String> cleared, String storedText) {
        boolean importBody = Boolean.TRUE.equals(body.importExternalImages());
        String featuredUrl = body.featuredImageUrl();
        if (body.featuredImageAlt() != null) {
            if (featuredUrl == null) {
                errors.add("featuredImageAlt", "featuredImageAlt applies only with featuredImageUrl.");
            }
            errors.check("featuredImageAlt", () -> ColumnLimits.requireMaxLength(
                    "featuredImageAlt", body.featuredImageAlt(), ColumnLimits.MEDIA_ALT_TEXT));
        }
        if (featuredUrl == null && !importBody) {
            return Plan.NONE;
        }
        boolean uploads = WebloggerRuntimeConfig.getBooleanProperty("uploads.enabled");
        if (featuredUrl != null) {
            if (featuredUrl.isBlank()) {
                errors.add("featuredImageUrl", "featuredImageUrl cannot be blank.");
            }
            if (body.featuredImageId() != null) {
                errors.add("featuredImageUrl", "Give featuredImageUrl or featuredImageId, not both.");
            }
            if (cleared.contains("featuredImageId")) {
                errors.add("featuredImageUrl", "featuredImageUrl sets the featured image; do not also clear featuredImageId.");
            }
            if (!uploads) {
                errors.add("featuredImageUrl", "File upload is disabled for this site.");
            }
        }
        if (!importBody) {
            return new Plan(featuredUrl, body.featuredImageAlt(), null, null);
        }
        if (!uploads) {
            errors.add("importExternalImages", "File upload is disabled for this site.");
        }
        String text = body.text() != null ? body.text() : storedText;
        ExternalImages.Scan scan = ExternalImages.scan(text, ownHosts(weblog));
        scan.refusals().forEach(refusal -> errors.add("text", refusal));
        int distinct = scan.distinctUrls().size();
        if (distinct > MAX_BODY_IMAGES) {
            errors.add("text", "The text loads " + distinct + " images from other sites; at most "
                    + MAX_BODY_IMAGES + " are imported in one write.");
        }
        return new Plan(featuredUrl, body.featuredImageAlt(), text, scan);
    }

    /**
     * Runs a plan {@link #plan} passed and returns the write to apply in
     * place of {@code body}. Refuses (400) when the default directory is
     * private, and with one error per image that failed.
     */
    EntryDtos.EntryWrite run(Plan plan, Weblog weblog, EntryDtos.EntryWrite body) throws WebloggerException {
        if (plan.isEmpty()) {
            return body;
        }
        String field = plan.featuredUrl() != null ? "featuredImageUrl" : "text";
        MediaFileDirectory directory = MediaUploads.defaultDirectory(weblog, weblogger.getMediaFileManager());
        if (directory.isPrivate()) {
            throw ApiException.invalidField(field,
                    "The weblog's default media directory is private, so readers could not see imported images.");
        }
        Instant started = clock.instant();
        Map<String, MediaFile> imported = new LinkedHashMap<>();
        FieldErrors failures = new FieldErrors();
        for (Wanted wanted : wanted(plan)) {
            if (imported.containsKey(wanted.url())) {
                continue;
            }
            if (Duration.between(started, clock.instant()).compareTo(BUDGET) >= 0) {
                failures.add(wanted.field(), "Image " + ExternalImages.abbreviate(wanted.url())
                        + " was not fetched: the import step's " + BUDGET.toSeconds() + "-second budget ran out.");
                continue;
            }
            try {
                imported.put(wanted.url(), MediaImports.importImage(weblogger, fetcher, weblog, directory,
                        wanted.url(), null, wanted.alt()).file());
            } catch (MediaImports.ImportRefused e) {
                failures.add(wanted.field(), "Image " + ExternalImages.abbreviate(wanted.url()) + ": " + e.getMessage());
            }
        }
        failures.throwIfAny();

        String featuredId = plan.featuredUrl() == null ? null : imported.get(plan.featuredUrl()).getId();
        String text = null;
        if (plan.scan() != null && !plan.scan().refs().isEmpty()) {
            Map<String, String> local = new HashMap<>();
            imported.forEach((url, file) ->
                    local.put(url, weblogger.getUrlStrategy().getMediaFileURL(weblog, file.getId(), true)));
            text = ExternalImages.rewrite(plan.text(), plan.scan().refs(), local);
        }
        return body.withImports(featuredId, text);
    }

    /** The featured image first, then each body URL once, with the first alt text it carries. */
    private static List<Wanted> wanted(Plan plan) {
        List<Wanted> wanted = new ArrayList<>();
        if (plan.featuredUrl() != null) {
            wanted.add(new Wanted(plan.featuredUrl(), plan.featuredAlt(), "featuredImageUrl"));
        }
        if (plan.scan() != null) {
            Map<String, String> altByUrl = new LinkedHashMap<>();
            for (ExternalImages.Ref ref : plan.scan().refs()) {
                altByUrl.merge(ref.url(), ref.alt() == null ? "" : ref.alt(),
                        (first, next) -> first.isEmpty() ? next : first);
            }
            altByUrl.forEach((url, alt) -> wanted.add(new Wanted(url, alt.isEmpty() ? null : alt, "text")));
        }
        return wanted;
    }

    /** This weblog's host (a custom domain included) and the site's, lower case. */
    private Set<String> ownHosts(Weblog weblog) {
        Set<String> hosts = new HashSet<>();
        addHost(hosts, weblogger.getUrlStrategy().getWeblogURL(weblog, null, true));
        addHost(hosts, WebloggerRuntimeConfig.getAbsoluteContextURL());
        return hosts;
    }

    private static void addHost(Set<String> hosts, String url) {
        if (url == null) {
            return;
        }
        try {
            String host = URI.create(url.trim()).getHost();
            if (host != null) {
                hosts.add(host.toLowerCase(Locale.ROOT));
            }
        } catch (IllegalArgumentException e) {
            // an unparseable site URL names no host; nothing is skipped as "ours" on its account
        }
    }
}
```

Note on alt text: `wanted` uses `""` internally only as "no alt yet" while merging; it never reaches a file (it maps back to null), so a body image's alt never writes the deliberate `""`.

- [ ] **Step 5: Wire the step into `create` and `update`**

In `EntriesWriteApi` add fields (imports `java.time.Clock`, `RemoteImageFetcher`, `Autowired`):

```java
    @Autowired
    RemoteImageFetcher imageFetcher;

    /** The import step's budget is measured on this; a test replaces it. */
    Clock clock = Clock.systemUTC();
```

In `create`, after `checkWrite(errors, weblog, entry, body, Set.of());` and before `errors.throwIfAny();`:

```java
        EntryImports imports = new EntryImports(weblogger, imageFetcher, clock);
        EntryImports.Plan importPlan = imports.plan(errors, weblog, body, Set.of(), null);
```

and replace the block from `errors.throwIfAny();` through `apply(...)` with:

```java
        errors.throwIfAny();
        if (body.anchor() != null) {
            requireAnchorFree(weblog, body.anchor());
        }

        EntryDtos.EntryWrite effective = imports.run(importPlan, weblog, body);
        if (body.anchor() != null) {
            // Again, immediately before the save: the import step can take a
            // minute, and a client that timed out may have retried meanwhile.
            requireAnchorFree(weblog, body.anchor());
            // saveWeblogEntry generates an anchor only when none is set
            entry.setAnchor(body.anchor());
        }
        apply(entry, weblog, effective, Set.of());
```

In `update`, after `checkWrite(errors, weblog, entry, body, cleared);`:

```java
        EntryImports imports = new EntryImports(weblogger, imageFetcher, clock);
        EntryImports.Plan importPlan = imports.plan(errors, weblog, body, cleared, entry.getText());
```

and replace `apply(entry, weblog, body, cleared);` with

```java
        // The import step commits each image it stores; nothing has been
        // applied to the managed entry yet, so nothing of the write goes with it.
        apply(entry, weblog, imports.run(importPlan, weblog, body), cleared);
```

- [ ] **Step 6: Run them and watch them pass**

Run: `mvn -o -q -pl app test -Dtest='EntriesWriteApi*Test,EntriesApi*Test,EntryDtos*Test,Mcp*Test,OpenApiDocumentTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS. If the anchor test `aChosenAnchorIsStoredAndThePermalinkEndsWithIt` now fails because `getWeblogEntryByAnchor` is called twice, that is expected behaviour: change any `verify(entries).getWeblogEntryByAnchor(...)` there to `times(2)`.

- [ ] **Step 7: Full verify and commit**

Run: `mvn -o -pl app verify` — expected BUILD SUCCESS (watch the security-boundary package rule: `EntryImports` is in `ui.restapi.v1`).

```bash
git add app/src/main/java/org/apache/roller/weblogger/ui/restapi/dto/EntryDtos.java \
        app/src/main/java/org/apache/roller/weblogger/ui/restapi/v1/{EntryImports,EntriesWriteApi}.java \
        app/src/test/java/org/apache/roller/weblogger/ui/restapi/v1/{EntryWrites,EntriesWriteApiImportsTest,EntriesWriteApiAnchorTest}.java
git commit -m "feat(api): featuredImageUrl and importExternalImages import images in the entry write"
```

---

### Task 9: Field errors across the rest of `/api/v1`

**Files:**
- Modify: `app/src/main/java/org/apache/roller/weblogger/ui/restapi/dto/ClearFields.java`
- Modify: `app/src/main/java/org/apache/roller/weblogger/ui/restapi/ApiExceptionHandler.java` (`handleTypeMismatch`)
- Modify (the 400 sites of): `v1/WeblogsApi.java`, `v1/TokensApi.java`, `v1/PagesApi.java`, `v1/AuditApi.java`, `v1/MediaApi.java`, `v1/WeblogCreateApi.java`, `v1/AdminApi.java`, `v1/EntriesApi.java`, `v1/CommentsApi.java`, `v1/RedirectsApi.java`, `v1/CategoriesApi.java`, `dto/PageDtos.java`, `dto/MediaDtos.java`, `dto/AdminDtos.java`
- Test: the existing test of each changed site, plus `ClearFieldsTest`, `ApiExceptionHandlerTest`

**Interfaces:**
- Consumes: Task 1 `ApiException.invalidField`.
- Produces: every single-field 400 in `/api/v1` carries `errors: [{field}]` (spec section 1).

The rule for each site: **a 400 whose message is about exactly one field the caller sent** (a body property, a query parameter, or `clear`) becomes `ApiException.invalidField("<that field's JSON name>", <same message>)` (or the cause-carrying overload when it chains a cause). A 400 not about one field — an unparseable body, a missing body, a refusal about the whole request ("File upload is disabled for this site.") — stays `badRequest`. Messages do not change.

- [ ] **Step 1: List the sites**

Run: `grep -n "ApiException.badRequest(" app/src/main/java/org/apache/roller/weblogger/ui/restapi -r`
Write the list, with your field-or-not decision for each line, into the task report before changing code.

- [ ] **Step 2: Write the failing tests**

`ClearFieldsTest` — add:

```java
    @Test
    void everyRefusalNamesTheClearField() {
        record Body(String a, String b, List<String> clear) { }
        for (List<String> clear : List.of(List.of("nope"), List.of("a"))) {
            ApiException ex = assertThrows(ApiException.class, () -> ClearFields.validate(clear,
                    new Body(null, null, clear), List.of("a", "b"), List.of(List.of("a", "b"))));
            assertEquals("clear", ex.getFieldErrors().get(0).field());
        }
        ApiException both = assertThrows(ApiException.class, () -> ClearFields.validate(List.of("a", "b"),
                new Body("set", null, List.of("a", "b")), List.of("a", "b"), List.of()));
        assertEquals("clear", both.getFieldErrors().get(0).field());
    }
```

`ApiExceptionHandlerTest` — add a test that a `MethodArgumentTypeMismatchException` for parameter `limit` answers a problem whose `errors[0].field` is `limit` (build it the way that test class already builds its other exceptions).

For every other site you marked "field" in Step 1: find the existing test that hits it (`grep -rn "<message fragment>" app/src/test`) and add one assertion that `errors[0].field` is the field name. Where a site has no test, add one in that controller's test class using its existing fixture.

- [ ] **Step 3: Run them and watch them fail**

Run: `mvn -o -q -pl app test -Dtest='ClearFieldsTest,ApiExceptionHandlerTest,*ApiTest,*Api*Test' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: the new assertions fail (no `errors` array).

- [ ] **Step 4: Convert the sites**

`ClearFields.validate`: its three `ApiException.badRequest(` become `ApiException.invalidField("clear", `. `ApiExceptionHandler.handleTypeMismatch`:

```java
        return handleApiException(
                ApiException.invalidField(ex.getName(), "'" + ex.getName() + "' is not a valid value for this parameter."),
                request);
```

Then each site from Step 1 by the rule above.

- [ ] **Step 5: Run them and watch them pass, then full verify**

Run the Step 3 command — expected PASS. Then `mvn -o -pl app verify` — expected BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/org/apache/roller/weblogger/ui/restapi app/src/test/java/org/apache/roller/weblogger/ui/restapi
git commit -m "feat(api): every single-field 400 names its field"
```

---

### Task 10: Instructions and documentation

**Files:**
- Modify: `app/src/main/java/org/apache/roller/weblogger/ui/restapi/mcp/McpInstructions.java`
- Modify: `app/src/test/java/org/apache/roller/weblogger/ui/restapi/mcp/McpInstructionsTest.java`
- Modify: `docs/api/README.md` (Errors, Entries, Media)
- Modify: `docs/dev/api.md`, `docs/dev/routing.md`, `docs/dev/media.md`

**Interfaces:** none (documentation; the instructions test pins the text).

- [ ] **Step 1: Write the failing test**

In `McpInstructionsTest`, add to the required points: `"anchor"`, `"errors"`, `"featuredImageUrl"`, `"importExternalImages"`, `"[image id"`, `"[gallery"`, `"[map]"`, `"first copy"`.

Run: `mvn -o -q -pl app test -Dtest=McpInstructionsTest` — expected FAIL ("instructions must mention: anchor").

- [ ] **Step 2: Update the instructions**

In `McpInstructions.TEXT`, replace the featured-image bullet with:

```
            - A featured image is a media file: set featuredImageUrl to a public http(s) image URL to import it \
            and set it in one call (featuredImageAlt describes it), or `media import` it and set featuredImageId. \
            Importing the same image again returns the first copy.
            - `importExternalImages: true` on create or update imports every image the text loads from another \
            site and points the text at the copies; if any image fails, nothing is written.
            - In entry text, `[image id=<media id> caption="..."]` places an imported image responsively; \
            `[gallery dir="<directory>"]`, `[map]` with `[pin lat=.. lng=.. label=".."]`, `[video url="..."]` \
            and `[faq]` with `[q]`/`[a]` are also available.
            - Give `anchor` on create to choose the URL slug (lower-case words joined by - or _); it cannot \
            change later. A refused write lists every bad field in `errors`.
```

Run the test — expected PASS (it also checks the text stays under 3000 characters; shorten wording, not content, if it does not).

- [ ] **Step 3: Update `docs/api/README.md`**

- **Errors**: replace the paragraph beginning "The problem type carries an `errors` array…" with: a 400 or 409 about one field carries `errors: [{"field", "message"}]`, `field` being the property or query parameter name as sent; an entry create or update lists every bad field at once, `detail` being the messages joined; a 400 not about one field (unparseable JSON, a missing body) carries none. Give this example:

```json
{
  "type": "https://roller.invalid/problems/invalid-request",
  "title": "Invalid request",
  "status": 400,
  "detail": "Unknown category 'Travle'; it is one of: Food, Travel. geoLatitude must be between -90 and 90.",
  "instance": "/api/v1/weblogs/myblog/entries",
  "errors": [
    {"field": "category", "message": "Unknown category 'Travle'; it is one of: Food, Travel."},
    {"field": "geoLatitude", "message": "geoLatitude must be between -90 and 90."}
  ]
}
```

  In the status table, add to 409: "an `anchor` another entry of the weblog holds (a trashed one included)".
- **Entries**: add a subsection "Slugs and images" covering `anchor` (form, create only, a PATCH sending the stored value back is fine, 409 rules and the trash message), `featuredImageUrl`/`featuredImageAlt` (what they do, what they can't be combined with, uploads must be enabled), `importExternalImages` (what is found and what counts as external, only `src` rewritten, Markdown images become inline, at most 10, the 60-second budget, all or nothing, a retry matches the files already imported), and that `featuredImageId`/`ogImageId` must name a public image of this weblog unless sent back unchanged.
- **Media**: in "Import by URL", add that bytes already in the target directory answer **200** with that file (no copy, no quota), and that a null alt text is filled while `""` is kept; and that every refusal is a 400 on `url`.

- [ ] **Step 4: Update the developer notes**

- `docs/dev/api.md`, under "New-weblog needs (step 2)", add a section "Entry writes: check, import, apply" (3-5 bullets): why `checkWrite` runs on `EntryDtos.scratchOf` (a refused write changes nothing, and `persistNewMediaFile` flushes mid-request, so nothing may be applied before the import step); `FieldErrors` collects only 400s; `MediaImports` is the one import path (logs the host only, commits each file); `EntryImports.BUDGET`/`MAX_BODY_IMAGES` and why (Cloudflare's 100 seconds).
- `docs/dev/routing.md`: change "Entry anchors are immutable, so there is no entry-side hook — `EntryBean` has no anchor field; `createAnchor` fires only when unset." to say anchors are immutable after create; the API's `anchor` sets one at create only (`EntryFieldRules.isValidAnchor`), so there is still no rename hook.
- `docs/dev/media.md`: add a bullet on `content_hash` (V032): set from the bytes as they stream to disk in `persistNewMediaFile`; cleared by `updateMediaFile(…, InputStream)` and `cropMediaFile`; matched per directory by `MediaImports`; null for older files; no unique constraint, so two concurrent identical imports can both store.

- [ ] **Step 5: Verify and commit**

Run: `mvn -o -q -pl app test -Dtest='McpInstructionsTest,McpDocsTest,OpenApiDocumentTest'` — expected PASS.

```bash
git add app/src/main/java/org/apache/roller/weblogger/ui/restapi/mcp/McpInstructions.java \
        app/src/test/java/org/apache/roller/weblogger/ui/restapi/mcp/McpInstructionsTest.java \
        docs/api/README.md docs/dev/api.md docs/dev/routing.md docs/dev/media.md
git commit -m "docs(api): field errors, anchors, image import; MCP instructions teach the shortcodes"
```

---

### Task 11: Browser ITs and full verification

**Files:**
- Modify: `it-selenium/src/test/java/org/apache/roller/it/McpIT.java`

**Interfaces:** consumes everything above through the real WAR.

- [ ] **Step 1: Write the ITs**

Add to `McpIT` (its helpers `mint`, `callTool`, `isError`, `text`, `revoke`, `restSend` and `WEBLOG_HANDLE` already exist):

```java
    /** Criterion 21: field errors arrive through MCP unchanged, at whichever context path the suite runs. */
    @Test
    void aRefusedWriteThroughMcpListsEveryBadField() throws Exception {
        Token token = mint("McpIT field errors", "POST", null);
        try {
            JsonNode refused = callTool(token.raw(), "entries", Map.of("action", "create",
                    "weblog", WEBLOG_HANDLE, "title", "t", "text", "x",
                    "category", "No Such Category", "geoLatitude", 91));
            assertTrue(isError(refused));
            JsonNode problem = JSON.readTree(text(refused));
            assertEquals(400, problem.get("status").asInt());
            List<String> fields = new ArrayList<>();
            for (JsonNode error : problem.get("errors")) {
                fields.add(error.get("field").asString());
            }
            assertEquals(Set.of("category", "geoLatitude"), Set.copyOf(fields));
            assertTrue(problem.get("detail").asString().contains("it is one of:"));
        } finally {
            revoke(token.id());
        }
    }

    /** Criterion 21: a chosen anchor is the permalink's last segment, and a second claim is a 409. */
    @Test
    void anAnchorChosenThroughMcpIsThePermalinkAndCannotBeClaimedTwice() throws Exception {
        Token token = mint("McpIT anchor", "POST", null);
        String anchor = "mcpit-anchor-" + System.nanoTime();
        String id = null;
        try {
            JsonNode created = callTool(token.raw(), "entries", Map.of("action", "create",
                    "weblog", WEBLOG_HANDLE, "title", "Any title at all", "text", "x", "anchor", anchor));
            assertFalse(isError(created), created.toString());
            JsonNode view = JSON.readTree(text(created));
            id = view.get("id").asString();
            assertTrue(view.get("permalink").asString().endsWith("/" + anchor), view.toString());

            JsonNode again = callTool(token.raw(), "entries", Map.of("action", "create",
                    "weblog", WEBLOG_HANDLE, "title", "Another", "text", "x", "anchor", anchor));
            assertTrue(isError(again));
            JsonNode problem = JSON.readTree(text(again));
            assertEquals(409, problem.get("status").asInt());
            assertEquals("anchor", problem.get("errors").get(0).get("field").asString());
        } finally {
            if (id != null) {
                restSend(token.raw(), "DELETE", "/api/v1/weblogs/" + WEBLOG_HANDLE + "/entries/" + id, null);
                restSend(token.raw(), "POST",
                        "/api/v1/weblogs/" + WEBLOG_HANDLE + "/entries/" + id + "/delete-forever", null);
            }
            revoke(token.id());
        }
    }
```

(imports `java.util.ArrayList`, `java.util.List`, `java.util.Set` if missing.) Image import is not exercised here: the fetcher refuses every non-public address and CI must not depend on a third-party host; the unit and DB tests above cover it.

- [ ] **Step 2: Run the browser suite at both context paths**

Run, one after the other (never two builds at once):

```bash
mvn -o verify -Pit
mvn -o verify -Pit -Dit.context.path=roller
```

Expected: BUILD SUCCESS both times; the unit suite and every browser IT green. If a test fails, follow superpowers:systematic-debugging; do not retry blindly.

- [ ] **Step 3: Diff coverage**

Run: `bin/check-diff-coverage.sh v0.1.18`
Expected: at least 90% of changed lines covered. Below that, add tests that assert behaviour — never assertion-free tests.

- [ ] **Step 4: Commit**

```bash
git add it-selenium/src/test/java/org/apache/roller/it/McpIT.java
git commit -m "test(it): field errors and chosen anchors through MCP on the real WAR"
```

Report the two `-Pit` results (test counts) and the diff-coverage percentage. Releasing 0.1.19 is Jake's call, not this plan's.

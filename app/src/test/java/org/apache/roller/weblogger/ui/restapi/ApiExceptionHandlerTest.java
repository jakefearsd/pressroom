package org.apache.roller.weblogger.ui.restapi;

import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.junit.jupiter.api.Assertions.*;

class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void anApiExceptionBecomesProblemJsonCarryingItsStatusAndInstance() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/weblogs/nope");
        ResponseEntity<ApiProblem> response =
                handler.handleApiException(ApiException.notFound("No weblog 'nope'."), request);

        assertEquals(HttpStatus.NOT_FOUND.value(), response.getStatusCode().value());
        assertEquals(MediaType.APPLICATION_PROBLEM_JSON, response.getHeaders().getContentType());
        ApiProblem body = response.getBody();
        assertNotNull(body);
        assertEquals(404, body.status());
        assertEquals("No weblog 'nope'.", body.detail());
        assertEquals("/api/v1/weblogs/nope", body.instance(),
                "instance must be the path the client actually called, /api prefix included");
    }

    /** One home for the opaque 500, shared with the MCP endpoint's own failures. */
    @Test
    void theInternalErrorFactoryIsTheOpaque500() {
        ApiProblem problem = ApiException.internalError().toProblem("/api/mcp");
        assertEquals("https://roller.invalid/problems/internal-error", problem.type());
        assertEquals("Internal error", problem.title());
        assertEquals(500, problem.status());
        assertEquals("The request could not be completed.", problem.detail());
    }

    /**
     * The anchor claim giving up behind another save of the same weblog is
     * transient: a retryable 503 with Retry-After (the lock timeout, which
     * the exception carries), not an opaque 500.
     */
    @Test
    void anAnchorLockTimeoutIsARetryable503() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/weblogs/myblog/entries");

        ResponseEntity<ApiProblem> response = handler.handleAnchorLockTimeout(
                new org.apache.roller.weblogger.business.AnchorLockTimeoutException("myblog", 10), request);

        assertEquals(503, response.getStatusCode().value());
        assertEquals(MediaType.APPLICATION_PROBLEM_JSON, response.getHeaders().getContentType());
        assertEquals("10", response.getHeaders().getFirst("Retry-After"));
        ApiProblem body = response.getBody();
        assertNotNull(body);
        assertEquals("https://roller.invalid/problems/busy", body.type());
        assertEquals("Busy", body.title());
        assertEquals(503, body.status());
        assertEquals("Another save of this weblog is in progress; retry shortly.", body.detail());
        assertEquals("/api/v1/weblogs/myblog/entries", body.instance());
    }

    /** Any other failure, a database one included, stays the opaque 500 with no Retry-After. */
    @Test
    void anyOtherFailureStaysTheOpaque500() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/weblogs/x/entries");
        Exception failure = new org.apache.roller.weblogger.WebloggerException("db",
                new java.sql.SQLException("canceling statement due to lock timeout", "55P03"));

        ResponseEntity<ApiProblem> response = handler.handleUnexpected(failure, request);

        assertEquals(500, response.getStatusCode().value());
        assertNull(response.getHeaders().getFirst("Retry-After"));
    }

    /**
     * An unexpected exception must not leak its message or stack to a client.
     * The detail is fixed text; the real cause goes to the log.
     */
    @Test
    void anUnexpectedExceptionBecomesAnOpaque500() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/weblogs/x/entries");
        ResponseEntity<ApiProblem> response = handler.handleUnexpected(
                new IllegalStateException("connection pool exhausted at com.example.Secret"), request);

        assertEquals(500, response.getStatusCode().value());
        ApiProblem body = response.getBody();
        assertNotNull(body);
        assertFalse(body.detail().contains("connection pool"),
                "internal detail must never reach the client");
        assertFalse(body.detail().contains("com.example"),
                "internal detail must never reach the client");
    }

    /**
     * Wave-level gap: HttpMessageNotReadableException (malformed/empty JSON
     * body) fires during argument resolution, before any controller method
     * runs, and used to fall through to handleUnexpected -- a 500 on every
     * *Api controller for a client mistake as ordinary as a trailing comma.
     * The parser's own message can echo fragments of the request body and
     * internal type names, so it must never reach the client, only the log.
     */
    @Test
    void aMalformedRequestBodyBecomesA400WithoutLeakingParserDetail() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/weblogs/x/categories");
        org.springframework.http.converter.HttpMessageNotReadableException ex =
                new org.springframework.http.converter.HttpMessageNotReadableException(
                        "JSON parse error: Unexpected end-of-input at [Source: (String)\"{\"; line: 1, column: 1]",
                        new org.springframework.mock.http.MockHttpInputMessage(new byte[0]));

        ResponseEntity<ApiProblem> response = handler.handleMalformedBody(ex, request);

        assertEquals(400, response.getStatusCode().value());
        assertEquals(MediaType.APPLICATION_PROBLEM_JSON, response.getHeaders().getContentType());
        ApiProblem body = response.getBody();
        assertNotNull(body);
        assertFalse(body.detail().contains("Unexpected end-of-input"),
                "the parser's own message must never reach the client");
        assertFalse(body.detail().contains("Source:"),
                "the parser's own message must never reach the client");
    }

    /**
     * Review round 1, Minor: {@code GET /v1/admin/users?limit=abc} used to
     * 500 -- Spring throws MethodArgumentTypeMismatchException while binding
     * the query parameter, before any controller method runs (the same
     * "fires during argument resolution" shape as the malformed-JSON-body
     * case above), and with no handler for it here it fell through to
     * {@link #handleUnexpected}. Affects every {@code offset}/{@code limit}
     * (or any other typed {@code @RequestParam}/{@code @PathVariable}) query
     * parameter across the whole wave, not just AdminApi's.
     */
    @Test
    void aTypeMismatchedQueryParameterBecomesA400WithoutLeakingParserDetail() throws NoSuchMethodException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/admin/users");

        MethodParameter methodParameter = new MethodParameter(
                ApiExceptionHandlerTest.class.getDeclaredMethod("dummyTypedTarget", int.class), 0);
        org.springframework.web.method.annotation.MethodArgumentTypeMismatchException ex =
                new org.springframework.web.method.annotation.MethodArgumentTypeMismatchException(
                        "abc", int.class, "limit", methodParameter,
                        new NumberFormatException("For input string: \"abc\""));

        ResponseEntity<ApiProblem> response = handler.handleTypeMismatch(ex, request);

        assertEquals(400, response.getStatusCode().value());
        assertEquals(MediaType.APPLICATION_PROBLEM_JSON, response.getHeaders().getContentType());
        ApiProblem body = response.getBody();
        assertNotNull(body);
        assertFalse(body.detail().contains("For input string"),
                "the parser's own message must never reach the client");
        assertFalse(body.detail().contains("NumberFormatException"),
                "the exception's own class name must never reach the client");
        assertTrue(body.detail().contains("limit"), "the offending parameter name is fine to name back");
        assertEquals("limit has an invalid value; it must be a whole number.", body.detail(),
                "the name is the subject, not a quoted value");
    }

    /** The detail names what the parameter takes, from the type it binds to. */
    @Test
    void aTypeMismatchSaysWhatTheParameterTakes() throws NoSuchMethodException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/admin/users");
        MethodParameter methodParameter = new MethodParameter(
                ApiExceptionHandlerTest.class.getDeclaredMethod("dummyTypedTarget", int.class), 0);

        assertEquals("enabled has an invalid value; it must be true or false.",
                handler.handleTypeMismatch(mismatch(Boolean.class, "enabled", methodParameter), request)
                        .getBody().detail());
        assertEquals("focalX has an invalid value; it must be a number.",
                handler.handleTypeMismatch(mismatch(Double.class, "focalX", methodParameter), request)
                        .getBody().detail());
        assertEquals("since has an invalid value.",
                handler.handleTypeMismatch(mismatch(java.time.Instant.class, "since", methodParameter), request)
                        .getBody().detail());
        assertEquals("when has an invalid value.",
                handler.handleTypeMismatch(mismatch(null, "when", methodParameter), request)
                        .getBody().detail());
    }

    private static org.springframework.web.method.annotation.MethodArgumentTypeMismatchException mismatch(
            Class<?> type, String name, MethodParameter parameter) {
        return new org.springframework.web.method.annotation.MethodArgumentTypeMismatchException(
                "x", type, name, parameter, new IllegalArgumentException("x"));
    }

    /**
     * A client typo is not a server fault: the type-mismatch line carries no
     * stack trace, only the parameter and the path.
     */
    @Test
    void aTypeMismatchIsLoggedWithoutAStackTrace() throws NoSuchMethodException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/admin/users");
        MethodParameter methodParameter = new MethodParameter(
                ApiExceptionHandlerTest.class.getDeclaredMethod("dummyTypedTarget", int.class), 0);
        org.apache.logging.log4j.core.Logger logger = (org.apache.logging.log4j.core.Logger)
                org.apache.logging.log4j.LogManager.getLogger(ApiExceptionHandler.class);
        java.util.List<org.apache.logging.log4j.core.LogEvent> events = new java.util.concurrent.CopyOnWriteArrayList<>();
        org.apache.logging.log4j.core.appender.AbstractAppender appender =
                new org.apache.logging.log4j.core.appender.AbstractAppender("capture", null, null, true,
                        org.apache.logging.log4j.core.config.Property.EMPTY_ARRAY) {
                    @Override
                    public void append(org.apache.logging.log4j.core.LogEvent event) {
                        events.add(event.toImmutable());
                    }
                };
        appender.start();
        org.apache.logging.log4j.Level before = logger.getLevel();
        logger.addAppender(appender);
        logger.setLevel(org.apache.logging.log4j.Level.INFO);
        try {
            handler.handleTypeMismatch(mismatch(int.class, "limit", methodParameter), request);
        } finally {
            logger.removeAppender(appender);
            logger.setLevel(before);
        }
        assertEquals(1, events.size(), events.toString());
        assertNull(events.get(0).getThrown(), "no stack trace for a client typo");
        assertTrue(events.get(0).getMessage().getFormattedMessage().contains("limit"));
    }

    @SuppressWarnings("unused")
    private void dummyTypedTarget(int arg) { }

    @Test
    void aTypeMismatchedParameterNamesItsFieldInErrors() throws NoSuchMethodException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/admin/users");
        MethodParameter methodParameter = new MethodParameter(
                ApiExceptionHandlerTest.class.getDeclaredMethod("dummyTypedTarget", int.class), 0);
        org.springframework.web.method.annotation.MethodArgumentTypeMismatchException ex =
                new org.springframework.web.method.annotation.MethodArgumentTypeMismatchException(
                        "abc", int.class, "limit", methodParameter,
                        new NumberFormatException("For input string: \"abc\""));

        ApiProblem body = handler.handleTypeMismatch(ex, request).getBody();

        assertNotNull(body);
        assertEquals("limit", body.errors().get(0).field());
    }
}

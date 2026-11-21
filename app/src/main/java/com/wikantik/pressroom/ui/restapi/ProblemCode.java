package com.wikantik.pressroom.ui.restapi;

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
    /**
     * RFC 9457's {@code about:blank}, which MCP answers for an error reply
     * that brought no problem body of its own: the status is the reply's and
     * the title its reason phrase, so it has no {@link ProblemType}, and no
     * detail is sent.
     */
    HTTP_ERROR("http.error", null, 0);

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

package org.apache.roller.weblogger.ui.restapi;

import java.util.List;

/**
 * The one exception the API layer throws for an expected failure. Every
 * response body is built from it by ApiExceptionHandler; no controller
 * assembles its own.
 */
public class ApiException extends RuntimeException {

    private static final long serialVersionUID = 1L;
    private static final String TYPE_BASE = "https://roller.invalid/problems/";

    private final int status;
    private final String type;
    private final String title;
    private final transient List<ApiProblem.FieldError> errors;

    public ApiException(int status, String type, String title, String detail) {
        this(status, type, title, detail, null);
    }

    public ApiException(int status, String type, String title, String detail,
                        List<ApiProblem.FieldError> errors) {
        super(detail);
        this.status = status;
        this.type = type;
        this.title = title;
        this.errors = errors;
    }

    /**
     * As above, but also chains {@code cause} -- the exception this API
     * exception was translated from -- so the original stack trace survives
     * in application logs even though the client only ever sees the problem
     * detail.
     */
    public ApiException(int status, String type, String title, String detail,
                        List<ApiProblem.FieldError> errors, Throwable cause) {
        super(detail, cause);
        this.status = status;
        this.type = type;
        this.title = title;
        this.errors = errors;
    }

    public static ApiException notFound(String detail) {
        return new ApiException(404, TYPE_BASE + "not-found", "Not found", detail);
    }

    public static ApiException unauthorized(String detail) {
        return new ApiException(401, TYPE_BASE + "unauthorized", "Unauthorized", detail);
    }

    public static ApiException forbidden(String detail) {
        return new ApiException(403, TYPE_BASE + "forbidden", "Forbidden", detail);
    }

    public static ApiException badRequest(String detail) {
        return new ApiException(400, TYPE_BASE + "invalid-request", "Invalid request", detail);
    }

    /** As above, chaining {@code cause} -- see the cause-carrying constructor. */
    public static ApiException badRequest(String detail, Throwable cause) {
        return new ApiException(400, TYPE_BASE + "invalid-request", "Invalid request", detail, null, cause);
    }

    public static ApiException validation(String detail, List<ApiProblem.FieldError> errors) {
        return new ApiException(400, TYPE_BASE + "invalid-request", "Invalid request", detail, errors);
    }

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

    public static ApiException conflict(String detail) {
        return new ApiException(409, TYPE_BASE + "conflict", "Conflict", detail);
    }

    public static ApiException quotaExceeded(String detail) {
        return new ApiException(413, TYPE_BASE + "quota-exceeded", "Upload quota exceeded", detail);
    }

    public static ApiException throttled(String detail) {
        return new ApiException(429, TYPE_BASE + "throttled", "Too many requests", detail);
    }

    /**
     * The request was well-formed and the work it asked for may already be
     * partly or fully done, but a dependency this API does not control (an
     * outbound mail send, for example) failed. Deliberately not 400: a
     * client that treats 502 as "my request was fine, something else broke"
     * will not retry with a different body and misread the resulting 409 as
     * proof the original request never took effect.
     */
    public static ApiException badGateway(String detail) {
        return new ApiException(502, TYPE_BASE + "upstream-failure", "Upstream failure", detail);
    }

    /** As above, chaining {@code cause} -- see the cause-carrying constructor. */
    public static ApiException badGateway(String detail, Throwable cause) {
        return new ApiException(502, TYPE_BASE + "upstream-failure", "Upstream failure", detail, null, cause);
    }

    public int getStatus() {
        return status;
    }

    /** The field errors this exception carries; empty when it names none. */
    public List<ApiProblem.FieldError> getFieldErrors() {
        return errors == null ? List.of() : List.copyOf(errors);
    }

    public ApiProblem toProblem(String instance) {
        return new ApiProblem(type, title, status, getMessage(), instance, errors);
    }
}

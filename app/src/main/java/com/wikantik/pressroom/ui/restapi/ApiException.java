package com.wikantik.pressroom.ui.restapi;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one exception the API layer throws for an expected failure. Every
 * response body is built from it by ApiExceptionHandler; no controller
 * assembles its own.
 *
 * <p>Every problem carries a {@link ProblemCode}. A <em>keyed</em> problem
 * ({@link #problem}, {@link #problemOnField}, and the fixed-text factories)
 * renders its {@code detail} from the code's text, in the caller's language
 * when ApiExceptionHandler writes it. A factory taking a literal {@code
 * detail} makes an <em>unkeyed</em> problem: the generic code for its type,
 * and the literal, in English whatever the caller asked for.
 * {@link #getMessage()} is always the English, for logs.
 */
public final class ApiException extends RuntimeException {

    private static final long serialVersionUID = 1L;
    private static final Logger log = LoggerFactory.getLogger(ApiException.class);
    private static final String ABOUT_BLANK = "about:blank";

    private final int status;
    private final String type;
    private final String title;
    /** The problem's own code; a single field error's code replaces it on the wire. */
    private final ProblemCode code;
    /** The keyed detail's arguments; null when the detail is a literal (or is the fields' messages). */
    private final transient List<String> args;
    private final transient List<FieldProblem> fields;
    /** The detail is the field messages joined by one space, rendered again for the caller. */
    private final boolean detailJoinsFields;
    /** The problem's currentContentHash extension member; null on every problem but entry- and page-changed. */
    private final String currentContentHash;

    /**
     * One field's problem: keyed (a code and its arguments) or a literal
     * message under {@code code}, the generic code for its problem's type.
     */
    record FieldProblem(String field, ProblemCode code, List<String> args, String english) {

        static FieldProblem literal(String field, ProblemCode code, String message) {
            return new FieldProblem(field, code, null, message);
        }

        static FieldProblem keyed(String field, ProblemCode code, String... args) {
            List<String> given = arguments(code, args);
            return new FieldProblem(field, code, given, ProblemMessages.english(code, given));
        }

        /** A field error some caller built as a wire record: its message is a literal. */
        static FieldProblem of(ApiProblem.FieldError error) {
            return new FieldProblem(error.field(), byCode(error.code()), null, error.message());
        }

        ApiProblem.FieldError render(ProblemMessages.Renderer renderer) {
            return new ApiProblem.FieldError(field, args == null ? english : renderer.render(code, args), code.code());
        }
    }

    private ApiException(Builder b) {
        super(b.detail, b.cause);
        this.status = b.status;
        this.type = b.type;
        this.title = b.title;
        this.code = b.code;
        this.args = b.args;
        this.fields = b.fields;
        this.detailJoinsFields = b.detailJoinsFields;
        this.currentContentHash = b.currentContentHash;
    }

    /** The pieces of a problem, so each factory names only the ones it sets. */
    private static final class Builder {
        private final int status;
        private final String type;
        private final String title;
        private final ProblemCode code;
        private String detail;
        private List<String> args;
        private List<FieldProblem> fields;
        private boolean detailJoinsFields;
        private String currentContentHash;
        private Throwable cause;

        private Builder(ProblemCode code) {
            this(code.type().status(), code.type().uri(), code.type().title(), code);
        }

        private Builder(int status, String type, String title, ProblemCode code) {
            this.status = status;
            this.type = type;
            this.title = title;
            this.code = code;
        }

        private Builder literal(String literalDetail) {
            this.detail = literalDetail;
            return this;
        }

        private Builder keyed(String... given) {
            this.args = arguments(code, given);
            this.detail = ProblemMessages.english(code, args);
            return this;
        }

        private Builder fields(List<FieldProblem> problems, boolean joined) {
            this.fields = List.copyOf(problems);
            this.detailJoinsFields = joined;
            if (joined) {
                this.detail = problems.stream().map(FieldProblem::english).collect(Collectors.joining(" "));
            }
            return this;
        }

        private Builder hash(String hash) {
            this.currentContentHash = hash;
            return this;
        }

        private Builder cause(Throwable throwable) {
            this.cause = throwable;
            return this;
        }

        private ApiException build() {
            return new ApiException(this);
        }
    }

    /**
     * {@code given} as a list, after checking it against the code's arity. A
     * wrong count is a display fault, never a decision one (controller
     * ruling, 2026-11-20): the status is what the caller branches on, so it
     * stands, a WARN names the code, and the text renders with the arguments
     * there are. {@code ProblemCodeCatalogTest} holds every code's arity to
     * its English.
     */
    private static List<String> arguments(ProblemCode code, String... given) {
        if (given.length != code.arity()) {
            log.warn("Problem code {} takes {} argument(s) but was given {}; its text renders with what it has",
                    code.code(), code.arity(), given.length);
        }
        return Collections.unmodifiableList(Arrays.asList(given.clone()));
    }

    private static ProblemCode byCode(String wire) {
        return Arrays.stream(ProblemCode.values()).filter(c -> c.code().equals(wire)).findFirst()
                .orElse(ProblemCode.REQUEST_INVALID);
    }

    // ----- keyed problems -----

    /**
     * A keyed problem: {@code code}'s status, type and title, and its text
     * with {@code args} as the detail. Arguments are strings, so the call
     * site formats a number exactly as it should read.
     *
     * @throws IllegalArgumentException for {@link ProblemCode#HTTP_ERROR},
     *         whose status is not fixed: use {@link #httpError}
     */
    public static ApiException problem(ProblemCode code, String... args) {
        return new Builder(typed(code)).keyed(args).build();
    }

    /**
     * As {@link #problem}, about one request field: an {@code errors} entry
     * names {@code field} and carries the same text and code.
     */
    public static ApiException problemOnField(String field, ProblemCode code, String... args) {
        return new Builder(typed(code)).fields(List.of(FieldProblem.keyed(field, code, args)), true).build();
    }

    /** As {@link #problem}, chaining {@code cause} for the log (a business-tier refusal this answers). */
    public static ApiException problem(ProblemCode code, Throwable cause, String... args) {
        return new Builder(typed(code)).keyed(args).cause(cause).build();
    }

    /** As {@link #problemOnField}, chaining {@code cause} for the log. */
    public static ApiException problemOnField(String field, ProblemCode code, Throwable cause, String... args) {
        return new Builder(typed(code)).fields(List.of(FieldProblem.keyed(field, code, args)), true)
                .cause(cause).build();
    }

    private static ProblemCode typed(ProblemCode code) {
        if (code.type() == null) {
            throw new IllegalArgumentException(code.code() + " has no fixed status; it is not a keyed problem");
        }
        return code;
    }

    /**
     * RFC 9457's {@code about:blank} for an HTTP error with no problem of its
     * own: {@code title} is the status phrase by definition, and there is no
     * detail.
     */
    public static ApiException httpError(int status, String title) {
        return new Builder(status, ABOUT_BLANK, title, ProblemCode.HTTP_ERROR).build();
    }

    // ----- unkeyed problems: a literal detail under the generic code for the type -----

    public static ApiException notFound(String detail) {
        return new Builder(ProblemCode.RESOURCE_NOT_FOUND).literal(detail).build();
    }

    public static ApiException unauthorized(String detail) {
        return new Builder(ProblemCode.AUTH_UNAUTHORIZED).literal(detail).build();
    }

    /**
     * The 401 for a request with no caller: fixed text, whatever was wrong
     * with the credential (if one was sent at all), so the answer never tells
     * which.
     */
    public static ApiException authenticationRequired() {
        return problem(ProblemCode.AUTH_UNAUTHORIZED);
    }

    public static ApiException forbidden(String detail) {
        return new Builder(ProblemCode.AUTH_FORBIDDEN).literal(detail).build();
    }

    public static ApiException badRequest(String detail) {
        return new Builder(ProblemCode.REQUEST_INVALID).literal(detail).build();
    }

    /**
     * As above, but also chains {@code cause} -- the exception this API
     * exception was translated from -- so the original stack trace survives
     * in application logs even though the client only ever sees the problem
     * detail.
     */
    public static ApiException badRequest(String detail, Throwable cause) {
        return new Builder(ProblemCode.REQUEST_INVALID).literal(detail).cause(cause).build();
    }

    /**
     * A 400 listing {@code errors}, with a literal {@code detail}. Its code
     * is the one error's code, or {@code request.invalidFields} for several.
     */
    public static ApiException validation(String detail, List<ApiProblem.FieldError> errors) {
        return new Builder(ProblemCode.REQUEST_INVALID).literal(detail)
                .fields(errors.stream().map(FieldProblem::of).toList(), false).build();
    }

    /** The 400 {@link FieldErrors} collected: its detail is the problems' messages, joined by one space. */
    static ApiException fieldsProblem(List<FieldProblem> problems) {
        return new Builder(ProblemCode.REQUEST_INVALID).fields(problems, true).build();
    }

    /** A 400 about one request field: its detail, and an {@code errors} entry naming the field. */
    public static ApiException invalidField(String field, String detail) {
        return invalidField(field, detail, null);
    }

    /** As above, chaining {@code cause} -- see {@link #badRequest(String, Throwable)}. */
    public static ApiException invalidField(String field, String detail, Throwable cause) {
        return onField(ProblemCode.REQUEST_INVALID, field, detail, cause);
    }

    /** A 409 about one request field, such as an entry anchor another entry already holds. */
    public static ApiException conflictOnField(String field, String detail) {
        return conflictOnField(field, detail, null);
    }

    /** As above, chaining {@code cause} -- see {@link #badRequest(String, Throwable)}. */
    public static ApiException conflictOnField(String field, String detail, Throwable cause) {
        return onField(ProblemCode.RESOURCE_CONFLICT, field, detail, cause);
    }

    private static ApiException onField(ProblemCode code, String field, String detail, Throwable cause) {
        return new Builder(code).literal(detail)
                .fields(List.of(FieldProblem.literal(field, code, detail)), false).cause(cause).build();
    }

    public static ApiException conflict(String detail) {
        return new Builder(ProblemCode.RESOURCE_CONFLICT).literal(detail).build();
    }

    /** A 409 raised from a business-tier refusal, which is kept as the cause for the log. */
    public static ApiException conflict(String detail, Throwable cause) {
        return new Builder(ProblemCode.RESOURCE_CONFLICT).literal(detail).cause(cause).build();
    }

    /**
     * A 409 for an update whose {@code expectedContentHash} is not the hash of
     * the text now stored: the entry changed after the caller read it. The
     * problem's {@code currentContentHash} carries the stored text's hash, as
     * Wikantik's update_page does: a caller whose earlier, timed-out attempt
     * did land finds its own text's hash there.
     */
    public static ApiException entryChanged(String currentContentHash) {
        return new Builder(ProblemCode.ENTRY_CHANGED).keyed().hash(currentContentHash).build();
    }

    /**
     * {@link #entryChanged}'s counterpart for a page: an update or a revision
     * restore whose {@code expectedContentHash} is not the hash of the
     * content now stored. {@code currentContentHash} carries the stored
     * content's hash.
     */
    public static ApiException pageChanged(String currentContentHash) {
        return new Builder(ProblemCode.PAGE_CHANGED).keyed().hash(currentContentHash).build();
    }

    /**
     * The opaque 500: fixed text, because an unexpected exception's message
     * routinely carries schema names, file paths and connection strings. The
     * cause belongs in the log, never in the body.
     */
    public static ApiException internalError() {
        return problem(ProblemCode.SERVER_INTERNAL_ERROR);
    }

    /**
     * A transient refusal the caller should retry after a short wait: the
     * server is healthy, but the work collided with other work in progress.
     */
    public static ApiException busy(String detail) {
        return new Builder(ProblemCode.SERVER_BUSY).literal(detail).build();
    }

    /** 413: the request body is over {@link ApiBodyLimitFilter#MAX_BODY_BYTES}. */
    public static ApiException tooLarge(String detail) {
        return new Builder(ProblemCode.REQUEST_TOO_LARGE).literal(detail).build();
    }

    /** A request body in a media type the route does not read: the API reads JSON. */
    public static ApiException unsupportedMediaType() {
        return problem(ProblemCode.REQUEST_UNSUPPORTED_MEDIA_TYPE);
    }

    /** An {@code Accept} the API cannot answer: it writes JSON (problems as problem+json). */
    public static ApiException notAcceptable() {
        return problem(ProblemCode.REQUEST_NOT_ACCEPTABLE);
    }

    public static ApiException throttled(String detail) {
        return new Builder(ProblemCode.REQUEST_THROTTLED).literal(detail).build();
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
        return badGateway(detail, null);
    }

    /** As above, chaining {@code cause} -- see {@link #badRequest(String, Throwable)}. */
    public static ApiException badGateway(String detail, Throwable cause) {
        return new Builder(ProblemCode.UPSTREAM_FAILURE).literal(detail).cause(cause).build();
    }

    public int getStatus() {
        return status;
    }

    /** The field errors this exception carries, in English; empty when it names none. */
    public List<ApiProblem.FieldError> getFieldErrors() {
        return fields == null ? List.of()
                : fields.stream().map(f -> f.render(ProblemMessages::english)).toList();
    }

    /** The field problems this exception carries, keyed ones still keyed; empty when it names none. */
    List<FieldProblem> fieldProblems() {
        return fields == null ? List.of() : fields;
    }

    /** This problem as one field's problem, for a check that named no field of its own. */
    FieldProblem asFieldProblem(String field) {
        return new FieldProblem(field, code, args, getMessage());
    }

    /** The problem in English: what MCP and the writers outside ApiExceptionHandler send. */
    public ApiProblem toProblem(String instance) {
        return toProblem(instance, ProblemMessages::english);
    }

    /** The problem with its keyed text rendered by {@code renderer}; literal text stays as it is. */
    ApiProblem toProblem(String instance, ProblemMessages.Renderer renderer) {
        List<ApiProblem.FieldError> errors = fields == null ? null
                : fields.stream().map(f -> f.render(renderer)).toList();
        String detail;
        if (detailJoinsFields) {
            detail = errors.stream().map(ApiProblem.FieldError::message).collect(Collectors.joining(" "));
        } else {
            detail = args == null ? getMessage() : renderer.render(code, args);
        }
        return new ApiProblem(type, title, status, detail, instance, errors, currentContentHash, wireCode());
    }

    /** One field error lends the problem its code; several make it {@code request.invalidFields}. */
    private String wireCode() {
        if (fields == null || fields.isEmpty()) {
            return code.code();
        }
        return fields.size() == 1 ? fields.get(0).code().code() : ProblemCode.REQUEST_INVALID_FIELDS.code();
    }
}

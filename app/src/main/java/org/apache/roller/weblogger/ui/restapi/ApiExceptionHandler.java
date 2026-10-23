package org.apache.roller.weblogger.ui.restapi;

import jakarta.servlet.http.HttpServletRequest;
import java.sql.SQLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * The only place an API error body is built. Scoped to the restapi package so
 * it cannot change how the JSP controllers report failures.
 */
@RestControllerAdvice(basePackages = "org.apache.roller.weblogger.ui.restapi")
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /**
     * PostgreSQL's lock_not_available: a statement gave up waiting for a lock
     * ({@code lock_timeout}). Today only the anchor claim sets a lock timeout
     * (pg_advisory_xact_lock, {@code weblogentry.anchor.lockTimeoutSeconds}),
     * so it means another save of the same weblog held on longer than that.
     * Only this SQLState is retryable here; every other database failure stays
     * the opaque 500.
     */
    private static final String LOCK_NOT_AVAILABLE = "55P03";

    /** The holder has already kept the lock past the timeout, so a short wait is enough to try again. */
    private static final String LOCK_TIMEOUT_RETRY_AFTER_SECONDS = "2";

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiProblem> handleApiException(ApiException ex, HttpServletRequest request) {
        return ResponseEntity.status(ex.getStatus())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(ex.toProblem(request.getRequestURI()));
    }

    /**
     * A malformed or empty request body -- Spring throws this during
     * argument resolution, before any controller method runs, so no
     * controller can catch it itself. Without this handler it fell through
     * to {@link #handleUnexpected}: an opaque 500 for a client mistake as
     * ordinary as a trailing comma, on every {@code *Api} controller in the
     * wave. The parser's own message can echo fragments of the request body
     * and internal type names, so -- same rule as {@link #handleUnexpected}
     * -- it is logged, never returned.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiProblem> handleMalformedBody(HttpMessageNotReadableException ex,
                                                           HttpServletRequest request) {
        log.info("Malformed request body at {}: {}", request.getRequestURI(), ex.getMessage());
        return handleApiException(
                ApiException.badRequest("The request body could not be parsed as JSON."), request);
    }

    /**
     * A query parameter or path variable that will not convert to the type
     * the handler method declares -- {@code ?limit=abc} against an {@code
     * int limit} -- fires during argument resolution, before any controller
     * method runs, the same "no controller can catch it itself" shape as
     * {@link #handleMalformedBody}. Without this handler it fell through to
     * {@link #handleUnexpected}: a 500 for a client mistake as ordinary as a
     * non-numeric {@code limit}, on every list endpoint in the wave that
     * takes a typed {@code @RequestParam}/{@code @PathVariable}, not just
     * one controller. The parameter NAME is fine to echo back (it is part of
     * this API's own published contract); the rejected VALUE and the
     * conversion exception's own message are not, for the same leak-risk
     * reason {@link #handleMalformedBody} withholds the parser's message.
     * A client typo is not a server fault, so the log line carries no stack
     * trace.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiProblem> handleTypeMismatch(MethodArgumentTypeMismatchException ex,
                                                          HttpServletRequest request) {
        log.info("Type mismatch for parameter '{}' at {}", ex.getName(), request.getRequestURI());
        return handleApiException(
                ApiException.invalidField(ex.getName(), ex.getName() + " has an invalid value" + takes(ex.getRequiredType())),
                request);
    }

    /** The rest of the type-mismatch sentence: what the parameter takes, when its type says so. */
    private static String takes(Class<?> type) {
        if (type == int.class || type == Integer.class || type == long.class || type == Long.class) {
            return "; it must be a whole number.";
        }
        if (type == double.class || type == Double.class || type == float.class || type == Float.class) {
            return "; it must be a number.";
        }
        if (type == boolean.class || type == Boolean.class) {
            return "; it must be true or false.";
        }
        return ".";
    }

    /** True when a {@link SQLException} with SQLState 55P03 is anywhere in {@code failure}'s cause chain. */
    static boolean isLockTimeout(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof SQLException sql && LOCK_NOT_AVAILABLE.equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Anything unforeseen. The cause is logged; the client is told nothing
     * about it, because an exception message here routinely carries schema
     * names, file paths and connection strings.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiProblem> handleUnexpected(Exception ex, HttpServletRequest request) {
        if (isLockTimeout(ex)) {
            log.warn("Lock timeout at {}; answered 503 for a retry", request.getRequestURI());
            return ResponseEntity.status(503)
                    .header("Retry-After", LOCK_TIMEOUT_RETRY_AFTER_SECONDS)
                    .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .body(ApiException.busy("Another save of this weblog is in progress; retry shortly.")
                            .toProblem(request.getRequestURI()));
        }
        log.error("Unhandled API exception at {}", request.getRequestURI(), ex);
        return handleApiException(ApiException.internalError(), request);
    }
}

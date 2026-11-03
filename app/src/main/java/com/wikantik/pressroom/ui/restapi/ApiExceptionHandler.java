package com.wikantik.pressroom.ui.restapi;

import jakarta.servlet.http.HttpServletRequest;
import com.wikantik.pressroom.business.AnchorLockTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * The only place an API error body is built. Scoped to the restapi package so
 * it cannot change how the JSP controllers report failures.
 */
@RestControllerAdvice(basePackageClasses = ApiExceptionHandler.class)
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

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

    /**
     * A save gave up waiting for its weblog's anchor lock (another save of the
     * same weblog held it past {@code weblogentry.anchor.lockTimeoutSeconds}).
     * Nothing was saved and nothing is wrong with the request, so it is a
     * retryable 503, with Retry-After set to the lock timeout the exception
     * carries. Expected under load, so a WARN with no stack trace. MCP forwards
     * to the same route, so its tool error carries this same problem.
     */
    @ExceptionHandler(AnchorLockTimeoutException.class)
    public ResponseEntity<ApiProblem> handleAnchorLockTimeout(AnchorLockTimeoutException ex,
                                                               HttpServletRequest request) {
        log.warn("Anchor lock timeout after {}s for weblog {} at {}; answered 503",
                ex.getTimeoutSeconds(), ex.getWeblogHandle(), request.getRequestURI());
        return ResponseEntity.status(503)
                .header("Retry-After", Integer.toString(Math.max(1, ex.getTimeoutSeconds())))
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(ApiException.busy("Another save of this weblog is in progress; retry shortly.")
                        .toProblem(request.getRequestURI()));
    }

    /**
     * A body the route cannot read for its media type ({@code
     * application/x-www-form-urlencoded}, {@code text/plain}), raised while
     * reading the {@code @RequestBody}, so inside the handler and here. A
     * client mistake: 415, logged at INFO, never the opaque 500. (A route
     * whose mapping declares {@code consumes}, the media upload, refuses
     * before any handler is chosen, so Spring answers that one itself.)
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiProblem> handleUnsupportedMediaType(HttpMediaTypeNotSupportedException ex,
                                                                  HttpServletRequest request) {
        log.info("Unsupported request Content-Type at {}", request.getRequestURI());
        return handleApiException(ApiException.unsupportedMediaType(), request);
    }

    /**
     * An {@code Accept} no converter can write the result in ({@code
     * text/html}), raised while writing the return value. 406, logged at
     * INFO. The problem is still written as problem+json: a response whose
     * Content-Type is set is written in it whatever {@code Accept} says, and a
     * client that cannot read it can still read the status.
     */
    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<ApiProblem> handleNotAcceptable(HttpMediaTypeNotAcceptableException ex,
                                                           HttpServletRequest request) {
        log.info("Unanswerable Accept at {}", request.getRequestURI());
        return handleApiException(ApiException.notAcceptable(), request);
    }

    /**
     * Anything unforeseen. The cause is logged; the client is told nothing
     * about it, because an exception message here routinely carries schema
     * names, file paths and connection strings.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiProblem> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled API exception at {}", request.getRequestURI(), ex);
        return handleApiException(ApiException.internalError(), request);
    }
}
